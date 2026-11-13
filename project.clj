(defproject org.clojars.pooriayousefi/ajent "2.2.0"
  :description "aJent: MCP-native Agentic AI Framework with offline + online LLM provider support"
  :url "https://github.com/pooriayousefi/ajent"
  :license {:name "Eclipse Public License 2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [org.clojure/data.json "2.5.0"]
                 [clj-http "3.13.0"]
                 [http-kit/http-kit "2.8.1"]]
  :main ajent.main
  :aot :all
  :uberjar-name "ajent.jar"
  :target-path "target"
  :scm {:name "git"
        :url "https://github.com/pooriayousefi/ajent"}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :sign-releases false}]])
