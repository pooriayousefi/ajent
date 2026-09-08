(defproject org.clojars.pooriayousefi/ajent "2.0.1"
  :description "aJent: Agentic AI Framework with unified offline + online LLM provider support"
  :url "https://github.com/pooriayousefi/ajent"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/data.json "2.5.0"]
                 [clj-http "3.13.0"]
                 [org.clojars.pooriayousefi/mcp.clj "0.1.2"]]
  :main ajent.main
  :aot :all
  :uberjar-name "ajent.jar"
  :target-path "target"
  :scm {:name "git"
        :url "https://github.com/pooriayousefi/ajent"}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :sign-releases false}]])