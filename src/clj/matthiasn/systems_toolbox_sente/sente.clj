(ns matthiasn.systems-toolbox-sente.sente
  (:gen-class)
  (:require
    [clojure.tools.logging :as log]
    [ring.middleware.defaults :as rmd]
    [ring.util.response :refer [resource-response response content-type]]
    [compojure.core :refer (routes GET POST)]
    [compojure.route :as route]
    [clojure.core.async :refer [<! chan put! mult tap pub sub timeout go-loop sliding-buffer]]
    [immutant.web :as immutant]
    [immutant.web.undertow :as undertow]
    [taoensso.sente :as sente]
    [taoensso.sente.server-adapters.immutant :refer (sente-web-server-adapter)]
    [taoensso.sente.packers.transit :as sente-transit]))

(def ring-defaults-config (assoc-in rmd/site-defaults [:security :anti-forgery]
                                    {:read-token (fn [req] (-> req :params :csrf-token))}))

(defn random-user-id
  "generates unique ID for request"
  [req]
  (let [uid (str (java.util.UUID/randomUUID))]
    (log/info "Connected:" (:remote-addr req) uid)
    uid))

(defn make-handler
  "Create handler function for messages from WebSocket connection. Calls put-fn with received messages."
  [_ put-fn]
  (fn [{:keys [event]}]
    (let [[cmd-type {:keys [msg msg-meta]}] event]
      (put-fn (with-meta [cmd-type msg] msg-meta)))))

(def default-host (get (System/getenv) "HOST" "localhost"))
(def default-port (get (System/getenv) "PORT" "8888"))

(defn sente-comp-fn
  "Return clean initial component state atom."
  [{:keys [index-page-fn middleware user-id-fn host port undertow-cfg]
    :or   {user-id-fn random-user-id
           host default-host
           port default-port}}]
  (fn [put-fn]
    (let [undertow-cfg (merge {:host host :port port} undertow-cfg)
          ws (sente/make-channel-socket! sente-web-server-adapter {:user-id-fn user-id-fn
                                                                   :packer (sente-transit/get-flexi-packer :edn)})
          {:keys [ch-recv ajax-get-or-ws-handshake-fn ajax-post-fn]} ws
          cmp-routes (routes
                       (GET "/" req (content-type (response (index-page-fn req)) "text/html"))
                       (GET "/chsk" req (ajax-get-or-ws-handshake-fn req))
                       (POST "/chsk" req (ajax-post-fn req))
                       (route/resources "/")
                       (route/not-found "Page not found"))]
      (let [ring-handler (rmd/wrap-defaults cmp-routes ring-defaults-config)
            wrapped-in-middleware (if middleware (middleware ring-handler) ring-handler)
            server (immutant/run wrapped-in-middleware (undertow/options undertow-cfg))]
        (log/info "Immutant-web is listening on port" port "on interface" host)
        (when-let [ssl-port (:ssl-port undertow-cfg)]
          (log/info "Immutant-web is listening on SSL-port" ssl-port "on interface" host))
        (sente/start-chsk-router! ch-recv (make-handler ws put-fn))
        {:state       ws
         :shutdown-fn #(immutant/stop server)}))))

(defn all-msgs-handler
  "Handle incoming messages: process / add to application state."
  [{:keys [cmp-state msg-type msg-meta msg-payload]}]
  (let [ws cmp-state
        chsk-send! (:send-fn ws)
        connected-uids (:any @(:connected-uids ws))
        dest-uid (:sente-uid msg-meta)
        msg-w-ser-meta [msg-type {:msg msg-payload :msg-meta msg-meta}]]
    (if dest-uid
      (when (contains? connected-uids dest-uid)
        (chsk-send! dest-uid msg-w-ser-meta))
      (doseq [uid connected-uids]
        (chsk-send! uid msg-w-ser-meta)))))

(defn cmp-map
  "Creates server-side WebSockets communication component map."
  {:added "0.3.1"}
  [cmp-id cfg-map-or-index-page-fn]
  (let [cfg-map (if (map? cfg-map-or-index-page-fn)
                  cfg-map-or-index-page-fn
                  {:index-page-fn cfg-map-or-index-page-fn})]
    {:cmp-id           cmp-id
     :state-fn         (sente-comp-fn cfg-map)
     :all-msgs-handler all-msgs-handler
     :opts             {:watch :connected-uids
                        :snapshots-on-firehose false}}))
