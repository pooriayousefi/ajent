(ns ajent.tools
  (:require [clojure.data.json :as json]
            [clj-http.client   :as client])
  (:import [java.io File]))

(def ^:private tool-config-file "tool_servers.json")

(defn- load-tool-config []
  (let [f (File. tool-config-file)]
    (if (not (.exists f))
      (do
        (println (str "⚠️  " tool-config-file " not found — no tool servers will be loaded."))
        {:rest_servers []})
      (try
        (let [data (json/read-str (slurp f) :key-fn keyword)]
          ;; Silently ignore any legacy :mcp_servers keys from older configurations
          (-> data
              (select-keys [:rest_servers])
              (update :rest_servers #(or % []))))
        (catch Exception e
          (println (str "⚠️  Could not parse " tool-config-file ": " (.getMessage e)))
          {:rest_servers []})))))

;; ---------------------------------------------------------------------------
;; REST Server Discovery
;; ---------------------------------------------------------------------------

(defn- body->str [body]
  (cond
    (nil? body) ""
    (string? body) body
    :else (slurp body)))

(defn- fetch-http-server-info [{:keys [name url timeout-ms]}]
  (try
    (let [resp   (client/get (str url "/schema")
                             {:as :text :throw-exceptions false
                              :socket-timeout (or timeout-ms 5000)
                              :conn-timeout   (or timeout-ms 5000)})
          status (:status resp)
          body   (body->str (:body resp))]
      (if (= status 200)
        (let [data    (json/read-str body :key-fn keyword)
              schemas (if (vector? data) data [data])]
          (into {}
                (for [schema schemas
                      :let [tool-name (get-in schema [:function :name])]]
                  (if tool-name
                    {tool-name {:schema schema :url url :type :rest :server name}}
                    (println (str "    [warn] No tool name in schema from " url))))))
        (println (str "    [warn] " (or name url) " returned HTTP " status))))
    (catch Exception e
      (println (str "    [warn] Could not reach " (or name url) ": " (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Unified Registry
;; ---------------------------------------------------------------------------

(defn- build-registry []
  (let [{:keys [rest_servers]} (load-tool-config)
        rest-servers (filter #(and (map? %) (not (false? (:enabled %))) (:url %)) rest_servers)]

    (println "Discovering tools…")
    (println (str "  REST servers configured: " (count rest-servers)))

    (let [rest-results (pmap fetch-http-server-info rest-servers)
          all-tools    (into {} (remove nil? rest-results))]

      (println (str "\n✅ Registry built with " (count all-tools) " tool(s):"))
      (doseq [[k v] (sort-by first all-tools)]
        (println (str "   - " (name k) "   [" (:type v) " · " (:server v "unnamed") "]")))
      all-tools)))

(defonce ^:private registry-cache (atom nil))

(defn- get-registry []
  (or @registry-cache
      (reset! registry-cache (build-registry))))

(defn refresh-registry! []
  (reset! registry-cache (build-registry)))

(defn get-tools
  "Returns a vector of OpenAI function schemas for the LLM."
  []
  (mapv :schema (vals (get-registry))))

(defn get-tools-by-names
  "Returns a vector of tool schemas filtered dynamically by tool names."
  [tool-names]
  (let [registry (get-registry)
        name-set (set tool-names)]
    (->> registry
         vals
         (filter #(name-set (get-in % [:schema :function :name])))
         (mapv :schema))))

(defn- handle-rest-call
  "Executes a REST tool server call."
  [info tool-name args-map]
  (try
    (let [resp   (client/post (str (:url info) "/tools/call")
                              {:headers {"Content-Type" "application/json"}
                               :body    (json/write-str {:name tool-name :arguments args-map})
                               :as      :text
                               :throw-exceptions false
                               :socket-timeout (or (:timeout-ms info) 30000)
                               :conn-timeout   (or (:timeout-ms info) 30000)})
          status (:status resp)
          body   (body->str (:body resp))]
      (if (= status 200)
        (let [parsed-body (json/read-str body :key-fn keyword)]
          (if (:is_error parsed-body)
            (str "Error: " (:error parsed-body))
            (or (:result parsed-body) "")))
        (str "HTTP Error from tool " tool-name " (" status "): " body)))
    (catch Exception e
      (str "Error calling HTTP tool " tool-name ": " (.getMessage e)))))

(defn handle-tool-call
  "Dispatches to the matching REST tool server. Returns the result as a string."
  [tool-name args-map]
  (let [info (get (get-registry) tool-name)]
    (if (not info)
      (str "Error: Tool " tool-name " not found in registry.")
      (handle-rest-call info tool-name args-map))))