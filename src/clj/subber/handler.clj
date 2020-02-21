(ns subber.handler
  (:require
   [reitit.ring :as reitit-ring]
   [subber.middleware :refer [middleware]]
   [hiccup.page :refer [include-js include-css html5]]
   [config.core :refer [env]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.anti-forgery :as anti-forgery :refer [wrap-anti-forgery]]
   [ring.middleware.session :refer [wrap-session]]
   [iapetos.export :as prometheus-export]
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

(defn metrics-handler
  [metrics-registry _req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (prometheus-export/text-format metrics-registry)})

(defn pubsub-handler [_req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (clojure.pprint/pprint _req)})

;; Handler
(defn app [{:keys [ws-router metrics-registry]}]
  (let [{:keys [ring-ajax-get-or-ws-handshake ring-ajax-post]} ws-router]
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
                 :post {:handler ring-ajax-post}}]
       ["/metrics" {:get {:handler (partial metrics-handler metrics-registry)}}]])
     (reitit-ring/routes
      (reitit-ring/create-resource-handler {:path "/" :root "/public"})
      (reitit-ring/create-default-handler))
     {:middleware (conj middleware
                        wrap-keyword-params
                        wrap-params
                        wrap-anti-forgery
                        wrap-session
                        )})))
