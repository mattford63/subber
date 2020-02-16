(ns subber.system
    (:require
     [subber.handler :refer [app]]
     [config.core :refer [env]]
     [ring.adapter.jetty :refer [run-jetty]]
     [ring.server.standalone :refer [serve]]
     [ring.middleware.file-info :refer [wrap-file-info]]
     [ring.middleware.file :refer [wrap-file]]
     [integrant.core :as ig])
    (:gen-class))

(def config
  {:prod {:adapter/jetty {:port (or (env :port) 3000)
                          :handler (ig/ref :handler/app)}
          :handler/app {:pubsub (ig/ref :pubsub/gcp)}
          :pubsub/gcp nil}
   :repl {:adapter/serve {:port (or (env :port) 3000)
                          :auto-reload? true
                          :handler (ig/ref :handler/app-dev)}
          :handler/app-dev {:pubsub (ig/ref :pubsub/gcp)}
          :pubsub/gcp nil}})

(defmethod ig/init-key :adapter/jetty [_ {:keys [handler] :as opts}]
  (run-jetty handler (-> opts
                         (dissoc :handler)
                         (assoc :join? false))))

(defmethod ig/init-key :adapter/serve [_ {:keys [handler] :as opts}]
  (serve handler (-> opts
                     (dissoc :handler)
                     (assoc :join? false))))

(defmethod ig/init-key :handler/app [_ {:keys [pubsub]}]
  (app pubsub))

(defmethod ig/init-key :handler/app-dev [_ {:keys [pubsub]}]
  (-> (app pubsub)
      (wrap-file "resources")
      (wrap-file-info)))

(defmethod ig/init-key :pubsub/gcp [_ _]
  {:fools "gold"})

(defmethod ig/halt-key! :adapter/jetty [_ jetty]
   (.stop jetty))

(defmethod ig/halt-key! :adapter/serve [_ server]
  (.stop server))

(defn -main [& args]
  (ig/init (:prod config)))
