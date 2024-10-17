(ns lambdaconnect-sync.test.push-foodie-memory
  (:require 
   [lambdaconnect-sync.test.basic-memory :as b]
   [lambdaconnect-model.core :as mp]            
   [lambdaconnect-model.tools :as mpt]            
   [lambdaconnect-sync.utils :as u]
   [lambdaconnect-sync.core :as sync]
   [lambdaconnect-sync.db :as db]
   [lambdaconnect-sync.db-drivers.memory :as mem]
   [clojure.string :refer [starts-with?]]           
   [clojure.pprint :refer [pprint]]
   
   #?@(:cljs [[clojure.test.check.generators]
              [cljs.spec.gen.alpha :as gen]
              [cljs.test :refer [deftest testing is use-fixtures] :as t]
              [cljs.spec.alpha :as s]]
       :clj [[clojure.spec.gen.alpha :as gen]
             [clojure.test :refer [deftest testing is use-fixtures] :as t]
             [clojure.spec.alpha :as s]])))

(use-fixtures :once (partial b/setup-basic-test-environment (b/load-model-fixture "model2.xml")
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
    (let [entities-by-name (mp/entities-by-name (b/load-model-fixture "model2.xml"))
          snapshot @b/conn

          generated-objs (into {} (map (fn [ename]
                                         [ename  (try
                                                   (doall (gen/sample
                                                           (s/gen (mp/spec-for-name ename)) 100))
                                                   (catch #?(:clj Throwable :cljs js/Error) e
                                                     (do (println "Exception for entity" ename ", " (u/exception-description e))
                                                         (throw e))))])
                                       (keys entities-by-name)))
          segregated (sync/compute-rel-objs-to-fetch b/mobile-sync-config generated-objs entities-by-name snapshot)]
      (is (>= (count (get segregated "FOUser")) 100)))))

(deftest parse-fixtures
  (testing "Reading fixtures"
    (b/slurp-fixture "fixtures2.json")))

