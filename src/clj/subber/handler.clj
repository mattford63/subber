(ns subber.handler
  (:require
   [reitit.ring :as reitit-ring]
   [subber.middleware :refer [middleware]]
   [subber.middleware.pubsub :refer [pubsub]]
   [hiccup.page :refer [include-js include-css html5]]
   [config.core :refer [env]]
   [taoensso.sente :as sente]
   [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.anti-forgery :as anti-forgery :refer [wrap-anti-forgery]]
   [ring.middleware.session :refer [wrap-session]]
   [clojure.core.async :as async  :refer (<! <!! >! >!! put! chan go go-loop)]
   [taoensso.encore    :as encore :refer (have have?)]
   [taoensso.timbre    :as timbre :refer (tracef debugf infof warnf errorf)]
   [taoensso.sente     :as sente]
   ))

(def mount-target
  [:div#app
   [:h2 "Welcome to subber"]
   [:p "please wait while Figwheel is waking up ..."]
   [:p "(Check the js console for hints if nothing exciting happens.)"]])

(defn head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport"
           :content "width=device-width, initial-scale=1"}]
   (include-css (if (env :dev) "/css/site.css" "/css/site.min.css"))])

(defn loading-page []
  (html5
   (head)
   [:body {:class "body-container"}
    mount-target
    (let [csrf-token (force anti-forgery/*anti-forgery-token*)]
      [:div#sente-csrf-token {:data-csrf-token csrf-token}])
     (include-js "/js/app.js")]))

(defn index-handler
  [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (loading-page)})

(defn pubsub-handler  [_req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (clojure.pprint/pprint _req)})

;; Sente Channel Socket
;;
(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! (get-sch-adapter) {})]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

;; Sente Event Handler
;;

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler`"
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg)
  ;; (future (-event-msg-handler ev-msg))
  )

(defmethod -event-msg-handler
  :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  "Default event message handler"
  "default"
  (let [session (:session ring-req)
        uid (:uid session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:unmatched-event-as-echoed-from-server event}))))

(defmethod -event-msg-handler
  :fn/inc
  [{:as ev-msg :keys [?data ?reply-fn]}]
  "increment a number"
  "inc"
  (when ?reply-fn
    (?reply-fn (update-in ?data [:counter] inc))))

;; Sente event router
(defonce sente_router_ (atom nil))
(defn sente-stop-router! [] (when-let [stop-fn @sente_router_] (stop-fn)))
(defn sente-start-router! []
  (sente-stop-router!)
  (reset! sente_router_
          (sente/start-server-chsk-router! ch-chsk event-msg-handler)))


;; Handler
(defn app [ps]
  (reitit-ring/ring-handler
   (reitit-ring/router
    [["/" {:get {:handler index-handler}}]
     ["/items"
      ["" {:get {:handler index-handler}}]
      ["/:item-id" {:get {:handler index-handler
                          :parameters {:path {:item-id int?}}}}]]
     ["/about" {:get {:handler index-handler}}]
     ["/pubsub" {:get {:handler pubsub-handler}}]
     ["/chsk" {:get {:handler ring-ajax-get-or-ws-handshake}
               :post {:handler ring-ajax-post}}]])
   (reitit-ring/routes
    (reitit-ring/create-resource-handler {:path "/" :root "/public"})
    (reitit-ring/create-default-handler))
   {:middleware (conj middleware
                      wrap-keyword-params
                      wrap-params
                      wrap-anti-forgery
                      wrap-session
                      (pubsub ps))}))
