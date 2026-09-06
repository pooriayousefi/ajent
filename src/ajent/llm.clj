(ns ajent.llm
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn build-message [role prompt]
  {:role role
   :content prompt})

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- body->str [body]
  (cond
    (nil? body)                                   ""
    (string? body)                                body
    (instance? java.io.InputStream body)          (slurp body)
    (bytes? body)                                 (String. ^bytes body "UTF-8")
    :else                                         (str body)))

(defn- ensure-success! [http-response]
  (let [status (:status http-response)
        body   (:body http-response)]
    (when (>= status 400)
      (throw
       (ex-info (str "LLM provider returned status " status)
                {:status status
                 :body   (body->str body)}))))
  http-response)

(defn- build-request-payload
  "Only includes optional sampling params when they are explicitly configured —
   strict online providers reject unknown fields like top_k, while local
   servers happily accept them."
  [{:keys [model-name temperature max-tokens top-p top-k seed response-format]
    :or {temperature 0.7}}
   messages tools]
  (let [base-payload {:model       model-name
                      :messages    messages
                      :temperature temperature}]
    (cond-> base-payload
      (seq tools)     (assoc :tools tools)
      max-tokens      (assoc :max_tokens max-tokens)
      top-p           (assoc :top_p top-p)
      top-k           (assoc :top_k top-k)
      seed            (assoc :seed seed)
      response-format (assoc :response_format response-format))))

(defn- extract-response [parsed-response]
  (let [message     (get-in parsed-response [:choices 0 :message])
        reasoning   (get message :reasoning_content) ; Zhipu GLM-4.5+, DeepSeek-R1, local reasoning models
        content     (get message :content)
        tool-calls  (get message :tool_calls)]
    (if tool-calls
      {:tool-calls tool-calls
       :content    content
       :reasoning  reasoning}
      {:reasoning  reasoning
       :content    content})))

(def ^:private retryable-statuses #{429 500 502 503 504 529})

(defn- backoff-ms [attempt]
  (* 5000 attempt)) ; 5s, 10s, 15s…

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn chat-completion
  "Non-streaming chat completion against any OpenAI-compatible endpoint —
   local servers (llama.cpp, vLLM, Ollama, LM Studio) or online providers.
   Sends a Bearer token only when an API key is configured, and retries
   transient failures (429/5xx) with linear backoff."
  [config messages tools]
  (let [{:keys [base-url api-key timeout max-attempts]
         :or {timeout 120000 max-attempts 3}} config
        endpoint (str base-url "/chat/completions")
        payload  (build-request-payload config messages tools)
        headers  (cond-> {"Content-Type" "application/json"}
                   (not (str/blank? api-key))
                   (assoc "Authorization" (str "Bearer " api-key)))
        request  {:headers            headers
                  :body               (json/write-str payload)
                  :as                 :text
                  :throw-exceptions   false
                  :connection-timeout timeout
                  :request-timeout    timeout}]
    (loop [attempt 1]
      (let [resp   (client/post endpoint request)
            status (:status resp)]
        (if (and (contains? retryable-statuses status) (< attempt max-attempts))
          (do
            (println (str "\n⏳ Provider returned HTTP " status " — retrying in "
                          (/ (backoff-ms attempt) 1000) "s (attempt "
                          (inc attempt) " of " max-attempts ")…"))
            (Thread/sleep (backoff-ms attempt))
            (recur (inc attempt)))
          (do
            (ensure-success! resp)
            (-> (:body resp)
                body->str
                (json/read-str :key-fn keyword)
                extract-response)))))))