(ns subber.middleware.pubsub)

(defn pubsub [ps]
  (fn [handler]
    (fn [req]
      (handler (assoc req :pubsub ps)))))
