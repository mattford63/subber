(ns subber.middleware.pubsub
  (:require [cheshire.core :as json]
            [clj-gcp.pub-sub.utils :as mqu]
            [clj-gcp.pub-sub.admin :as sut-admin]
            [clj-gcp.pub-sub.core :as sut]
            [config.core :refer [env]])
  (:import java.util.UUID))

(defn pubsub [ps]
  (fn [handler]
    (fn [req]
      (handler (assoc req :pubsub ps)))))

(defn create-topic []
  (let [topic-id "DELETEME.subber"]
    (sut-admin/create-topic (env :project-id) topic-id)))

(defn delete-topic []
  (let [topic-id "DELETEME.subber"]
    (sut-admin/delete-topic (env :project-id) topic-id)))

(defn create-sub []
  (let [topic-id "DELETEME.subber"
        subscription-id "DELETEME.subber"
        ack-deadline-seconds 30]
    (sut-admin/create-subscription (env :project-id) topic-id subscription-id ack-deadline-seconds)))

(comment
  (create-topic)
  (delete-topic)
  (create-sub))
