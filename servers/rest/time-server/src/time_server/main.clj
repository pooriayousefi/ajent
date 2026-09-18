(ns time-server.main
  (:require [time-server.core :as core])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress])
  (:gen-class))

(defn -main [& [host port]]
  (let [host (or host "127.0.0.1")
        port (if port (Integer/parseInt port) 4006)
        server (HttpServer/create (InetSocketAddress. ^String host ^int port) 0)]

    ;; Using reify instead of proxy for modern, idiomatic Clojure interface implementation
    (.createContext server "/schema"
                    (reify HttpHandler
                      (handle [this ex]
                        (core/handle-schema ex))))

    (.createContext server "/tools/call"
                    (reify HttpHandler
                      (handle [this ex]
                        (core/handle-tool-call ex))))

    (.setExecutor server nil)
    (.start server)

    (println (str "time REST server running on http://" host ":" port))))