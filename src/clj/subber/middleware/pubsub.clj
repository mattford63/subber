(ns subber.middleware.pubsub
  (:require [cheshire.core :as json]
            [clj-gcp.pub-sub.utils :as mqu]
            [clj-gcp.pub-sub.admin :as sut-admin]
            [clj-gcp.pub-sub.core :as sut]
            [config.core :refer [env]]
            [iapetos.core :as prometheus]
            [iapetos.collector.ring :as ring]
            )
  (:import java.util.UUID))

;; ring middlware
(defn pubsub [ps]
  (fn [handler]
    (fn [req]
      (handler (assoc req :pubsub ps)))))

;; pubsub admin
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

;; pubsub simple subscriber handler and state for received messages
(def seen-msgs (atom []))

(defn see-req!
  "Small utility to hook in the debugger."
  [seen-reqs req]
  (swap! seen-reqs conj req))

(defn handler [msgs]
  (doseq [msg msgs]
    (see-req! seen-msgs msg))
  (map #(assoc % :ok? true) msgs))

;; pubsub handler to broadcast all messages via Sente
(defn sente-handler [chsk-send! connected-uids msgs]
  (doseq [uid (:any @connected-uids)]
    (doseq [msg msgs]
      (chsk-send! uid [:pubsub/msg msg])))
  (map #(assoc % :ok? true) msgs))

;; prometheus monitoring
(defn metrics-registry [registry]
  (-> registry
      (prometheus/register
       (prometheus/counter
        :clj-gcp.pub-sub.core/message-count
        {:description "life-cycle of pub-sub msgs",
         :labels      [:state]}))))

;; very quick and hacky publisher
;; - TODO use a proper publisher i.e add to clj-gcp a publisher in the same style as the subscriber
(defn publish [project topic msg]
  (mqu/pubsub-publish msg project topic))

(defn publisher [project topic]
  (partial publish project topic))

(comment
  (create-topic)
  (delete-topic)
  (create-sub))
