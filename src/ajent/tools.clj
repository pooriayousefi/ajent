(ns ajent.tools
  "MCP connection registry and OpenAI-compatible tool adapter."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [mcp.client :as mcp]
            [mcp.openai :as openai])
  (:import [java.io File]))

(def ^:private config-file "mcp_servers.json")
(defonce ^:private registry-cache (atom nil))

(defn- load-config []
  (let [file (File. config-file)]
    (when-not (.exists file)
      (throw (ex-info (str config-file " not found") {:file config-file})))
    (let [config (json/read-str (slurp file) :key-fn keyword)
          servers (:mcp_servers config)]
      (when-not (vector? servers)
        (throw (ex-info "mcp_servers.json must contain an mcp_servers array" {})))
      servers)))

(defn- connect-server [{:keys [name command url cwd env headers] :as server}]
  (when-not (and (string? name) (not (str/blank? name)))
    (throw (ex-info "Every MCP server requires a non-empty name" {:server server})))
  (when-not (or (and (sequential? command) (seq command) (nil? url))
                (and (string? url) (nil? command)))
    (throw (ex-info "MCP server must define exactly one of command or url"
                    {:server name})))
  (let [options (cond-> {:timeout-ms (or (:timeout-ms server) (:timeout_ms server) 30000)
                         :client-info {:name "aJent" :version "2.2.0"}}
                  command (assoc :command command)
                  url (assoc :url url)
                  cwd (assoc :cwd cwd)
                  (seq env) (assoc :env env)
                  (seq headers) (assoc :headers headers))]
    (mcp/connect options)))

(defn- build-registry []
  (let [servers (filterv #(and (map? %) (not (false? (:enabled %)))) (load-config))
        names (mapv :name servers)]
    (when-not (= (count names) (count (set names)))
      (throw (ex-info "MCP server names must be unique" {:names names})))
    (println "Connecting to MCP servers…")
    (let [connections
          (reduce (fn [acc server]
                    (try
                      (let [conn (connect-server server)]
                        (println (str "  ✓ " (:name server) " connected"))
                        (assoc acc (:name server) conn))
                      (catch Exception e
                        (println (str "  ✗ " (:name server) ": " (.getMessage e)))
                        acc))
                  (array-map)
                  servers)
          {:keys [tools routes]} (openai/tools connections)]
      (println (str "\n✓ MCP registry built: " (count connections)
                    " server(s), " (count tools) " tool(s)"))
      {:connections connections :tools tools :routes routes})))

(defn- get-registry []
  (or @registry-cache
      (locking registry-cache
        (or @registry-cache
            (reset! registry-cache (build-registry))))))

(defn refresh-registry! []
  (let [old @registry-cache
        fresh (build-registry)]
    (reset! registry-cache fresh)
    (doseq [[_ conn] (:connections old)]
      (try (mcp/close! conn) (catch Exception _)))
    fresh))

(defn get-tools
  "Return OpenAI-compatible function schemas generated from MCP tools."
  []
  (:tools (get-registry)))

(defn get-tools-by-names
  "Return schemas whose exposed OpenAI function names are in tool-names."
  [tool-names]
  (let [wanted (set tool-names)]
    (filterv #(wanted (get-in % [:function :name])) (get-tools))))

(defn original-tool-name
  "Resolve an exposed OpenAI function name to its original MCP tool name."
  [function-name]
  (get-in (get-registry) [:routes function-name :tool-name]))

(defn handle-tool-call
  "Execute an OpenAI Chat Completions tool_call through its MCP route.
  Returns the standard tool-role message expected by the conversation."
  [tool-call]
  (openai/execute-tool-call (:routes (get-registry)) tool-call))

(defn close-connections! []
  (when-let [registry @registry-cache]
    (doseq [[_ conn] (:connections registry)]
      (try (mcp/close! conn) (catch Exception _)))
    (reset! registry-cache nil))
  nil)
