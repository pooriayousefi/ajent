(ns ajent.core
  (:require [ajent.agent :as agent])
  (:import [java.io File]))

(defn get-workspace-dir []
  (let [home      (System/getProperty "user.home")
        workspace (str home File/separator "aJentWorkbench")]
    (.mkdirs (File. workspace))
    workspace))

(defn build-system-prompt []
  (let [workspace (get-workspace-dir)]
    (str
     "You are the aJent Orchestrator, a highly capable AI assistant with access to a fleet of microservice tools.\n"
     "Your core directive is to accurately accomplish the user's tasks using these tools.\n\n"
     "--- FILE SYSTEM RULES ---\n"
     "You have a dedicated workspace directory for all file, directory, and PDF operations. Your workspace absolute path is:\n"
     workspace "\n"
     "You MUST use absolute paths for all file, directory, and PDF tools. Do not use relative paths.\n"
     "Example: If the user asks to create 'report.pdf', you must call the tool with the path: \"" workspace "/report.pdf\".\n\n"
     "--- ADVANCED EXECUTION RULES ---\n"
     "1. CONCURRENT EXECUTION: If the user requests multiple independent tasks, DO NOT do them sequentially. Call multiple tools in a single response to execute them concurrently.\n"
     "2. DUAL-PATH VERIFICATION: If you are uncertain which tool or which arguments will yield the correct result, call the tool with multiple variations concurrently, compare the observations, and use the best result.\n"
     "3. ERROR RECOVERY: If a tool returns an error, DO NOT report failure immediately. Analyze the error message, adjust your arguments or choose a different tool, and try again.\n"
     "4. CONTEXTUAL RESOLUTION: You hold the entire conversation history. If the user says 'that file' or 'the result', resolve what they mean from the conversation history before calling a tool.\n"
     "5. PLANNING: Before calling tools, explicitly outline your step-by-step plan in your reasoning.\n\n"
     "--- LARGE CONTENT RULES (CRITICAL) ---\n"
     "6. NEVER generate massive text strings inside JSON tool arguments. If you need to write a long document (like a story, report, or PDF), DO NOT pass the whole text in one `create_pdf` or `write_text_file` call. \n"
     "   Instead, create the file with a short first sentence, and then use `append_text_file` or `append_text_to_pdf` multiple times to build the document in smaller chunks.\n\n"
     "Always synthesize the final tool results into a clear, natural language answer for the user.")))

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

          ;; If history starts with a tool response, it's orphaned. Drop it.
          (= (:role first-msg) "tool")
          (recur (rest msgs))

          ;; If history starts with an assistant tool_call, drop it AND its subsequent tool responses.
          (and (= (:role first-msg) "assistant") (:tool_calls first-msg))
          (recur (drop-while #(= (:role %) "tool") (rest msgs)))

          ;; Valid starting point (user or normal assistant message)
          :else
          (vec msgs))))))

(defn run
  "Entry point.
   Usage: clojure -M:run <host URL> <model name> <temperature>"
  [& args]
  (let [[host-url model-name temp-str] args
        base-url     (str host-url "/v1")
        temperature  (Double/parseDouble temp-str)
        config       {:base-url    base-url
                      :model-name  model-name
                      :temperature temperature}
        system-prompt (build-system-prompt)]

    (println "----------------------------------------------------------------------------")
    (println "                     aJent Agentic AI Framework                         ")
    (println "----------------------------------------------------------------------------")
    (println " ")
    (println " Connecting to AI provider at:" host-url)
    (println " Using model:" model-name)
    (println " Workspace Directory:" (get-workspace-dir))
    (println " ")
    (println "----------------------------------------------------------------------------")
    (println "Welcome to the aJent Chat. Type 'exit' or 'quit' to stop.")

    (loop [history []]
      (print "\naJent> ")
      (flush)
      (let [user-input (read-line)]
        (if (or (= user-input "exit") (= user-input "quit"))
          (println "Goodbye!")
          (let [new-history   (conj history {:role "user" :content user-input})
                final-messages (try
                                 (agent/run-agent config system-prompt new-history 10)
                                 (catch Exception e
                                   (println (str "\n⚠️ Unhandled Exception in Agent execution: " (.getMessage e)))
                                   (when-let [data (ex-data e)]
                                     (println "Error details:" data))
                                   (conj new-history
                                         {:role "assistant"
                                          :content "I'm sorry, I encountered an unexpected error and couldn't process that request."})))]
            (if final-messages
              (let [clean-history (filterv #(not= (:role %) "system") final-messages)
                    ;; Apply sliding window to keep only the 20 most recent messages
                    history-window (apply-sliding-window clean-history 20)]
                (recur history-window))
              (recur history))))))))