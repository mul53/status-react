(ns status-im.commands.core
  (:require
   [re-frame.core :as re-frame]
   [status-im.ethereum.json-rpc :as json-rpc]
   [status-im.utils.fx :as fx]))

(fx/defn handle-prepare-accept-request-address-for-transaction
  {:events [:commands/prepare-accept-request-address-for-transaction]}
  [{:keys [db]} message]
  {:db (assoc db :commands/select-account {:message message})})

(fx/defn set-selected-account
  {:events [:commands/set-selected-account]}
  [{:keys [db]} _ account]
  {:db (-> (assoc-in db [:commands/select-account :from] account)
           (assoc :bottom-sheet/show? false))})

(fx/defn handle-accept-request-address-for-transaction
  {:events [:commands/accept-request-address-for-transaction]}
  [{:keys [db]} message-id address]
  {:db (dissoc db :commands/select-account)
   ::json-rpc/call [{:method "shhext_acceptRequestAddressForTransaction"
                     :params [message-id address]
                     :on-success #(re-frame/dispatch [:transport/message-sent % 1])}]})

(fx/defn handle-decline-request-address-for-transaction
  {:events [:commands/decline-request-address-for-transaction]}
  [cofx message-id]
  {::json-rpc/call [{:method "shhext_declineRequestAddressForTransaction"
                     :params [message-id]
                     :on-success #(re-frame/dispatch [:transport/message-sent % 1])}]})

(fx/defn handle-decline-request-transaction
  {:events [:commands/decline-request-transaction]}
  [cofx message-id]
  {::json-rpc/call [{:method "shhext_declineRequestTransaction"
                     :params [message-id]
                     :on-success #(re-frame/dispatch [:transport/message-sent % 1])}]})
