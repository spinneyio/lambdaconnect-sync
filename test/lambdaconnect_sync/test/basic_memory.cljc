(ns lambdaconnect-sync.test.basic-memory
  (:require [lambdaconnect-sync.db-drivers.memory :as memory-driver]
            [lambdaconnect-model.core :as mp]
            #?(:clj [clojure.data.json :refer [read-str]])))


(defn load-model-fixture [filename]
  #?(:cljs (let [fs (js/require "fs")]
             (.readFileSync fs (str "env/test/resources/" filename) "utf8"))
     :clj (str "env/test/resources/" filename)))

(defn slurp-fixture [filename] 
  #?(:clj (->> filename
               (str "env/test/resources/")
               slurp
               read-str) 
     :cljs (->> filename
                (str "env/test/resources/") 
                (#(let [fs (js/require "fs")]
                   (.readFileSync fs % "utf8")))
                (.parse js/JSON)
                (js->clj))))

(defn get-mobile-sync-config []
  {:log nil; println
   })

(def mobile-sync-config (let [c (get-mobile-sync-config)]
                          (assoc c :driver (memory-driver/->MemoryDatabaseDriver c))))
(def conn (atom nil))

(defn setup-basic-test-environment [model-path generators f]
  (let [db-name "datomic:mem://test-db"
        entities-by-name (mp/entities-by-name model-path)]    

    (reset! conn (memory-driver/create-db db-name entities-by-name))
    (mp/specs entities-by-name generators)
    (try (f)
         (finally           
           (reset! conn nil)))))
