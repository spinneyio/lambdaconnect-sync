(ns lambdaconnect-sync.test.push-foodie
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :refer [starts-with?]]
            [lambdaconnect-sync.test.basic :refer [mobile-sync-config conn]  :as b]
            [clojure.spec.alpha :as s]
            [lambdaconnect-model.core :as mp]
            [datomic.api :as d]
            [clojure.data.json :refer [read-str]]
            [clojure.spec.gen.alpha :as gen]
            [lambdaconnect-sync.core :as sync]
            [lambdaconnect-sync.utils :as u]
            [lambdaconnect-sync.db :as db]))

(use-fixtures :once (partial b/setup-basic-test-environment "env/test/resources/model2.xml"
                             {:FOUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                              :FOFeedback/rating #(s/gen #{1 2 3 4 5})
                              :FORestaurant/rating #(s/gen #{1 2 3 4 5})
                              :FORestaurant/costRating #(s/gen #{1 2 3})
                              :FOLocalization/latitude #(gen/double* {:infinite? false :NaN? false :min -90 :max 90})
                              :FOLocalization/longitude #(gen/double* {:infinite? false :NaN? false :min -180 :max 180})
                              :FOLocalization/centerHash #(s/gen #{"aaa"})
                              :FOLocalization/leftHash #(s/gen #{"aaa"})
                              :FOLocalization/rightHash #(s/gen #{"aaa"})
                              :FOLocalization/topHash #(s/gen #{"aaa"})
                              :FOLocalization/bottomHash #(s/gen #{"aaa"})}))


(deftest rel-objs-to-fetch
  (testing "Generating data"
    (let [entities-by-name (mp/entities-by-name "env/test/resources/model2.xml")
          snapshot (d/db @conn)

          generated-objs (into {} (map (fn [ename]
                                         [ename  (try
                                                   (doall (gen/sample
                                                           (s/gen (mp/spec-for-name ename)) 100))
                                                   (catch Throwable e
                                                     (do (println "Exception for entity" ename ", " (u/exception-description e))
                                                         (throw e))))])
                                       (keys entities-by-name)))
          segregated (sync/compute-rel-objs-to-fetch mobile-sync-config generated-objs entities-by-name snapshot)]
      (is (>= (count (get segregated "FOUser")) 100)))))

(deftest parse-fixtures
  (testing "Reading fixtures"
    (-> "env/test/resources/fixtures2.json"
        slurp
        read-str)))

(deftest test-model-db
  (testing "get-related-objects"
    (let [empty-db (d/db @conn)
          entities-by-name (mp/entities-by-name "env/test/resources/model2.xml")
          test-entity (first (filter #(some :to-many (vals (:datomic-relationships %)))
                                     (vals entities-by-name))) ; entity that has a to-many rel
          test-relationship (first (filter :to-many (vals (:datomic-relationships test-entity))))
          uuid-one (db/uuid)
          uuid-two (db/uuid)
          test-json (read-str (slurp "env/test/resources/fixtures2.json"))]
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
              [t2 [c2 _]] (sync/push-transaction mobile-sync-config test-json nil after-import entities-by-name nil)]
          (is (seq (reduce concat (map vals (vals c1)))))
          (is (empty? (reduce concat (map vals (vals c2)))))
          (is (empty? t2)) ; Double push generates an empty set
          (testing "duplicates;"
            (testing "single-entity-json;"
              (is (thrown-with-msg? java.lang.AssertionError #".*Duplicates.*7228d756-1af5-4d3c-9b10-471461fde996.*" (sync/push-transaction
                                                                                                                      mobile-sync-config
                                                                                                                      {"FODiscountTime" [{"discount" 25
                                                                                                                                          "active" 1
                                                                                                                                          "size" 3
                                                                                                                                          "date" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "restaurant" nil
                                                                                                                                          "createdAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "updatedAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "startHour" "15:15"
                                                                                                                                          "uuid" "7228d756-1af5-4d3c-9b10-471461fde996"}
                                                                                                                                         {"discount" 5
                                                                                                                                          "active" 1
                                                                                                                                          "size" 3
                                                                                                                                          "date" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "restaurant" nil
                                                                                                                                          "createdAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "updatedAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "startHour" "15:15"
                                                                                                                                          "uuid" "7228d756-1af5-4d3c-9b10-471461fde996"}]} nil after-import entities-by-name nil))))
            (testing "multiple-entity-json;"
              (is (thrown-with-msg? java.lang.AssertionError #".*Duplicates.*7228d756-1af5-4d3c-9b10-471461fde996.*" (sync/push-transaction
                                                                                                                      mobile-sync-config
                                                                                                                      {"FODiscountTime" [{"discount" 25
                                                                                                                                          "active" 1
                                                                                                                                          "size" 3
                                                                                                                                          "date" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "restaurant" nil
                                                                                                                                          "createdAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "updatedAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                          "startHour" "15:15"
                                                                                                                                          "uuid" "7228d756-1af5-4d3c-9b10-471461fde996"}]
                                                                                                                       "FOUser" [{"googleID" nil
                                                                                                                                  "uuid" "7228d756-1af5-4d3c-9b10-471461fde996"
                                                                                                                                  "updatedAt" "2019-06-07T11:33:54.423Z"
                                                                                                                                  "active" 1
                                                                                                                                  "surname" "anananan"
                                                                                                                                  "localization" nil
                                                                                                                                  "facebookID" nil
                                                                                                                                  "firstname" "anna"
                                                                                                                                  "username" "marek@spinney.io"
                                                                                                                                  "reservations" []
                                                                                                                                  "webUsers" nil
                                                                                                                                  "email"  "marek@spinney.io"
                                                                                                                                  "createdAt"  "2019-06-07T11:33:54.381Z"
                                                                                                                                  "phoneNumber"  "2342222222"}]} nil after-import entities-by-name nil))))

            (testing "multiple-entity-db-json;"
              (is (thrown-with-msg? java.lang.AssertionError #".*Wrong.*5cea60b5-7fb0-446d-a58f-fb49a3b0b035.*" (sync/push-transaction
                                                                                                                 mobile-sync-config
                                                                                                                 {"FODiscountTime" [{"discount" 25
                                                                                                                                     "active" 1
                                                                                                                                     "size" 3
                                                                                                                                     "date" "2019-12-14T01:36:23.000Z"
                                                                                                                                     "restaurant" nil
                                                                                                                                     "createdAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                     "updatedAt" "2019-12-14T01:36:23.000Z"
                                                                                                                                     "startHour" "15:15"
                                                                                                                                     "uuid" "5CEA60B5-7FB0-446D-A58F-FB49A3B0B035"}]} nil after-import entities-by-name nil)))))))
      
      (testing "push-active;"
        (let [[transaction _] (sync/push-transaction
                               mobile-sync-config
                               {"FODiscountTime" [{"discount" 25
                                                   "active" 1
                                                   "size" 3
                                                   "date" "2019-12-14T01:36:23.000Z"
                                                   "restaurant" nil
                                                   "createdAt" "2019-12-14T01:36:23.000Z"
                                                   "updatedAt" "2019-12-14T01:36:23.000Z"
                                                   "startHour" "15:15"
                                                   "uuid" "7228d756-1af5-4d3c-9b10-471461fde996"}]} nil empty-db entities-by-name nil)
              after-import (db/speculate mobile-sync-config empty-db transaction)
              [transaction2 _] (sync/push-transaction
                                mobile-sync-config
                                {"FODiscountTime" [{"discount" 25
                                                    "active" 0
                                                    "restaurant" nil
                                                    "date" "2019-12-14T01:36:23.000Z"
                                                    "createdAt" "2019-12-14T01:37:23.000Z"
                                                    "updatedAt" "2019-12-14T01:37:23.000Z"

                                                    "size" 3
                                                    "startHour" "15:15"
                                                    "uuid" "7228d756-1af5-4d3c-9b10-471461fde996"}]} nil after-import entities-by-name nil)]
          (is (empty? (filter #(= :db/retract (first %)) transaction2)) (str "There should be no retraction in transaction " (into [] transaction2))))))))


