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

(def output-el (or (.getElementById js/document "output") "matt"))

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
                                          })]
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
    ["/items"
     ["" :items]
     ["/:item-id" :item]]
    ["/about" :about]]))

(defn path-for [route & [params]]
  (if params
    (:path (reitit/match-by-name router route params))
    (:path (reitit/match-by-name router route))))

(path-for :about)

;; -------------------------
;; Page components

(def buttons
  [:p
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
    "inc"]])

(defn home-page []
  (fn []
    [:span.main
     [:h1 "Welcome to Subber"]
     [:ul
      [:li [:a {:href (path-for :items)} "Items of subber"]]
      [:li [:a {:href "/broken/link"} "Broken link"]]]
     buttons]))

(defn items-page []
  (fn []
    [:span.main
     [:h1 "The items of subber"]
     [:ul (map (fn [item-id]
                 [:li {:name (str "item-" item-id) :key (str "item-" item-id)}
                  [:a {:href (path-for :item {:item-id item-id})} "Item: " item-id]])
               (range 1 60))]]))


(defn item-page []
  (fn []
    (let [routing-data (session/get :route)
          item (get-in routing-data [:route-params :item-id])]
      [:span.main
       [:h1 (str "Item " item " of subber")]
       [:p [:a {:href (path-for :items)} "Back to the list of items"]]])))


(defn about-page []
  (fn [] [:span.main
          [:h1 "About subber"]]))

;; -------------------------
;; Translate routes -> page components

(defn page-for [route]
  (case route
    :index #'home-page
    :about #'about-page
    :items #'items-page
    :item #'item-page))


;; -------------------------
;; Page mounting component

(defn current-page []
  (fn []
    (let [page (:current-page (session/get :route))]
      [:div
       [:header
        [:p [:a {:href (path-for :index)} "Home"] " | "
         [:a {:href (path-for :about)} "About subber"]]]
       [page]
       [:footer
        [:p "subber was generated by the "
         [:a {:href "https://github.com/reagent-project/reagent-template"} "Reagent Template"] "."]]])))

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
