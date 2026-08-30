(defproject ajent/ajent "1.0.0"
  :description "aJent: Agentic AI Framework"
  :url "https://github.com/pooriayousefi/ajent"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/data.json "2.5.0"]
                 [clj-http "3.13.0"]]
  :main ajent.main
  :aot :all
  :uberjar-name "ajent.jar"
  :target-path "target")