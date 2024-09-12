(ns lambdaconnect-sync.test.core-memory
  (:require [lambdaconnect-sync.core :as sync]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-sync.test.basic-memory :as b]
            [lambdaconnect-model.tools :as mpt]
            [lambdaconnect-sync.db :as db]
            [lambdaconnect-sync.db-drivers.memory :as memory]
            [lambdaconnect-model.scoping :as scoping]         

            #?@(:cljs [[clojure.test.check.generators]
                       [cljs.spec.gen.alpha :as gen]
                       [cljs.test :refer [deftest testing is use-fixtures] :as t]                       
                       [cljs.spec.alpha :as s]]
                :clj [[clojure.spec.gen.alpha :as gen]                      
                      [clojure.test :refer [deftest testing is use-fixtures] :as t]
                      [clojure.spec.alpha :as s]])
            
            [clojure.repl]
            [clojure.pprint :refer [pprint]]))


(def Epsilon 0.0001)

(use-fixtures :once (partial b/setup-basic-test-environment (b/load-model-fixture "test-model-0.xml") 
                             {:LAUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                              :LAUser/gender #(s/gen #{"U" "M" "F"})}))

(defn push-transaction [snapshot user-uuid params suppress-log? constants]
  (let [entities-by-name (mp/entities-by-name (b/load-model-fixture "test-model-0.xml"))
        ;; Scoping doesnt work for in-memory driver

        ;; scoping-edn (read-edn "env/test/resources/test-scope-0.edn")
        ;; _ (scoping/validate-pull-scope entities-by-name scoping-edn)                
        ;; push-scoping-edn (assoc (scoping/add-include-in-push-permission scoping-edn)
        ;;                         :constants constants)
        time #?(:clj (java.util.Date.) :cljs (js/Date.))      
        user {:app/uuid user-uuid
              :db/id 0} 
        config b/mobile-sync-config ;
        ;; (if-not suppress-log? 
        ;;   (assoc b/mobile-sync-config :log println)
        ;;   b/mobile-sync-config)
                                        ;to generate data import transactions REMOVE :log

        [transaction [created-objects updated-objects] rejections]
        (sync/push-transaction config params user snapshot entities-by-name nil time)]
    {:tx transaction
     :rejections rejections}))

(defn pull [snapshot user-uuid params suppress-log? constants]
  (let [
        entities-by-name (mp/entities-by-name (b/load-model-fixture "test-model-0.xml"))
        ;; Scoping doesnt work for in-memory driver

        ;; scoping-edn (read-edn "env/test/resources/test-scope-0.edn")
        ;; _ (scoping/validate-pull-scope entities-by-name scoping-edn)                
        ;; pull-scoping-edn (assoc scoping-edn
        ;;                         :constants constants)
        time #?(:clj (java.util.Date.) :cljs (js/Date.))             
        user {:app/uuid user-uuid
              :db/id 0} 
        config b/mobile-sync-config
        ;; (if-not suppress-log? 
        ;;          (assoc b/mobile-sync-config :log println)
        ;;          b/mobile-sync-config)
]
    (sync/pull config params user snapshot entities-by-name nil)))

(deftest test-core-data-xml-conversion
  (testing "Reading model file one"
    (let [model (mp/entities-by-name (b/load-model-fixture "test-model-0.xml"))]
      (is (= (count model) 6))
      (testing ";Json to model converter"
        
          (let [game-model (get model "LAGame")

                json (b/slurp-fixture "fixtures-0.json")   
                ent (-> json (get "LAGame") first (mp/json-to-clojure game-model))
                generated-games (gen/sample (s/gen (mp/spec-for-name :LAGame)) 200)]
            (is (s/valid? (mp/spec-for-name :LAGame) ent) (s/explain-str (mp/spec-for-name :LAGame) ent))
            (testing ";Inverse"
              (is (= (mp/clojure-to-json ent game-model) (-> json (get "LAGame") first)))
              (doseq [generated-game generated-games]
                (let [processed-game (-> generated-game
                                         (mp/clojure-to-json game-model)
                                         (mp/json-to-clojure game-model))]
                  (is (mpt/compare-objects generated-game processed-game game-model)))))))))

  (testing "Schema from model"
    (let [model (mp/entities-by-name (b/load-model-fixture "test-model-0.xml"))
          schema (mp/datomic-schema model)]
      (is (= (+ 43 8 (count model)) (count schema)))))
  
  (testing "User info"
    (let [model (mp/entities-by-name (b/load-model-fixture "test-model-0.xml"))]
      (is (seq (get-in model ["LAGame" :user-info])))
      (is (seq (get-in model ["LAGame" :attributes "gameDescription"]))))))


(deftest
  push-accepted 
  (let [user-uuid (random-uuid)
        ebn (mp/entities-by-name (b/load-model-fixture "test-model-0.xml"))
        snapshot @b/conn
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address nil)
                    (assoc :LAUser/organisedGames [])
                    (assoc :LAUser/playsFor []))
        {:keys [tx rejections]} 
        (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
                          true (fn [_snapshot _user] 
                                 {:wow (delay true)
                                  :are-you-there? false
                                  :can-create? true
                                  :whatsupp? true
                                  :some-new-fields ["firstName"]}))
        {:keys [rejected-objects rejected-fields]} rejections]
    (is (not (empty? tx)))
    (is (empty? rejected-objects))
    (is (empty? rejected-fields))))

(deftest 
  replace-fields-wont-work-without-scoping
  (let [user-uuid (random-uuid)
        location-uuid (random-uuid)
        ebn (mp/entities-by-name (b/load-model-fixture "test-model-0.xml"))
        snapshot @b/conn
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address {:app/uuid location-uuid})
                    (assoc :LAUser/organisedGames [])
                    (assoc :LAUser/playsFor []))
        la-location (-> (gen/sample (s/gen (mp/spec-for-name :LALocation)) 100)
                        (first)                        
                        (assoc :app/uuid location-uuid)
                        (assoc :LALocation/city "Krakow")
                        (assoc :LALocation/latitude 31.21)
                        (assoc :LALocation/country "Polska")
                        (assoc :LALocation/games [])
                        (assoc :LALocation/users [{:app/uuid (:app/uuid la-user)}])
                        (assoc :LALocation/ticketsSold []))
        json {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]
              "LALocation" [(mp/clojure-to-json la-location (get ebn "LALocation"))]} 
        {:keys [tx rejections]} 
        (testing "creating an object"
          (push-transaction snapshot user-uuid json
                            true (fn [_snapshot _user] 
                                   {:wow (delay true)
                                    :are-you-there? false
                                    :can-create? true
                                    :whatsupp? true
                                    :some-new-fields ["firstName"]})))
        {:keys [rejected-objects rejected-fields]} rejections]
    (is (not (empty? tx)))
    (is (empty? rejected-objects))
    (is (empty? rejected-fields))    
    
    (testing "Do a pull and see what came in"
      (let [snapshot (db/speculate b/mobile-sync-config snapshot tx)
            result (pull snapshot user-uuid {"LAUser" 0 "LALocation" 0} 
                         true (fn [_snapshot _user]
                                {:wow (delay true)
                                 :are-you-there? false
                                 :can-create? true
                                 :whatsupp? true                                
                                 :lat 82.3
                                 :some-new-fields ["firstName"]}))]
        (is (= "Krakow" (-> result 
                            (get "LALocation")
                            (first)
                            (get "city"))))
        ;; Replacement with a constant doesnt work       
        (is (< Epsilon (abs (- 82.3 (-> result 
                                        (get "LALocation")
                                        (first)
                                        (get "latitude"))))))))
    
    (testing "Try editing succeeds and replaced fields are actually replaced (no scoping)"
      (let [snapshot (db/speculate b/mobile-sync-config snapshot tx)            
            {:keys [tx rejections]} 
            (push-transaction snapshot user-uuid {"LALocation" 
                                                  [(mp/clojure-to-json (assoc la-location 
                                                                              :LALocation/city "Bombaj"
                                                                              :LALocation/latitude 85.0) 
                                                                       (get ebn "LALocation"))]} 
                              true (fn [_snapshot _user] 
                                     {:wow (delay true)
                                      :are-you-there? false
                                      :can-create? true
                                      :lat 82.3
                                      :whatsupp? true
                                      :some-new-fields ["firstName"]}))
            {:keys [rejected-objects rejected-fields]} rejections]

        (is (not (empty? tx)))
        (is (empty? rejected-fields))
        (is (empty? rejected-objects))))))

