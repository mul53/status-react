(ns status-im.wallet.core
  (:require [clojure.set :as set]
            [re-frame.core :as re-frame]
            [status-im.multiaccounts.update.core :as multiaccounts.update]
            [status-im.constants :as constants]
            [status-im.chat.models.message :as chat.message]
            [status-im.ethereum.core :as ethereum]
            [status-im.ethereum.eip55 :as eip55]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.ethereum.tokens :as tokens]
            [status-im.i18n :as i18n]
            [status-im.ui.screens.navigation :as navigation]
            [status-im.wallet.utils :as wallet.utils]
            [status-im.utils.config :as config]
            [status-im.utils.core :as utils.core]
            [status-im.utils.fx :as fx]
            [status-im.utils.money :as money]
            [status-im.utils.prices :as prices]
            [status-im.utils.utils :as utils.utils]
            [taoensso.timbre :as log]
            [status-im.wallet.db :as wallet.db]
            [status-im.ethereum.abi-spec :as abi-spec]
            [status-im.signing.core :as signing]
            [clojure.string :as string]
            [status-im.contact.db :as contact.db]
            [status-im.ui.components.bottom-sheet.core :as bottom-sheet]))

(re-frame/reg-fx
 :wallet/get-balance
 (fn [{:keys [account-address on-success on-error]}]
   (json-rpc/call
    {:method     "eth_getBalance"
     :params     [account-address "latest"]
     :on-success on-success
     :on-error   on-error})))

