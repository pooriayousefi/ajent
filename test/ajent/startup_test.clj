(ns ajent.startup-test
  (:require [ajent.cli :as cli]
            [ajent.tools :as tools]
            [clojure.test :refer [deftest is]]
            [mcp.client :as mcp]
            [mcp.openai :as openai]))

(deftest validates-and-normalizes-local-endpoint
  (is (= "http://127.0.0.1:8080/v1"
         (:base-url (cli/parse-args ["local" "http://127.0.0.1:8080/"]))))
  (doseq [url ["localhost:8080" "https:///" "https://api.example?key=value"
               "http://localhost:99999" "https://api.example#fragment"]]
    (is (thrown-with-msg? IllegalArgumentException #"absolute HTTP"
                          (cli/parse-args ["local" url])))))

(deftest startup-summary-omits-api-key
  (let [output (with-out-str (cli/print-startup-summary {:api-key "secret-value"}))]
    (is (re-find #"configured" output))
    (is (not (re-find #"secret" output)))))

(deftest discovery-failure-closes-opened-connections
  (let [closed (atom [])]
    (with-redefs-fn {#'ajent.tools/load-config
                     (constantly [{:name "first" :url "http://localhost:5001/mcp"}
                                  {:name "second" :url "http://localhost:5002/mcp"}])
                     #'mcp/connect (fn [{:keys [url]}] url)
                     #'openai/tools (fn [_] (throw (ex-info "discovery failed" {})))
                     #'mcp/close! (fn [conn] (swap! closed conj conn))}
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"discovery failed"
                             (#'ajent.tools/build-registry))))
    (is (= #{"http://localhost:5001/mcp" "http://localhost:5002/mcp"}
           (set @closed)))))
