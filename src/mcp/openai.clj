(ns mcp.openai
  "OpenAI-compatible function-tool conversion and MCP tool-call execution."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [mcp.client :as client]
            [mcp.protocol :as protocol]))

(defn- field [m k]
  (if (contains? m k)
    (get m k)
    (get m (name k))))

(defn- function-name [server-name tool-name]
  (let [raw (str server-name "__" tool-name)
        safe (str/replace raw #"[^A-Za-z0-9_-]" "_")
        suffix (format "%08x" (.hashCode raw))
        prefix (subs safe 0 (min 54 (count safe)))]
    (str prefix "_" suffix)))

(defn tools
  "Convert {server-name connection} into {:tools [...] :routes {...}}.
  Prefixes function names with a stable server/tool identifier so tools
  from separate MCP servers cannot collide."
  [connections]
  (let [entries (vec
                 (mapcat
                  (fn [[server-name conn]]
                    (map (fn [tool]
                           [(function-name server-name (:name tool))
                            {:connection conn :tool-name (:name tool)
                             :server-name server-name :tool tool}])
                         (client/list-tools conn)))
                  connections))
        routes (into {} entries)]
    (when-not (= (count routes) (count entries))
      (throw (ex-info "OpenAI function name collision after sanitization" {})))
    {:tools (mapv (fn [[name {:keys [tool]}]]
                    (let [openai-tool (protocol/tool->openai tool)]
                      (assoc openai-tool :function
                             (assoc (:function openai-tool) :name name))))
                  (sort-by key routes))
     :routes routes}))

(defn- arguments-map [value]
  (cond
    (nil? value) {}
    (map? value) (walk/keywordize-keys value)
    (string? value)
    (let [parsed (json/read-str value :key-fn keyword)]
      (if (map? parsed)
        parsed
        (throw (ex-info "OpenAI function arguments must decode to an object"
                        {:arguments value}))))
    :else
    (throw (ex-info "OpenAI function arguments must be a JSON object"
                    {:arguments value}))))

(defn- result-content [result]
  (let [content (if (contains? result :structuredContent)
                  (protocol/write-json (:structuredContent result))
                  (let [parts (keep (fn [block]
                                      (when (= "text" (:type block)) (:text block)))
                                    (:content result))]
                    (if (seq parts)
                      (str/join "\n" parts)
                      (protocol/write-json result))))]
    (if (:isError result)
      (str "MCP tool reported an error: " content)
      content)))

(defn execute-tool-call
  "Execute one OpenAI Chat Completions-style tool_call via its MCP route.
  Returns a standard tool-role message with the original tool_call_id."
  [routes tool-call]
  (let [id (field tool-call :id)
        function (field tool-call :function)
        name (field function :name)
        route (get routes name)]
    (when-not (and (string? id) (string? name))
      (throw (ex-info "OpenAI tool call requires string id and function name"
                      {:tool-call tool-call})))
    (when-not route
      (throw (ex-info (str "No MCP route for OpenAI function " name)
                      {:function-name name})))
    (let [result (client/call-tool (:connection route)
                                   (:tool-name route)
                                   (arguments-map (field function :arguments)))]
      {:role "tool"
       :tool_call_id id
       :content (result-content result)})))

(defn execute-tool-calls
  "Execute an assistant message's tool_calls concurrently and preserve
  their original order and tool_call_id values."
  [routes assistant-message]
  (let [calls (field assistant-message :tool_calls)]
    (when-not (sequential? calls)
      (throw (ex-info "Assistant message is missing a tool_calls sequence"
                      {})))
    (->> calls
         (mapv #(future
                  (try
                    (execute-tool-call routes %)
                    (catch Exception e
                      {:role "tool"
                       :tool_call_id (field % :id)
                       :content (str "Tool call failed: " (.getMessage e))}))))
         (mapv deref))))