(re-frame/reg-fx
 :wallet/get-balances
 (fn [addresses]
   (doseq [address addresses]
     (json-rpc/call
      {:method     "eth_getBalance"
       :params     [address "latest"]
       :on-success #(re-frame/dispatch [::update-balance-success address %])
       :on-error    #(re-frame/dispatch [::update-balance-fail %])}))))

;; TODO(oskarth): At some point we want to get list of relevant
;; assets to get prices for
(re-frame/reg-fx
 :wallet/get-prices
 (fn [{:keys [from to mainnet? success-event error-event chaos-mode?]}]
   (prices/get-prices from
                      to
                      mainnet?
                      #(re-frame/dispatch [success-event %])
                      #(re-frame/dispatch [error-event %])
                      chaos-mode?)))

(defn assoc-error-message [db error-type err]
  (assoc-in db [:wallet :errors error-type] (or err :unknown-error)))

(fx/defn on-update-prices-fail
  {:events [::update-prices-fail]}
  [{:keys [db]} err]
  (log/debug "Unable to get prices: " err)
  {:db (-> db
           (assoc-error-message :prices-update :error-unable-to-get-prices)
           (assoc :prices-loading? false))})

(fx/defn on-update-balance-fail
  {:events [::update-balance-fail]}
  [{:keys [db]} err]
  (log/debug "Unable to get balance: " err)
  {:db (-> db
           (assoc-error-message :balance-update :error-unable-to-get-balance))})

(fx/defn on-update-token-balance-fail
  {:events [::update-token-balance-fail]}
  [{:keys [db]} err]
  (log/debug "Unable to get tokens balances: " err)
  {:db (-> db
           (assoc-error-message :balance-update :error-unable-to-get-token-balance))})

(fx/defn open-transaction-details
  [{:keys [db] :as cofx} hash address]
  (navigation/navigate-to-cofx cofx :wallet-transaction-details {:hash hash :address address}))

(defn- validate-token-name!
  [{:keys [address symbol name]}]
  (json-rpc/eth-call
   {:contract address
    :method "name()"
    :outputs ["string"]
    :on-success
    (fn [[contract-name]]
      (when (and (not (empty? contract-name))
                 (not= name contract-name))
        (let [message (i18n/label :t/token-auto-validate-name-error
                                  {:symbol   symbol
                                   :expected name
                                   :actual   contract-name
                                   :address  address})]
          (log/warn message)
          (utils.utils/show-popup (i18n/label :t/warning) message))))}))

(defn- validate-token-symbol!
  [{:keys [address symbol]}]
  (json-rpc/eth-call
   {:contract address
    :method "symbol()"
    :outputs ["string"]
    :on-success
    (fn [[contract-symbol]]
      ;;NOTE(goranjovic): skipping check if field not set in contract
      (when (and (not (empty? contract-symbol))
                 (not= (clojure.core/name symbol) contract-symbol))
        (let [message (i18n/label :t/token-auto-validate-symbol-error
                                  {:symbol   symbol
                                   :expected (clojure.core/name symbol)
                                   :actual   contract-symbol
                                   :address  address})]
          (log/warn message)
          (utils.utils/show-popup (i18n/label :t/warning) message))))}))

(defn- validate-token-decimals!
  [{:keys [address symbol decimals nft?]}]
  (when-not nft?
    (json-rpc/eth-call
     {:contract address
      :method "decimals()"
      :outputs ["uint256"]
      :on-success
      (fn [[contract-decimals]]
        (when (and (not (nil? contract-decimals))
                   (not= decimals contract-decimals))
          (let [message (i18n/label :t/token-auto-validate-decimals-error
                                    {:symbol   symbol
                                     :expected decimals
                                     :actual   contract-decimals
                                     :address  address})]
            (log/warn message)
            (utils.utils/show-popup (i18n/label :t/warning) message))))})))

(re-frame/reg-fx
 :wallet/validate-tokens
 (fn [tokens]
   (doseq [token tokens]
     (validate-token-decimals! token)
     (validate-token-symbol! token)
     (validate-token-name! token))))

(defn- clean-up-results
  "remove empty balances
   if there is no visible assets, returns all positive balances
   otherwise return only the visible assets balances"
  [results tokens assets]
  (let [balances
        (reduce (fn [acc [address balances]]
                  (let [pos-balances
                        (reduce (fn [acc [token-address token-balance]]
                                  (let [token-symbol (get tokens (name token-address))]
                                    (if (or (and (empty? assets) (pos? token-balance))
                                            (and (seq assets) (assets token-symbol)))
                                      (assoc acc token-symbol token-balance)
                                      acc)))
                                {}
                                balances)]
                    (if (not-empty pos-balances)
                      (assoc acc (eip55/address->checksum (name address)) pos-balances)
                      acc)))
                {}
                results)]
    (when (not-empty balances)
      balances)))

(re-frame/reg-fx
 :wallet/get-tokens-balances
 (fn [{:keys [addresses tokens init? assets]}]
   (json-rpc/call
    {:method "wallet_getTokensBalances"
     :params [addresses (keys tokens)]
     :on-success
     (fn [results]
       (when-let [balances (clean-up-results results tokens (if init? nil assets))]
         (re-frame/dispatch (if init?
                              ;; NOTE: when there it is not a visible
                              ;; assets we make an initialization round
                              [::tokens-found balances]
                              [::update-tokens-balances-success balances]))))
     :on-error   #(re-frame/dispatch [::update-token-balance-fail %])})))

(defn clear-error-message [db error-type]
  (update-in db [:wallet :errors] dissoc error-type))

(defn tokens-symbols
  [visible-token-symbols all-tokens chain]
  (set/difference (set visible-token-symbols)
                  (set (map :symbol (tokens/nfts-for all-tokens chain)))))

(defn rpc->token [tokens]
  (reduce (fn [acc {:keys [address] :as token}]
            (assoc acc
                   address
                   (assoc token :custom? true)))
          {}
          tokens))

(fx/defn initialize-tokens
  [{:keys [db] :as cofx} custom-tokens]
  (let [chain         (ethereum/chain-keyword db)
        custom-tokens {chain (rpc->token custom-tokens)}
        ;;TODO why do we need all tokens ? chain can be changed only through relogin
        all-tokens    (merge-with
                       merge
                       (utils.core/map-values #(utils.core/index-by :address %)
                                              tokens/all-default-tokens)
                       custom-tokens)]
    (fx/merge
     cofx
     (merge
      {:db (assoc db :wallet/all-tokens all-tokens)}
      (when config/erc20-contract-warnings-enabled?
        {:wallet/validate-tokens (get tokens/all-default-tokens chain)})))))

(fx/defn update-balances
  [{{:keys [network-status :wallet/all-tokens
            multiaccount :multiaccount/accounts] :as db} :db
    :as cofx} addresses]
  (let [addresses (or addresses (map (comp string/lower-case :address) accounts))
        {:keys [:wallet/visible-tokens]} multiaccount
        chain     (ethereum/chain-keyword db)
        assets    (get visible-tokens chain)
        init?     (or (empty? assets)
                      (= assets (constants/default-visible-tokens chain)))
        tokens    (->> (tokens/tokens-for all-tokens chain)
                       (remove #(or (:hidden? %)
                                    ;;if not init remove not visible tokens
                                    (and (not init?)
                                         (not (get assets (:symbol %))))))
                       (reduce (fn [acc {:keys [address symbol]}]
                                 (assoc acc address symbol))
                               {}))]
    (when (not= network-status :offline)
      (fx/merge
       cofx
       {:wallet/get-balances        addresses
        :wallet/get-tokens-balances {:addresses addresses
                                     :tokens    tokens
                                     :assets    assets
                                     :init?     init?}
        :db                         (clear-error-message db :balance-update)}
       (when-not assets
         (multiaccounts.update/multiaccount-update
          :wallet/visible-tokens (assoc visible-tokens chain (or (constants/default-visible-tokens chain)
                                                                 #{}))
          {}))))))

(fx/defn update-prices
  [{{:keys [network-status :wallet/all-tokens]
     {:keys [address currency chaos-mode? :wallet/visible-tokens]
      :or {currency :usd}} :multiaccount :as db} :db}]
  (let [chain       (ethereum/chain-keyword db)
        mainnet?    (= :mainnet chain)
        assets      (get visible-tokens chain #{})
        tokens      (tokens-symbols assets all-tokens chain)
        currency    (get constants/currencies currency)]
    (when (not= network-status :offline)
      {:wallet/get-prices
       {:from          (if mainnet?
                         (conj tokens "ETH")
                         [(-> (tokens/native-currency chain)
                              (wallet.utils/exchange-symbol))])
        :to            [(:code currency)]
        :mainnet?      mainnet?
        :success-event ::update-prices-success
        :error-event   ::update-prices-fail
        :chaos-mode?   chaos-mode?}

       :db
       (-> db
           (clear-error-message :prices-update)
           (assoc :prices-loading? true))})))

(defn- set-checked [tokens-id token-id checked?]
  (let [tokens-id (or tokens-id #{})]
    (if checked?
      (conj tokens-id token-id)
      (disj tokens-id token-id))))

(fx/defn on-update-prices-success
  {:events [::update-prices-success]}
  [{:keys [db]} prices]
  {:db (assoc db
              :prices prices
              :prices-loading? false)})

(fx/defn update-balance
  {:events [::update-balance-success]}
  [{:keys [db]} address balance]
  {:db (assoc-in db
                 [:wallet :accounts (eip55/address->checksum address) :balance :ETH]
                 (money/bignumber balance))})

(fx/defn update-toggle-in-settings
  [{{:keys [multiaccount] :as db} :db :as cofx} symbol checked?]
  (let [chain          (ethereum/chain-keyword db)
        visible-tokens (get multiaccount :wallet/visible-tokens)]
    (multiaccounts.update/multiaccount-update
     cofx
     :wallet/visible-tokens (update visible-tokens
                                    chain
                                    #(set-checked % symbol checked?))
     {})))

(fx/defn toggle-visible-token
  [cofx symbol checked?]
  (update-toggle-in-settings cofx symbol checked?))

(fx/defn update-tokens-balances
  {:events [::update-tokens-balances-success]}
  [{:keys [db]} balances]
  (let [accounts (get-in db [:wallet :accounts])]
    {:db (assoc-in db
                   [:wallet :accounts]
                   (reduce (fn [acc [address balances]]
                             (assoc-in acc
                                       [address :balance]
                                       (reduce (fn [acc [token-symbol balance]]
                                                 (assoc acc
                                                        token-symbol
                                                        (money/bignumber balance)))
                                               (get-in accounts [address :balance])
                                               balances)))
                           accounts
                           balances))}))

(fx/defn configure-token-balance-and-visibility
  {:events [::tokens-found]}
  [{:keys [db] :as cofx} balances]
  (let [chain (ethereum/chain-keyword db)
        visible-tokens (get-in db [:multiaccount :wallet/visible-tokens])
        chain-visible-tokens (into (or (constants/default-visible-tokens chain)
                                       #{})
                                   (flatten (map keys (vals balances))))]
    (fx/merge cofx
              (multiaccounts.update/multiaccount-update
               cofx
               :wallet/visible-tokens (assoc visible-tokens
                                             chain
                                             chain-visible-tokens)
               {})
              (update-tokens-balances balances)
              (update-prices))))

(fx/defn add-custom-token
  [{:keys [db] :as cofx} {:keys [symbol]}]
  (fx/merge cofx
            (update-toggle-in-settings symbol true)
            (update-balances nil)))

(fx/defn remove-custom-token
  [{:keys [db] :as cofx} {:keys [symbol]}]
  (update-toggle-in-settings cofx symbol false))

(fx/defn set-and-validate-amount
  {:events [:wallet.send/set-amount-text]}
  [{:keys [db]} amount]
  {:db (assoc-in db [:wallet/prepare-transaction :amount-text] amount)})

(fx/defn sign-transaction-button-clicked-from-chat
  {:events  [:wallet.ui/sign-transaction-button-clicked-from-chat]}
  [{:keys [db] :as cofx} {:keys [to amount from token from-chat? gas gasPrice]}]
  (let [{:keys [symbol address]} token
        to-norm (ethereum/normalized-hex (if (string? to) to (:address to)))
        from-address (:address from)]
    (fx/merge cofx
              {:db (dissoc db :wallet/prepare-transaction)
              ;;TODO from chat, send request message or if ens name sign tx and send tx message
              ::json-rpc/call [{:method "shhext_requestAddressForTransaction"
                                :params [(:current-chat-id db)
                                         from-address
                                         amount
                                         (when-not (= symbol :ETH)
                                           address)]
                                :on-success #(re-frame/dispatch [:transport/message-sent % 1])}]})))

(fx/defn sign-transaction-button-clicked-from-command
  {:events  [:wallet.ui/sign-transaction-button-clicked-from-command]}
  [{:keys [db] :as cofx} {:keys [to amount from token gas gasPrice]}]
  (let [{:keys [symbol address]} token
        amount-hex (str "0x" (abi-spec/number-to-hex amount))
        to-norm (ethereum/normalized-hex (if (string? to) to (:address to)))
        from-address (:address from)]
    (fx/merge cofx
              {:db (dissoc db :wallet/prepare-transaction)}
              (fn [cofx]
                (signing/sign cofx {:tx-obj (if (= symbol :ETH)
                                              {:to    to-norm
                                               :from  from-address
                                               :value amount-hex}
                                              {:to       (ethereum/normalized-hex address)
                                               :from     from-address
                                               :data     (abi-spec/encode
                                                          "transfer(address,uint256)"
                                                          [to-norm amount-hex])
                                               ;;Note: data from qr (eip681)
                                               :gas      gas
                                               :gasPrice gasPrice})})))))

(fx/defn sign-transaction-button-clicked
  {:events  [:wallet.ui/sign-transaction-button-clicked]}
  [{:keys [db] :as cofx} {:keys [to amount from token gas gasPrice]}]
  (let [{:keys [symbol address]} token
        amount-hex (str "0x" (abi-spec/number-to-hex amount))
        to-norm (ethereum/normalized-hex (if (string? to) to (:address to)))
        from-address (:address from)]
    (fx/merge cofx
              {:db (dissoc db :wallet/prepare-transaction)}
              (fn [cofx]
                (signing/sign cofx {:tx-obj (if (= symbol :ETH)
                                              {:to    to-norm
                                               :from  from-address
                                               :value amount-hex}
                                              {:to       (ethereum/normalized-hex address)
                                               :from     from-address
                                               :data     (abi-spec/encode
                                                          "transfer(address,uint256)"
                                                          [to-norm amount-hex])
                                               ;;Note: data from qr (eip681)
                                               :gas      gas
                                               :gasPrice gasPrice})})))))

(fx/defn set-and-validate-amount-request
  {:events [:wallet.request/set-and-validate-amount]}
  [{:keys [db]} amount symbol decimals]
  (let [{:keys [value error]} (wallet.db/parse-amount amount decimals)]
    {:db (-> db
             (assoc-in [:wallet :request-transaction :amount] (money/formatted->internal value symbol decimals))
             (assoc-in [:wallet :request-transaction :amount-text] amount)
             (assoc-in [:wallet :request-transaction :amount-error] error))}))

;;TODO request isn't implemented
(fx/defn set-symbol-request
  {:events [:wallet.request/set-symbol]}
  [{:keys [db]} symbol]
  {:db (assoc-in db [:wallet :request-transaction :symbol] symbol)})

(fx/defn prepare-transaction-from-chat
  {:events [:wallet/prepare-transaction-from-chat]}
  [{:keys [db]}]
  (let [identity (:current-chat-id db)]
    {:db (assoc db :wallet/prepare-transaction
                {:from (ethereum/get-default-account (get db [:multiaccount/accounts]))
                 :to   (or (get-in db [:contacts/contacts identity])
                           (-> identity
                               contact.db/public-key->new-contact
                               contact.db/enrich-contact))
                 :symbol :ETH
                 :from-chat? true})}))

(fx/defn prepare-transaction-from-wallet
  {:events [:wallet/prepare-transaction-from-wallet]}
  [{:keys [db]} account]
  {:db (assoc db :wallet/prepare-transaction
              {:from       account
               :to         nil
               :symbol     :ETH
               :from-chat? false})})

(fx/defn finalize-transaction-from-command
  {:events [:wallet/finalize-transaction-from-command]}
  [{:keys [db]} account to symbol amount]
  {:db (assoc db :wallet/prepare-transaction
              {:from       account
               :to         to
               :symbol     symbol
               :amount     amount
               :from-command? true})})

(fx/defn qr-scanner-allowed
  {:events [:wallet.send/qr-scanner-allowed]}
  [{:keys [db] :as cofx} options]
  (fx/merge cofx
            {:db (assoc-in db [:wallet/prepare-transaction :modal-opened?] true)}
            (bottom-sheet/hide-bottom-sheet)
            (navigation/navigate-to-cofx :qr-scanner options)))

(fx/defn wallet-send-set-symbol
  {:events [:wallet.send/set-symbol]}
  [{:keys [db] :as cofx} symbol]
  (fx/merge cofx
            {:db (assoc-in db [:wallet/prepare-transaction :symbol] symbol)}
            (bottom-sheet/hide-bottom-sheet)))

(fx/defn wallet-send-set-field
  {:events [:wallet.send/set-field]}
  [{:keys [db] :as cofx} field value]
  (fx/merge cofx
            {:db (assoc-in db [:wallet/prepare-transaction field] value)}
            (bottom-sheet/hide-bottom-sheet)))

(fx/defn navigate-to-recipient-code
  {:events [:wallet.send/navigate-to-recipient-code]}
  [{:keys [db] :as cofx}]
  (fx/merge cofx
            {:db (assoc-in db [:wallet/prepare-transaction :modal-opened?] true)}
            (bottom-sheet/hide-bottom-sheet)
            (navigation/navigate-to-cofx :contact-code nil)))

(fx/defn show-delete-account-confirmation
  {:events [:wallet.settings/show-delete-account-confirmation]}
  [_ account]
  {:ui/show-confirmation {:title               (i18n/label :t/are-you-sure?)
                          :confirm-button-text (i18n/label :t/yes)
                          :cancel-button-text  (i18n/label :t/no)
                          :on-accept           #(re-frame/dispatch [:wallet.accounts/delete-account account])
                          :on-cancel           #()}})
