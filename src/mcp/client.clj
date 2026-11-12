(ns mcp.client
  "MCP 2026-07-28 client for tools over stdio and Streamable HTTP."
  (:require [clojure.string :as str]
            [mcp.protocol :as protocol]
            [org.httpkit.client :as http])
  (:import (java.io BufferedReader File InputStreamReader OutputStreamWriter
                    PrintWriter)
           (java.lang ProcessBuilder ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(def default-timeout-ms 30000)
(def ^:private transport-header-names
  #{"accept" "content-type" "mcp-protocol-version" "mcp-method" "mcp-name"})

(defn- error! [response]
  (when-let [error (:error response)]
    (throw (ex-info (or (:message error) "MCP request failed")
                    {:response response :error error})))
  response)

(defn- fail-pending! [conn message]
  (let [pending @(:pending conn)]
    (reset! (:pending conn) {})
    (doseq [[_ p] pending]
      (deliver p (ex-info message {:transport (:transport conn)})))))

(defn- stdio-reader! [conn]
  (try
    (loop []
      (when-let [line (.readLine ^BufferedReader (:reader conn))]
        (let [message (try (protocol/parse-json line)
                           (catch Exception _ nil))]
          (when (protocol/response? message)
            (when-let [p (get @(:pending conn) (:id message))]
              (swap! (:pending conn) dissoc (:id message))
              (deliver p message))))
        (recur)))
    (finally
      (reset! (:closed conn) true)
      (fail-pending! conn "MCP server closed its stdio connection"))))

(defn connect-stdio
  "Launch an MCP server as a subprocess. Options: :env, :cwd, :timeout-ms,
  :client-info, and :capabilities."
  [command & [{:keys [env cwd timeout-ms client-info capabilities]
               :or {env {}}}]]
  (when-not (and (sequential? command) (seq command))
    (throw (ex-info "stdio command must be a non-empty argument sequence"
                    {:command command})))
  (let [builder (doto (ProcessBuilder. ^java.util.List (mapv str command))
                  (.redirectError ProcessBuilder$Redirect/INHERIT))
        _ (when cwd (.directory builder (File. (str cwd))))
        _ (doseq [[k v] env]
            (.put (.environment builder) (name k) (str v)))
        process (.start builder)
        conn {:transport :stdio
              :process process
              :writer (PrintWriter.
                       (OutputStreamWriter. (.getOutputStream process) "UTF-8"))
              :reader (BufferedReader.
                       (InputStreamReader. (.getInputStream process) "UTF-8"))
              :write-lock (Object.)
              :pending (atom {})
              :closed (atom false)
              :timeout-ms (or timeout-ms default-timeout-ms)
              :client-info client-info
              :capabilities capabilities
              :tools (atom nil)}
        thread (Thread. #(stdio-reader! conn) "mcp-stdio-reader")]
    (.setDaemon thread true)
    (.start thread)
    conn))

(defn connect-http
  "Connect to a Streamable HTTP MCP endpoint. Options: :timeout-ms,
  :headers, :client-info, and :capabilities."
  [url & [{:keys [timeout-ms headers client-info capabilities]
           :or {headers {}}}]]
  (when-not (and (string? url) (re-matches #"https?://.+" url))
    (throw (ex-info "HTTP transport requires an absolute http(s) URL"
                    {:url url})))
  {:transport :http
   :url url
   :headers headers
   :timeout-ms (or timeout-ms default-timeout-ms)
   :client-info client-info
   :capabilities capabilities
   :closed (atom false)
   :tools (atom nil)})

(defn connect
  "Connect using exactly one of {:command [...]} or {:url \"https://...\"}."
  [{:keys [command url] :as options}]
  (cond
    (and command (not url)) (connect-stdio command options)
    (and url (not command)) (connect-http url options)
    :else (throw (ex-info "Choose exactly one MCP transport: :command or :url"
                          {:options (select-keys options [:command :url])}))))

(defn- request-headers [conn message tool]
  (let [standard {"Content-Type" "application/json"
                  "Accept" "application/json, text/event-stream"
                  "MCP-Protocol-Version" protocol/version
                  "Mcp-Method" (:method message)}
        tool-or-resource-name (or (get-in message [:params :name])
                                  (get-in message [:params :uri]))
        names (when tool-or-resource-name
                {"Mcp-Name" (protocol/header-encode tool-or-resource-name)})
        annotated (when (and tool (= "tools/call" (:method message)))
                    (into {}
                          (keep (fn [{:keys [name path type]}]
                                  (let [value (get-in (get-in message
                                                              [:params :arguments])
                                                      path)]
                                    (when (some? value)
                                      (when-not (protocol/header-type-matches?
                                                 type value)
                                        (throw
                                         (ex-info "Tool argument does not match its x-mcp-header type"
                                                  {:name name :path path
                                                   :type type})))
                                      (when-let [s (protocol/header-value value)]
                                        [(str "Mcp-Param-" name)
                                         (protocol/header-encode s)])))))
                          (protocol/header-annotations (:inputSchema tool))))]
    (merge (into {}
                 (remove (fn [[k _]]
                           (let [k (str/lower-case (clojure.core/name k))]
                             (or (contains? transport-header-names k)
                                 (str/starts-with? k "mcp-param-")))))
                 (:headers conn))
           standard names annotated)))

(defn- sse-responses [body]
  (keep (fn [lines]
          (let [data (keep (fn [line]
                             (when (str/starts-with? line "data:")
                               (str/trim (subs line 5))))
                           lines)]
            (when (seq data)
              (try
                (protocol/parse-json (str/join "\n" data))
                (catch Exception _ nil)))))
        (partition-by str/blank? (str/split-lines body))))

(defn- http-response [conn message tool]
  (let [response @(http/post (:url conn)
                             {:headers (request-headers conn message tool)
                              :body (protocol/write-json message)
                              :timeout (:timeout-ms conn)})
        body (:body response)]
    (when (:error response)
      (throw (ex-info (str "MCP HTTP request failed: "
                           (or (some-> response :error .getMessage)
                               (str (:error response))))
                      {:status (:status response) :cause (:error response)})))
    (let [response-headers (into {} (map (fn [[k v]]
                                           [(str/lower-case (clojure.core/name k)) v])
                                         (:headers response)))
          content-type (str/lower-case (or (get response-headers "content-type") ""))]
      (cond
        (and (string? body) (str/includes? content-type "text/event-stream"))
        (or (some (fn [event]
                    (when (and (protocol/response? event)
                               (= (:id message) (:id event)))
                      event))
                  (sse-responses body))
            (throw (ex-info "MCP SSE response ended without a matching response"
                            {:status (:status response) :body body})))

        (string? body)
        (try
          (protocol/parse-json body)
          (catch Exception e
            (throw (ex-info "MCP server returned invalid JSON"
                            {:status (:status response)} e))))

        :else
        (throw (ex-info "MCP server returned an unsupported response body"
                        {:status (:status response)
                         :content-type content-type}))))))

(defn- send-stdio! [conn message]
  (when @(:closed conn)
    (throw (ex-info "MCP stdio connection is closed" {})))
  (let [request? (contains? message :id)
        response-p (when request? (promise))]
    (when request?
      (swap! (:pending conn) assoc (:id message) response-p))
    (try
      (locking (:write-lock conn)
        (let [writer ^PrintWriter (:writer conn)]
          (.println writer (protocol/write-json message))
          (.flush writer)
          (when (.checkError writer)
            (throw (ex-info "Failed to write to MCP server stdio" {})))))
      (catch Exception e
        (when request? (swap! (:pending conn) dissoc (:id message)))
        (throw e)))
    (if request?
      (let [response (deref response-p (:timeout-ms conn) ::timeout)]
        (if (= ::timeout response)
          (do
            (swap! (:pending conn) dissoc (:id message))
            (locking (:write-lock conn)
              (.println ^PrintWriter (:writer conn)
                        (protocol/write-json
                         {:jsonrpc "2.0"
                          :method "notifications/cancelled"
                          :params {:requestId (:id message)
                                   :reason "Client request timed out"}}))
              (.flush ^PrintWriter (:writer conn)))
            (throw (ex-info "MCP stdio request timed out"
                            {:method (:method message)
                             :timeout-ms (:timeout-ms conn)})))
          (if (instance? Throwable response) (throw response) response)))
      nil)))

(defn- rpc! [conn method params tool]
  (when @(:closed conn)
    (throw (ex-info "MCP connection is closed" {})))
  (let [message (protocol/request
                 (str (random-uuid)) method params
                 {:client-info (:client-info conn)
                  :capabilities (:capabilities conn)})
        response (case (:transport conn)
                   :stdio (send-stdio! conn message)
                   :http (http-response conn message tool)
                   (throw (ex-info "Unknown MCP transport"
                                   {:transport (:transport conn)})))]
    (when-not (and (protocol/response? response)
                   (= (:id message) (:id response)))
      (throw (ex-info "MCP server returned a mismatched JSON-RPC response"
                      {:request-id (:id message) :response response})))
    (error! response)))

(defn discover [conn]
  (:result (rpc! conn "server/discover" {} nil)))

(defn list-tools
  "List all server tools, following pagination cursors."
  [conn]
  (let [cached @(:tools conn)]
    (if (and cached (> (:expires-at cached) (System/currentTimeMillis)))
      (:tools cached)
      (let [pages (loop [cursor nil tools [] seen #{} ttl nil]
                    (let [params (cond-> {} cursor (assoc :cursor cursor))
                          result (:result (rpc! conn "tools/list" params nil))
                          page (:tools result)
                          next-cursor (:nextCursor result)]
                      (when-not (sequential? page)
                        (throw (ex-info "tools/list result is missing tools"
                                        {:result result})))
                      (when (= :http (:transport conn))
                        (doseq [tool page]
                          (protocol/header-annotations (:inputSchema tool))))
                      (when (and next-cursor (contains? seen next-cursor))
                        (throw (ex-info "Server repeated a tools/list cursor"
                                        {:cursor next-cursor})))
                      (if next-cursor
                        (recur next-cursor (into tools page)
                               (conj seen next-cursor)
                               (or (:ttlMs result) ttl))
                        {:tools (into tools page)
                         :ttl (or (:ttlMs result) ttl 0)})))]
        (reset! (:tools conn)
                {:tools (:tools pages)
                 :expires-at (+ (System/currentTimeMillis) (:ttl pages))})
        (:tools pages)))))

(defn call-tool
  "Call a named MCP tool with a JSON-object argument map. Returns its
  MCP result, throwing ex-info for JSON-RPC errors."
  [conn name arguments]
  (when-not (map? arguments)
    (throw (ex-info "Tool arguments must be a map" {:arguments arguments})))
  (let [tool (some #(when (= name (:name %)) %) (list-tools conn))]
    (when-not tool
      (throw (ex-info (str "Unknown MCP tool: " name) {:tool name})))
    (:result (rpc! conn "tools/call"
                   {:name name :arguments arguments}
                   tool))))

(defn close!
  "Close the client connection, including a launched stdio child process."
  [conn]
  (reset! (:closed conn) true)
  (when (= :stdio (:transport conn))
    (fail-pending! conn "MCP client closed the connection")
    (try (.close ^PrintWriter (:writer conn)) (catch Exception _))
    (let [process ^Process (:process conn)]
      (when-not (.waitFor process 2 TimeUnit/SECONDS)
        (.destroy process)
        (when-not (.waitFor process 2 TimeUnit/SECONDS)
          (.destroyForcibly process)))))
  nil)
