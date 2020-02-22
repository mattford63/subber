(ns subber.core
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require
   [reagent.core :as reagent :refer [atom]]
   [reagent.session :as session]
   [reitit.frontend :as reitit]
   [clerk.core :as clerk]
   [accountant.core :as accountant]
   [cljs.core.async :as async :refer (<! >! put! chan)]
   [taoensso.sente :as sente :refer (cb-success?)]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.encore :as encore :refer-macros (have have?)]))

;; ---------------------
;; Sente init

(def output-el (or (.getElementById js/document "output") ""))

(defn ->output! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (aset output-el "value" (str "• " (.-value output-el) "\n" msg))
    (aset output-el "scrollTop" (.-scrollHeight output-el))))

(def ?csrf-token
  (when-let [el (.getElementById js/document "sente-csrf-token")]
    (.getAttribute el "data-csrf-token")))

(if ?csrf-token
  (->output! "CSRF token detected in HTML, great!")
  (->output! "CSRF token NOT detected in HTML, default Sente config will reject requests"))

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client! "/chsk" ; Note the same path as before
                                         ?csrf-token
                                         {:type :auto ; e/o #{:auto :ajax :ws}
                                          :wrap-recv-evs? false})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  )

;; -------------------------
;; Sente Event Handlers -- lifted from
;;   https://github.com/ptaoussanis/sente/blob/master/example-project/src/example/client.cljs

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (->output! "Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (let [[old-state-map new-state-map] (have vector? ?data)]
    (if (:first-open? new-state-map)
      (->output! "Channel socket successfully established!: %s" new-state-map)
      (->output! "Channel socket state change: %s"              new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (->output! "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (->output! "Handshake: %s" ?data)))

(defonce received-msgs (atom (sorted-map)))

(defonce counter (atom 0))

(defn add-msg [msg]
  (let [id (swap! counter inc)]
    (swap! received-msgs assoc id msg)))

(defmethod -event-msg-handler :pubsub/msg
  [{:as ev-msg :keys [?data]}]
  (->output! "Push event from server: %s" ?data)
  (add-msg ?data))

;; -------------------------
;; Sente event router (our `event-msg-handler` loop)
;;
(defonce sente_router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @sente_router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! sente_router_
          (sente/start-client-chsk-router!
           ch-chsk event-msg-handler)))

;; -------------------------
;; UI - TODO unclear why this doesn't work
;; (when-let [target-el (.getElementById js/document "btn1")]
;;   (.addEventListener target-el "click"
;;                      (fn [ev]
;;                        (->output! "Button 1 was clicked (won't receive any reply from server)")
;;                        (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))


;; -------------------------
;; Routes

(def router
  (reitit/router
   [["/" :index]
    ["/metrics" :metrics]
    ["/about" :about]]))

(defn path-for [route & [params]]
  (if params
    (:path (reitit/match-by-name router route params))
    (:path (reitit/match-by-name router route))))

(path-for :about)
(path-for :metrics)

;; -------------------------
;; Page components

(defn debug-buttons []
  (fn []
    [:span.main
     [:h4 "Internal Debug buttons"]
     [:button#btn1 {:type "button"
                    :on-click (fn [ev]
                                (->output! "Button 1 was clicked (won't receive any reply from server)")
                                (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))}
      "chsk-send! (w/o reply)"]
     [:button#btn2 {:type "button"
                    :on-click (fn [ev]
                                (->output! "Button 2 was clicked (should see a response from server)")
                                (chsk-send! [:example/button2 {:had-a-callback? "yes"}]
                                            5000
                                            (fn [cb-reply] (->output! "Callback reply: %s" cb-reply))))}
      "chsk-send! (with reply)"]
     [:button#btn3 {:type "button"
                    :on-click (fn [ev]
                                (->output! "Button 3 was clicked (should see a response from server)")
                                (chsk-send! [:fn/inc {:counter 1}]
                                            5000
                                            (fn [cb-reply] (->output! "Inc: %s" cb-reply))))}
      "inc"]]))

;; https://stackoverflow.com/questions/41680143/how-is-a-chat-input-field-defined-in-reagent?noredirect=1&lq=1
(defn update-rows
  [row-count-atom max-rows dom-node value]
  (let [field-height   (.-clientHeight dom-node)
        content-height (.-scrollHeight dom-node)]
    (cond
      (and (not-empty value)
           (> content-height field-height)
           (< @row-count-atom max-rows))
      (swap! row-count-atom inc)

      (empty? value)
      (reset! row-count-atom 1))))

(defn expanding-textarea
  "a textarea which expands up to max-rows as it's content expands"
  [{:keys [max-rows] :as opts}]
  (let [dom-node      (atom nil)
        row-count     (atom 1)
        written-text  (atom "")
        enter-keycode 13]
    (reagent/create-class
     {:display-name "expanding-textarea"

      :component-did-mount
      (fn [ref]
        (reset! dom-node (reagent/dom-node ref))
        (update-rows row-count max-rows @dom-node @written-text))

      :component-did-update
      (fn []
        (update-rows row-count max-rows @dom-node @written-text))

      :reagent-render
      (fn [{:keys [on-change-fn] :as opts}]
        (let [opts (dissoc opts :max-rows)]
          [:textarea
           (merge opts
                  {:rows        @row-count
                   :value       @written-text
                   :on-change   (fn [e]
                                  (reset! written-text (-> e .-target .-value)))
                   :on-key-down (fn [e]
                                  (let [key-code (.-keyCode e)]
                                    (when (and (= enter-keycode key-code)
                                               (not (.-shiftKey e))
                                               (not (.-altKey e))
                                               (not (.-ctrlKey e))
                                               (not (.-metaKey e)))
                                      (do
                                        (.preventDefault e)
                                        (chsk-send! [:pubsub/publish {:text @written-text}])
                                        (reset! written-text "")))))})]))})))

(defn pubsub-submit-section []
  (fn []
    [:span.main
     [:h4 "Type your message to send"]
     [expanding-textarea {:max-rows 10}]]))

(defn pubsub-response-section []
  (fn []
    [:span.main
     [:h4 "Pubsub Response"]
     [:ul {:style {:list-style-type "none"}}
      (map (fn [msg] (let [id (key msg)
                           msg-id (str "msg-" id)
                           payload (:payload (val msg ))
                           ack-id (:pubsub/ack-id (val msg))]
                       [:li {:name msg-id :key msg-id}
                        [:b id] (str " " payload " ");
                        [:i [:small [:sub (take 16 ack-id)]]]]))
           (take 10 (reverse @received-msgs)))]]))

(defonce pubsub-connection-settings (atom {}))

(defn get-pubsub-connection-settings []
  (chsk-send! [:pubsub/settings {}]
              5000
              (fn [cb-reply] (reset! pubsub-connection-settings cb-reply))))

(defn pubsub-connections-section []
  (fn []
    [:span.main
     [:h4 "Pubsub Connection Details"]
     [:button#refresh {:type "button"
                       :on-click (fn [ev]
                                   (get-pubsub-connection-settings))} "Refresh"]
     [:ul {:style {:list-style-type "disc"}}
      [:li [:b "Project Id:      "] (:project-id @pubsub-connection-settings)]
      [:li [:b "Topic Id:        "] (:topic-id @pubsub-connection-settings)]
      [:li [:b "Subscription id: "] (:subscription-id @pubsub-connection-settings)]]
     ]))

(defn home-page []
  (fn []
    [:span.main
     [:h1 "Welcome to Subber PoC"]
     "This PoC allows you to easily send a message to a pubsub topic.
     It will then dynamically update showing the last 10 results."
     [pubsub-connections-section]
     [pubsub-submit-section]
     [pubsub-response-section]
     [debug-buttons]]))

(defn about-page []
  (fn [] [:span.main
          [:h1 "To Be Done"]
          [:ul
           [:li "JSON Validation of messages"]
           [:li "Syntax highlighting of JSON text area"]
           [:li "Buttons to fill text area with create/update/delete events"]
           [:li "Schema Validation"]
           [:li "CSS for list items (foldable text?)"]
           [:li "Tests"]
           [:li "Initial startup issues (CSRF timing issues forces refreshes)"]]
          ]))

;; -------------------------
;; Translate routes -> page components

(defn page-for [route]
  (case route
    :index #'home-page
    :about #'about-page
    ))


;; -------------------------
;; Page mounting component

(defn current-page []
  (fn []
    (let [page (:current-page (session/get :route))]
      [:div
       [:header
        [:p [:a {:href (path-for :index)} "Home"] " | "
         [:a {:href (path-for :about)} "To Be Done"] " | "
         [:a {:href (path-for :metrics)} "Metrics"]]]
       [page]
       [:footer
        [:p "Subber can be found here on "
         [:a {:href "https://github.com/mattford63/subber"} "Github"] "."]]])))

;; -------------------------
;; Initialize app

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (clerk/initialize!)
  (accountant/configure-navigation!
   {:nav-handler
    (fn [path]
      (let [match (reitit/match-by-path router path)
            current-page (:name (:data  match))
            route-params (:path-params match)]
        (reagent/after-render clerk/after-render!)
        (session/put! :route {:current-page (page-for current-page)
                              :route-params route-params})
        (clerk/navigate-page! path)
        ))
    :path-exists?
    (fn [path]
      (boolean (reitit/match-by-path router path)))})
  (accountant/dispatch-current!)
  (mount-root))

;; start sente router
(defn start! [] (start-router!))
(defonce _start-once (start!))
