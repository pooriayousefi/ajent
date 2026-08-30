(ns time-server.main
  (:require [time-server.core :as core])
  (:gen-class))

(defn -main [& args]
  (let [port (if-let [p (first args)] (Integer/parseInt p) 4005)]
    (core/start! :port port)))