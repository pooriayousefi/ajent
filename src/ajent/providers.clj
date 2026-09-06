(ns ajent.providers
  "Provider registry: knows how to reach every OpenAI-compatible backend,
   whether it's a local server (offline) or a hosted API (online).
   Built-in definitions can be overridden or extended via providers.json
   in the working directory."
  (:require [clojure.data.json :as json]
            [clojure.string    :as str])
  (:import [java.io File]))

(def ^:private built-in-providers
  ;; :base-url nil means "supply the host URL on the command line" (local mode).
  {"local"      {:description    "Custom local OpenAI-compatible server"
                 :base-url       nil
                 :default-model  "qwen3:8b"
                 :max-tokens     8192
                 :max-iterations 15
                 :history-window 20}
   "ollama"     {:description    "Ollama (local)"
                 :base-url       "http://localhost:11434/v1"
                 :default-model  "qwen3:8b"
                 :max-tokens     8192
                 :max-iterations 15
                 :history-window 20}
   "lmstudio"   {:description    "LM Studio (local)"
                 :base-url       "http://localhost:1234/v1"
                 :default-model  "local-model"
                 :max-tokens     8192
                 :max-iterations 15
                 :history-window 20}
   "zhipu"      {:description    "Zhipu AI (GLM models)"
                 :base-url       "https://open.bigmodel.cn/api/paas/v4"
                 :default-model  "glm-4-plus"
                 :api-key-env    "ZHIPU_API_KEY"
                 :max-tokens     4096
                 :max-iterations 40
                 :history-window 60}
   "openai"     {:description    "OpenAI"
                 :base-url       "https://api.openai.com/v1"
                 :default-model  "gpt-4o"
                 :api-key-env    "OPENAI_API_KEY"
                 :max-tokens     4096
                 :max-iterations 40
                 :history-window 60}
   "deepseek"   {:description    "DeepSeek"
                 :base-url       "https://api.deepseek.com/v1"
                 :default-model  "deepseek-chat"
                 :api-key-env    "DEEPSEEK_API_KEY"
                 :max-tokens     8192
                 :max-iterations 40
                 :history-window 60}
   "groq"       {:description    "Groq (fast inference)"
                 :base-url       "https://api.groq.com/openai/v1"
                 :default-model  "llama-3.3-70b-versatile"
                 :api-key-env    "GROQ_API_KEY"
                 :max-tokens     4096
                 :max-iterations 40
                 :history-window 60}
   "mistral"    {:description    "Mistral AI"
                 :base-url       "https://api.mistral.ai/v1"
                 :default-model  "mistral-large-latest"
                 :api-key-env    "MISTRAL_API_KEY"
                 :max-tokens     4096
                 :max-iterations 40
                 :history-window 60}
   "together"   {:description    "Together AI"
                 :base-url       "https://api.together.xyz/v1"
                 :default-model  "meta-llama/Llama-3.3-70B-Instruct-Turbo"
                 :api-key-env    "TOGETHER_API_KEY"
                 :max-tokens     4096
                 :max-iterations 40
                 :history-window 60}
   "openrouter" {:description    "OpenRouter (many models)"
                 :base-url       "https://openrouter.ai/api/v1"
                 :default-model  "openai/gpt-4o"
                 :api-key-env    "OPENROUTER_API_KEY"
                 :max-tokens     4096
                 :max-iterations 40
                 :history-window 60}})

(defn- user-providers
  "Reads optional providers.json from the working directory.
   Top-level keys are provider ids; their maps override the built-in defaults.
   Any OpenAI-compatible endpoint can be registered here."
  []
  (let [f (File. "providers.json")]
    (if (.exists f)
      (try
        (let [data (json/read-str (slurp f) :key-fn keyword)]
          (if (map? data)
            (into {} (for [[k v] data :when (map? v)] [(name k) v]))
            (do (println "⚠️ providers.json must contain a JSON object — ignoring it.")
                {})))
        (catch Exception e
          (println "⚠️ Could not parse providers.json — ignoring it:" (.getMessage e))
          {}))
      {})))

(defn known-providers
  "All available providers: built-ins deep-merged with providers.json overrides.
   User entries override individual fields (default-model, max-tokens, …)
   rather than replacing the whole definition; brand-new providers are added as-is."
  []
  (merge-with merge built-in-providers (user-providers)))

(defn resolve-provider
  "Returns the merged definition for a provider id, or nil if unknown."
  [provider-id]
  (get (known-providers) provider-id))

(defn host->base-url
  "Converts a bare host URL into an OpenAI-compatible base URL.
   Appends /v1 unless the URL already ends in a version segment (/v1, /v4…)."
  [host]
  (let [u (str/trim (str host))]
    (cond
      (str/blank? u)         nil
      (str/ends-with? u "/") (host->base-url (subs u 0 (dec (count u))))
      (re-find #"/v\d+$" u)  u
      :else                  (str u "/v1"))))

(defn provider-table
  "Human-readable list of providers (used in help output)."
  []
  (->> (known-providers)
       (sort-by first)
       (map (fn [[id p]]
              (let [desc (str/trim (or (:description p) ""))
                    url  (if (str/blank? (str (:base-url p)))
                           "<host-url required>"
                           (str (:base-url p)))
                    key  (when (:api-key-env p)
                           (str "  [key: " (:api-key-env p) "]"))]
                (format "  %-14s %-34s %s%s"
                        id
                        (if (str/blank? desc) "-" desc)
                        url
                        (or key "")))))
       (str/join "\n")))