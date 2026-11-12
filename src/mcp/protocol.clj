(ns mcp.protocol
  "MCP 2026-07-28 wire-format helpers for JSON-RPC and tools."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def version "2026-07-28")
(def client-info {:name "mcp.clj" :version "1.0.0"})

(def ^:private protocol-version-key
  "io.modelcontextprotocol/protocolVersion")
(def ^:private client-info-key
  "io.modelcontextprotocol/clientInfo")
(def ^:private client-capabilities-key
  "io.modelcontextprotocol/clientCapabilities")
(def ^:private server-info-key
  "io.modelcontextprotocol/serverInfo")

(def error-codes
  {:parse-error -32700
   :invalid-request -32600
   :method-not-found -32601
   :invalid-params -32602
   :internal-error -32603
   :header-mismatch -32020
   :unsupported-protocol-version -32022})

(defn parse-json [s]
  (json/read-str s :key-fn keyword))

(defn write-json [x]
  (json/write-str x))

(defn request
  ([id method params] (request id method params {}))
  ([id method params {:keys [client-info capabilities]}]
   {:jsonrpc "2.0"
    :id id
    :method method
    :params (assoc (or params {})
                   :_meta {protocol-version-key version
                           client-info-key (or client-info mcp.protocol/client-info)
                           client-capabilities-key (or capabilities {})})}))

(defn response [id result server-info]
  {:jsonrpc "2.0"
   :id id
   :result (assoc result
                  :resultType "complete"
                  :_meta {server-info-key server-info})})

(defn error-response
  ([id code message] (error-response id code message nil))
  ([id code message data]
   {:jsonrpc "2.0"
    :id id
    :error (cond-> {:code (if (keyword? code) (get error-codes code) code)
                    :message message}
             data (assoc :data data))}))

(defn request? [msg]
  (and (map? msg)
       (= "2.0" (:jsonrpc msg))
       (string? (:method msg))
       (contains? msg :id)
       (or (string? (:id msg)) (number? (:id msg)))))

(defn notification? [msg]
  (and (map? msg)
       (= "2.0" (:jsonrpc msg))
       (string? (:method msg))
       (not (contains? msg :id))))

(defn response? [msg]
  (and (map? msg)
       (= "2.0" (:jsonrpc msg))
       (contains? msg :id)
       (or (contains? msg :result) (contains? msg :error))))

(defn request-version [msg]
  (let [meta (get-in msg [:params :_meta])]
    (or (get meta (keyword protocol-version-key))
        (get meta protocol-version-key))))

(defn request-client-info [msg]
  (let [meta (get-in msg [:params :_meta])]
    (or (get meta (keyword client-info-key))
        (get meta client-info-key))))

(defn request-client-capabilities [msg]
  (let [meta (get-in msg [:params :_meta])]
    (or (get meta (keyword client-capabilities-key))
        (get meta client-capabilities-key))))

(defn server-info [msg]
  (get-in msg [:result :_meta (keyword server-info-key)]))

(def ^:private header-token
  #"[A-Za-z0-9!#$%&'*+.^_`|~-]+")

(defn- header-plain-safe? [s]
  (and (string? s)
       (or (empty? s)
           (and (not (Character/isWhitespace ^char (first s)))
                (not (Character/isWhitespace ^char (last s)))))
       (every? (fn [c]
                 (let [n (int c)]
                   (or (= n 32) (= n 9) (<= 33 n 126))))
               s)))

(defn header-encode [value]
  (let [s (str value)
        safe? (header-plain-safe? s)
        sentinel? (and (str/starts-with? s "=?base64?")
                       (str/ends-with? s "?="))]
    (if (and safe? (not sentinel?))
      s
      (str "=?base64?"
           (.encodeToString (java.util.Base64/getEncoder)
                            (.getBytes s "UTF-8"))
           "?="))))

(defn header-decode [value]
  (if (and (string? value)
           (str/starts-with? value "=?base64?")
           (str/ends-with? value "?="))
    (try
      (String. (.decode (java.util.Base64/getDecoder)
                        (subs value 9 (- (count value) 2)))
               "UTF-8")
      (catch IllegalArgumentException _
        (throw (ex-info "Invalid Base64-encoded MCP header" {:value value}))))
    (if (header-plain-safe? value)
      value
      (throw (ex-info "Invalid plain MCP header value" {:value value})))))

(defn integer-number? [v]
  (and (number? v)
       (try
         (zero? (.compareTo
                 (bigdec v)
                 (.setScale (bigdec v) 0 java.math.RoundingMode/UNNECESSARY)))
         (catch Exception _ false))))

(defn header-value [v]
  (cond
    (string? v) v
    (integer-number? v) (str (.toBigIntegerExact (bigdec v)))
    (instance? Boolean v) (str/lower-case (str v))
    :else nil))

(defn header-type-matches? [type value]
  (case type
    "string" (string? value)
    "integer" (integer-number? value)
    "boolean" (instance? Boolean value)
    false))

(defn header-annotations
  "Return [{:name header-name :path property-path :type property-type}]
  or throw if an x-mcp-header annotation is invalid."
  [schema]
  (let [found (atom [])
        names (atom #{})]
    (letfn [(visit [node path legal?]
              (when (map? node)
                (doseq [[property definition] (:properties node)]
                  (let [property-path (conj path property)
                        header-name (:x-mcp-header definition)]
                    (when (some? header-name)
                    (let [lower (when (string? header-name)
                                  (str/lower-case header-name))]
                        (when-not (and legal?
                                       (string? header-name)
                                       (re-matches header-token header-name)
                                       (contains? #{"string" "integer" "boolean"}
                                                  (:type definition))
                                       (not (contains? @names lower)))
                          (throw (ex-info "Invalid x-mcp-header annotation"
                                          {:path property-path
                                           :header header-name})))
                        (swap! names conj lower)
                        (swap! found conj {:name header-name
                                           :path property-path
                                           :type (:type definition)})))
                    (visit definition property-path legal?)))
                (doseq [k [:items :oneOf :anyOf :allOf :not :if :then :else]
                        :let [sub (get node k)]
                        child (if (sequential? sub) sub [sub])
                        :when (map? child)]
                  (visit child path false))))]
      (visit schema [] true)
      @found)))

(defn tool->openai [tool]
  {:type "function"
   :function {:name (:name tool)
              :description (or (:description tool) "")
              :parameters (or (:inputSchema tool)
                              {:type "object"
                               :properties {}
                               :additionalProperties false})}})
