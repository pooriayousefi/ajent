(ns ajent.tools
  (:require [clojure.data.json :as json]
            [clj-http.client   :as client]
            [clojure.string    :as str]))

(defn- load-registry-config []
  (let [content (slurp "registry.txt")]
    (->> (str/split-lines content)
         (map str/trim)
         (remove str/blank?)
         (mapv (fn [url] {:url url})))))

(defn- body->str [body]
  (cond
    (nil? body) ""
    (string? body) body
    :else (slurp body)))

(defn- fetch-http-server-info [url]
  (try
    (let [resp   (client/get (str url "/schema")
                             {:as :text :throw-exceptions false})
          status (:status resp)
          body   (body->str (:body resp))]
      (if (= status 200)
        (let [data (json/read-str body :key-fn keyword)
              schemas (if (vector? data) data [data])]
          (into {}
                (for [schema schemas
                      :let [tool-name (get-in schema [:function :name])]]
                  (if tool-name
                    {tool-name {:schema schema :url url}}
                    (println (str "    [warn] No tool name in schema from " url))))))
        (println (str "    [warn] " url " returned HTTP " status))))
    (catch Exception e
      (println (str "    [warn] Could not reach " url ": " (.getMessage e))))))

(defn- build-registry []
  (let [http-urls (->> (load-registry-config) (mapv :url))]
    (println "Discovering tools…")
    (println "  HTTP servers: " (count http-urls))
    (let [http-res    (pmap fetch-http-server-info http-urls)
          registry    (into {} (remove nil? http-res))]
      (println (str "\n✅ Registry built with " (count registry) " tool(s):"))
      (doseq [[k v] (sort-by first registry)]
        (println (str "   - " (name k) "  [http]")))
      registry)))

(defonce ^:private registry-cache (atom nil))

(defn- get-registry []
  (or @registry-cache
      (reset! registry-cache (build-registry))))

(defn refresh-registry! []
  (reset! registry-cache (build-registry)))

(defn get-tools
  "Returns a vector of OpenAI function schemas for the LLM."
  []
  (mapv :schema (vals (get-registry))))

(defn get-tools-by-names
  "Returns a vector of tool schemas filtered dynamically by tool names."
  [tool-names]
  (let [registry (get-registry)
        name-set (set tool-names)]
    (->> registry
         vals
         (filter #(name-set (get-in % [:schema :function :name])))
         (mapv :schema))))

(defn handle-tool-call
  "Dispatches to the matching HTTP tool server. Returns the result as a string."
  [tool-name args-map]
  (let [info (get (get-registry) tool-name)]
    (if (not info)
      (str "Error: Tool " tool-name " not found in registry.")
      (try
        (let [resp   (client/post (str (:url info) "/tools/call")
                                  {:headers {"Content-Type" "application/json"}
                                   :body    (json/write-str {:name tool-name :arguments args-map})
                                   :as      :text
                                   :throw-exceptions false})
              status (:status resp)
              body   (body->str (:body resp))]
          (if (= status 200)
            (let [parsed-body (json/read-str body :key-fn keyword)]
              (if (:is_error parsed-body)
                (str "Error: " (:error parsed-body))
                (or (:result parsed-body) "")))
            (str "HTTP Error from tool " tool-name " (" status "): " body)))
        (catch Exception e
          (str "Error calling HTTP tool " tool-name ": " (.getMessage e)))))))