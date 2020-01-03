(ns status-im.ui.screens.home.filter.views
  (:require [status-im.ui.components.list.views :as list]
            [status-im.ui.screens.home.styles :as styles]
            [status-im.ui.components.react :as react]
            [status-im.i18n :as i18n]
            [status-im.ui.components.colors :as colors]
            [status-im.ui.screens.home.views.inner-item :as inner-item]
            [status-im.utils.utils :as utils]
            [status-im.ui.components.animation :as animation]
            [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [status-im.ui.components.icons.vector-icons :as icons]
            [taoensso.timbre :as log])
  (:require-macros [status-im.utils.views :as views]))

(def animation-duration 150)

(def search-active? (reagent/atom false))

(def input-ref (reagent/atom nil))

(views/defview search-input [{:keys [on-cancel on-focus on-change]}]
  (views/letsubs
    [{:keys [search-filter]} [:home-items]]
    {:component-did-update
     (fn []
       (log/info "!!! DID UPDATE, focused:" (.isFocused @input-ref))
                    ;(when-not @search-active?
                    ;  (do
                    ;    (.blur @input-ref)
                    ;    (log/info "!!! BLUR: " (.isFocused @input-ref))))
)}
    (let [;input-ref (reagent/atom nil)
]
      (log/info "!!!!!!!!!!!!! search-input")
      [react/view {:style styles/search-container}
       [react/view {:style styles/search-input-container}
        [icons/icon :main-icons/search {:color           colors/gray
                                        :container-style {:margin-left  6
                                                          :margin-right 2}}]
        [react/text-input {:placeholder     (i18n/label :t/search)
                           :blur-on-submit  true
                           :multiline       false
                           :ref             #(reset! input-ref %)
                           :style           styles/search-input
                           :default-value   search-filter
                           :on-focus        #(do
                                               (when on-focus
                                                 (on-focus search-filter))
                                               (reset! search-active? true)
                                               (log/info "=== SET ACTIVE: " @search-active?))
                           :on-change       (fn [e]
                                              (let [native-event (.-nativeEvent e)
                                                    text         (.-text native-event)]
                                                (when on-change
                                                  (on-change text))))}]]
       (when @search-active?
         [react/touchable-highlight
          {:on-press #(do

                        (.clear @input-ref)
                                                               ;(.setNativeProps @input-ref (clj->js {:text ""}))
                        (.blur @input-ref)
                                       ;(set! (.-editable @input-ref) false)
                                       ;(set! (.-editable @input-ref) true)
                                       ;(react/dismiss-keyboard!)
                        (when on-cancel
                          (on-cancel))
                        (reset! search-active? false)
                        (log/info "=== SET ACTIVE: " @search-active?))
           :style {:margin-left 16}}
          [react/text {:style {:color colors/blue}}
           (i18n/label :t/cancel)]])])))

(defonce search-input-state
  (reagent/atom {:show?  false
                 :height (animation/create-value
                          (- styles/search-input-height))
                 :to-hide? false}))

(defn search-input-wrapper
  [search-filter]
  [search-input                                         ;search-filter
   {:on-cancel #(re-frame/dispatch [:search/filter-changed nil])
    :on-focus  (fn [search-filter]
                 (when-not search-filter
                   (re-frame/dispatch [:search/filter-changed ""])))
    :on-change (fn [text]
                 (re-frame/dispatch [:search/filter-changed text]))}])

(defn home-filtered-items-list
  [chats search-filter all-home-items]
  (let  [list-ref (reagent/atom nil)]
    [list/section-list
     {:sections                    [{:title :t/chats
                                     :data (if @search-active? chats all-home-items)}
                                    {:title :t/messages
                                     :data  []}]
      :key-fn                      first
    ;; true by default on iOS
      :stickySectionHeadersEnabled false
      :keyboard-should-persist-taps :always
      :ref           #(reset! list-ref %)
      :header  [search-input-wrapper search-filter]
      :contentInset {:top styles/search-input-height}
      :on-scroll-end-drag
      (fn [e]
        (let [y (-> e .-nativeEvent .-contentOffset .-y)]
          (if (and (< y 0) (> y (- (/ styles/search-input-height 2))))
            (.scrollToLocation @list-ref #js {:sectionIndex 0 :itemIndex 0}))))

      :render-section-header-fn
      (fn [{:keys [title data]}]
        (when @search-active?
          [react/view {:style {:height 40}}
           [react/text {:style styles/filter-section-title}
            (i18n/label title)]]))
      :render-section-footer-fn
      (fn [{:keys [title data]}]
        (when (and @search-active? (empty? data))
          [list/big-list-item
           {:text          (i18n/label (if (= title "messages")
                                         :t/messages-search-coming-soon
                                         :t/no-result))
            :text-color    colors/gray
            :hide-chevron? true
            :action-fn     #()
            :icon          (case title
                             "messages" :main-icons/one-on-one-chat
                             "browser" :main-icons/browser
                             "chats" :main-icons/message)
            :icon-color    colors/gray}]))

      :render-fn                   (fn [home-item]
                                     [inner-item/home-list-item home-item])}]))