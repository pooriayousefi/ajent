(ns ajent.core
  (:require [clojure.string :as str]
            [ajent.agent :as agent]
            [ajent.cli   :as cli]
            [ajent.config :as config]
            [ajent.tools :as tools]))

(defn build-system-prompt [agent-name]
  (let [path "cfg/system_prompts.json"
        prompt (get (config/read-json path) (keyword agent-name))]
    (when-not (and (string? prompt) (not (str/blank? prompt)))
      (throw (IllegalArgumentException.
              (str "Unknown or empty agent prompt '" agent-name "' in " path))))
    (println "System prompts:" path)
    prompt))

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
          system-prompt (build-system-prompt (:agent config "orchestrator"))]
      (cli/print-banner)
      (cli/print-startup-summary config)
      (try
        (tools/get-tools)
        (println "\n  Ready. Type 'exit' or 'quit' to stop.")
        (chat-loop config system-prompt)
        (finally
          (tools/close-connections!))))))
