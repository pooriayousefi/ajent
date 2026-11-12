(ns ajent.core
  (:require [clojure.string :as str]
            [ajent.agent :as agent]
            [ajent.cli   :as cli]
            [ajent.tools :as tools]))

(defn build-system-prompt []
  (str
   "You are the aJent Orchestrator, a highly capable AI assistant with access to MCP tools.\n"
   "Your core directive is to accurately accomplish the user's tasks using these tools.\n\n"
   "--- FILE SYSTEM RULES ---\n"
   "Use exact absolute paths for all file, directory, and document tools. Do not guess paths; "
   "if a path is ambiguous, ask the user for clarification.\n\n"
   "--- ADVANCED EXECUTION RULES ---\n"
   "1. CONCURRENT EXECUTION: Call independent tools in the same assistant response; aJent executes them concurrently.\n"
   "2. ERROR RECOVERY: Analyze tool errors, correct arguments where possible, and retry when appropriate.\n"
   "3. CONTEXTUAL RESOLUTION: Resolve references such as 'that file' from the conversation history.\n"
   "4. PLANNING: Plan before executing multi-step tasks.\n\n"
   "--- LARGE CONTENT RULES ---\n"
   "Avoid massive text values in tool arguments. Prefer incremental writes or server-side file operations.\n\n"
   "--- PYTHON TOOL RULES ---\n"
   "When using a Python MCP tool, save generated files to the directory exposed by that server. "
   "Keep printed output concise and do not use Python to bypass the host security gate.\n\n"
   "--- SECURITY RULES ---\n"
   "Destructive operations require explicit user approval. Never attempt to bypass aJent's approval gate.\n\n"
   "Always synthesize tool results into a clear, natural-language answer."))

(defn- apply-sliding-window
  "Keeps the most recent N messages without splitting assistant tool calls from their responses."
  [messages window-size]
  (if (<= (count messages) window-size)
    messages
    (loop [msgs (subvec (vec messages) (- (count messages) window-size))]
      (let [first-msg (first msgs)]
        (cond
          (nil? first-msg) []
          (= (:role first-msg) "tool") (recur (vec (rest msgs)))
          (and (= (:role first-msg) "assistant") (:tool_calls first-msg))
          (recur (vec (drop-while #(= (:role %) "tool") (rest msgs))))
          :else (vec msgs))))))

(defn- chat-loop [config system-prompt]
  (loop [history []]
    (print "\naJent> ")
    (flush)
    (let [input (read-line)]
      (if (or (nil? input) (contains? #{"exit" "quit"} input))
        (println "Goodbye!")
        (let [new-history (conj history {:role "user" :content input})
              final-messages (try
                               (agent/run-agent config system-prompt new-history
                                                (:max-iterations config))
                               (catch Exception e
                                 (println (str "\n⚠️ Unhandled exception in agent execution: "
                                               (.getMessage e)))
                                 (when-let [data (ex-data e)]
                                   (println "Error details:" data))
                                 (conj new-history
                                       {:role "assistant"
                                        :content "I'm sorry, I encountered an unexpected error and couldn't process that request."})))]
          (if final-messages
            (let [clean (filterv #(not= (:role %) "system") final-messages)
                  window (apply-sliding-window clean (:history-window config))]
              (recur window))
            (recur history)))))))

(defn run
  "Application entry point: resolve provider, connect MCP servers, and start chat."
  [& args]
  (cond
    (cli/version-requested? args) (println (str "aJent " cli/version))
    (cli/help-requested? args) (cli/print-usage)
    :else
    (let [config (cli/parse-args args)
          system-prompt (build-system-prompt)]
      (cli/print-banner)
      (cli/print-startup-summary config)
      (when (and (:api-key-env config) (str/blank? (:api-key config)))
        (println (str "  ⚠ " (:api-key-env config)
                      " is not set — the provider will likely reject requests (401).")))
      (try
        (tools/get-tools)
        (println "\n  Ready. Type 'exit' or 'quit' to stop.")
        (chat-loop config system-prompt)
        (finally
          (tools/close-connections!))))))
