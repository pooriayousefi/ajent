(ns ajent.config
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn find-file [& paths]
  (some #(when (.isFile (io/file %)) %) paths))

(defn read-json [path]
  (try
    (json/read-str (slurp path) :key-fn keyword)
    (catch Exception e
      (throw (ex-info (str "Cannot read " path ": " (.getMessage e))
                      {:file path} e)))))
