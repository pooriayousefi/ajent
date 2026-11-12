(ns ajent.agent
  (:require [ajent.llm   :as llm]
            [ajent.tools :as tools]
            [clojure.data.json :as json]
            [clojure.string    :as str]))

(defn- print-section [label body]
  (when (and body (not (str/blank? body)))
    (println (str "\n" label ":"))
    (println body)))

(defn- truncate-observation
  "Truncates massive tool outputs so they don't blow up the LLM context."
  [content max-chars]
  (if (and max-chars (> (count content) max-chars))
    (str (subs content 0 max-chars) "\n... [TRUNCATED to " max-chars " chars]")
    content))

(def ^:private destructive-shell-patterns
  #"^\\s*(rm|rmdir|del|format|mkfs|dd|shutdown|reboot|halt)\\s+")

(defn- parse-tool-arguments [tool-call]
  (let [arguments (get-in tool-call [:function :arguments])]
    (cond
      (map? arguments) arguments
      (or (nil? arguments) (str/blank? arguments)) {}
      (string? arguments) (json/read-str arguments :key-fn keyword)
      :else (throw (ex-info "Tool arguments must be a JSON object" {})))))

(defn- confirm-destructive-action
  "Apply aJent's human approval gate to the original MCP tool name."
  [tool-name args-map]
  (let [is-destructive (cond
                         (contains? #{"delete_file" "delete_directory"} tool-name) true
                         (and (= tool-name "run_command")
                              (re-find destructive-shell-patterns
                                       (str (:command args-map "")))) true
                         :else false)]
    (if is-destructive
      (do
        (println "\n🚨 SECURITY GATE: Destructive action requested.")
        (println (str "   Tool: " tool-name))
        (println (str "   Target: " (if (= tool-name "run_command")
                                      (:command args-map)
                                      (:path args-map "unknown path"))))
        (print "   Approve this action? [y/N]: ")
        (flush)
        (let [response (read-line)]
          (boolean (and response (or (= response "y") (= response "Y")))))
      true)))

(defn run-agent
  "Non-streaming ReAct loop. MCP tool calls are approved before concurrent dispatch."
  [config system-prompt messages max-iterations]
  (let [max-obs-chars (:max-observation-chars config 4000)
        initial-messages (into [{:role "system" :content system-prompt}] messages)]
    (loop [messages initial-messages
           iteration max-iterations]
      (if (zero? iteration)
        (do
          (println "\nWARNING: Max. iterations hit in agent loop/recur")
          messages)
        (let [all-tools (tools/get-tools)
              result (llm/chat-completion config messages all-tools)]
          (print-section "REASONING" (:reasoning result))
          (when (and (:content result) (not (str/blank? (:content result))))
            (println)
            (println (:content result)))
          (if (:tool-calls result)
            (let [raw-tcs (:tool-calls result)
                  tcs (filterv #(and (:id %)
                                     (not (str/blank? (get-in % [:function :name]))))
                               raw-tcs)]
              (if (empty? tcs)
                (let [final-messages (conj messages
                                           {:role "assistant" :content (or (:content result) "")})]
                  (println "\n⚠️ Discarded malformed tool-call payload from provider.")
                  (println "============================\n")
                  final-messages)
                (let [assistant-msg (cond-> {:role "assistant" :tool_calls tcs}
                                      (and (:content result)
                                           (not (str/blank? (:content result))))
                                      (assoc :content (:content result)))
                      ;; Approval prompts must be sequential: stdin is not safe for
                      ;; simultaneous prompts from pmap/futures.
                      approvals (mapv (fn [tc]
                                        (let [function-name (get-in tc [:function :name])
                                              original-name (tools/original-tool-name function-name)
                                              args (try (parse-tool-arguments tc)
                                                        (catch Exception _ {}))]
                                          (if original-name
                                            (confirm-destructive-action original-name args)
                                            false)))
                                      tcs)
                      calls (mapv (fn [tc approved?]
                                    (future
                                      (if approved?
                                        (try
                                          (let [response (tools/handle-tool-call tc)
                                                content (truncate-observation
                                                         (str (:content response))
                                                         max-obs-chars)]
                                            (println "\nACTION: MCP tool" (get-in tc [:function :name]))
                                            (println "OBSERVATION:" content "\n")
                                            (assoc response :content content))
                                          (catch Exception e
                                            {:role "tool"
                                             :tool_call_id (:id tc)
                                             :content (str "MCP tool call failed: " (.getMessage e))}))
                                        {:role "tool"
                                         :tool_call_id (:id tc)
                                         :content "Error: User denied the destructive operation for security reasons."})))
                                  tcs approvals)
                      tool-responses (mapv deref calls)
                      next-messages (-> messages
                                        (conj assistant-msg)
                                        (into tool-responses))]
                  (recur next-messages (dec iteration)))))
            (let [final-messages (conj messages
                                       {:role "assistant" :content (:content result)})]
              (println "============================\n")
              final-messages)))))))
