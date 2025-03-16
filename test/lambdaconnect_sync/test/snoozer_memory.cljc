(ns lambdaconnect-sync.test.snoozer-memory
  (:require 
   [lambdaconnect-sync.test.basic-memory :as b]
   [lambdaconnect-model.core :as mp]            
   [lambdaconnect-model.tools :as mpt]            
   [lambdaconnect-sync.utils :as u]
   [lambdaconnect-sync.core :as sync]
   [lambdaconnect-sync.db-drivers.memory :as mem]
   [lambdaconnect-sync.db :as db]
   [lambdaconnect-sync.db-interface :as i]
   [lambdaconnect-model.tools :refer [special-attribs] :as dt]
   [lambdaconnect-model.graph-generator :as generator]
   [clojure.pprint :refer [pprint]]
   
   #?@(:cljs [[clojure.test.check.generators]
              [cljs.spec.gen.alpha :as gen]
              [cljs.test :refer [deftest testing is use-fixtures] :as t]
              [cljs.spec.alpha :as s]]
       :clj [[clojure.spec.gen.alpha :as gen]
             [clojure.test :refer [deftest testing is use-fixtures] :as t]
             [clojure.spec.alpha :as s]]))
  
  #?(:clj (:import [java.text SimpleDateFormat])))


(defn pull-body [database last-pull-sync-revision]
  (let [ebn (get-in database [:entities-by-name])
        cntrs (into {} (map (fn [n] [n (inc (or last-pull-sync-revision -1))]) (keys ebn)))]
     cntrs))

(defn inverse-parser-for-attribute [attribute]
  (case (:type attribute)
    :db.type/uuid str 
    nil))

