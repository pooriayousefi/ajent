(ns ajent.main
  (:require [ajent.core :as core])
  (:gen-class))

(defn -main
  [& args]
  (let [exit-code (try
                    (apply core/run args)
                    0
                    (catch Exception e
                      (println "Exception: " (.getMessage e))
                      (when-let [data (ex-data e)]
                        (println "Error details:" data))
                      1))]
    (System/exit exit-code)))