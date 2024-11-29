(ns lambdaconnect-sync.test.benchmark
  (:require [lambdaconnect-sync.core :as sync]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-sync.test.basic-memory :as b]
            [lambdaconnect-model.tools :as mpt]
            [lambdaconnect-sync.db :as db]
            [lambdaconnect-model.scoping :as scoping]         
            [lambdaconnect-model.graph-generator :as graph]
            [lambdaconnect-sync.db-drivers.memory :as lsm]
            [lambdaconnect-sync.db-interface :as driver]

            [taoensso.tufte :as tufte :refer [defnp p profiled profile]]
            #?@(:cljs [[clojure.test.check.generators]
                       [cljs.spec.gen.alpha :as gen]
                       [cljs.test :refer [deftest testing is use-fixtures] :as t]
                       [lambdaconnect-model.macro :refer-macros [bench]] 
                       [cljs.spec.alpha :as s]]
                :clj [[clojure.spec.gen.alpha :as gen]                      
                      [lambdaconnect-model.utils :refer [bench]]
                      [clojure.test :refer [deftest testing is use-fixtures] :as t]
                      [clojure.spec.alpha :as s]])
            
            [clojure.repl]
            [clojure.pprint :refer [pprint]]))


(defn create-db []
  (->> "model1.xml"
       (b/load-model-fixture)
       (mp/entities-by-name)
       (lsm/create-db "xxx")))


(defn test-sync-performance [vertices edges slave-mode?]
  (tufte/add-basic-println-handler! {})
  (println "Benchmarking graph processing. Using " vertices "vertices and" edges "edges.")
  (let [config {:log nil 
                :driver (lsm/->MemoryDatabaseDriver nil)}
        db (create-db)
        now #?(:cljs (js/Date.) :clj (java.util.Date.))
        ebn (get-in db [:entities-by-name])
        _ (mp/specs ebn {:FIUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                         :FIUser/gender #(s/gen #{"U" "M" "F"})
                         :FIGame/gender #(s/gen #{"U" "M" "F"})
                         :FILocation/bottomHash #(s/gen #{"aaa"})
                         :FILocation/centerHash  #(s/gen #{"aaa"})
                         :FILocation/topHash  #(s/gen #{"aaa"})
                         :FILocation/leftHash  #(s/gen #{"aaa"})
                         :FILocation/rightHash   #(s/gen #{"aaa"})})
        [test-graph gen-time] (bench (graph/generate-entity-graph ebn :vertices vertices :edges edges :create-sync-revisions? true))
        _ (println "Took" (* 1000 gen-time) "ms to generate graph." )
        [[tx] push-gen-time] (bench (first (profile {} (mapv (fn [_] (sync/push-transaction {:config config 
                                                                                             :incoming-json test-graph 
                                                                                             :snapshot db 
                                                                                             :entities-by-name ebn 
                                                                                             :now now
                                                                                             :slave-mode? slave-mode?
                                                                                             :sync-revision-for-slave-mode 1})) 
                                                             (range 5)))))
        push-gen-time (/ push-gen-time 5)
        _ (println "Took" (* 1000 push-gen-time) "ms to generate push.")
        [new-db speculation-time] (bench (profile {} (p :whole-speculate (db/speculate config db tx))))
        _ (println "Took" (* 1000 speculation-time) "ms to integrate push.")
        [pull-output pull-time] (bench (sync/pull {:config config
                                                   :incoming-json  (into {} (map (fn [n] [n 0]) (keys ebn)))
                                                   :snapshot new-db 
                                                   :entities-by-name ebn
                                                   :slave-mode? slave-mode?}))
        _ (println "Took" (* 1000 pull-time) "ms to generate pull.")
        #?@(:cljs [[js-version js-time] (bench (clj->js pull-output))])
        #?@(:cljs [_ (println "Took" (* 1000 js-time) "ms to convert output to JS data structures.")])
        [[tx] push-gen-time] (bench (sync/push-transaction {:config config 
                                                            :incoming-json test-graph 
                                                            :snapshot new-db 
                                                            :entities-by-name ebn 
                                                            :now #?(:cljs (js/Date.) :clj (java.util.Date.))
                                                            :slave-mode? slave-mode?
                                                            :sync-revision-for-slave-mode 1}))
        _ (println "Took" (* 1000 push-gen-time) "ms to generate push again: " tx)


        game-entity (get ebn "FIGame")

        [games games-extract-time] (bench (lsm/get-paginated-collection new-db "FIGame" 0 1))
        _ (println "Got games in " (* 1000 games-extract-time) "ms.")
        [[game] game-extract-time] (bench (driver/objects-by-ids (:driver config) new-db game-entity games true))
        _ (println "Fetched game in " (* 1000 game-extract-time) "ms.")
        
        new-game (-> (-> game 
                         (assoc :FIGame/inThePast false)
                         (assoc :FIGame/gender "F"))
                     (mp/replace-inverses game-entity true)
                     (mp/clojure-to-json game-entity))
        
        [[tx] push-gen-time] (bench (profile {} (sync/push-transaction {:config config 
                                                                        :incoming-json {"FIGame" [new-game]} 
                                                                        :snapshot new-db 
                                                                        :entities-by-name ebn 
                                                                        :now #?(:cljs (js/Date.) :clj (java.util.Date.))
                                                                        :slave-mode? slave-mode?
                                                                        :sync-revision-for-slave-mode 1})))
        _ (println "Took" (* 1000 push-gen-time) "ms to generate small tx: " tx)
        [newest-db speculation-time] (bench (profile {} (p :whole-speculate (db/speculate config new-db tx))))
        _ (println "Took" (* 1000 speculation-time) "ms to integrate push.")
        test-graph-2 (into {} (map (fn [[k v]] [k []]) test-graph)) 
        [_ push-gen-time-2] (bench (first (profile {} (mapv (fn [_] (sync/push-transaction {:config config 
                                                                                             :incoming-json test-graph-2
                                                                                             :snapshot db 
                                                                                             :entities-by-name ebn 
                                                                                             :now now
                                                                                             :slave-mode? slave-mode?
                                                                                             :sync-revision-for-slave-mode 1})) (range 5))))) 
        _ (println "Took" (* 1000 push-gen-time-2) "ms to generate empty push.")


        ]
    nil))

(deftest benchmarks
  (testing "2000 5000"
    (test-sync-performance 5000 10000 false)
    (test-sync-performance 5000 10000 true)
    ))




