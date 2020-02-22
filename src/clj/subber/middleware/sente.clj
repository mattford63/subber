(ns subber.middleware.sente
  (:require
   [taoensso.sente :as sente]
   [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
   [clojure.core.async :as async  :refer (<! <!! >! >!! put! chan go go-loop)]
   [taoensso.encore    :as encore :refer (swap-in! reset-in! swapped have have! have?)]
   [taoensso.timbre    :as timbre :refer (tracef debugf infof warnf errorf)]
   [taoensso.sente     :as sente]
   ))

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
  [{:as ev-msg :keys [id ?data event]} {:as opts :or {}}]
  (-event-msg-handler ev-msg opts)
  ;; (future (-event-msg-handler ev-msg))
  )

(defmethod -event-msg-handler
  :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]} _]
  "Default event message handler"
  "default"
  (let [session (:session ring-req)
        uid (:uid session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:unmatched-event-as-echoed-from-server event}))))

(defmethod -event-msg-handler
  :fn/inc
  [{:as ev-msg :keys [?data ?reply-fn]} _]
  "increment a number"
  "inc"
  (when ?reply-fn
    (?reply-fn (update-in ?data [:counter] inc))))

(defmethod -event-msg-handler
  :pubsub/publish
  [{:as ev-msg :keys [?data]} {:keys [publisher]}]
  "Publish data to pubsub"
  (publisher (:text ?data)))

(defmethod -event-msg-handler
  :pubsub/settings
  [{:as ev-msg :keys [?reply-fn]} {:keys [project-id topic-id subscription-id] :as opts}]
  "Return pubsub connection settings"
  (when ?reply-fn
    (?reply-fn {:project-id project-id :topic-id topic-id :subscription-id subscription-id})))

;; Sente event router

(defn- -start-chsk-router!
  [server? ch-recv event-msg-handler event-msg-handler-opts opts]
  (let [{:keys [trace-evs? error-handler simple-auto-threading?]} opts
        ch-ctrl (chan)
        execute1 (if simple-auto-threading?
                   (fn [f] (future-call f))
                   (fn [f] (f)))]

    (go-loop []
      (let [[v p] (async/alts! [ch-recv ch-ctrl])
            stop? (or (= p ch-ctrl) (nil? v))]

        (when-not stop?
          (let [{:as event-msg :keys [event]} v]

            (execute1
              (fn []
                (encore/catching
                  (do
                    (when trace-evs? (tracef "Pre-handler event: %s" event))
                    (event-msg-handler
                      (if server?
                        (have! sente/server-event-msg? event-msg)
                        (have! sente/client-event-msg? event-msg))
                      event-msg-handler-opts))
                  e1
                  (encore/catching
                    (if-let [eh error-handler]
                      (error-handler e1 event-msg)
                       (errorf e1 "Chsk router `event-msg-handler` error: %s" event))
                    e2 (errorf e2 "Chsk router `error-handler` error: %s"     event)))))

            (recur)))))

    (fn stop! [] (async/close! ch-ctrl))))

(defn start-server-chsk-router!
  "Creates a simple go-loop to call `(event-msg-handler <server-event-msg>)`
  Or for simple automatic future-based threading of every request, enable
  the `:simple-auto-threading?` opt (disabled by default)."
  [ch-recv event-msg-handler event-msg-handler-opts &
   [{:as opts :keys [trace-evs? error-handler simple-auto-threading?]}]]
  (-start-chsk-router! :server ch-recv event-msg-handler event-msg-handler-opts opts))

(defn stop-router! [router] (router))

(defn start-router! [event-msg-handler-opts]
  (start-server-chsk-router! ch-chsk event-msg-handler event-msg-handler-opts))
