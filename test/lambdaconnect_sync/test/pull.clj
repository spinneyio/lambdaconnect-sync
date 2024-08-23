(ns lambdaconnect-sync.test.pull
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [lambdaconnect-sync.test.basic :refer [setup-basic-test-environment mobile-sync-config conn]]
            [lambdaconnect-sync.db :as db]
            [clojure.spec.alpha :as s]
            [lambdaconnect-model.core :as mp]
            [datomic.api :as d]
            [clojure.spec.gen.alpha :as gen]
            [clojure.data.json :refer [read-str]]
            [lambdaconnect-sync.core :as sync]))

(use-fixtures :once (partial setup-basic-test-environment "env/test/resources/model1.xml"
                             {:LAUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                              :LAUser/gender #(s/gen #{"U" "M" "F"})}))

(deftest test-pull
  (testing "pull;"
    (let [empty-db (d/db @conn)
          entities-by-name (mp/entities-by-name "env/test/resources/model1.xml")
          input-json (read-str (slurp "env/test/resources/fixtures.json"))
          [transaction _ _] (sync/push-transaction mobile-sync-config input-json nil empty-db entities-by-name nil)
          after-import (db/speculate mobile-sync-config empty-db transaction)
          [t2 _ _] (sync/push-transaction mobile-sync-config input-json nil after-import entities-by-name nil)
          [t3 _ _] (sync/push-transaction mobile-sync-config {"FIUser" [{"active"  1
                                                                         "createdAt" "2018-06-29T04:40:22.111Z"
                                                                         "updatedAt" "2018-06-29T04:45:22.111Z"
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
                                                                         "sports"  []}]} nil after-import entities-by-name nil)
          after-delete (db/speculate mobile-sync-config  after-import t3)]
      (is (empty? t2))
      (is (= 0 (count (sync/get-changed-ids mobile-sync-config empty-db "FIUser" 0 entities-by-name)))) ; empty db
      (is (= 4 (count (sync/get-changed-ids mobile-sync-config after-import "FIUser" 0 entities-by-name)))) ; 4 new users
      (is (= 0 (count (sync/get-changed-ids mobile-sync-config after-import "FIUser" (+ 1 (d/basis-t after-import)) entities-by-name)))) ; 0 new users since 4 new users were created
      (is (= 4 (count (sync/get-changed-ids mobile-sync-config after-delete "FIUser" 0 entities-by-name)))) ; 4 users created and later one of them modified still yields 4
      (is (= 1 (count (sync/get-changed-ids mobile-sync-config after-delete "FIUser" (+ 1 (d/basis-t after-import)) entities-by-name)))) ; 1 user modified (his game availability has been removed
      (is (= 0 (count (sync/get-changed-ids mobile-sync-config after-delete "FIUser" (+ 1 (d/basis-t after-delete)) entities-by-name)))) ; no changes, 0 entities changed

      (testing "Inversion;"
        (let [pull-output  (sync/pull mobile-sync-config (into {} (map (fn [n] [n 0]) (keys entities-by-name))) nil after-import entities-by-name nil)
              [t4 _ _] (sync/push-transaction mobile-sync-config pull-output nil after-import entities-by-name nil)
              [t5 _ _] (sync/push-transaction mobile-sync-config pull-output nil empty-db entities-by-name nil)
              after-output (db/speculate mobile-sync-config  empty-db t5)
              [t6 _ _] (sync/push-transaction mobile-sync-config input-json nil after-output entities-by-name nil)]
          (is (empty? t4)) ; we have already imported all of them
          (is (empty? t6)) ; we have already imported all of them

          (testing "Removal;"
            (let [pull-output  (sync/pull mobile-sync-config (into {} (map (fn [n] [n 0]) (keys entities-by-name))) nil after-delete entities-by-name nil)
                  [t4 _ _] (sync/push-transaction mobile-sync-config pull-output nil after-import entities-by-name nil)
                  [t5 _ _] (sync/push-transaction mobile-sync-config pull-output nil empty-db entities-by-name nil)
                  after-output (db/speculate mobile-sync-config  empty-db t5)
                  [t6 _ _] (sync/push-transaction mobile-sync-config input-json nil after-output entities-by-name nil)]
              (is (= 1 (count t4)))
              (is (= :db/retract (ffirst t4)))
              (is (= 1 (count t6)))
              (is (= :db/add (ffirst t6)))
              (db/speculate mobile-sync-config  after-output t6))))))))
