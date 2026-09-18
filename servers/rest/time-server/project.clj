(defproject time-server "1.0.0"
  :description "Time REST server for aJent"
  :main time-server.main
  :aot :all
  :uberjar-name "time-server.jar"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/data.json "2.5.0"]])