(ns ajent.main
  (:require [ajent.core :as core])
  (:gen-class))

(defn -main
  [& args]
  (let [exit-code (try
                    (apply core/run args)
                    0
                    (catch IllegalArgumentException e
                      (println (str "\n❌ " (.getMessage e)))
                      (println "Run with --help to see usage.")
                      1)
                    (catch Exception e
                      (println (str "\n❌ " (.getMessage e)))
                      (when-let [data (ex-data e)]
                        (println "Details:" data))
                      1))]
    (System/exit exit-code)))