(ns ajent.cli
  "Command-line entrance: argument parsing, banner, usage, startup summary."
  (:require [ajent.providers :as providers]
            [clojure.string  :as str]))

(def ^:const version "2.2.0")

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
    "   offline & online LLM providers  ·  concurrent MCP tool fleet\n"
    "  ──────────────────────────────────────────────────────────\n")))

(defn print-usage []
  (println
   (str
    "aJent v" version " — agentic AI framework (offline + online LLM providers)\n\n"
    "Usage:\n"
    "  java -jar ajent.jar local <host-url> [model] [temperature]\n"
    "  java -jar ajent.jar online <provider> [model] [temperature]\n\n"
    "Examples:\n"
    "  java -jar ajent.jar local http://localhost:8080 gpt-oss-20b 0.7\n"
    "  java -jar ajent.jar local http://localhost:11434/v1 llama3.1:8b\n"
    "  java -jar ajent.jar online zhipu glm-4.5\n"
    "  java -jar ajent.jar online openai gpt-4o\n\n"
    "Online mode requires an API key; local mode does not use one.\n"
    "Set the provider-specific environment variable or AJENT_API_KEY.\n"
    "Override or add providers via\n"
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

(defn- resolve-common-config [provider-id provider base-url model-name temp-str api-key]
  (let [model (or (when-not (str/blank? (str model-name)) model-name)
                  (:default-model provider))
        _ (when (str/blank? model)
            (fail! (str "No model given and provider '" provider-id
                        "' has no default model.")))
        temp-val (if (str/blank? temp-str)
                   (or (:default-temp provider) 0.7)
                   (try
                     (Double/parseDouble temp-str)
                     (catch NumberFormatException _
                       (fail! (str "Invalid temperature: '" temp-str "'")))))]
    (merge provider
           {:provider provider-id
            :base-url base-url
            :model-name model
            :temperature temp-val
            :api-key api-key
            :max-iterations (or (:max-iterations provider) 20)
            :max-observation-chars (or (:max-observation-chars provider) 4000)
            :history-window (or (:history-window provider) 20)
            :max-attempts (or (:max-attempts provider) 3)})))

(defn- parse-local-args [[host model temp]]
  (when (str/blank? host)
    (fail! "Local mode requires a host URL. Example: aJent local http://localhost:8080 gpt-oss-20b 0.7"))
  (let [provider (providers/resolve-provider "local")]
    (resolve-common-config "local" provider
                           (providers/host->base-url host)
                           model temp nil)))

(defn- parse-online-args [[provider-id model temp]]
  (when (str/blank? provider-id)
    (fail! "Online mode requires a provider ID. Example: aJent online openai gpt-4o"))
  (let [provider (or (providers/resolve-provider provider-id)
                     (fail! (str "Unknown online provider: '" provider-id "'.")))
        _ (when (or (= :local (:mode provider))
                    (and (nil? (:mode provider)) (nil? (:api-key-env provider))))
            (fail! (str "'" provider-id "' is not configured as an online provider.")))
        base-url (clean-url (:base-url provider))
        _ (when (str/blank? base-url)
            (fail! (str "Online provider '" provider-id "' has no base-url.")))
        api-key (or (some-> (:api-key-env provider) System/getenv)
                    (System/getenv "AJENT_API_KEY"))
        _ (when (str/blank? api-key)
            (fail! (str "Missing API key. Set " (or (:api-key-env provider) "AJENT_API_KEY")
                        " or AJENT_API_KEY before starting online mode.")))]
    (resolve-common-config provider-id provider base-url model temp api-key)))

(defn parse-args
  "Dual entry point: local <host-url> [model] [temperature], or
   online <provider> [model] [temperature]."
  [args]
  (let [[mode & more] args]
    (case mode
      "local" (parse-local-args more)
      "online" (parse-online-args more)
      (fail! (str "Unknown execution mode: '" mode
                  "'. Use 'local' or 'online'. Run with --help for usage.")))))