(defn inverse-parser-for-relationship [relationship]
  (if (:to-many relationship)
    (fn [val] (map #(-> % :app/uuid str) val))
    (fn [val]
      (if (map? val)
        (or (some-> val :app/uuid str) #?(:cljs js/undefined :clj nil))
        (or (some-> val first :app/uuid str) #?(:cljs js/undefined :clj nil))))))

(defn build-keys-for-keywords [entity]
  (into {} (concat
            (map #(vector (dt/datomic-name %) (:name %))
                 (concat (vals (:attributes entity))
                         (vals (:datomic-relationships entity))))
            (map #(vector (dt/datomic-inverse-name %) (:name %))
                 (filter #(not ((:datomic-relationships entity) (:name %)))
                         (vals (:relationships entity)))))))

(defn build-inverse-parsers-for-keywords [entity]
  (into {} (concat
            (map #(vector (dt/datomic-name %) (inverse-parser-for-attribute %)) (vals (:attributes entity)))
            (map #(vector (if ((:datomic-relationships entity) (:name %))
                            (dt/datomic-name %)
                            (dt/datomic-inverse-name %))
                          (inverse-parser-for-relationship %)) (vals (:relationships entity))))))

(def keys-for-keywords-by-entity (atom nil))
(def inverse-parsers-for-keywords-by-entity (atom nil))

(use-fixtures :once (fn [f]
                      (let [model-str (b/load-model-fixture "snoozer.xml")
                            model (mp/entities-by-name model-str)]
                        (reset! keys-for-keywords-by-entity
                                (into {} (map #(-> [(first %) (build-keys-for-keywords (second %))])
                                              model)))
                        (reset! inverse-parsers-for-keywords-by-entity (into {} (map #(vector (first %) (build-inverse-parsers-for-keywords (second %))) model)))
                        (b/setup-basic-test-environment model-str nil f))))

(defn sync-fields [uuid currentDate]
  {"active" true
   "createdAt" currentDate
   "updatedAt" currentDate
   "uuid" uuid})


#?(:cljs
   (defn transform-js-object-field-value [value]
     (cond (instance? js/Date value) (.toISOString value)
           (boolean? value) (if value 1 0)
           :default value))
   :clj
   (defn transform-js-object-field-value [value]
     (let [df (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")]
       (cond (instance? java.util.Date value) (.format df value)
             (boolean? value) (if value 1 0)
             :default value))))

(defn update-js-objects [objects]
  (update-vals (group-by #(get % "entityName") objects)
               #(map (comp (fn [o] (update-vals o transform-js-object-field-value)) (fn [o] (get o "entityContent"))) %)))


(defn update-objects [db js-objects]
  (let [transformed (update-js-objects js-objects)      
        ebn (get db :entities-by-name)
        [tx] (sync/push-transaction {:config b/mobile-sync-config
                                     :incoming-json transformed
                                     :snapshot db
                                     :entities-by-name ebn
                                     :now #?(:cljs (js/Date.) :clj (java.util.Date.))
                                     :slave-mode? false})
        ;; _ (println "UPDATE:" )
        ;; _ (pprint tx)

        speculated (db/speculate b/mobile-sync-config db tx)]
    speculated))

(defn sync-objects [db js-objects sync-revision-for-slave-mode]
  (let [transformed (update-js-objects js-objects)
        ebn (get db :entities-by-name)
        [tx] (sync/push-transaction {:config b/mobile-sync-config  ;;(assoc b/mobile-sync-config :log println)
                                     :incoming-json transformed
                                     :snapshot db
                                     :sync-revision-for-slave-mode sync-revision-for-slave-mode
                                     :entities-by-name ebn
                                     :now #?(:cljs (js/Date.) :clj (java.util.Date.))
                                     :slave-mode? true})
        ;; _ (println "SYNC:")
        ;; _ (pprint tx)
        speculated (db/speculate b/mobile-sync-config db tx)]
    speculated))


(defn js-objects-by-fun [snapshot entity-name some-ids extract-fun]
  (let [entity (get-in snapshot [:entities-by-name entity-name])
        extracted (doall (extract-fun b/mobile-sync-config entity some-ids snapshot true))
        keys-for-keywords (get @keys-for-keywords-by-entity entity-name)
        inverse-parsers-for-keywords (get @inverse-parsers-for-keywords-by-entity entity-name)]    
    (map (fn [obj]
           (into {}
                 (map (fn [[k v]]
                        (when-let [key (k keys-for-keywords)]
                          (let [value (if (nil? v) #?(:cljs js/undefined :clj nil)
                                          (if-let [p (inverse-parsers-for-keywords k)]
                                            (p v) v))]
                            [key value]))) obj)))
         extracted)))

(defn js-objects-by-ids [snapshot entity-name ids]
  (js-objects-by-fun snapshot entity-name ids db/get-objects-by-ids))

(defn js-objects-by-uuids [snapshot entity-name uuids]
  (js-objects-by-fun snapshot entity-name (map parse-uuid uuids) db/get-objects-by-uuids))


(defn get-paginated-collection [& [snapshot entity-name page per-page sorts condition]]
  (let [fix-key #(keyword (if (special-attribs %) "app" entity-name) %)
        sorts (map
               (fn [sort]
                 (let [sort-key (get sort "key")
                       sort-options (get sort "options")
                       sort-direction (get sort "direction")]
                   (merge {:key (fix-key sort-key)
                           :direction sort-direction}
                          (when sort-options {:options (set (map keyword sort-options))}))))
               sorts)
        condition-parser (fn condition-parser [condition]
                           (when condition
                             (let [where (get condition "where-fn")]
                               (if where {:where-fn (fn [x] (where (if (uuid? x) (str x) x)))
                                          :key (fix-key (get condition "key"))}
                                   (let [type (keyword (get condition "condition-type"))]
                                     {:condition-type type
                                      :conditions (map condition-parser (get condition "conditions"))})))))
        condition (condition-parser condition)]
    (->> condition
         (mem/get-paginated-collection snapshot entity-name page per-page sorts)
         (js-objects-by-ids snapshot entity-name))))

(defn get-object [db entity-name]
  (let [ebn (get db :entities-by-name)
        objs (get-paginated-collection db entity-name 0 1 nil nil)]
    (first objs)))

(deftest test-to-one-relationship
  (testing "Setup;"
    (let [empty-db @b/conn
          entities-by-name (mp/entities-by-name  (b/load-model-fixture "snoozer.xml"))
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
              revision (db/get-sync-revision b/mobile-sync-config after-import)
              baby-1 (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-uuid-index #uuid "f845c7d2-391e-49af-af2e-8e19e28a4751"])
              sleep-1 (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNActiveSleep" :collection-uuid-index #uuid "5c197ca6-fe43-4168-b803-754d400b8828"])
              sleep-2 3]          

          (let [updated-at (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content baby-1 :app/updatedAt])]
            (is (inst? updated-at) #?(:clj (class updated-at) :cljs updated-at)))

          (let [sleep-rel (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content baby-1 :SNBaby/activeSleep])]
            (is (map? sleep-rel))
            (is (uuid? (:app/uuid sleep-rel)))
            (is (int? (:db/id sleep-rel))))
          
          (let [sleep-rels (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNActiveSleep" :collection-content sleep-1 :SNBaby/_activeSleep])
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


              (let [old-sleep (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNActiveSleep" :collection-content sleep-1])]
                (is (= false (:app/active old-sleep)) old-sleep)
                (is (empty? (:SNBaby/_activeSleep old-sleep))))

              (let [new-sleep (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNActiveSleep" :collection-content sleep-2])]
                (is (= #{{:db/id baby-1, :app/uuid #uuid "f845c7d2-391e-49af-af2e-8e19e28a4751"}} (:SNBaby/_activeSleep new-sleep)))
                (is (= true (:app/active new-sleep)) new-sleep))

              (let [baby (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNBaby" :collection-content baby-1])]
                (is (= {:db/id sleep-2, :app/uuid #uuid "5c197ca6-fe43-4168-b803-754d400b8829"} (:SNBaby/activeSleep baby)))
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
              revision (db/get-sync-revision b/mobile-sync-config after-import)
              baby-1 (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-uuid-index #uuid "f845c7d2-391e-49af-af2e-8e19e28a4751"])
              baby-2 (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-uuid-index #uuid "f845c7d2-391e-49af-af2e-8e19e28a6666"])

              prediction-1 (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNSleepPrediction" :collection-uuid-index #uuid "5c197ca6-fe43-4168-b803-754d400b8828"])]          

          (let [updated-at (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content baby-1 :app/updatedAt])]
            (is (inst? updated-at) #?(:clj (class updated-at) :cljs updated-at)))
          (let [updated-at (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content baby-2 :app/updatedAt])]
            (is (inst? updated-at) #?(:clj (class updated-at) :cljs updated-at)))

          (let [sleep-rel (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNSleepPrediction" :collection-content prediction-1 :SNSleepPrediction/baby])]
            (is (map? sleep-rel))
            (is (uuid? (:app/uuid sleep-rel)))
            (is (int? (:db/id sleep-rel))))
          
          (let [sleep-rels (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content baby-1 :SNSleepPrediction/_baby])
                sleep-rel (first sleep-rels)]
            (is (set? sleep-rels) sleep-rel)
            (is (uuid? (:app/uuid sleep-rel)))
            (is (int? (:db/id sleep-rel))))

          (let [sleep-rels (get-in after-import [:snapshots (:newest-snapshot-idx after-import) :collections "SNBaby" :collection-content baby-2 :SNSleepPrediction/_baby])
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

              (let [old-baby (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNBaby" :collection-content baby-1])]
                (is (empty? (:SNSleepPrediction/_baby old-baby)) old-baby)
                (is (set? (:SNSleepPrediction/_baby old-baby)) old-baby)
                (is (= true (:app/active old-baby)) old-baby)) 

              (let [new-baby (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNBaby" :collection-content baby-2])]
                (is (= #{{:db/id prediction-1, :app/uuid #uuid "5c197ca6-fe43-4168-b803-754d400b8828"}} (:SNSleepPrediction/_baby new-baby)))
                (is (= true (:app/active new-baby)) new-baby))

              (let [prediction (get-in after-import2 [:snapshots (:newest-snapshot-idx after-import2) :collections "SNSleepPrediction" :collection-content prediction-1])]
                (is (= {:db/id baby-2, :app/uuid #uuid "f845c7d2-391e-49af-af2e-8e19e28a6666"} (:SNSleepPrediction/baby prediction)))
                (is (= true (:app/active prediction)) prediction)))))))))

(deftest test-resolving-m-n-relation-conflicts
  (let [ebn (mp/entities-by-name (b/load-model-fixture "snoozer.xml"))
        _ (mp/specs ebn {:SNBaby/gender #(s/gen #{"Male" "Female" "Other"})
                         :SNSubscriptionOrder/providerType #(s/gen #{"imoje" "appstore" "googleplay"})
                         :SNPredictionCohortWeight/cohortName #(s/gen #{"AllOK" "Sleepyhead" "InvertedRhythm" "NapsBadNightOK" "AllBad"})
                         :SNPredictionCohortWeight/groupName #(s/gen #{"group1" "group2"})
                         :SNPredictionCohortWeight/group #(s/gen #{1 2})
                         :SNPredictionCohortWeight/cohort #(s/gen #{1 2 3 4 5})
                         :SNCarer/language #(s/gen #{"pl" "en" "ar"})
                         :SNBaby/assignedPredictionCohortName #(s/gen #{"AllOK" "Sleepyhead" "InvertedRhythm" "NapsBadNightOK" "AllBad"})
                         :SNBaby/assignedPredictionGroupName #(s/gen #{"group0" "group1" "group2"})
                         :SNContentManager/contentLanguage #(s/gen #{"pl" "en" "ar"})
                         :SNAdditionalSleepData/babyMood #(s/gen #{"Happy" "Sad"})
                         :SNAdditionalSleepData/estimatedTimeToFallAsleep #(s/gen #{"Short" "Average" "Long"})
                         :SNAdditionalSleepData/babyMoodAfterWakeup #(s/gen #{"Happy" "Sad"})
                         :SNAdditionalSleepData/circumstances #(s/gen #{"InBedAlone" "InBedAssisted" "ByBreast" "ByBottle" "Cosleeping" "RockedOrInYoke" "InStrollerOrCar" "InBabySwing"})
                         :SNAdditionalSleepData/sleepType #(s/gen #{"Nap" "Sleep"})
                         :SNSleepPrediction/sleepType #(s/gen #{"Nap" "Sleep"})
                         :SNSlideSection/type #(s/gen #{"markdown" "header" "image" "video" "quote"})
                         :SNSlideSection/textAlign #(s/gen #{"left" "right" "center" "justify"})
                         :SNSlideSection/bulletPointType #(s/gen #{"default" "yellowStar" "whiteStar" "moon" "rocket" "lightning" "do" "dont"})
                         :SNSlideSection/enumerationType #(s/gen #{"numerical" "alphabetical"})
                         :SNSectionContent/language #(s/gen #{"pl" "en" "ar"})
                         :SNSubchapterCheckbox/language #(s/gen #{"pl" "en" "ar"})
                         :SNSubchapterTitle/language #(s/gen #{"pl" "en" "ar"})
                         :SNChapterTitle/language #(s/gen #{"pl" "en" "ar"})
                         :SNMissionContent/language #(s/gen #{"pl" "en" "ar"})
                         :SNMissionFaq/language #(s/gen #{"pl" "en" "ar"})
                         :SNCourseQuestionnaireAnswers/attitudeTowardsCrying #(s/gen #{"PrioritizeFastResults" "MinimizeCrying" "NoCrying" "NoSleepTraining"})
                         :SNCourseQuestionnaireAnswers/babyDescription #(s/gen #{"Energetic" "Calm" "Grupmpy" "Anxious" "HardToTell"})
                         :SNCourseQuestionnaireAnswers/bedtimeMood #(s/gen #{"Happy" "Grupmy" "Differs"})
                         :SNCourseQuestionnaireAnswers/carers #(s/gen #{"Mother" "Father" "Nanny" "Mother,Nanny" "Mother,Father" "Father,Nanny" "Father,Mother" "Mother,Father,Nanny"})
                         :SNCourseQuestionnaireAnswers/mainGoal #(s/gen #{"LessNightWakeups" "HelpDifferentiateDayFromNight" "LongerNaps" "LaterWakeups" "EasierPuttingToSleep" "OwnBedTransition"})
                         :SNCourseQuestionnaireAnswers/nightSleepCircumstances #(s/gen #{"ByBreast" "ByBottle" "InStroller" "Rocked" "Cosleeping" "InBedAssisted" "InBedAlone" "InCar" "ByBreast,ByBottle" "ByBreast,InStroller" "ByBreast,Rocked" "InCar,Cosleeping"})
                         :SNCourseQuestionnaireAnswers/napCircumstances #(s/gen #{"ByBreast" "ByBottle" "InStroller" "Rocked" "Cosleeping" "InBedAssisted" "InBedAlone" "InCar" "ByBreast,ByBottle" "ByBreast,InStroller" "ByBreast,Rocked" "InCar,Cosleeping"})
                         :SNCourseQuestionnaireAnswers/nightSleepLocation #(s/gen #{"Crib" "ParentsBed" "OwnBed" "StrollerOrCarrier" "ParentsArms"})
                         :SNCourseQuestionnaireAnswers/wakeupMood #(s/gen #{"Energetic" "Grumpy" "EnergeticButThenGrumpy" "GrumpyButThenEnergetic" "Differs"})
                         :SNSubscriptionTransaction/providerType #(s/gen #{"imoje" "appstore" "googleplay"})
                         :SNSubscriptionTemplate/providerType #(s/gen #{"imoje" "appstore" "googleplay"})
                         :SNPaymentInstrument/providerType #(s/gen #{"imoje" "appstore" "googleplay"})
                         :SNSubscriptionState/subscriptionState #(s/gen #{"ACTIVE" "CANCELLED" "INACTIVE"})
                         :SNSubscriptionOrder/expectedCurrency #(s/gen #{"PLN"})
                         :SNPrice/currency #(s/gen #{"PLN"})
                         :SNSubscriptionTransaction/currency #(s/gen #{"PLN"})
                         :SNPaymentRefund/currency #(s/gen #{"PLN"})
                         :SNSubscriptionState/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))
                         :SNUser/email (fn [] (gen/fmap #(str % "@test.com") (gen/string-alphanumeric)))})
        graph (generator/generate-entity-graph ebn {:vertices 1000
                                                    :edges 10000
                                                    :create-sync-revisions? true
                                                    :force-active? true})
        empty-db @b/conn
        user-uuid (random-uuid)
        date-parser (mpt/parser-for-attribute (get-in ebn ["SNBaby" :attributes "updatedAt"]))
        [initial-tx]
        (sync/push-transaction
         {:config b/mobile-sync-config
          :incoming-json graph
          :internal-user user-uuid
          :snapshot empty-db
          :entities-by-name ebn
          :scoping-edn nil
          :slave-mode? true
          :sync-revision-for-slave-mode 0 ;; last-import-sync-revision
          :now (date-parser "2060-01-01T01:02:00.000Z")})

        last-pull-sync-revision
        (reduce max 0 (mapcat #(keep (fn [o] (get o "syncRevision")) %)
                              (vals graph)))

        after-first-pull (db/speculate b/mobile-sync-config empty-db initial-tx)]
    (is (< 0 last-pull-sync-revision))
    (testing "Babies"
      (let [baby (get-object after-first-pull "SNBaby")]
        (is baby)
        (is (get baby "active"))
        (is (get baby "updatedAt"))
        (is (inst? (get baby "updatedAt")))

        (is (get baby "createdAt"))
        (is (inst? (get baby "createdAt")))

        (let [carers (js-objects-by-uuids
                      after-first-pull
                      "SNCarer"
                      (get baby "additionalCarers"))
              carer (first carers)]
          (is (< 0 (count carers)))
          (is carer)
          (is (get carer "active"))
          (is (< 0 (count (get carer "additionalCarerOf")))))

        (testing "Zorro baby"
          (let [new-baby (assoc baby "name" "Zorro!!!!")
                after-baby-update (update-objects after-first-pull [{"entityName" "SNBaby"
                                                                     "entityContent" new-baby}])
                sr1 (db/get-sync-revision b/mobile-sync-config after-first-pull)
                sr2 (db/get-sync-revision b/mobile-sync-config after-baby-update)
                new-baby-fetched (first (js-objects-by-uuids after-baby-update "SNBaby" [(get new-baby "uuid")]))]
            (is (not= sr1 sr2))
            (is new-baby-fetched)
            (is (= "Zorro!!!!" (get new-baby-fetched "name")))
            (testing "Test resolving m-n relation conflicts"
              (let [completionAddedBeforeSyncId (random-uuid)
                    completionAddedAfterSyncId (random-uuid)
                    now #?(:clj (java.util.Date.) :cljs (js/Date.))
                    db-added-completion (update-objects after-baby-update
                                                        [{"entityName" "SNMissionCompletion"
                                                          "entityContent"
                                                          (merge
                                                           {"baby" (get baby "uuid")
                                                            "isCompleted" true
                                                            "date" now}
                                                           (sync-fields completionAddedBeforeSyncId now))}])

                    added-completion-syncrev (db/get-sync-revision
                                              b/mobile-sync-config
                                              db-added-completion)
                    localUpdateDate #?(:clj (java.util.Date.) :cljs (js/Date.))
                    remoteUpdateDate #?(:clj (java.util.Date. (+ (System/currentTimeMillis) 100))
                                        :cljs (js/Date. (+ 100 (.getTime (js/Date.)))))

                    babyBeforeSync (get-object db-added-completion "SNBaby")
                    db-updated-during-sync (update-objects db-added-completion
                                                           [{"entityName" "SNBaby"
                                                             "entityContent"
                                                             (merge
                                                              babyBeforeSync
                                                              {"name" "locally set name"
                                                               "updatedAt" localUpdateDate})}])
                    now2 #?(:clj (java.util.Date.)
                            :cljs (js/Date.))
                    db-updated-during-sync-2 (update-objects db-updated-during-sync
                                                             [{"entityName" "SNMissionCompletion"
                                                               "entityContent"
                                                               (merge
                                                                {"baby" (get babyBeforeSync "uuid")
                                                                 "isCompleted" true
                                                                 "date" now2}
                                                                (sync-fields completionAddedAfterSyncId now2))}])
                    db-synced (sync-objects db-updated-during-sync-2
                                            [{"entityName" "SNBaby"
                                              "entityContent"
                                              (merge
                                               babyBeforeSync
                                               {"name" "remotely set name"
                                                "updatedAt" remoteUpdateDate
                                                ;; It does not matter for frontend conflict resolution if this line is commented out or not
                                                ;"syncRevision" 1000000000
                                                })}]
                                            added-completion-syncrev)

                    baby-after-all (get-object db-synced "SNBaby")]

                (is (= (get baby "uuid") (get baby-after-all "uuid")))
                (is (= "remotely set name" (get baby-after-all "name")))
                (is (= (get baby "uuid") (get babyBeforeSync "uuid")))
                (is (contains? (set (get baby-after-all "missionCompletions"))
                               (str completionAddedBeforeSyncId)))
                (is (contains? (set (get baby-after-all "missionCompletions"))
                               (str completionAddedAfterSyncId)))

                (is (empty? (reduce concat (vals (sync/pull {:config b/mobile-sync-config
                                                             :incoming-json (into {} (map (fn [n] [n (inc added-completion-syncrev)]) (keys ebn)))
                                                             :snapshot db-added-completion
                                                             :entities-by-name ebn
                                                             :slave-mode? true})))))))))))))
