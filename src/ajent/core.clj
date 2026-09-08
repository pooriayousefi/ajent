(ns ajent.core
  (:require [clojure.string :as str]
            [ajent.agent :as agent]
            [ajent.cli   :as cli]
            [ajent.tools :as tools]))

(defn build-system-prompt []
  (str
   "You are the aJent Orchestrator, a highly capable AI assistant with access to a fleet of microservice tools.\n"
   "Your core directive is to accurately accomplish the user's tasks using these tools.\n\n"
   "--- FILE SYSTEM RULES ---\n"
   "You have FULL, unrestricted access to the host machine's file system.\n"
   "You MUST use exact absolute paths for all file, directory, and PDF tools. Do not guess paths; if a path is ambiguous, ask the user for clarification.\n\n"
   "--- ADVANCED EXECUTION RULES ---\n"
   "1. CONCURRENT EXECUTION: If the user requests multiple independent tasks, DO NOT do them sequentially. Call multiple tools in a single response to execute them concurrently.\n"
   "2. DUAL-PATH VERIFICATION: If you are uncertain which tool or which arguments will yield the correct result, call the tool with multiple variations concurrently, compare the observations, and use the best result.\n"
   "3. ERROR RECOVERY: If a tool returns an error, DO NOT report failure immediately. Analyze the error message, adjust your arguments or choose a different tool, and try again.\n"
   "4. CONTEXTUAL RESOLUTION: You hold the entire conversation history. If the user says 'that file' or 'the result', resolve what they mean from the conversation history before calling a tool.\n"
   "5. PLANNING: Before calling tools, explicitly outline your step-by-step plan in your reasoning.\n\n"
   "--- LARGE CONTENT RULES (CRITICAL) ---\n"
   "6. NEVER generate massive text strings inside JSON tool arguments. If you need to write a long document (like a story, report, or PDF), DO NOT pass the whole text in one `create_pdf` or `write_text_file` call. \n"
   "   Instead, create the file with a short first sentence, and then use `append_text_file` or `append_text_to_pdf` multiple times to build the document in smaller chunks.\n\n"
   "--- SECURITY RULES ---\n"
   "7. DESTRUCTION GATE: You are forbidden from performing destructive operations (like deleting files or directories) autonomously. If you need to delete something, you MUST ask the user for explicit permission first.\n\n"
   "Always synthesize the final tool results into a clear, natural language answer for the user."))

(defn- apply-sliding-window
  "Keeps only the most recent N messages to prevent endless context growth.
   Safely drops leading 'tool' or 'assistant' tool-call messages to prevent
   OpenAI API 400 errors (tool_calls must be accompanied by their tool responses)."
  [messages window-size]
  (if (<= (count messages) window-size)
    messages
    (loop [msgs (subvec messages (- (count messages) window-size))]
      (let [first-msg (first msgs)]
        (cond
          (nil? first-msg)
          []

          (= (:role first-msg) "tool")
          (recur (rest msgs))

          (and (= (:role first-msg) "assistant") (:tool_calls first-msg))
          (recur (drop-while #(= (:role %) "tool") (rest msgs)))

          :else
          (vec msgs))))))

(defn- chat-loop
  "Interactive REPL: read input → run agent → window history → repeat."
  [config system-prompt]
  (loop [history []]
    (print "\naJent> ")
    (flush)
    (let [input (read-line)]
      (if (or (nil? input) (contains? #{"exit" "quit"} input))
        (println "Goodbye!")
        (let [new-history    (conj history {:role "user" :content input})
              final-messages (try
                               (agent/run-agent config system-prompt
                                                new-history (:max-iterations config))
                               (catch Exception e
                                 (println (str "\n⚠️ Unhandled exception in agent execution: "
                                               (.getMessage e)))
                                 (when-let [data (ex-data e)]
                                   (println "Error details:" data))
                                 (conj new-history
                                       {:role "assistant"
                                        :content "I'm sorry, I encountered an unexpected error and couldn't process that request."})))]
          (if final-messages
            (let [clean  (filterv #(not= (:role %) "system") final-messages)
                  window (apply-sliding-window clean (:history-window config))]
              (recur window))
            (recur history)))))))

(defn run
  "Application entry point: parse args → banner → config summary →
   tool discovery → interactive chat."
  [& args]
  (cond
    (cli/version-requested? args) (println (str "aJent " cli/version))

    (cli/help-requested? args)    (cli/print-usage)

    :else
    (let [config        (cli/parse-args args)
          system-prompt (build-system-prompt)]
      (cli/print-banner)
      (cli/print-startup-summary config)
      (when (and (:api-key-env config) (str/blank? (:api-key config)))
        (println (str "  ⚠ " (:api-key-env config)
                      " is not set — the provider will likely reject requests (401).")))
      ;; Force tool discovery up-front so the fleet is visible before chatting.
      (tools/get-tools)
      (println "\n  Ready. Type 'exit' or 'quit' to stop.")
      (chat-loop config system-prompt))))