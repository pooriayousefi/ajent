(ns time-server.core
  (:require [clojure.data.json :as json])
  (:import [com.sun.net.httpserver HttpExchange]
           [java.time Instant ZonedDateTime ZoneOffset]))

(defn send-response [^HttpExchange exchange status ^String body]
  (let [bytes (.getBytes body "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

;; Define schemas as native Clojure data structures
(def tool-schemas
  [{:type "function"
    :function {:name "get_current_time"
               :description "Returns the current UTC date and time in ISO-8601 format."
               :parameters {:type "object" :properties {}}}}
   {:type "function"
    :function {:name "get_local_time"
               :description "Returns the current local date and time in ISO-8601 format based on the system's default timezone."
               :parameters {:type "object" :properties {}}}}
   {:type "function"
    :function {:name "get_unix_timestamp"
               :description "Returns the current Unix epoch timestamp in seconds."
               :parameters {:type "object" :properties {}}}}])

(defn handle-schema [^HttpExchange exchange]
  (send-response exchange 200 (json/write-str tool-schemas)))

(defn handle-tool-call [^HttpExchange exchange]
  (try
    (let [body (slurp (.getRequestBody exchange) :encoding "UTF-8")
          ;; Safely parse JSON request into a Clojure map
          {:keys [name arguments]} (json/read-str body :key-fn keyword)

          ;; Build response map based on tool name
          res (case name
                "get_current_time"
                {:result (str (ZonedDateTime/now ZoneOffset/UTC)) :is_error false}

                "get_local_time"
                {:result (str (ZonedDateTime/now)) :is_error false}

                "get_unix_timestamp"
                {:result (.getEpochSecond (Instant/now)) :is_error false}

                ;; Fallback for unknown tools
                {:error (str "Unknown tool: " name) :is_error true})]

      ;; Serialize response map back to JSON
      (send-response exchange 200 (json/write-str res)))

    (catch Exception e
      (let [err-res {:error (str "Server error: " (.getMessage e)) :is_error true}]
        (send-response exchange 500 (json/write-str err-res))))))