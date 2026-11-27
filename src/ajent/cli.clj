(ns ajent.cli
  "Command-line entrance: argument parsing, banner, usage, startup summary."
  (:require [ajent.providers :as providers]
            [ajent.config :as config]
            [clojure.string  :as str])
  (:import [java.net URI URISyntaxException]))

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
    "  java -jar ajent.jar [--agent NAME] --provider NAME --model NAME\n\n"
    "  --agent NAME     Prompt in cfg/system_prompts.json (default: orchestrator)\n"
    "  --provider NAME  Provider in cfg/ai_providers.json (required)\n"
    "  --model NAME     Model configured for that provider (required)\n"
    "  --help           Show this help\n\n"
    "Development: lein run --provider local --model gpt-oss-20b")))

(defn- row [label value]
  (format "  %-13s %s" label value))

(defn print-startup-summary
  "Prints the resolved runtime configuration."
  [config]
  (let [{:keys [agent config-path provider base-url model-name temperature api-key
                max-iterations history-window]} config]
    (println
     (str
      "\n"
      (row "Agent"       (or agent "orchestrator"))
      "\n" (row "Provider"    provider)
      (when config-path (str "\n" (row "Provider file" config-path)))
      "\n" (row "Endpoint"    base-url)
      "\n" (row "Model"       model-name)
      "\n" (row "Temperature" temperature)
      "\n" (row "API Key"     (if (str/blank? api-key) "none" "configured"))
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

(defn- endpoint-url? [^String value]
  (try
    (let [uri (URI. value)]
      (and (contains? #{"http" "https"} (.getScheme uri))
           (.getHost uri)
           (nil? (.getUserInfo uri))
           (nil? (.getRawQuery uri))
           (nil? (.getRawFragment uri))
           (or (= -1 (.getPort uri)) (<= 1 (.getPort uri) 65535))))
    (catch URISyntaxException _ false)))

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
  (let [base-url (providers/host->base-url host)]
    (when-not (endpoint-url? base-url)
      (fail! "Local mode requires an absolute HTTP(S) host URL."))
    (resolve-common-config "local" (providers/resolve-provider "local")
                           base-url model temp nil)))

(defn- parse-online-args [[provider-id model temp]]
  (when (str/blank? provider-id)
    (fail! "Online mode requires a provider ID. Example: aJent online openai gpt-4o"))
  (let [provider (or (providers/resolve-provider provider-id)
                     (fail! (str "Unknown online provider: '" provider-id "'.")))
        provider-mode (some-> (:mode provider) name str/lower-case keyword)
        _ (when-not (or (= :online provider-mode)
                        (and (nil? provider-mode) (not (str/blank? (:api-key-env provider)))))
            (fail! (str "'" provider-id "' is not configured as an online provider.")))
        base-url (clean-url (:base-url provider))
        _ (when-not (endpoint-url? base-url)
            (fail! (str "Online provider '" provider-id "' requires an absolute HTTP(S) base-url.")))
        api-key (or (some-> (:api-key-env provider) System/getenv)
                    (System/getenv "AJENT_API_KEY"))
        _ (when (str/blank? api-key)
            (fail! (str "Missing API key. Set " (or (:api-key-env provider) "AJENT_API_KEY")
                        " or AJENT_API_KEY before starting online mode.")))]
    (resolve-common-config provider-id provider base-url model temp api-key)))

(defn- parse-configured-args [args]
  (let [options (reduce (fn [parsed [flag value]]
                          (when-not (contains? #{"--agent" "--provider" "--model"} flag)
                            (fail! (str "Unknown option: " flag)))
                          (when (or (str/blank? value) (str/starts-with? value "--"))
                            (fail! (str flag " requires a value.")))
                          (assoc parsed flag value))
                        {} (partition-all 2 args))
        {agent-name "--agent" provider-name "--provider" model-name "--model"
         :or {agent-name "orchestrator"}} options
        path "cfg/ai_providers.json"]
    (when (or (nil? provider-name) (nil? model-name))
      (fail! "Both --provider and --model are required."))
    (let [entries (:providers (config/read-json path))
          provider (some #(when (= provider-name (:name %)) %) entries)
          model (some #(when (= model-name (:model-name %)) %) (:models provider))
          base-url (clean-url (:base-url model))
          key-env (or (:api-key-env model) (:api-key-env provider))
          api-key (when key-env
                    (or (not-empty (System/getenv key-env))
                        (System/getenv "AJENT_API_KEY")))]
      (when-not provider
        (fail! (str "Unknown provider '" provider-name "' in " path)))
      (when-not model
        (fail! (str "Unknown model '" model-name "' for provider '" provider-name "'.")))
      (when-not (endpoint-url? base-url)
        (fail! (str "Model '" model-name "' requires an absolute HTTP(S) base-url.")))
      (when (and key-env (str/blank? api-key))
        (fail! (str "Missing API key. Set " key-env " or AJENT_API_KEY.")))
      (-> (resolve-common-config provider-name
                                 (assoc (merge provider model) :default-temp (:temp model))
                                 base-url model-name nil api-key)
          (assoc :agent agent-name :config-path path)))))

(defn parse-args [args]
  (let [[mode & more] args]
    (case mode
      "local" (parse-local-args more)
      "online" (parse-online-args more)
      (parse-configured-args args))))
