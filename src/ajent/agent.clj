(ns ajent.agent
  (:require [ajent.llm   :as llm]
            [ajent.tools :as tools]
            [clojure.data.json :as json]
            [clojure.string    :as str]))

(defn- print-section [label body]
  (when (and body (not (str/blank? body)))
    (println (str "\n" label ":"))
    (println body)))

;; NEW: Helper to truncate massive tool outputs so they don't blow up the LLM context
(defn- truncate-observation [content max-chars]
  (if (> (count content) max-chars)
    (str (subs content 0 max-chars) "\n... [TRUNCATED to " max-chars " chars]")
    content))

(defn run-agent
  "Non-streaming ReAct loop for the Unified Orchestrator.
   Handles multiple concurrent tool calls."
  [config system-prompt messages max-iterations]
  (let [initial-messages (into [{:role "system" :content system-prompt}] messages)]

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
                                                    args-map   (try
                                                                 (json/read-str args-json :key-fn keyword)
                                                                 (catch Exception e
                                                                   (println "\n⚠️ Bad tool arguments JSON for"
                                                                            tool-name ":" (.getMessage e))
                                                                   {}))
                                                    tool-result (tools/handle-tool-call tool-name args-map)
                                                    tool-call-id (:id tc)

                                                    ;; NEW: Truncate the result to 4000 characters (~1000 tokens)
                                                    raw-content (str tool-result)
                                                    content     (truncate-observation raw-content 4000)]

                                                (println "\nACTION: Concurrently calling tool"
                                                         tool-name "with args" args-map)
                                                (println "OBSERVATION:" content "\n")

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