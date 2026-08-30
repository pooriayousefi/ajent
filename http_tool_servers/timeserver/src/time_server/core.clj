(ns time-server.core
  (:require [aleph.http :as http]
            [clojure.data.json :as json])
  (:import [java.time ZonedDateTime LocalDateTime ZoneOffset])
  (:gen-class))

;; FIX 4: Removed the illogical "content" parameter. Time tools take no arguments.
(def tool-schema
  [{:type "function"
    :function {:name "get_current_utc_date_time"
               :description "Gets the current system date and time (UTC) in ISO 8601 format. Useful for finding out what day 'today' is."
               :parameters {:type "object"
                            :properties {}
                            :required []}}}
   {:type "function"
    :function {:name "get_current_local_date_time"
               :description "Gets the current system date and time in the local system timezone in ISO 8601 format."
               :parameters {:type "object"
                            :properties {}
                            :required []}}}])

(defn- read-body [request]
  (let [body-stream (:body request)
        body-string (when body-stream (slurp body-stream))]
    (if body-string
      (json/read-str body-string :key-fn keyword)
      {})))

;; Small helpers so the handler stays readable
(defn- utc-now-str []
  (str (ZonedDateTime/now ZoneOffset/UTC)))

(defn- local-now-str []
  (str (LocalDateTime/now)))

(defn- json-response
  ([status body-map] (json-response status body-map {}))
  ([status body-map extra-headers]
   {:status status
    :headers (merge {"Content-Type" "application/json"} extra-headers)
    :body (json/write-str body-map)}))

(defn handler [request]
  (cond
    (and (= (:request-method request) :get) (= (:uri request) "/health"))
    (json-response 200 {:status "healthy"})

    (and (= (:request-method request) :get) (= (:uri request) "/schema"))
    (json-response 200 tool-schema)

    ;; FIX 1: Changed URI from /tool/calls to /tools/call to match the standard
    (and (= (:request-method request) :post) (= (:uri request) "/tools/call"))
    (let [body (read-body request)
          ;; FIX 2: Parse :name directly, as the framework sends {"name": "...", "arguments": {...}}
          tool-name (:name body)]
      (cond
        (= tool-name "get_current_utc_date_time")
        ;; FIX 3: Return {"result": "...", "is_error": false} instead of {"content": "..."}
        (json-response 200 {:result (utc-now-str) :is_error false})

        (= tool-name "get_current_local_date_time")
        ;; FIX 3: Return {"result": "...", "is_error": false}
        (json-response 200 {:result (local-now-str) :is_error false})

        :else
        (json-response 400 {:error (str "Unknown tool: " tool-name) :is_error true})))

    :else
    (json-response 404 {:error "Endpoint not found"})))

(defonce ^:private server (atom nil))

(defn start! [& {:keys [port] :or {port 8082}}]
  (reset! server (http/start-server handler {:port port}))
  (println (str "time-server listening on http://localhost:" port)))

(defn stop! []
  (when-let [s @server]
    (.close s)
    (reset! server nil)))