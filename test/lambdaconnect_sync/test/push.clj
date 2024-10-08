(ns lambdaconnect-sync.test.push
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.spec.alpha :as s]
            [lambdaconnect-sync.test.basic :refer [mobile-sync-config conn] :as b]
            [lambdaconnect-model.core :as mp]
            [datomic.api :as d]
            [lambdaconnect-model.tools :as mpt]            
            [lambdaconnect-sync.utils :as u]
            [clojure.data.json :refer [read-str]]
            [clojure.spec.gen.alpha :as gen]
            [lambdaconnect-sync.core :as sync]
            [lambdaconnect-sync.db :as db]))

(use-fixtures :once (partial b/setup-basic-test-environment "env/test/resources/model1.xml"
                             {:LAUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                              :LAUser/gender #(s/gen #{"U" "M" "F"})}))

(deftest rel-objs-to-fetch
  (testing "Generating data"
    (let [entities-by-name (mp/entities-by-name "env/test/resources/model1.xml")
          snapshot (d/db @b/conn)]
      (mp/specs entities-by-name {:FIUser/gender #(s/gen #{"M" "F" "U"})
                                  :FIUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                                  :FIGame/gender #(s/gen #{"M" "F" "U"})
                                  :FILocation/latitude #(gen/double* {:infinite? false :NaN? false :min -90 :max 90})
                                  :FILocation/longitude #(gen/double* {:infinite? false :NaN? false :min -180 :max 180})
                                  :FILocation/centerHash #(s/gen #{"aaa"})
                                  :FILocation/leftHash #(s/gen #{"aaa"})
                                  :FILocation/rightHash #(s/gen #{"aaa"})
                                  :FILocation/topHash #(s/gen #{"aaa"})
                                  :FILocation/bottomHash #(s/gen #{"aaa"})})
      (let [generated-objs (into {} (map (fn [ename]
                                           [ename  (try
                                                     (doall (gen/sample
                                                             (s/gen (mp/spec-for-name ename)) 100))
                                                     (catch Throwable e
                                                       (do (println "Exception for entity" ename ", " (u/exception-description e))
                                                           (throw e))))]) 
                                         (keys entities-by-name)))
            segregated (sync/compute-rel-objs-to-fetch b/mobile-sync-config generated-objs entities-by-name snapshot)]
        (is (>= (count (get segregated "FIUser")) 100))))))

(deftest parse-fixtures
  (testing "Reading fixtures"
    (-> "env/test/resources/fixtures.json"
        slurp
        read-str)))

(deftest test-optionals
  (testing "Import foodie model;"
    (let [empty-db (d/db @b/conn)
          entities-by-name (mp/entities-by-name "env/test/resources/model1.xml")
          test-json (read-str (slurp "env/test/resources/fixtures.json"))
          [transaction _] (sync/push-transaction b/mobile-sync-config test-json nil empty-db entities-by-name nil)
          after-import (db/speculate mobile-sync-config empty-db transaction)
          original-game
          {"active" 1
           "createdAt" "2018-06-28T04:40:22.111Z"
           "updatedAt" "2018-06-28T04:40:22.111Z"
           "date" "2018-06-28T04:40:22.111Z"
           "gameDescription"  "My cool game"
           "gender"  "U"
           "inThePast"  0
           "sharedOnFacebook"  0
           "shareOnFacebook"  0
           "skillLevelRequired" "beginner"
           "teamName" "Gypsies"
           "title"  "Awesome game"
           "uuid"  "20840e26-72e7-4172-8f46-d6a724e5476c"
           "availableUsers"  ["00001e26-72e7-4172-8f46-d6a724e5476c", "11111e26-72e7-4172-8f46-d6a724e5476c"]
           "location"  "33330e26-72e7-4172-8f46-d6a724e5476c"
           "organiser"  "44440e26-72e7-4172-8f46-d6a724e5476c"
           "sport" "55550e26-72e7-4172-8f46-d6a724e5476c"
           "participants"  ["66661e26-72e7-4172-8f46-d6a724e5476c"]}]
      (testing "Wrong spec;"
        ; this fails because non-optional title was replaced with nil
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Spec failed" (db/speculate mobile-sync-config after-import (first (sync/push-transaction mobile-sync-config {"FIGame" [(assoc original-game "title" nil)]} nil after-import entities-by-name nil)))))
        ; this fails because non-optional title was removed from input
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Spec failed" (db/speculate mobile-sync-config after-import (first (sync/push-transaction mobile-sync-config {"FIGame" [(dissoc original-game "title")]} nil after-import entities-by-name nil))))))
      (testing "Optional team name;"
        (let [t1 (first (sync/push-transaction mobile-sync-config {"FIGame" [(dissoc original-game "teamName")]} nil after-import entities-by-name nil))
              t2 (first (sync/push-transaction mobile-sync-config {"FIGame" [(assoc original-game "teamName" nil)]} nil after-import entities-by-name nil))
              _ (db/speculate mobile-sync-config after-import t1)
              after-t2 (db/speculate mobile-sync-config after-import t2)]
          (is (= 0 (count t1)))
          ; we see a retraction here together with bumping of updatedAt
          (is (= 2 (count t2)) t2)
          (is (= 1 (count (filter #(= (first %) :db/retract) t2))) t2)
          (let [t3 (first (sync/push-transaction mobile-sync-config {"FIGame" [(assoc original-game "teamName" nil)]} nil after-t2 entities-by-name nil))
                after-t3 (db/speculate mobile-sync-config after-t2 t3)
                t4 (first (sync/push-transaction mobile-sync-config {"FIGame" [(assoc original-game "teamName" "Drabs")]} nil after-t3 entities-by-name nil))
                _ (db/speculate mobile-sync-config after-t3 t4)]
            ; we see a cas with "Drabs" here
            (is (= 2 (count t4)) t4)
            (is (= 1 (count (filter #(= (last %) "Drabs") t4))) t4)
            (is (= :db/cas (ffirst (filter #(= (last %) "Drabs") t4))) t4)
            (let [t11 (first (sync/push-transaction mobile-sync-config {"FIGame" [(assoc original-game "active" 0)]} nil after-import entities-by-name nil))]
              (is (= 2 (count t11)) t11)
              (is (empty? (first (sync/push-transaction mobile-sync-config {"FIGame" [(assoc original-game "active" 1)]} nil (db/speculate mobile-sync-config after-import t11) entities-by-name nil)))))))))))



(deftest test-model-db
  (testing "get-related-objects"
    (let [empty-db (d/db @b/conn)
          entities-by-name (mp/entities-by-name "env/test/resources/model1.xml")
          test-entity (first (filter #(some :to-many (vals (:datomic-relationships %)))
                                     (vals entities-by-name))) ; entity that has a to-many rel
          test-relationship (first (filter :to-many (vals (:datomic-relationships test-entity))))
          uuid-one (db/uuid)
          uuid-two (db/uuid)
          test-json (read-str (slurp "env/test/resources/fixtures.json"))]
      (let [test-transaction [{:app/uuid uuid-one}
                              {:app/uuid uuid-two
                               (mp/datomic-name test-relationship) [{:app/uuid uuid-one}]}]
            snapshot (db/speculate mobile-sync-config empty-db test-transaction)
            result (sync/get-related-objects mobile-sync-config test-relationship [uuid-one] snapshot)]
        (is (= 1 (count result)))
        (is (first result) uuid-two))
      (testing "push;"
        (let [[transaction [c1 _]] (sync/push-transaction mobile-sync-config test-json nil empty-db entities-by-name nil)
              after-import (db/speculate mobile-sync-config empty-db transaction)
              [t2 [c2 _]] (sync/push-transaction mobile-sync-config test-json nil after-import entities-by-name nil)
              [t3 [c3 _]] (sync/push-transaction
                           mobile-sync-config
                           {"FIUser" [{"active"  1
                                       "createdAt" "2018-06-29T04:40:22.111Z"
                                       "updatedAt" "2018-06-29T04:40:22.111Z"
                                       "uuid" "11110e26-72e7-4172-8f46-d6a724e5476c"
                                       "birthDate" "1988-08-18T04:40:22.111Z"
                                       "email"  "djabel@gmail.com"
                                       "firstName" "djabel"
                                       "lastName"  "szatan"
                                       "gender"     "M"
                                       "internalUserId"  1
                                       "profileConfirmed"  1
                                       "pushNotificationsEnabled"  1
                                       "address" "33330e26-72e7-4172-8f46-d6a724e5476c"
                                       "availableForGames"  []
                                       "gamesConfirmed" []
                                       "messagesReceived"  []
                                       "messagesSent"  []
                                       "organisedGames"  []
                                       "sports"  []}]}
                           nil after-import entities-by-name nil)
              [t4 _] (sync/push-transaction
                      mobile-sync-config
                      {"FIUser" [{"active"  0
                                  "createdAt" "2018-06-29T04:40:22.111Z"
                                  "updatedAt" "2018-06-29T04:40:22.111Z"
                                  "uuid" "11110e26-72e7-4172-8f46-d6a724e5476c"
                                  "birthDate" "1988-08-18T04:40:22.111Z"
                                  "email"  "djabel@gmail.com"
                                  "firstName" "djabel"
                                  "lastName"  "szatan"
                                  "gender"     "M"
                                  "internalUserId"  1
                                  "profileConfirmed"  1
                                  "pushNotificationsEnabled"  1
                                  "address" "33330e26-72e7-4172-8f46-d6a724e5476c"
                                  "availableForGames"  []
                                  "gamesConfirmed" []
                                  "messagesReceived"  []
                                  "messagesSent"  []
                                  "organisedGames"  []
                                  "sports"  []}]}
                      nil (db/speculate mobile-sync-config after-import t3) entities-by-name nil)]
          (is (seq (reduce concat (map vals (vals c1)))))
          (is (empty? (reduce concat (map vals (vals c2)))))
          (is (empty? (reduce concat (map vals (vals c3)))))
          (is (empty? t2)) ; Double push generates an empty set
          (is (= 1 (count t3)))
          (is (= :db/retract  (ffirst t3)))
          (is (= :FIGameAvailabilityIndication/user (nth (first t3) 2)))
          (is (seq t4))))
      (testing "replacement;"
        (let [[transaction _] (sync/push-transaction mobile-sync-config test-json nil empty-db entities-by-name nil)
              after-import (db/speculate mobile-sync-config empty-db transaction)
              [t2 _] (sync/push-transaction
                      mobile-sync-config
                      {"FIUser" [{"active"  1
                                  "createdAt" "2018-06-29T04:40:22.111Z"
                                  "updatedAt" "2018-06-29T04:40:22.111Z"
                                  "uuid" "11110e26-72e7-4172-8f46-d6a724e5476c"
                                  "birthDate" "1988-08-18T04:40:22.111Z"
                                  "email"  "djabel@gmail.com"
                                  "firstName" "djabel"
                                  "lastName"  "szatan"
                                  "gender"     "M"
                                  "internalUserId"  1
                                  "profileConfirmed"  1
                                  "pushNotificationsEnabled"  1
                                  "address" "33330e26-72e7-4172-8f46-d6a724e5476c"
                                  "availableForGames"  []
                                  "gamesConfirmed" []
                                  "messagesReceived"  []
                                  "messagesSent"  []
                                  "organisedGames"  []
                                  "sports"  []}
                                 {"active"  1
                                  "createdAt" "2018-06-29T04:40:22.111Z"
                                  "updatedAt" "2018-06-29T04:40:22.111Z"
                                  "uuid" "00000e26-72e7-4172-8f46-d6a724e5476c"
                                  "birthDate" "1984-04-30T04:40:22.111Z"
                                  "email"  "marek.lipert@gmail.com"
                                  "firstName" "marek"
                                  "lastName"  "lipert"
                                  "gender"     "M"
                                  "internalUserId"  1
                                  "profileConfirmed"  1
                                  "pushNotificationsEnabled"  1
                                  "address" "33330e26-72e7-4172-8f46-d6a724e5476c"
                                  "availableForGames"  ["00001e26-72e7-4172-8f46-d6a724e5476c", "11111e26-72e7-4172-8f46-d6a724e5476c"]
                                  "gamesConfirmed" []
                                  "messagesReceived"  []
                                  "messagesSent"  []
                                  "organisedGames"  []
                                  "sports"  []}]}
                      nil after-import entities-by-name nil)]
          (is (= 2 (count t2)))
          (db/speculate mobile-sync-config after-import t2))))))

(deftest test-conflict-resolution
  (testing "Setup;"
    (let [empty-db (d/db @b/conn)
          entities-by-name (mp/entities-by-name  (b/load-model-fixture "model1.xml"))          
          test-json (b/slurp-fixture "fixtures.json")
          date-parser (mpt/parser-for-attribute (get-in entities-by-name ["FIGame" :attributes "updatedAt"]))]     
      (testing "push;"
        (let [[transaction [c1 _]] (sync/push-transaction b/mobile-sync-config test-json nil empty-db entities-by-name nil (date-parser "2060-01-01T01:01:00.000Z"))
              after-import (db/speculate b/mobile-sync-config empty-db transaction)
              revision (db/get-sync-revision b/mobile-sync-config after-import)

              ;; cut one user
              [t2 [c2 _]] (sync/push-transaction b/mobile-sync-config {"FIGame" [{
                                                                                  "active" 1,
                                                                                  "createdAt" "2060-01-01T01:01:00.000Z",
                                                                                  "updatedAt" "2060-01-01T01:01:01.000Z",
                                                                                  "date" "2018-06-28T04:40:22.111Z",
                                                                                  "gameDescription" "My cool games",
                                                                                  "gender"  "U",
                                                                                  "inThePast" 0,
                                                                                  "sharedOnFacebook" 0,
                                                                                  "shareOnFacebook" 0,
                                                                                  "skillLevelRequired" "beginner",
                                                                                  "teamName" "Gypsies",
                                                                                  "title"  "Awesome game",
                                                                                  "uuid"  "20840e26-72e7-4172-8f46-d6a724e5476c",
                                                                                  "availableUsers" ["11111e26-72e7-4172-8f46-d6a724e5476c"],
                                                                                  "location" "33330e26-72e7-4172-8f46-d6a724e5476c",
                                                                                  "organiser" "44440e26-72e7-4172-8f46-d6a724e5476c",
                                                                                  "sport" "55550e26-72e7-4172-8f46-d6a724e5476c",
                                                                                  "participants" ["66661e26-72e7-4172-8f46-d6a724e5476c"]
                                                                                  }]} nil after-import entities-by-name nil (date-parser "2060-01-01T01:01:01.000Z"))
              snapshot2 (db/speculate b/mobile-sync-config after-import t2)
              ;; Should only change gender, gameDescription and availibleUsers should remain unchanged
              [t3 [c2 _]] (sync/push-transaction b/mobile-sync-config
                                                 {"FIGame" [{
                                                             "active" 1,
                                                             "createdAt" "2060-01-01T01:01:00.000Z",
                                                             "updatedAt" "2060-01-01T01:01:02.000Z",
                                                             "date" "2018-06-28T04:40:22.111Z",
                                                             "gameDescription" "My cool game",
                                                             "gender"  "M",
                                                             "inThePast" 0,
                                                             "sharedOnFacebook" 0,
                                                             "shareOnFacebook" 0,
                                                             "skillLevelRequired" "beginner",
                                                             "teamName" "Gypsies",
                                                             "title"  "Awesome game",
                                                             "uuid"  "20840e26-72e7-4172-8f46-d6a724e5476c",
                                                             "availableUsers" ["00001e26-72e7-4172-8f46-d6a724e5476c", "11111e26-72e7-4172-8f46-d6a724e5476c"],
                                                             "location" "33330e26-72e7-4172-8f46-d6a724e5476c",
                                                             "organiser" "44440e26-72e7-4172-8f46-d6a724e5476c",
                                                             "sport" "55550e26-72e7-4172-8f46-d6a724e5476c",
                                                             "participants" ["66661e26-72e7-4172-8f46-d6a724e5476c"]
                                                             "syncRevision" revision
                                                             }]} nil snapshot2 entities-by-name nil (date-parser "2060-01-01T01:01:02.000Z"))]
          
          ;; First transaction removes one element from available users. It also changes description and updatedAt
          
          ;; 17592186045419 and 9 are abused here.

          (is (= #{[:db/cas 17592186045419 :app/updatedAt #inst "2060-01-01T01:01:00.000-00:00" #inst "2060-01-01T01:01:01.000-00:00"] 
                   [:db/retract 17592186045420 :FIGameAvailabilityIndication/game 17592186045419] 
                   [:db/cas 17592186045419 :FIGame/gameDescription "My cool game" "My cool games"]} (set t2)))

          ;; Second transaction detects conflict and resolves it by ignoring description and available users changes (since they are the same as they were). Only gender is altered.

          ;; 17592186045419 is abused here

          (is (= #{[:db/cas 17592186045419 :FIGame/gender "U" "M"] 
                   [:db/cas 17592186045419 :app/updatedAt #inst "2060-01-01T01:01:01.000-00:00" #inst "2060-01-01T01:01:02.000-00:00"]} (set t3))))))))

