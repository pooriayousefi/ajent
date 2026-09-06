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
  #"^\s*(rm|rmdir|del|format|mkfs|dd|shutdown|reboot|halt)\s+")

(defn- confirm-destructive-action
  "Blocks execution and asks for user confirmation if a tool is destructive.
   Catches delete_file, delete_directory, and destructive shell commands."
  [tool-name args-map]
  (let [is-destructive (cond
                         (contains? #{"delete_file" "delete_directory"} tool-name) true
                         (and (= tool-name "run_command")
                              (let [cmd (:command args-map "")]
                                (re-find destructive-shell-patterns cmd))) true
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
          (if (and response (or (= response "y") (= response "Y")))
            true
            false)))
      true))) ; Non-destructive tools auto-approve

(defn run-agent
  "Non-streaming ReAct loop for the Unified Orchestrator.
   Handles multiple concurrent tool calls.
   Reads :max-observation-chars from config (default 4000)."
  [config system-prompt messages max-iterations]
  (let [max-obs-chars     (:max-observation-chars config 4000)
        initial-messages  (into [{:role "system" :content system-prompt}] messages)]

    (loop [messages  initial-messages
           iteration max-iterations]

      (if (zero? iteration)
        (do
          (println "\nWARNING: Max. iterations hit in agent loop/recur")
          messages)

        (let [all-tools (tools/get-tools)
              result    (llm/chat-completion config messages all-tools)]

          ;; ---- Render reasoning + content for the user ----
          (print-section "REASONING" (:reasoning result))
          (when (and (:content result) (not (str/blank? (:content result))))
            (println)
            (println (:content result)))

          (if (:tool-calls result)

            ;; ---------------- Tool Call Branch ----------------
            (let [raw-tcs (:tool-calls result)
                  tcs (filterv (fn [tc]
                                 (and (:id tc)
                                      (not (str/blank? (get-in tc [:function :name])))))
                               raw-tcs)]

              (if (empty? tcs)
                (let [content        (or (:content result) "")
                      final-messages (conj messages {:role "assistant" :content content})]
                  (println "\n⚠️ Discarded malformed tool-call payload from provider.")
                  (println "============================\n")
                  final-messages)

                (let [assistant-msg (if (and (:content result)
                                             (not (str/blank? (:content result))))
                                      {:role "assistant" :tool_calls tcs :content (:content result)}
                                      {:role "assistant" :tool_calls tcs})

                      tool-responses (doall
                                      (pmap (fn [tc]
                                              (let [tool-name  (get-in tc [:function :name])
                                                    args-json  (get-in tc [:function :arguments])
                                                    ;; Some providers return arguments as a map
                                                    ;; instead of a JSON string — handle both.
                                                    args-map   (cond
                                                                 (map? args-json) args-json
                                                                 (str/blank? args-json) {}
                                                                 :else
                                                                 (try
                                                                   (json/read-str args-json :key-fn keyword)
                                                                   (catch Exception e
                                                                     (println "\n⚠️ Bad tool arguments JSON for"
                                                                              tool-name ":" (.getMessage e))
                                                                     {})))
                                                    ;; --- Security Gate ---
                                                    approved    (confirm-destructive-action tool-name args-map)
                                                    tool-result (if approved
                                                                  (tools/handle-tool-call tool-name args-map)
                                                                  "Error: User denied the destructive operation for security reasons.")
                                                    tool-call-id (:id tc)
                                                    raw-content (str tool-result)
                                                    content     (truncate-observation raw-content max-obs-chars)]

                                                (println "\nACTION: Concurrently calling tool"
                                                         tool-name "with args" args-map)
                                                (if approved
                                                  (println "OBSERVATION:" content "\n")
                                                  (println "OBSERVATION: [BLOCKED BY USER]" "\n"))

                                                {:role "tool"
                                                 :tool_call_id tool-call-id
                                                 :content      content}))
                                            tcs))

                      messages-with-request   (conj messages assistant-msg)
                      messages-with-responses (into messages-with-request tool-responses)]

                  (recur messages-with-responses (dec iteration)))))

            ;; ---------------- Final Answer Branch ----------------
            (let [content        (:content result)
                  final-messages (conj messages {:role "assistant" :content content})]
              (println "============================\n")
              final-messages)))))))