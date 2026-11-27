(ns ajent.tools
  "MCP connection registry and OpenAI-compatible tool adapter."
  (:require [ajent.config :as config]
            [clojure.string :as str]
            [mcp.client :as mcp]
            [mcp.openai :as openai])
  (:import [java.net URI URISyntaxException]))

(defonce ^:private registry-cache (atom nil))

(defn- load-config []
  (let [path "cfg/mcp_servers.json"
        servers (:mcp_servers (config/read-json path))]
    (when-not (and (vector? servers) (every? map? servers))
      (throw (ex-info (str path " must contain an mcp_servers array of objects")
                      {:file path})))
    (println "MCP servers:" path)
    servers))

(defn- http-endpoint [host port]
  (when-not (and (string? host) (not (str/blank? host))
                 (integer? port) (<= 1 port 65535))
    (throw (ex-info "HTTP transport requires a host and port from 1 to 65535" {})))
  (try
    (str (URI. "http" nil host (int port) "/mcp" nil nil))
    (catch URISyntaxException e
      (throw (ex-info "Invalid MCP HTTP host" {:host host} e)))))

(defn- transport-options [{:keys [transports] :as server}]
  (if (contains? server :transports)
    (let [{:keys [stdio http]} transports
          stdio? (true? (:enable stdio))
          http? (true? (:enable http))
          {:keys [command args env]} stdio
          {:keys [host port]} http
          url (when http? (http-endpoint host port))]
      (when-not (and (boolean? (:enable stdio)) (boolean? (:enable http)))
        (throw (ex-info "Both transports require a boolean enable field" {})))
      (when (and stdio?
                 (not (and (string? command) (not (str/blank? command))
                           (or (nil? args) (and (vector? args) (every? string? args)))
                           (or (nil? env) (and (map? env) (every? string? (vals env)))))))
        (throw (ex-info "Stdio requires a command string, optional args array, and optional env object" {})))
      (cond
        stdio? (assoc server :command
                      (into (if http?
                              [command "both" host (str port)]
                              [command "stdio"])
                            args)
                      :env env)
        http? (assoc server :url url)))
    (when-not (false? (:enabled server)) server)))

(defn- connect-server [{:keys [name command url cwd env headers] :as server}]
  (when-not (and (string? name) (not (str/blank? name)))
    (throw (ex-info "Every MCP server requires a non-empty name" {:server server})))
  (when-not (or (and (sequential? command) (seq command) (nil? url))
                (and (string? url) (nil? command)))
    (throw (ex-info "MCP server must define exactly one of command or url"
                    {:server name})))
  (let [options (cond-> {:timeout-ms (or (:timeout-ms server) (:timeout_ms server) 30000)
                         :client-info {:name "aJent" :version "2.3.0"}}
                  command (assoc :command command)
                  url (assoc :url url)
                  cwd (assoc :cwd cwd)
                  (seq env) (assoc :env env)
                  (seq headers) (assoc :headers headers))]
    (mcp/connect options)))

(defn- close-registry! [registry]
  (doseq [[_ conn] (:connections registry)]
    (try (mcp/close! conn) (catch Exception _))))

(defn- server-step [server-name action f]
  (try
    (f)
    (catch Exception e
      (throw (ex-info (str "MCP server '" server-name "' " action ": " (.getMessage e))
                      {:server server-name} e)))))

(defn- build-registry []
  (let [servers (load-config)
        names (mapv :name servers)
        connections (atom {})]
    (when-not (every? #(and (string? %) (not (str/blank? %))) names)
      (throw (ex-info "Every MCP server requires a non-empty name" {})))
    (when-not (= (count names) (count (set names)))
      (throw (ex-info "MCP server names must be unique" {:names names})))
    (println "Connecting to MCP servers…")
    (try
      (doseq [{:keys [name] :as server} servers]
        (server-step name "failed to connect"
                     #(when-let [options (transport-options server)]
                        (swap! connections assoc name (connect-server options)))))
      (let [catalogs (mapv (fn [[name conn]]
                             (server-step name "failed discovery"
                                          #(openai/tools {name conn})))
                           @connections)
            tools (vec (sort-by #(get-in % [:function :name]) (mapcat :tools catalogs)))
            routes (into {} (mapcat :routes catalogs))]
        (when-not (= (count tools) (count routes))
          (throw (ex-info "OpenAI function name collision after sanitization" {})))
        (println (str "\n✓ MCP registry built: " (count @connections)
                      " server(s), " (count tools) " tool(s)"))
        {:connections @connections :tools tools :routes routes})
      (catch Exception e
        (close-registry! {:connections @connections})
        (throw e)))))

(defn- get-registry []
  (or @registry-cache
      (locking registry-cache
        (or @registry-cache
            (reset! registry-cache (build-registry))))))

(defn refresh-registry! []
  (let [old @registry-cache
        fresh (build-registry)]
    (reset! registry-cache fresh)
    (close-registry! old)
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
    (close-registry! registry)
    (reset! registry-cache nil))
  nil)
