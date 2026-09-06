(ns ajent.cli
  "Command-line entrance: argument parsing, banner, usage, startup summary."
  (:require [ajent.providers :as providers]
            [clojure.string  :as str]))

(def ^:const version "2.0.0")

(def ^:private help-flags    #{"help" "--help" "-h"})
(def ^:private version-flags #{"-v" "--version"})

(defn help-requested?    [args] (or (empty? args) (contains? help-flags    (first args))))
(defn version-requested? [args] (contains? version-flags (first args)))

;; ---------------------------------------------------------------------------
;; Output: banner, usage, summary
;; ---------------------------------------------------------------------------

(defn print-banner []
  (println
   (str
    "\n"
    "  ──────────────────────────────────────────────────────────\n" 
    "   aJent v" version "  ·  Agentic AI Framework\n"
    "   offline & online LLM providers  ·  concurrent tool fleet\n"
    "  ──────────────────────────────────────────────────────────\n")))

(defn print-usage []
  (println
   (str
    "aJent v" version " — agentic AI framework (offline + online LLM providers)\n\n"
    "Usage:\n"
    "  java -jar ajent.jar <provider> [model] [temperature]\n"
    "  java -jar ajent.jar local <host-url> [model] [temperature]\n\n"
    "Examples:\n"
    "  java -jar ajent.jar local http://localhost:8080 gpt-oss-20b 0.7\n"
    "  java -jar ajent.jar ollama llama3.1:8b\n"
    "  java -jar ajent.jar zhipu glm-4.5\n"
    "  java -jar ajent.jar openai gpt-4o\n\n"
    "API keys come from each provider's environment variable (see list below);\n"
    "AJENT_API_KEY is a universal fallback. Override or add providers via\n"
    "providers.json in the working directory.\n\n"
    "(development: lein run <same args>)\n\n"
    "Providers:\n"
    (providers/provider-table))))

(defn- row [label value]
  (format "  %-13s %s" label value))

(defn print-startup-summary
  "Prints the resolved runtime configuration."
  [config]
  (let [{:keys [provider base-url model-name temperature api-key
                max-iterations history-window]} config]
    (println
     (str
      "\n"
      (row "Provider"    provider)
      "\n" (row "Endpoint"    base-url)
      "\n" (row "Model"       model-name)
      "\n" (row "Temperature" temperature)
      "\n" (row "API Key"     (if (str/blank? api-key)
                                "none"
                                (str (subs api-key 0 (min 6 (count api-key))) "…")))
      "\n" (row "Iterations"  (str max-iterations " / turn"))
      "\n" (row "History"     (str history-window " messages"))
      "\n"))))

;; ---------------------------------------------------------------------------
;; Argument parsing
;; ---------------------------------------------------------------------------

(defn- fail! [msg]
  (throw (IllegalArgumentException. msg)))

(defn- clean-url [u]
  (let [s (str/trim (str u))]
    (if (str/ends-with? s "/")
      (clean-url (subs s 0 (dec (count s))))
      s)))

(defn parse-args
  "Parses CLI args into the fully-resolved LLM config map.
   Throws IllegalArgumentException with a short, human-friendly message
   on bad input — the caller decides where to show usage."
  [args]
  (let [[provider-id & more] args]
    (when (str/blank? provider-id)
      (fail! "No provider specified."))
    (let [provider       (or (providers/resolve-provider provider-id)
                             (fail! (str "Unknown provider: '" provider-id "'.")))
          host-required? (str/blank? (str (:base-url provider)))
          [base-url model-name temp-str]
          (if host-required?
            (let [[host model temp] more]
              (when (str/blank? host)
                (fail! (str "Provider '" provider-id "' requires a host URL:\n"
                            "  java -jar ajent.jar " provider-id
                            " http://host:port [model] [temperature]")))
              [(providers/host->base-url host) model temp])
            (let [[model temp] more]
              [(clean-url (:base-url provider)) model temp]))
          model    (or (when-not (str/blank? (str model-name)) model-name)
                       (:default-model provider))
          _        (when (str/blank? model)
                     (fail! (str "No model given and provider '" provider-id
                                 "' has no default model.")))
          temp-val (if (str/blank? temp-str)
                     (or (:default-temp provider) 0.7)
                     (try
                       (Double/parseDouble temp-str)
                       (catch NumberFormatException _
                         (fail! (str "Invalid temperature: '" temp-str "'")))))
          api-key  (or (some-> (:api-key-env provider) System/getenv)
                       (System/getenv "AJENT_API_KEY"))]
      (merge provider
             {:provider              provider-id
              :base-url              base-url
              :model-name            model
              :temperature           temp-val
              :api-key               api-key
              :max-iterations        (or (:max-iterations provider) 20)
              :max-observation-chars (or (:max-observation-chars provider) 4000)
              :history-window        (or (:history-window provider) 20)
              :max-attempts          (or (:max-attempts provider) 3)}))))