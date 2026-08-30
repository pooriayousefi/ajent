(ns ajent.llm
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]))

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
  [{:keys [model-name temperature max-tokens top-p top-k seed response-format]
    :or {temperature 0.7
         max-tokens  8192
         top-p       1.0}}
   messages tools]
  (let [base-payload {:model       model-name
                      :messages    messages
                      :temperature temperature}]
    (cond-> base-payload
      (seq tools)        (assoc :tools tools)
      max-tokens         (assoc :max_tokens max-tokens)
      top-p              (assoc :top_p top-p)
      top-k              (assoc :top_k top-k)
      seed               (assoc :seed seed)
      response-format    (assoc :response_format response-format))))

(defn- extract-response [parsed-response]
  (let [message     (get-in parsed-response [:choices 0 :message])
        reasoning   (get message :reasoning_content)
        content     (get message :content)
        tool-calls  (get message :tool_calls)]
    (if tool-calls
      {:tool-calls tool-calls
       :content    content
       :reasoning  reasoning}
      {:reasoning  reasoning
       :content    content})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn chat-completion
  "Non-streaming chat completion against an OpenAI-compatible endpoint."
  [config messages tools]
  (let [{:keys [base-url timeout]
         :or {timeout 120000}} config
        endpoint (str base-url "/chat/completions")
        payload  (build-request-payload config messages tools)
        resp     (client/post endpoint
                              {:headers            {"Content-Type" "application/json"}
                               :body               (json/write-str payload)
                               :as                 :text
                               :throw-exceptions   false
                               :connection-timeout timeout
                               :request-timeout    timeout})
        _        (ensure-success! resp)
        body-str (body->str (:body resp))]
    (-> body-str
        (json/read-str :key-fn keyword)
        extract-response)))