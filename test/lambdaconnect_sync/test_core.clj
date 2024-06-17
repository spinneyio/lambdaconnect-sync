(ns lambdaconnect-sync.test-core
  (:require [lambdaconnect-sync.core :as sync]
            [clojure.test :refer [deftest testing is use-fixtures] :as t]
            [clojure.spec.alpha :as s]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-model.tools :as mpt]
            [lambdaconnect-model.scoping :as scoping]
            [clojure.data.json :refer [read-str]]
            [clojure.spec.gen.alpha :as gen]
            [datomic.api :as d]
            [clojure.repl]
            [clojure.pprint :refer [pprint]]))

;; Normally the exception thrown by such-that does not point to the failed spec, which makes it useless
;; Eval this to override it and include `path` to the spec (and `form`, not sure which better)

;; Uncomment this for detailed spec debugging

;; (in-ns 'clojure.spec.alpha)
;; (defn- gensub
;;   [spec overrides path rmap form]
;;   (prn {:spec spec :over overrides :path path :form form})
;;   (when (keyword? spec) (eval `(clojure.repl/doc ~spec)))
;;   (let [spec (specize spec)]
;;     (if-let [g (c/or (when-let [gfn (c/or (get overrides (c/or (spec-name spec) spec))
;;                                           (get overrides path))]
;;                        (gfn))
;;                      (gen* spec overrides path rmap))]
;;       (gen/such-that #(valid? spec %) g {:max-tries 100
;;                                          :ex-fn (fn [{:keys [max-tries]}]
;;                                                   (ex-info (str "Couldn't satisfy " (spec-name spec) "  after " max-tries " tries.")
;;                                                            {:max  max-tries
;;                                                             :path path
;;                                                             :sample-explain (->> (first (gen/sample g 1))
;;                                                                                  (explain-data spec)
;;                                                                                  :clojure.spec.alpha/problems)}))})
;;       (let [abbr (abbrev form)]
;;         (throw (ex-info (str "Unable to construct gen at: " path " for: " abbr)
;;                         {::path path ::form form ::failure :no-gen}))))))

;; (in-ns 'lambdaconnect-sync.test-core)

(def Epsilon 0.0001)

(defn speculate [db t]
  (:db-after (d/with db t)))

(defn get-mobile-sync-config []
  {:log (constantly nil)
   :as-of d/as-of
   :pull d/pull
   :pull-many d/pull-many
   :q d/q
   :history d/history
   :tx->t d/tx->t
   :with d/with
   :basis-t d/basis-t})

(def conn (atom nil))

(defn setup-basic-test-environment [model-path f]
  (let [db-name "datomic:mem://test-db"
        entities-by-name (mp/entities-by-name model-path)]    
    (d/create-database db-name)

    (reset! conn (d/connect db-name))
    @(d/transact @conn [{:db/ident              :app/uuid
                         :db/valueType          :db.type/uuid
                         :db/cardinality        :db.cardinality/one
                         :db/unique             :db.unique/identity
                         :db/doc                "UUID for an object in the system"}
                        
                        {:db/ident              :app/createdAt
                         :db/valueType          :db.type/instant
                         :db/cardinality        :db.cardinality/one
                         :db/doc                "Creation time"}
                        
                        {:db/ident              :app/updatedAt
                         :db/valueType          :db.type/instant
                         :db/cardinality        :db.cardinality/one
                         :db/doc                "Last update time"}
                        
                        {:db/ident              :app/active
                         :db/valueType          :db.type/boolean
                         :db/cardinality        :db.cardinality/one
                         :db/doc                "If false, it means the entity was deleted"}])
    @(d/transact @conn (mp/datomic-schema entities-by-name))   
    (mp/specs entities-by-name 
     {:LAUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
      :LAUser/gender #(s/gen #{"U" "M" "F"})})

    (try (f)
         (finally           
           (d/delete-database db-name)
           (reset! conn nil)))))

(use-fixtures :once (partial setup-basic-test-environment "env/test/test-model.xml"))

(defn push-transaction [snapshot user-uuid params suppress-log? constants]
  (let [read-edn (fn [path] (read-string (slurp path)))
        entities-by-name (mp/entities-by-name "env/test/test-model.xml")
        scoping-edn (read-edn "env/test/test-scope.edn")
        _ (scoping/validate-pull-scope entities-by-name scoping-edn)                
        push-scoping-edn (assoc (scoping/add-include-in-push-permission scoping-edn)
                                :constants constants)
        time (java.util.Date.)       
        user (d/entity snapshot (d/q '[:find ?e . :in $ ?uuid :where [?e :app/uuid ?uuid]] snapshot user-uuid))
        config (if-not suppress-log? 
                 (assoc (get-mobile-sync-config) :log println)
                 (get-mobile-sync-config))
                                        ;to generate data import transactions REMOVE :log

        [transaction [created-objects updated-objects] rejections]
        (sync/push-transaction config params user snapshot entities-by-name push-scoping-edn time)]
    {:tx transaction
     :rejections rejections}))

(defn pull [snapshot user-uuid params suppress-log? constants]
  (let [read-edn (fn [path] (read-string (slurp path)))
        entities-by-name (mp/entities-by-name "env/test/test-model.xml")
        scoping-edn (read-edn "env/test/test-scope.edn")
        _ (scoping/validate-pull-scope entities-by-name scoping-edn)                
        pull-scoping-edn (assoc scoping-edn
                                :constants constants)
        time (java.util.Date.)       
        user (d/entity snapshot (d/q '[:find ?e . :in $ ?uuid :where [?e :app/uuid ?uuid]] snapshot user-uuid))
        config (if-not suppress-log? 
                 (assoc (get-mobile-sync-config) :log println)
                 (get-mobile-sync-config))]
    (sync/pull config params user snapshot entities-by-name pull-scoping-edn)))

(deftest test-core-data-xml-conversion
  (testing "Reading model file one"
    (let [model (mp/entities-by-name "env/test/test-model.xml")]
      (is (= (count model) 6))
      (testing ";Json to model converter"
        (try
          (let [game-model (get model "LAGame")
                json (-> "env/test/fixtures.json"
                         slurp
                         read-str)
                ent (-> json (get "LAGame") first (mp/json-to-clojure game-model))
                generated-games (gen/sample (s/gen (mp/spec-for-name :LAGame)) 200)]
            (is (s/valid? (mp/spec-for-name :LAGame) ent) (s/explain-str (mp/spec-for-name :LAGame) ent))
            (testing ";Inverse"
              (is (= (mp/clojure-to-json ent game-model) (-> json (get "LAGame") first)))
              (doseq [generated-game generated-games]
                (let [processed-game (-> generated-game
                                         (mp/clojure-to-json game-model)
                                         (mp/json-to-clojure game-model))]
                  (is (mpt/compare-objects generated-game processed-game game-model))))))
          (catch clojure.lang.ExceptionInfo e
            (.printStackTrace e))))))

  (testing "Schema from model"
    (let [model (mp/entities-by-name "env/test/test-model.xml")
          schema (mp/datomic-schema model)]
      (is (= (+ 37 8 (count model)) (count schema)))))
  
  (testing "User info"
    (let [model (mp/entities-by-name "env/test/test-model.xml")]
      (is (seq (get-in model ["LAGame" :user-info])))
      (is (seq (get-in model ["LAGame" :attributes "gameDescription"]))))))

(deftest
  push-rejected 
  (let [user-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/test-model.xml")
        _ @(d/transact @conn [{:app/uuid user-uuid}])
        snapshot (d/db @conn)
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address nil)
                    (assoc :LAUser/organisedGames [])
                    (assoc :LAUser/playsFor []))
        {:keys [tx rejections]} 
        (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
                          true {:wow (delay true)
                                 :are-you-there? false
                                 :can-create? false
                                 :whatsupp? true
                                 :some-new-fields []})
        {:keys [rejected-objects rejected-fields]} rejections]
    (is (empty? tx))
    (is (not (empty? rejected-objects)))
    (is (empty? rejected-fields))))

(deftest
  push-accepted 
  (let [user-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/test-model.xml")
        _ @(d/transact @conn [{:app/uuid user-uuid}])
        snapshot (d/db @conn)
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address nil)
                    (assoc :LAUser/organisedGames [])
                    (assoc :LAUser/playsFor []))
        {:keys [tx rejections]} 
        (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
                          true {:wow (delay true)
                                 :are-you-there? false
                                 :can-create? true
                                 :whatsupp? true
                                 :some-new-fields ["lala"]})
        {:keys [rejected-objects rejected-fields]} rejections]
    (is (not (empty? tx)))
    (is (empty? rejected-objects))
    (is (empty? rejected-fields))))

(deftest 
  push-accepted-edit-rejected
  (let [user-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/test-model.xml")
        _ @(d/transact @conn [{:app/uuid user-uuid}])
        snapshot (d/db @conn)
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address nil)
                    (assoc :LAUser/organisedGames [])
                    (assoc :LAUser/playsFor []))
        {:keys [tx rejections]} 
        (testing "Even though the protected fields ('some-new-firelds') are nonsense, they are not being checked here since we do not use them when first creating an object"
          (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
                            true {:wow (delay true)
                                  :are-you-there? false
                                  :can-create? true
                                  :whatsupp? true
                                  :some-new-fields ["lala"]}))
        {:keys [rejected-objects rejected-fields]} rejections]
    (is (not (empty? tx)))
    (is (empty? rejected-objects))
    (is (empty? rejected-fields))    
    
    (testing "Try editing fails - wrong protected fields definition"
      (let [snapshot (speculate snapshot tx)]
        (is (thrown? AssertionError
                     (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
                                       true {:wow (delay true)
                                             :are-you-there? false
                                             :can-create? true
                                             :whatsupp? true
                                             :some-new-fields ["lala"]})))))

    (testing "Try editing succeeds but with rejections and empty transaction"
      (let [snapshot (speculate snapshot tx)
            {:keys [tx rejections]} 
            (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json (assoc la-user :LAUser/firstName "Zorro") (get ebn "LAUser"))]} 
                                       true {:wow (delay true)
                                             :are-you-there? false
                                             :can-create? true
                                             :whatsupp? true
                                             :some-new-fields ["firstName"]})
            {:keys [rejected-objects rejected-fields]} rejections]

        (is (= "firstName" (-> rejected-fields
                               (get "LAUser")
                               (first)
                               (second)
                               (#(filter :field %))
                               (first)
                               :field)) (with-out-str (pprint rejections)))
        (is (empty? tx))
        (is (not (empty? rejected-fields)))
        (is (empty? rejected-objects))))))

(deftest ^:test-refresh/focus 
  replace-fields
  (let [user-uuid (random-uuid)
        location-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/test-model.xml")
        _ @(d/transact @conn [{:app/uuid user-uuid}])
        snapshot (d/db @conn)
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
        (testing "Even though the protected fields ('some-new-fields') are nonsense, they are not being checked here since we do not use them when first creating an object"
          (push-transaction snapshot user-uuid json
                            true {:wow (delay true)
                                  :are-you-there? false
                                  :can-create? true
                                  :whatsupp? true
                                  :some-new-fields ["lala"]}))
        {:keys [rejected-objects rejected-fields]} rejections]
    (is (not (empty? tx)))
    (is (empty? rejected-objects))
    (is (empty? rejected-fields))    
    
    (testing "Do a pull (fails because constant 'lat' is not defined)"
      (let [snapshot (speculate snapshot tx)]
        ;; Lazy nightmare
        (is (thrown? java.util.concurrent.ExecutionException 
                     (doseq [x (vals (pull snapshot user-uuid {"LAUser" 0 "LALocation" 0} 
                                           true {:wow (delay true)
                                                 :are-you-there? false
                                                 :can-create? true
                                                 :whatsupp? true                                
                                                 :some-new-fields ["lala"]}))]
                       (doall x))))))
    
    (testing "Do a pull and see what came in - replacements should have arrived"
      (let [snapshot (speculate snapshot tx)
            result (pull snapshot user-uuid {"LAUser" 0 "LALocation" 0} 
                         true {:wow (delay true)
                               :are-you-there? false
                               :can-create? true
                               :whatsupp? true                                
                               :lat 82.3
                               :some-new-fields ["lala"]})]
        ;; Replacement with another field
        (is (= "Polska" (-> result 
                            (get "LALocation")
                            (first)
                            (get "city"))))
        ;; Replacement with a constant        
        (is (> Epsilon (abs (- 82.3 (-> result 
                                        (get "LALocation")
                                        (first)
                                        (get "latitude"))))))))
    
    (testing "Try editing succeeds but replaced fields are not actually replaced"
      (let [snapshot (speculate snapshot tx)
            {:keys [tx rejections]} 
            (push-transaction snapshot user-uuid {"LALocation" 
                                                  [(mp/clojure-to-json (assoc la-location 
                                                                              :LALocation/city "Bombaj"
                                                                              :LALocation/latitude 85.0) 
                                                                       (get ebn "LALocation"))]} 
                              true {:wow (delay true)
                                    :are-you-there? false
                                    :can-create? true
                                    :whatsupp? true
                                    :some-new-fields ["firstName"]})
            {:keys [rejected-objects rejected-fields]} rejections]

        (is (empty? tx))
        (is (empty? rejected-fields))
        (is (empty? rejected-objects))))))