(deftest test-model-db
  (testing "get-related-objects"
    (let [empty-db @b/conn
          entities-by-name (mp/entities-by-name (b/load-model-fixture "model2.xml"))
          test-entity (first (filter #(some :to-many (vals (:datomic-relationships %)))
                                     (vals entities-by-name))) ; entity that has a to-many rel
          test-relationship (first (filter :to-many (vals (:datomic-relationships test-entity))))
          uuid-one (db/uuid)
          uuid-two (db/uuid)
          test-json (b/slurp-fixture "fixtures2.json")]
      (let [test-transaction [{:app/uuid uuid-one
                               (->> test-relationship
                                    :destination-entity
                                    (get entities-by-name)
                                    mpt/unique-datomic-identifier) true}
                              {:app/uuid uuid-two
                               (->> test-relationship
                                    :entity-name
                                    (get entities-by-name)
                                    mpt/unique-datomic-identifier) true
                               (mp/datomic-name test-relationship) [{:app/uuid uuid-one}]}]
            snapshot (db/speculate b/mobile-sync-config empty-db test-transaction)
            result (sync/get-related-objects b/mobile-sync-config test-relationship [uuid-one] snapshot)]
        (is (= 1 (count result)))
        (is (first result) uuid-two))
      (testing "push;"
        (let [[transaction [c1 _]] (sync/push-transaction b/mobile-sync-config test-json nil empty-db entities-by-name nil)
              after-import (db/speculate b/mobile-sync-config empty-db transaction)
              [t2 [c2 _]] (sync/push-transaction b/mobile-sync-config test-json nil after-import entities-by-name nil)]
          (is (seq (reduce concat (map vals (vals c1)))))
          (is (empty? (reduce concat (map vals (vals c2)))))
          (is (empty? t2)) ; Double push generates an empty set
          (testing "duplicates;"
            (testing "single-entity-json;"
              (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error) #".*Duplicates.*7228d756-1af5-4d3c-9b10-471461fde996.*" (sync/push-transaction
                                                                                                                      b/mobile-sync-config
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
              (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error) #".*Duplicates.*7228d756-1af5-4d3c-9b10-471461fde996.*" (sync/push-transaction
                                                                                                                      b/mobile-sync-config
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
              (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error) #".*Wrong.*5cea60b5-7fb0-446d-a58f-fb49a3b0b035.*" (sync/push-transaction
                                                                                                                 b/mobile-sync-config
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
                               b/mobile-sync-config
                               {"FODiscountTime" [{"discount" 25
                                                   "active" 1
                                                   "size" 3
                                                   "date" "2019-12-14T01:36:23.000Z"
                                                   "restaurant" nil
                                                   "createdAt" "2019-12-14T01:36:23.000Z"
                                                   "updatedAt" "2019-12-14T01:36:23.000Z"
                                                   "startHour" "15:15"
                                                   "uuid" "7228d756-1af5-4d3c-9b10-471461fde996"}]} nil empty-db entities-by-name nil)
              after-import (db/speculate b/mobile-sync-config empty-db transaction)
              [transaction2 _] (sync/push-transaction
                                b/mobile-sync-config
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



(deftest sorting-filtering 
  (testing "filling the database"
    (let [empty-db @b/conn
          entities-by-name (mp/entities-by-name (b/load-model-fixture "model2.xml"))
          test-json (b/slurp-fixture "fixtures2.json")]
      (testing "push;"
        (let [[transaction] (sync/push-transaction b/mobile-sync-config test-json nil empty-db entities-by-name nil)
              after-import (db/speculate b/mobile-sync-config empty-db transaction)
              resolve-ids (fn [ids] (db/get-objects-by-ids b/mobile-sync-config (entities-by-name "FOLocalization") ids after-import))]

          (testing "Sort by name" 
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 [{:key :FOLocalization/city :direction 1}] nil)
                  results (map :FOLocalization/city (resolve-ids ids))]
              (is (= 18 (count ids)))
              (is (= "kolibrowa" (second results)))))

          (testing "No sorts at all" 
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 nil nil)
                  results (map #(select-keys % [:FOLocalization/city :app/uuid :app/createdAt]) (resolve-ids ids))]
              (is (= 18 (count ids)))
              (is (= "ul. Ulicowa 12") (:FOLocalization/city (second results)))))

          (testing "Sort by name descending" 
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 [{:key :FOLocalization/city :direction -1}] nil)
                  results (map :FOLocalization/city (resolve-ids ids))]              
              (is (= 18 (count ids)))
              (is (= "ul. Żurawia 6/12" (second results)))
              (is (= '("ul. Żurawia 6/12"
                       "ul. Żurawia 6/12"
                       "ul. Żurawia 6/12"
                       "ul. Żurawia 6/12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "ul. Ulicowa 12"
                       "kolibrowa"
                       "Kolibrowa") results))))

          (testing "Sort by logitude descending + filter by name"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                  [{:key :FOLocalization/logitude :direction -1}] 
                                                  {:key :FOLocalization/city :where-fn #(= %  "ul. Żurawia 6/12")})
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/logitude]) (resolve-ids ids))]
              (is (= #:FOLocalization{:city "ul. Żurawia 6/12",
                                      :logitude 20.109153254229014} (first results)))))
          
          (testing "Sort by logitude descending + filter by name and logitude"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                    [{:key :FOLocalization/logitude :direction -1}] 
                                                    {:condition-type :and :conditions [{:key :FOLocalization/city :where-fn #(= %  "ul. Żurawia 6/12")}
                                                                                       {:key :FOLocalization/logitude :where-fn #(> %  20.0)}]})                                    
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/logitude]) (resolve-ids ids))]
                        (is (= 2 (count results)))
                        (is (empty? (filter #(< % 20.0) (map :FOLocalization/logitude results))))))

          (testing "Sort by city descending and logitude ascending + filter by logitude from a single and"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                    [{:key :FOLocalization/city :direction -1}
                                                     {:key :FOLocalization/logitude :direction 1}] 
                                                    {:condition-type :and :conditions [{:key :FOLocalization/logitude :where-fn #(> %  19.7)}]})   
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/logitude]) (resolve-ids ids))]
              (is (= results '(#:FOLocalization{:city "ul. Żurawia 6/12", :logitude 19.7916142668045}
                                #:FOLocalization{:city "ul. Żurawia 6/12",
                                                 :logitude 20.033605325340403}
                                #:FOLocalization{:city "ul. Żurawia 6/12",
                                                 :logitude 20.109153254229014}
                                #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.919015835763744}
                                #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.996433962795617}
                                #:FOLocalization{:city "ul. Ulicowa 12", :logitude 20.00282566170049}
                                #:FOLocalization{:city "ul. Ulicowa 12", :logitude 20.11523327635091}
                                #:FOLocalization{:city "ul. Ulicowa 12", :logitude 20.13399817567732})))))

          (testing "Sort by city descending and logitude ascending + filter down by logitude from a single and"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                    [{:key :FOLocalization/city :direction -1}
                                                     {:key :FOLocalization/logitude :direction 1}] 
                                                    {:condition-type :and :conditions [{:key :FOLocalization/logitude :where-fn #(< %  19.7)}]})   
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/logitude]) (resolve-ids ids))]
              (is (= results '(#:FOLocalization{:city "ul. Żurawia 6/12",
                                                :logitude 19.55365864069841}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.03030310673314}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.07798234210828}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.17957466636357}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.332211814117727}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.361832275276036}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.463674071081314}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.60787488523789}
                               #:FOLocalization{:city "kolibrowa", :logitude 19.003658640698408}
                               #:FOLocalization{:city "Kolibrowa", :logitude 19.00265864069841})))))
          (testing "Sort by city descending CASE INSENSITIVE and logitude ascending + filter down by logitude from a single and"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                    [{:key :FOLocalization/city :direction -1 :options [:case-insensitive]}
                                                     {:key :FOLocalization/logitude :direction 1}] 
                                                    {:condition-type :and :conditions [{:key :FOLocalization/logitude :where-fn #(< %  19.7)}]})   
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/logitude]) (resolve-ids ids))]
              (is (= results '(#:FOLocalization{:city "ul. Żurawia 6/12",
                                                :logitude 19.55365864069841}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.03030310673314}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.07798234210828}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.17957466636357}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.332211814117727}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.361832275276036}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.463674071081314}
                               #:FOLocalization{:city "ul. Ulicowa 12", :logitude 19.60787488523789}
                               #:FOLocalization{:city "Kolibrowa", :logitude 19.00265864069841}
                               #:FOLocalization{:city "kolibrowa", :logitude 19.003658640698408})))))

          (testing "Sort by left hash"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                    [{:key :FOLocalization/leftHash :direction 1 :options [:case-insensitive]}
                                                     {:key :FOLocalization/city :direction 1 :options [:case-insensitive]}] nil)   
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/leftHash]) (resolve-ids ids))]
              (is (not (:FOLocalization/leftHash (first results))))
              (is (:FOLocalization/leftHash (last results)))))

          (testing "Sort by left hash, nulls to the top"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                    [{:key :FOLocalization/leftHash :direction 1 :options [:nulls-first]}
                                                     {:key :FOLocalization/city :direction 1 :options [:case-insensitive]}] nil)   
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/leftHash]) (resolve-ids ids))]
              (is (not (:FOLocalization/leftHash (first results))))
              (is (:FOLocalization/leftHash (last results)))))

          (testing "Sort by left hash, nulls to the bottom"
            (let [ids (mem/get-paginated-collection after-import "FOLocalization" 0 100 
                                                    [{:key :FOLocalization/leftHash :direction 1 :options [:nulls-last]}
                                                     {:key :FOLocalization/city :direction 1 :options [:case-insensitive]}] nil)   
                  results (map #(select-keys % [:FOLocalization/city :FOLocalization/leftHash]) (resolve-ids ids))]
              (is (:FOLocalization/leftHash (first results)))
              (is (not (:FOLocalization/leftHash (last results))))))
)))))
