(ns lambdaconnect-sync.test.core
  (:require [lambdaconnect-sync.core :as sync]
            [clojure.test :refer [deftest testing is use-fixtures] :as t]
            [clojure.spec.alpha :as s]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-sync.test.basic :as b]
            [lambdaconnect-model.tools :as mpt]
            [lambdaconnect-sync.db :as db]
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

(use-fixtures :once (partial b/setup-basic-test-environment "env/test/resources/test-model-0.xml"
                             {:LAUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                              :LAUser/gender #(s/gen #{"U" "M" "F"})}))

(defn push-transaction [snapshot user-uuid params suppress-log? constants]
  (let [read-edn (fn [path] (read-string (slurp path)))
        entities-by-name (mp/entities-by-name "env/test/resources/test-model-0.xml")
        scoping-edn (read-edn "env/test/resources/test-scope-0.edn")
        _ (scoping/validate-pull-scope entities-by-name scoping-edn)                
        push-scoping-edn (assoc (scoping/add-include-in-push-permission scoping-edn)
                                :constants constants)
        time (java.util.Date.)       
        user (d/entity snapshot (d/q '[:find ?e . :in $ ?uuid :where [?e :app/uuid ?uuid]] snapshot user-uuid))
        config (if-not suppress-log? 
                 (assoc b/mobile-sync-config :log println)
                 b/mobile-sync-config)
                                        ;to generate data import transactions REMOVE :log

        [transaction [created-objects updated-objects] rejections]
        (sync/push-transaction config params user snapshot entities-by-name push-scoping-edn time)]
    {:tx transaction
     :rejections rejections}))

(defn pull [snapshot user-uuid params suppress-log? constants]
  (let [read-edn (fn [path] (read-string (slurp path)))
        entities-by-name (mp/entities-by-name "env/test/resources/test-model-0.xml")
        scoping-edn (read-edn "env/test/resources/test-scope-0.edn")
        _ (scoping/validate-pull-scope entities-by-name scoping-edn)                
        pull-scoping-edn (assoc scoping-edn
                                :constants constants)
        time (java.util.Date.)       
        user (d/entity snapshot (d/q '[:find ?e . :in $ ?uuid :where [?e :app/uuid ?uuid]] snapshot user-uuid))
        config (if-not suppress-log? 
                 (assoc b/mobile-sync-config :log println)
                 b/mobile-sync-config)]
    (sync/pull config params user snapshot entities-by-name pull-scoping-edn)))

(deftest test-core-data-xml-conversion
  (testing "Reading model file one"
    (let [model (mp/entities-by-name "env/test/resources/test-model-0.xml")]
      (is (= (count model) 7))
      (testing ";Json to model converter"
        (try
          (let [game-model (get model "LAGame")
                json (-> "env/test/resources/fixtures-0.json"
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
    (let [model (mp/entities-by-name "env/test/resources/test-model-0.xml")
          schema (mp/datomic-schema model)]
      (is (= (+ 43 11 (count model)) (count schema)))))
  
  (testing "User info"
    (let [model (mp/entities-by-name "env/test/resources/test-model-0.xml")]
      (is (seq (get-in model ["LAGame" :user-info])))
      (is (seq (get-in model ["LAGame" :attributes "gameDescription"]))))))

(deftest
  push-rejected 
  (let [user-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/resources/test-model-0.xml")
        _ @(d/transact @b/conn [{:app/uuid user-uuid}])
        snapshot (d/db @b/conn)
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address nil)
                    (assoc :LAUser/internalNotes [])
                    (assoc :LAUser/organisedGames [])
                    (assoc :LAUser/playsFor []))
        {:keys [tx rejections]} 
        (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
                          true (fn [_snapshot _user] 
                                 {:wow (delay true)
                                  :are-you-there? false
                                  :can-create? false
                                  :whatsupp? true
                                  :some-new-fields []}))
        {:keys [rejected-objects rejected-fields]} rejections]
    (is (empty? tx))
    (is (not (empty? rejected-objects)))
    (is (empty? rejected-fields))))

(deftest
  push-accepted 
  (let [user-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/resources/test-model-0.xml")
        _ @(d/transact @b/conn [{:app/uuid user-uuid}])
        snapshot (d/db @b/conn)
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address nil)
                    (assoc :LAUser/internalNotes [])
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
  push-accepted-edit-rejected
  (let [user-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/resources/test-model-0.xml")
        _ @(d/transact @b/conn [{:app/uuid user-uuid}])
        snapshot (d/db @b/conn)
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address nil)
                    (assoc :LAUser/internalNotes [])
                    (assoc :LAUser/organisedGames [])
                    (assoc :LAUser/playsFor []))
        {:keys [tx rejections]} 
        (testing "Creating the object"
          (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
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
    
    (testing "Try editing fails - wrong protected fields definition"
      (let [snapshot (db/speculate b/mobile-sync-config snapshot tx)]
        (is (thrown? AssertionError
                     (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json la-user (get ebn "LAUser"))]} 
                                       true (fn [_snapshot _user] 
                                              {:wow (delay true)
                                               :are-you-there? false
                                               :can-create? true
                                               :whatsupp? true
                                               :some-new-fields ["lala"]}))))))

    (testing "Try editing succeeds but with rejections and empty transaction"
      (let [snapshot (db/speculate b/mobile-sync-config snapshot tx)
            {:keys [tx rejections]} 
            (push-transaction snapshot user-uuid {"LAUser" [(mp/clojure-to-json (assoc la-user :LAUser/firstName "Zorro") (get ebn "LAUser"))]} 
                                       true (fn [_snapshot _user] 
                                              {:wow (delay true)
                                               :are-you-there? false
                                               :can-create? true
                                               :whatsupp? true
                                               :some-new-fields ["firstName"]}))
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

(deftest 
  replace-fields
  (let [user-uuid (random-uuid)
        location-uuid (random-uuid)
        ebn (mp/entities-by-name "env/test/resources/test-model-0.xml")
        _ @(d/transact @b/conn [{:app/uuid user-uuid}])
        snapshot (d/db @b/conn)
        la-user (-> (gen/sample (s/gen (mp/spec-for-name :LAUser)) 1)
                    (first)
                    (assoc :LAUser/internalUserId user-uuid)
                    (assoc :LAUser/address {:app/uuid location-uuid})
                    (assoc :LAUser/internalNotes [])
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

    (testing "Do a pull (fails because constant 'lat' is not defined)"
      (let [snapshot (db/speculate b/mobile-sync-config snapshot tx)]
        ;; Lazy nightmare
        (is (thrown? java.lang.AssertionError
                     (doseq [x (vals (pull snapshot user-uuid {"LAUser" 0 "LALocation" 0}
                                           true (fn [_snapshot _user]
                                                  {:wow (delay true)
                                                   :are-you-there? false
                                                   :can-create? true
                                                   :whatsupp? true
                                                   :some-new-fields ["firstName"]})))]
                       (doall x))))))

    (testing "Do a pull and see what came in - replacements should have arrived"
      (let [snapshot (db/speculate b/mobile-sync-config snapshot tx)
            result (pull snapshot user-uuid {"LAUser" 0 "LALocation" 0}
                         true (fn [_snapshot _user]
                                {:wow (delay true)
                                 :are-you-there? false
                                 :can-create? true
                                 :whatsupp? true
                                 :lat 82.3
                                 :some-new-fields ["firstName"]}))]
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

        (is (empty? tx))
        (is (empty? rejected-fields))
        (is (empty? rejected-objects))))

    (testing "Do not erase a relation if the object on the other side is out of scope"
      (let [internal-note-uuid (random-uuid)
            la-internal-note (-> (gen/sample (s/gen (mp/spec-for-name :LAInternalNote)) 1)
                                 (first)
                                 (assoc :app/uuid internal-note-uuid)
                                 (assoc :LAInternalNote/user {:app/uuid (:app/uuid la-user)}))
            snapshot (db/speculate b/mobile-sync-config snapshot (conj tx la-internal-note))
            pull-result (pull snapshot user-uuid {"LAUser" 0 "LAInternalNote" 0}
                              true (fn [_snapshot _user]
                                     {:wow (delay true)
                                      :are-you-there? false
                                      :can-create? true
                                      :whatsupp? true
                                      :lat 82.3
                                      :some-new-fields ["firstName"]}))
            {:keys [tx rejections]}
            (push-transaction snapshot user-uuid {"LAUser"
                                                  [(assoc
                                                    (mp/clojure-to-json la-user (get ebn "LAUser"))
                                                    "syncRevision" (-> pull-result (get "LAUser") first (get "syncRevision")))]}
                              true (fn [_snapshot _user]
                                     {:wow (delay true)
                                      :are-you-there? false
                                      :can-create? true
                                      :lat 82.3
                                      :whatsupp? true
                                      :some-new-fields ["firstName"]}))
            {:keys [rejected-objects rejected-fields]} rejections]
      
        (is (empty? (get pull-result "LAInternalNote")))
        (is (empty? (get-in pull-result ["LAUser" 0 "internalNotes"])))
        
        (is (empty? tx))
        (is (empty? rejected-fields))
        (is (empty? rejected-objects))))))
