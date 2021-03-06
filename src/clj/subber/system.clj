(ns subber.system
    (:require
     [subber.handler :refer [app]]
     [subber.middleware.sente :as sente-mw]
     [subber.middleware.pubsub :as pubsub-mw]
     [config.core :refer [env]]
     [ring.adapter.jetty :refer [run-jetty]]
     [ring.server.standalone :refer [serve]]
     [ring.middleware.file-info :refer [wrap-file-info]]
     [ring.middleware.file :refer [wrap-file]]
     [org.httpkit.server :refer [run-server]]
     [integrant.core :as ig]
     [iapetos.core :as prometheus]
     [taoensso.timbre :as timbre :refer (tracef debugf infof warnf errorf)])
    (:gen-class))

(def config
  {:prod {:adapter/http-kit {:port (or (env :port) 3000)
                             :handler (ig/ref :handler/app)}
          :handler/app {:subscriber (ig/ref :clj-gcp.pub-sub.core/subscriber)
                        :publisher (ig/ref :pubsub/publisher)
                        :ws-router (ig/ref :ws-router/sente)
                        :metrics-registry (ig/ref :prometheus/collector-registry)}
          :clj-gcp.pub-sub.core/subscriber {:handler (ig/ref :pubsub/sente-handler)
                                            :project-id (env :project-id)
                                            :pull-max-messages 10
                                            :subscription-id (env :subscription-id)
                                            :metrics-registry (ig/ref :prometheus/collector-registry)
                                            :json? false
                                            }
          :pubsub/publisher {:project-id (env :project-id)
                             :topic-id (env :subscription-id)}
          :pubsub/sente-handler {:sente (ig/ref :ws-router/sente)}
          :prometheus/collector-registry nil
          :ws-router/sente {:publisher (ig/ref :pubsub/publisher)}}
   :repl {:adapter/http-kit {:port (or (env :port) 3000)
                             :handler (ig/ref :handler/app-dev)}
          :handler/app-dev {:subscriber (ig/ref :clj-gcp.pub-sub.core/subscriber)
                            :publisher (ig/ref :pubsub/publisher)
                            :ws-router (ig/ref :ws-router/sente)
                            :metrics-registry (ig/ref :prometheus/collector-registry)}
          :clj-gcp.pub-sub.core/subscriber {:handler (ig/ref :pubsub/sente-handler)
                                            :project-id (env :project-id)
                                            :pull-max-messages 10
                                            :subscription-id (env :subscription-id)
                                            :metrics-registry (ig/ref :prometheus/collector-registry)
                                            :json? false
                                            }
          :pubsub/publisher {:project-id (env :project-id)
                             :topic-id (env :topic-id)}
          :pubsub/sente-handler {:sente (ig/ref :ws-router/sente)}
          :prometheus/collector-registry nil
          :ws-router/sente {:publisher (ig/ref :pubsub/publisher)
                            :project-id (env :project-id)
                            :topic-id (env :topic-id)
                            :subscription-id (env :subscription-id)}}})

;; ------------------
;; Init methods

(defmethod ig/init-key :adapter/jetty [_ {:keys [handler] :as opts}]
  (run-jetty handler (-> opts
                         (dissoc :handler)
                         (assoc :join? false))))

(defmethod ig/init-key :adapter/serve [_ {:keys [handler] :as opts}]
  (serve handler (-> opts
                     (dissoc :handler))))

(defmethod ig/init-key :adapter/http-kit [_ {:keys [handler] :as opts}]
  (run-server handler (-> opts
                          (dissoc :handler)
                          (assoc :join? false))))

(defmethod ig/init-key :handler/app [_ opts]
  (app opts))

(defmethod ig/init-key :handler/app-dev [_ opts]
  (-> (app opts)
      (wrap-file "resources")
      (wrap-file-info)))

(defmethod ig/init-key :pubsub/gcp [_ _]
  {:fools "gold"})

(defmethod ig/init-key :ws-router/sente [_ opts]
  {:router-stop-fn (sente-mw/start-router! opts) ;; pattern: services return the fn to stop them.
   :ring-ajax-post sente-mw/ring-ajax-post ;; is it sensible to manage these calls like this?
   :ring-ajax-get-or-ws-handshake sente-mw/ring-ajax-get-or-ws-handshake
   :chsk-send! sente-mw/chsk-send!
   :connected-uids sente-mw/connected-uids})

(defmethod ig/init-key :pubsub/sente-handler [_ {:keys [sente]}]
  (partial pubsub-mw/sente-handler (:chsk-send! sente) (:connected-uids sente)))

(defmethod ig/init-key :prometheus/collector-registry [_ _]
  (-> (prometheus/collector-registry)
      pubsub-mw/metrics-registry))

(defmethod ig/init-key :pubsub/publisher [_ {:keys [project-id topic-id]}]
  (pubsub-mw/publisher project-id topic-id))

;; -------------------------
;; Halt methods
(defmethod ig/halt-key! :adapter/jetty [_ jetty]
   (.stop jetty))

(defmethod ig/halt-key! :adapter/serve [_ server]
  (.stop server))

(defmethod ig/halt-key! :adapter/http-kit [_ server]
  (server))

(defmethod ig/halt-key! :ws-router/sente [_ {:keys [router-stop-fn]}]
  (router-stop-fn))

;; -------------------------
;; Main
(defn -main [& args]
  (ig/init (:prod config))
  (let [uri (str "http://localhost:" (get-in config [:prod :adapter/http-kit :port]))]
    (infof "Web server is running at `%s`" uri)
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch java.awt.HeadlessException _))))
