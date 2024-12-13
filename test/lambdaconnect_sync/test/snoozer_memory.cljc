(ns lambdaconnect-sync.test.snoozer-memory
  (:require 
   [lambdaconnect-sync.test.basic-memory :as b]
   [lambdaconnect-model.core :as mp]            
   [lambdaconnect-model.tools :as mpt]            
   [lambdaconnect-sync.utils :as u]
   [lambdaconnect-sync.core :as sync]
   [lambdaconnect-sync.db :as db]
   [clojure.pprint :refer [pprint]]
   
   #?@(:cljs [[clojure.test.check.generators]
              [cljs.spec.gen.alpha :as gen]
              [cljs.test :refer [deftest testing is use-fixtures] :as t]
              [cljs.spec.alpha :as s]]
       :clj [[clojure.spec.gen.alpha :as gen]
             [clojure.test :refer [deftest testing is use-fixtures] :as t]
             [clojure.spec.alpha :as s]])))

(use-fixtures :once (partial b/setup-basic-test-environment (b/load-model-fixture "model5.xml") 
                             nil))

(deftest test-to-one-relationship
  (testing "Setup;"
    (let [empty-db @b/conn
          entities-by-name (mp/entities-by-name  (b/load-model-fixture "model5.xml"))
          ;test-json (b/slurp-fixture "fixtures.json")
          date-parser (mpt/parser-for-attribute (get-in entities-by-name ["SNBaby" :attributes "updatedAt"]))]     
      (testing "push;"
        (let [input-1 {"SNBaby" [{"name" "Asia",
                                  "dateOfBirth" "2024-11-13T11:00:00.000-00:00",
                                  "updatedAt" "2024-11-13T11:03:32.486-00:00",
                                  "syncRevision" 3861,
                                  "gender" "Unknown",
                                  "createdAt" "2024-11-13T11:02:48.958-00:00",
                                  "activeSleep" "5c197ca6-fe43-4168-b803-754d400b8828",
                                  "active" true,
                                  "uuid" "f845c7d2-391e-49af-af2e-8e19e28a4751"}]
                       "SNActiveSleep" [{"startDate" "2024-11-13T11:23:25.258-00:00",
                                         "updatedAt" "2024-11-13T11:23:35.507-00:00",
                                         "syncRevision" 3861,
                                         "createdAt" "2024-11-13T11:23:26.310-00:00",
                                         "baby" "f845c7d2-391e-49af-af2e-8e19e28a4751"
                                         "active" true,
                                         "uuid" "5c197ca6-fe43-4168-b803-754d400b8828"}]}


              [transaction [c1 _]] (sync/push-transaction b/mobile-sync-config input-1 nil empty-db entities-by-name nil (date-parser "2060-01-01T01:01:00.000Z"))
              after-import (db/speculate b/mobile-sync-config empty-db transaction)
              revision (db/get-sync-revision b/mobile-sync-config after-import)]          

          (let [updated-at (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content 2 :app/updatedAt])]
            (is (inst? updated-at) (class updated-at)))


          (let [sleep-rel (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content 2 :SNBaby/activeSleep])]
            (is (map? sleep-rel))
            (is (uuid? (:app/uuid sleep-rel)))
            (is (int? (:db/id sleep-rel))))
          
          (let [sleep-rels (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNActiveSleep" :collection-content 1 :SNBaby/_activeSleep])
                sleep-rel (first sleep-rels)]
            (is (set? sleep-rels) sleep-rel)
            (is (uuid? (:app/uuid sleep-rel)))
            (is (int? (:db/id sleep-rel))))
          (testing "Replacing active sleep"
            (let [input-2 {"SNBaby" [{"name" "Asia",
                                      "dateOfBirth" "2024-11-13T11:00:00.000-00:00",
                                      "updatedAt" "2024-11-13T11:03:32.486-00:00",
                                      "syncRevision" 3861,
                                      "gender" "Unknown",
                                      "createdAt" "2024-11-13T11:02:48.958-00:00",
                                      "activeSleep" "5c197ca6-fe43-4168-b803-754d400b8829",
                                      "active" true,
                                      "uuid" "f845c7d2-391e-49af-af2e-8e19e28a4751"}]
                           "SNActiveSleep" [{"startDate" "2024-11-13T11:23:25.258-00:00",
                                             "updatedAt" "2060-01-01T01:02:00.000Z",
                                             "syncRevision" 1,
                                             "createdAt" "2024-11-13T11:23:26.310-00:00",
                                             "baby" nil
                                             "active" false,
                                             "uuid" "5c197ca6-fe43-4168-b803-754d400b8828"}
                                            {"startDate" "2024-11-13T11:23:25.258-00:00",
                                             "updatedAt" "2024-11-13T11:23:35.507-00:00",
                                             "syncRevision" 3861,
                                             "createdAt" "2024-11-13T11:23:26.310-00:00",
                                             "baby" "f845c7d2-391e-49af-af2e-8e19e28a4751"
                                             "active" true,
                                             "uuid" "5c197ca6-fe43-4168-b803-754d400b8829"}]}

                  [transaction [c1 _]] (sync/push-transaction b/mobile-sync-config input-2 nil after-import entities-by-name nil (date-parser "2060-01-01T01:02:00.000Z"))
              
              after-import2 (db/speculate b/mobile-sync-config after-import transaction)
              revision (db/get-sync-revision b/mobile-sync-config after-import2)]

              (let [old-sleep (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNActiveSleep" :collection-content 1])]
                (is (= false (:app/active old-sleep)) old-sleep)
                (is (empty? (:SNBaby/_activeSleep old-sleep))))

              (let [new-sleep (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNActiveSleep" :collection-content 3])]
                (is (= #{{:db/id 2, :app/uuid #uuid "f845c7d2-391e-49af-af2e-8e19e28a4751"}} (:SNBaby/_activeSleep new-sleep)))
                (is (= true (:app/active new-sleep)) new-sleep))

              (let [baby (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNBaby" :collection-content 2])]
                (is (= {:db/id 3, :app/uuid #uuid "5c197ca6-fe43-4168-b803-754d400b8829"} (:SNBaby/activeSleep baby)))
                (is (= true (:app/active baby)) baby)))))))))


(deftest test-to-many-relationship
  (testing "Setup;"
    (let [empty-db @b/conn
          entities-by-name (mp/entities-by-name  (b/load-model-fixture "model5.xml"))
          ;test-json (b/slurp-fixture "fixtures.json")
          date-parser (mpt/parser-for-attribute (get-in entities-by-name ["SNBaby" :attributes "updatedAt"]))]     
      (testing "push;"
        (let [input-1 {"SNBaby" [{"name" "Asia",
                                  "dateOfBirth" "2024-11-13T11:00:00.000-00:00",
                                  "updatedAt" "2024-11-13T11:03:32.486-00:00",
                                  "syncRevision" 3861,
                                  "gender" "Unknown",
                                  "createdAt" "2024-11-13T11:02:48.958-00:00",
                                  "sleepPredictions" ["5c197ca6-fe43-4168-b803-754d400b8828"],
                                  "active" true,
                                  "uuid" "f845c7d2-391e-49af-af2e-8e19e28a4751"}
                                 {"name" "Jola",
                                  "dateOfBirth" "2024-11-13T11:00:00.000-00:00",
                                  "updatedAt" "2024-11-13T11:03:32.486-00:00",
                                  "syncRevision" 3861,
                                  "gender" "Unknown",
                                  "createdAt" "2024-11-13T11:02:48.958-00:00",
                                  "sleepPredictions" [],
                                  "active" true,
                                  "uuid" "f845c7d2-391e-49af-af2e-8e19e28a6666"}]
                       "SNSleepPrediction" [{"expectedStartDate" "2024-11-13T11:23:25.258-00:00",
                                             "expectedEndDate" "2024-11-13T11:23:25.258-00:00",
                                             "updatedAt" "2024-11-13T11:23:35.507-00:00",
                                             "syncRevision" 3861,
                                             "sleepType" "Nap",
                                             "createdAt" "2024-11-13T11:23:26.310-00:00",
                                             "baby" "f845c7d2-391e-49af-af2e-8e19e28a4751"
                                             "active" true,
                                             "uuid" "5c197ca6-fe43-4168-b803-754d400b8828"}]}


              [transaction [c1 _]] (sync/push-transaction b/mobile-sync-config input-1 nil empty-db entities-by-name nil (date-parser "2060-01-01T01:01:00.000Z"))
              after-import (db/speculate b/mobile-sync-config empty-db transaction)
              revision (db/get-sync-revision b/mobile-sync-config after-import)]          
;          (pprint transaction)
;          (pprint (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content]))
 ;         (pprint (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNSleepPrediction" :collection-content]))
          (let [updated-at (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content 2 :app/updatedAt])]
            (is (inst? updated-at) (class updated-at)))
          (let [updated-at (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content 3 :app/updatedAt])]
            (is (inst? updated-at) (class updated-at)))

          (let [sleep-rel (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNSleepPrediction" :collection-content 1 :SNSleepPrediction/baby])]
            (is (map? sleep-rel))
            (is (uuid? (:app/uuid sleep-rel)))
            (is (int? (:db/id sleep-rel))))
          
          (let [sleep-rels (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content 2 :SNSleepPrediction/_baby])
                sleep-rel (first sleep-rels)]
            (is (set? sleep-rels) sleep-rel)
            (is (uuid? (:app/uuid sleep-rel)))
            (is (int? (:db/id sleep-rel))))

          (let [sleep-rels (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content 3 :SNSleepPrediction/_baby])
                sleep-rel (first sleep-rels)]
            (is (or (nil? sleep-rels) (and (set? sleep-rels) (empty? sleep-rels))) sleep-rels))

          (testing "Replacing baby on prediction"
            (let [input-2 {"SNBaby" [{"name" "Asia",
                                      "dateOfBirth" "2024-11-13T11:00:00.000-00:00",
                                      "updatedAt" "2060-11-13T11:03:32.486-00:00",
                                      "syncRevision" 3861,
                                      "gender" "Unknown",
                                      "createdAt" "2024-11-13T11:02:48.958-00:00",
                                      "sleepPredictions" [],
                                      "active" true,
                                      "uuid" "f845c7d2-391e-49af-af2e-8e19e28a4751"}
                                     {"name" "Jola",
                                      "dateOfBirth" "2024-11-13T11:00:00.000-00:00",
                                      "updatedAt" "2060-11-13T11:03:32.486-00:00",
                                      "syncRevision" 3861,
                                      "gender" "Unknown",
                                      "createdAt" "2024-11-13T11:02:48.958-00:00",
                                      "sleepPredictions" ["5c197ca6-fe43-4168-b803-754d400b8828"],
                                      "active" true,
                                      "uuid" "f845c7d2-391e-49af-af2e-8e19e28a6666"}]
                           "SNSleepPrediction" [{"expectedStartDate" "2024-11-13T11:23:25.258-00:00",
                                                 "expectedEndDate" "2024-11-13T11:23:25.258-00:00",
                                                 "updatedAt" "2060-11-13T11:23:35.508-00:00",
                                                 "syncRevision" 3861,
                                                 "sleepType" "Nap",
                                                 "createdAt" "2024-11-13T11:23:26.310-00:00",
                                                 "baby" "f845c7d2-391e-49af-af2e-8e19e28a6666"
                                                 "active" true,
                                                 "uuid" "5c197ca6-fe43-4168-b803-754d400b8828"}
                                                ]}

                  [transaction [c1 _]] (sync/push-transaction b/mobile-sync-config input-2 nil after-import entities-by-name nil (date-parser "2060-01-01T01:02:00.000Z"))
              
              after-import2 (db/speculate b/mobile-sync-config after-import transaction)
              revision (db/get-sync-revision b/mobile-sync-config after-import2)]

              (let [old-baby (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNBaby" :collection-content 2])]
                (is (empty? (:SNSleepPrediction/_baby old-baby)) old-baby)
                (is (set? (:SNSleepPrediction/_baby old-baby)) old-baby)
                (is (= true (:app/active old-baby)) old-baby))

              (let [new-baby (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNBaby" :collection-content 3])]
                (is (= #{{:db/id 1, :app/uuid #uuid "5c197ca6-fe43-4168-b803-754d400b8828"}} (:SNSleepPrediction/_baby new-baby)))
                (is (= true (:app/active new-baby)) new-baby))

              (let [prediction (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNSleepPrediction" :collection-content 1])]
                (is (= {:db/id 3, :app/uuid #uuid "f845c7d2-391e-49af-af2e-8e19e28a6666"} (:SNSleepPrediction/baby prediction)))
                (is (= true (:app/active prediction)) prediction)))))))))
