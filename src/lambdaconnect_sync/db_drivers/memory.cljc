(ns lambdaconnect-sync.db-drivers.memory
  (:require [lambdaconnect-model.data-xml :as xml]
            [lambdaconnect-sync.db-interface :as i]
            [lambdaconnect-sync.hooks :refer [get-datomic-relationships-from-model 
                                              get-ids-from-entry
                                              update-ids-in-entry]]
            [lambdaconnect-model.tools :as t]
            [lambdaconnect-model.utils :as u]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [prewalk postwalk]]
            [clojure.string :refer [lower-case]]
            [clojure.set :refer [intersection union difference]]
            #?(:clj [clojure.spec.alpha :as s] 
               :cljs [cljs.spec.alpha :as s])))

;; A simple in-memory database driver.

;; Database spec

(s/def ::entity-id pos-int?)
(s/def ::entity-uuid uuid?)

;; (inc (max entity-ids)) for the whole db:
(s/def ::inc-max-entity-id pos-int?)
(s/def ::snapshot-id pos-int?)

;; we encode relationships as sets
(s/def ::entity map?)
(s/def ::collection-content (s/map-of ::entity-id ::entity))
(s/def ::collection-uuid-index (s/map-of ::entity-uuid ::entity-id))

(s/def ::collection (s/keys :req-un [::collection-content ::collection-uuid-index]))
(s/def ::collections (s/map-of string? ::collection))

(s/def ::entities-by-name (s/map-of :lambdaconnect-model.data-xml/entity-name
                                    :lambdaconnect-model.data-xml/entity))


(s/def ::ids-to-entity-names (s/map-of ::entity-id
                                       :lambdaconnect-model.data-xml/entity-name))


(s/def ::keywords-without-inverses-by-name (s/map-of string? (s/coll-of qualified-keyword?)))

(s/def ::snapshot 
  (s/keys :req-un 
          [::collections
           ::ids-to-entity-names])) 

(s/def ::newest-snapshot-idx ::snapshot-id)
(s/def ::snapshots (s/map-of ::snapshot-id ::snapshot))
(s/def ::db-name string?)

(s/def ::memory-database
  (s/keys :req-un 
          [::newest-snapshot-idx
           ::entities-by-name
           ::keywords-without-inverses-by-name
           ::snapshots
           ::inc-max-entity-id
           ::db-name]))

;; Transaction spec

(s/def ::transaction-entity-id (s/or :created string?
                                     :existing ::entity-id))

(defmulti transaction-entry-type first)

(defmethod transaction-entry-type :db/add [_]
  (s/tuple #{:db/add} ::transaction-entity-id qualified-keyword? any?))


;; Please note - we do not allow retracts with no value (this is different from datomic).
(defmethod transaction-entry-type :db/retract [_]
  (s/tuple #{:db/retract} ::entity-id qualified-keyword? some?))

(defmethod transaction-entry-type :db/cas [_]
  (s/tuple #{:db/cas} ::transaction-entity-id qualified-keyword? any? some?))

(defmethod transaction-entry-type :db.fn/cas [_]
  (s/tuple #{:db.fn/cas} ::transaction-entity-id qualified-keyword? any? some?))


;; At this stage we do not support entity retraction - it is not needed for synchro and since we have the "active" flag not needed in apps too.

;; (defmethod transaction-entry-type :db/retractEntity [_]
;;   (s/tuple #{:db/retractEntity} ::entity-id))

;; Compound entries must have a :app/uuid key with either string (new value) or uuid (existing value).
;; This is different from datomic spec, which is broader and allows uuids as new id's as well. Synchro follows my guideline.

(s/def ::transaction-compound-entry (s/map-of qualified-keyword? any?))

(s/def ::transaction-entry (s/or 
                            :fact (s/multi-spec transaction-entry-type first)
                            :creation ::transaction-compound-entry))


(s/def ::transaction (s/coll-of ::transaction-entry))

(defn- lookup-relationship [snapshot allocated-ids relationship id-or-lookup]
  (assert relationship)
  (if (pos-int? id-or-lookup) 
    id-or-lookup
    (do 
      ;; We allow :app/uuid lookups inside relationships instead of id
      (assert (map? id-or-lookup))
      (let [uuid (:app/uuid id-or-lookup)]
        (assert uuid)
        (assert (uuid? uuid))
        (let [result 
              (or 
               (get-in snapshot [:collections
                                 (:destination-entity relationship)
                                 :collection-uuid-index
                                 uuid])
               (->> allocated-ids
                    (map #(hash-map :db/id 
                                    % 
                                    :app/uuid 
                                    (get-in snapshot 
                                            [:collections
                                             (:destination-entity relationship)
                                             :collection-content
                                             %
                                             :app/uuid])))
                    (filter #(= uuid
                                (:app/uuid %)))
                    (first)
                    :db/id))]
          (assert result (str "Could not look up " id-or-lookup " in the database."))
          result)))))

;; We do not want to separately handle db.fn/cas and db/cas, so we abuse the fact that all transaction entries (except for :db.fn/cas) have a :db type
(defmulti process-transaction-entry (fn [[op] _ _ _ _ _] (keyword "db" (name op))))

(defmethod process-transaction-entry :db/cas 
  [[_ entity-id attribute-key old-value new-value] 
   snapshot 
   database
   datomic-relationships 
   entities-by-id
   allocated-ids]
  (let [entity-name (entities-by-id entity-id)]
    (assert entity-name)
    (if (datomic-relationships attribute-key) 
      (assert (= old-value (get-in snapshot [:collections 
                                             entity-name 
                                             :collection-content 
                                             entity-id 
                                             attribute-key
                                             :db/id])))
      
      (assert (= old-value (get-in snapshot [:collections 
                                             entity-name 
                                             :collection-content 
                                             entity-id 
                                             attribute-key]))))

    (process-transaction-entry [:db/add entity-id attribute-key new-value]
                               snapshot
                               database
                               datomic-relationships
                               entities-by-id
                               allocated-ids)))

(defmethod process-transaction-entry :db/add 
  [[_ entity-id attribute-key new-value] 
   snapshot
   database
   datomic-relationships 
   entities-by-id
   allocated-ids]
  (let [entity-name (entities-by-id entity-id)]
    (assert entity-name "Each new added entity has to have at least one attribute that differentiates it from other entities set (bare minimum is :EntityName/ident__")
    (if-not (datomic-relationships attribute-key)
      ;; Attribute
      (assoc-in snapshot [:collections 
                          entity-name 
                          :collection-content 
                          entity-id 
                          attribute-key] new-value)
      ;; Relationship
      (let [relationship (get-in database [:entities-by-name
                                           entity-name
                                           :datomic-relationships
                                           (name attribute-key)])
            inverse-relationship (get-in database [:entities-by-name
                                                   (:inverse-entity relationship)
                                                   :relationships
                                                   (:inverse-name relationship)])
            _ (assert relationship)
            _ (assert inverse-relationship)

            id->rel (fn [id]
                      (let [uuid (get-in snapshot 
                                         [:collections
                                          (entities-by-id id)
                                          :collection-content
                                          id
                                          :app/uuid])]
                        (assert uuid (str (get-in snapshot [:collections
                                                            (entities-by-id id)
                                                            :collection-content])))
                        {:db/id id
                         :app/uuid uuid}))

            new-value (lookup-relationship snapshot allocated-ids relationship new-value)]
        (cond 

          ;; many to many 

          (and (:to-many relationship)
               (:to-many inverse-relationship))         
          
          (-> snapshot
              (update-in [:collections
                          entity-name
                          :collection-content
                          entity-id
                          attribute-key]
                         #(conj (or % #{}) (id->rel new-value)))
              (update-in [:collections
                          (:destination-entity relationship)
                          :collection-content
                          new-value
                          (t/datomic-inverse-name inverse-relationship)]
                         #(conj (or % #{}) (id->rel entity-id))))

          ;; one to many

          (and (not (:to-many relationship))
               (:to-many inverse-relationship))         
          
          (let [old-value (get-in snapshot 
                                  [:collections
                                   entity-name
                                   :collection-content
                                   entity-id
                                   attribute-key])]
            
            (cond-> snapshot
            
              old-value
              (update-in [:collections
                          (:destination-entity relationship)
                          :collection-content
                          old-value
                          (t/datomic-inverse-name inverse-relationship)]
                         #(disj (or % #{}) (id->rel entity-id)))
  
              true (assoc-in [:collections
                               entity-name
                               :collection-content
                               entity-id
                               attribute-key]
                             (id->rel new-value))
              true (update-in [:collections
                               (:destination-entity relationship)
                               :collection-content
                               new-value
                               (t/datomic-inverse-name inverse-relationship)]
                              #(conj (or % #{}) (id->rel entity-id)))))

          ;; one to one

          (and (not (:to-many relationship))
               (not (:to-many inverse-relationship)))         
          
          (let [old-value (:db/id (get-in snapshot 
                                  [:collections
                                   entity-name
                                   :collection-content
                                   entity-id
                                   attribute-key]))
                old-target-value (-> snapshot 
                                     (get-in [:collections
                                              (:destination-entity relationship)
                                              :collection-content
                                              new-value
                                              (t/datomic-inverse-name inverse-relationship)])
                                     (first)
                                     :db/id)]
            
            (cond-> snapshot
            
              old-value
              (update-in [:collections
                          (:destination-entity relationship)
                          :collection-content
                          old-value
                          (t/datomic-inverse-name inverse-relationship)]
                         #(disj (or % #{}) (id->rel entity-id)))
  
              old-target-value 
              (update-in [:collections
                          entity-name
                          :collection-content
                          old-target-value]
                         #(do
                            (assert (= new-value (:db/id (attribute-key %))) 
                                    (str "ID: " entity-id " ak: " attribute-key " old value: " old-value " new value " new-value "\n"
                                         "OLD TARGET VALUE: " old-target-value "\n"
                                         "(ak %): " (attribute-key %) "\n"
                                         "Whole O: " (with-out-str (pprint %))))
                            (dissoc % attribute-key)))

              true (assoc-in [:collections
                              entity-name
                              :collection-content
                              entity-id
                              attribute-key]
                             (id->rel new-value))
              
              true (assoc-in [:collections
                              (:destination-entity relationship)
                              :collection-content
                              new-value
                              (t/datomic-inverse-name inverse-relationship)]
                             #{(id->rel entity-id)}))))))))

(defmethod process-transaction-entry :db/retract
  [[_ entity-id attribute-key retracted-value] 
   snapshot 
   database
   datomic-relationships 
   entities-by-id
   allocated-ids]
  (let [entity-name (entities-by-id entity-id)]
    (assert entity-name)
    (if-not (datomic-relationships attribute-key)
      ;; Attribute
      (update-in snapshot [:collections 
                          entity-name 
                          :collection-content 
                          entity-id]
                 #(do (assert (= retracted-value (attribute-key %)))
                      (dissoc % attribute-key)))
      ;; Relationship
      (let [relationship (get-in database [:entities-by-name
                                           entity-name
                                           :datomic-relationships
                                           (name attribute-key)])
            inverse-relationship (get-in database [:entities-by-name
                                                   (:inverse-entity relationship)
                                                   :relationships
                                                   (:inverse-name relationship)])
            _ (assert relationship)
            _ (assert inverse-relationship)

            id->rel (fn [id]
                      (let [uuid (get-in snapshot 
                                         [:collections
                                          (entities-by-id id)
                                          :collection-content
                                          id
                                          :app/uuid])]
                        (assert uuid)
                        {:db/id id
                         :app/uuid uuid}))
            retracted-value (lookup-relationship snapshot allocated-ids relationship retracted-value)]
        (cond 

          ;; many to many 

          (and (:to-many relationship)
               (:to-many inverse-relationship))         
          
          (-> snapshot
              (update-in [:collections
                          entity-name
                          :collection-content
                          entity-id
                          attribute-key]
                         #(disj (or % #{}) (id->rel retracted-value)))
              (update-in [:collections
                          (:destination-entity relationship)
                          :collection-content
                          retracted-value
                          (t/datomic-inverse-name inverse-relationship)]
                         #(disj (or % #{}) (id->rel entity-id))))

          ;; one to many

          (and (not (:to-many relationship))
               (:to-many inverse-relationship))         
          
          (let [old-value (:db/id (get-in snapshot 
                                          [:collections
                                           entity-name
                                           :collection-content
                                           entity-id
                                           attribute-key]))]
            (cond-> snapshot
            
              old-value
              (update-in [:collections
                          (:destination-entity relationship)
                          :collection-content
                          old-value
                          (t/datomic-inverse-name inverse-relationship)]
                         #(disj (or % #{}) (id->rel entity-id)))
  
              true (update-in [:collections
                               entity-name
                               :collection-content
                               entity-id]
                              #(do 
                                 (assert (= (attribute-key %) (id->rel retracted-value)))
                                 (dissoc % attribute-key)))))

          ;; one to one

          (and (not (:to-many relationship))
               (not (:to-many inverse-relationship)))         
                               
          (-> snapshot
            
            (update-in [:collections
                        entity-name
                        :collection-content
                        entity-id]
                       #(do
                          (assert (= retracted-value (:db/id (attribute-key %))) (str "Retraction failure: '" retracted-value "' is not equal to '" (:db/id (attribute-key %)) "' extracted by key: '" attribute-key "'."))
                          (dissoc % attribute-key)))
            
            (update-in [:collections
                        (:destination-entity relationship)
                        :collection-content
                        retracted-value
                        (t/datomic-inverse-name inverse-relationship)]
                       #(disj (or % #{}) (id->rel entity-id)))))))))


(defn- apply-transaction 
  [database transaction]
  
  (s/assert ::memory-database database)
  (s/assert ::transaction transaction)
  
  (let [snapshot (get-in database [:snapshots (:newest-snapshot-idx database)])
        new-snapshot-idx (inc (:newest-snapshot-idx database))
        
        ;; This could be cached for performance if required:
        datomic-relationships (get-datomic-relationships-from-model (:entities-by-name database))

        ;; Some maps might not include :db/id, we then assign random ones.
        transaction (map #(if (and (map? %) (not (:db/id %))) 
                            (assoc % :db/id (str (random-uuid)))
                            %) 
                         transaction)
        ids (->> transaction
                 (map (partial get-ids-from-entry datomic-relationships))
                 (reduce union #{}))
        
        new-ids (filter string? ids)
        starting-id  (:inc-max-entity-id database)
        ending-id (+ starting-id (count new-ids))
        allocated-ids (range starting-id ending-id)
        id-to-correct-id (merge 
                          (into {} (map vector new-ids allocated-ids))
                          (into {} (map #(vector % %) (remove string? ids))))

        ;; Replacing temporary ids with newly assigned ones:
        transaction (map (partial update-ids-in-entry datomic-relationships id-to-correct-id) 
                         transaction)

        ;; Unpacking all the convenience "{:db/id ... :app/uuid ... :NOUser/fullName ....}" entries:
        transaction (mapcat (fn [entry] (if (map? entry) 
                                            (mapcat 
                                             (fn [[attr value]]
                                               (if (sequential? value)
                                                 (map #(vector :db/add (:db/id entry) attr %) value)
                                                 [[:db/add (:db/id entry) attr value]]))   
                                             (dissoc entry :db/id))
                                            [entry]))  
                              transaction)

        allocated-ids-set (set allocated-ids)
        
        entities-by-id (merge (:ids-to-entity-names snapshot)
                              (->> transaction
                                   (filter (fn [[_ id]] (allocated-ids-set id)))
                                   (filter (fn [[_ _ attr]] (not= (namespace attr) "app")))
                                   (map (fn [[_ id attr]] [id (namespace attr)]))
                                   (into {})))
        ;; Apply transactions in a particular  order that ensures data compatibility + remove duplicate entries:
        transaction (sort-by (fn [[op _ attr]]
                               (case op 
                                 :db/cas 1
                                 :db.fn/cas 1
                                 :db/retract 2
                                 :db/add (if-not (datomic-relationships attr) 3 4)
                                 :db/retractEntity 5)) 
                             (set transaction))

        ;; Add the "ident" and :db/id to every entity

        new-snapshot (reduce (fn [snapshot allocated-id]
                               (-> snapshot 
                                   (assoc-in [:collections                                              
                                              (entities-by-id allocated-id)
                                              :collection-content
                                              allocated-id
                                              (keyword (entities-by-id allocated-id)
                                                       "ident__")]
                                             true)
                                   (assoc-in [:collections
                                              (entities-by-id allocated-id)
                                              :collection-content
                                              allocated-id
                                              :db/id]
                                             allocated-id)))
                             snapshot allocated-ids)
        ;; Apply transaction entries one by one:
        new-snapshot (reduce 
                      (fn [snapshot transaction]
                        (process-transaction-entry transaction snapshot database datomic-relationships entities-by-id allocated-ids))
                      new-snapshot transaction)

        ;; Update the :collection-uuid-index for each collection that has entities added
        new-snapshot (reduce (fn [snapshot [entity-name allocated-ids]]                          
                               (update-in snapshot [:collections entity-name :collection-uuid-index]
                                          #(merge % 
                                               (into {}
                                                     (map (fn [allocated-id]
                                                            {(get-in snapshot [:collections entity-name :collection-content allocated-id :app/uuid]) allocated-id}) 
                                                          allocated-ids)))))                             
                             new-snapshot (group-by entities-by-id allocated-ids))


        new-snapshot (assoc new-snapshot :ids-to-entity-names entities-by-id)
        new-db (-> database
                   (assoc :inc-max-entity-id ending-id)
                   (assoc :newest-snapshot-idx new-snapshot-idx)
                   (assoc-in [:snapshots new-snapshot-idx] new-snapshot))]
;; TODO: detecting inconsistencies in transactions (multiple :db/add for same entity and attribute with different values, ...)
    (s/assert ::memory-database new-db)
    new-db
 ))

(defrecord MemoryDatabaseDriver [config]
  i/IDatabaseDriver

  (speculate [_ database transaction]
    (apply-transaction database transaction))

  (objects-by-ids [_ database entity ids fetch-inverses?]
    (doseq [id ids] (assert id))
    (let [current-snapshot (get-in database 
                                   [:snapshots
                                    (:newest-snapshot-idx database)])
          content (get-in current-snapshot 
                          [:collections
                           (:name entity)
                           :collection-content])
          all-keys (when (not fetch-inverses?)
                     (get-in database
                             [:keywords-without-inverses-by-name
                              (:name entity)]))
          entities (if fetch-inverses? 
                     (doall (map #(get content %) ids))
                     (doall (map #(select-keys (get content %) all-keys) ids)))]
      entities))

  (objects-by-uuids [this database entity uuids fetch-inverses?]
    (let [index (or (get-in database [:snapshots
                                  (:newest-snapshot-idx database)
                                  :collections
                                  (:name entity)
                                  :collection-uuid-index]) {})]
      (i/objects-by-ids this database entity (keep index uuids) fetch-inverses?)))

  (check-uuids-for-entity [_ database entity-name uuids]
    (let [index (or (get-in database [:snapshots
                                  (:newest-snapshot-idx database)
                                  :collections
                                  entity-name
                                  :collection-uuid-index]) {})]
      (set (filter index uuids))))  

  (id-for-uuid [_ database entity uuid]
    (let [index (or (get-in database [:snapshots
                                  (:newest-snapshot-idx database)
                                  :collections
                                  (:name entity)
                                  :collection-uuid-index]) {})]
      (index uuid)))

  (sync-revision [_ database]
    (:newest-snapshot-idx database))

  (as-of [_ database sync-revision]
    (let [as-of-revision (->> database
                              :snapshots
                              keys
                              (filter #(<= % sync-revision))
                              (concat [1])
                              (apply max))]
      (-> database
          (assoc :newest-snapshot-idx as-of-revision)
          (update :snapshots (fn [snapshots] 
                               (select-keys snapshots 
                                            (filter #(<= % as-of-revision) 
                                                    (keys snapshots))))))))

  (misclassified-objects [this database entity uuids]
    (let [entities (-> database 
                       (get-in [:entities-by-name])
                       (dissoc (:name entity))
                       (vals))]
      (mapcat #(i/objects-by-uuids this database % uuids false) entities)))

  (related-objects [_ database relationship uuids]
    (when (seq uuids)
      (let [source-contents (vals 
                             (get-in database 
                                     [:snapshots
                                      (:newest-snapshot-idx database)
                                      :collections
                                      (:entity-name relationship)
                                      :collection-content]))
            destination-index (->> uuids
                               (select-keys 
                                (get-in database 
                                        [:snapshots
                                         (:newest-snapshot-idx database)
                                         :collections
                                         (:destination-entity relationship)
                                         :collection-uuid-index]))
                               (map (fn [[k v]] {:db/id v 
                                                 :app/uuid k}))
                               (set))
            target-key (t/datomic-name relationship)
            result (if (:to-many relationship)
                     (->> source-contents
                          (filter #(seq (intersection destination-index (or (target-key %) #{}))))
                          (map :app/uuid))
                     (->> source-contents
                          (filter #(destination-index (target-key %)))
                          (map :app/uuid)))]
        result
        )))

  (referenced-objects [_ database relationship uuids]
    (when (seq uuids)
      (let [source-coll (get-in database 
                                [:snapshots
                                 (:newest-snapshot-idx database)
                                 :collections
                                 (:entity-name relationship)])
            source-index (:collection-uuid-index source-coll)
            source-contents (:collection-content source-coll)
            source-objects (->> uuids
                                (select-keys source-index)
                                (vals)
                                (select-keys source-contents)
                                (vals))

            target-key (t/datomic-name relationship)
            result (set (mapcat #(let [rel (target-key %)]
                                     (cond (set? rel) (map :app/uuid rel)
                                           rel [(:app/uuid rel)]
                                           :default []))
                                  source-objects))]
        result)))
  
  (changed-ids [_ database entity sync-revision scoped-ids]
    (let [sync-revision (dec sync-revision)
          content-for-snapshot 
          (fn [current-snapshot]
            (let [content (get-in current-snapshot 
                                  [:collections
                                   (:name entity)
                                   :collection-content])]
              (if scoped-ids 
                (select-keys content scoped-ids)
                content)))

          newest-snapshot-idx (:newest-snapshot-idx database)
          basis-snapshot-idx (->> database
                                  :snapshots
                                  keys
                                  (filter #(<= % sync-revision))
                                  (concat [1])
                                  (apply max))

          delta-snapshots (->> database
                               :snapshots
                               (filter (fn [[idx _]]
                                         (and (> idx sync-revision)
                                              (<= idx newest-snapshot-idx))))
                               (sort-by first)                                    
                               (map second))

          
          basis-snapshot (content-for-snapshot
                          (get-in database [:snapshots basis-snapshot-idx]))

          result (second 
                  (reduce (fn [[last-unchanged-content changed-ids] new-content]
                            (let [old-keys (set (keys last-unchanged-content))
                                  new-keys (set (keys new-content))]
                              [new-content (union changed-ids 
                                                  (difference old-keys new-keys)
                                                  (difference new-keys old-keys)
                                                  (set (filter #(not= (last-unchanged-content %)
                                                                      (new-content %))
                                                               (intersection old-keys new-keys))))])) 
                          [basis-snapshot #{}] (map content-for-snapshot delta-snapshots)))]
      result))

  (uuids-for-ids [_ database ids]
    (let [snapshot (get-in database [:snapshots (:newest-snapshot-idx database)])]
      (map (fn [id]
             (let [entity-name (get-in snapshot [:ids-to-entity-names id])]
               (get-in snapshot [:collections entity-name :collection-content id :app/uuid]))) 
           ids))))



;; External interface

(defn create-db [name entities-by-name]
  {:post [(s/valid? ::memory-database %)]}
  {:db-name name
   :newest-snapshot-idx 1
   :inc-max-entity-id 1
   :entities-by-name entities-by-name
   :keywords-without-inverses-by-name 
   (update-vals entities-by-name
                (fn [entity]
                  (concat
                   [(t/unique-datomic-identifier entity)]
                   (map t/datomic-name 
                        (concat (vals (:attributes entity))
                                (vals (:datomic-relationships entity)))))))
   :snapshots {1 {:collections (into {} (map #(vector % {:collection-content {}
                                                         :collection-uuid-index {}}) (keys entities-by-name)))                  
                  :ids-to-entity-names {}
}}})

(defn truncate-db [database]
  (update database :snapshots #(select-keys % [(:newest-snapshot-idx database)])))


;; get access
  
(s/def ::key qualified-keyword?)
(s/def ::direction #{-1 1})
(s/def ::where-fn fn?)
(s/def ::option #{:case-insensitive :nulls-first :nulls-last})
(s/def ::options (s/coll-of ::option))

(s/def ::sort (s/keys :req-un [::key ::direction] :opt-un [::options]))
(s/def ::sorts (s/nilable (s/coll-of ::sort)))

(s/def ::simple-condition (s/keys :req-un [::key ::where-fn]))
(s/def ::condition-type #{:and :or})
(s/def ::condition (s/or :simple ::simple-condition :composite ::composite-condition))
(s/def ::conditions (s/coll-of ::condition))
(s/def ::composite-condition (s/keys :req-un [::condition-type ::conditions]))

(s/def ::input-condition (s/nilable ::condition))


(defn get-collection [database entity-name]
  (let [coll (get-in database [:snapshots (:newest-snapshot-idx database) :collections entity-name :collection-content])]
    (assert coll (str "No collection with name '" entity-name "' present in database."))
    coll))

(defn get-stable-coll-ids [snapshot entity-name]
  (-> (get-collection snapshot entity-name)
      (keys)
      (sort)))

(defmulti execute-condition (fn [condition _ _ _]  (or (:condition-type condition) :simple)))

(defmethod execute-condition :simple [{:keys [key where-fn]} obj attributes entity-name]
  (assert (attributes key) (str "Unknown attribute key: " key " for entity " entity-name))
  (-> obj key where-fn))

(defmethod execute-condition :and [{:keys [conditions]} obj attributes entity-name]
  (reduce #(and %1 %2) true (map #(execute-condition % obj attributes entity-name) conditions)))

(defmethod execute-condition :or [{:keys [conditions]} obj attributes entity-name]
  (reduce #(or %1 %2) false (map #(execute-condition % obj attributes entity-name) conditions)))

(defn get-paginated-collection 
  ([database entity-name page per-page sorts condition]
   (s/assert ::sorts sorts)
   (s/assert ::input-condition condition)
   (s/assert ::memory-database database)
   (s/assert int? page)
   (s/assert int? per-page)
   (s/assert string? entity-name)
   (let [entity (get-in database [:entities-by-name entity-name])
         _ (assert entity (str "Unknown entity: " entity-name))
         sorts (prewalk #(if (:direction %) 
                           (if (:options %) (update % :options set) (assoc % :options #{}))
                           %) sorts)
         attribute-keys (->> entity
                         :attributes
                         (vals)
                         (map t/datomic-name)
                         (set))
         attributes-by-name (-> entity :attributes)
         sorts (if (seq sorts) 
                 (if (empty? (filter #(= :app/uuid (:key %)) sorts))
                             (concat sorts [{:key :app/uuid :direction 1 :options #{}}])
                             sorts)
                   [{:key :app/createdAt :direction 1 :options #{}}
                    {:key :app/uuid :direction 1 :options #{}}])
         condition  (if-not condition {:key :app/active :where-fn identity}
                            {:condition-type :and 
                             :conditions [condition {:key :app/active :where-fn identity}]})

         comparators (map (fn [{:keys [key direction options]}]
                            (let [string-attr? (= :db.type/string 
                                                  (-> key name attributes-by-name :type))
                                  trafo (if (options :case-insensitive) 
                                          #(some-> % key lower-case)
                                          key)
                                  trafo (comp #(if (uuid? %) (str %) %) trafo)
                                  null-ret (if (options :nulls-first) -1 1)
                                  cmp-with-null (if (not (or (options :nulls-first) (options :nulls-last)))
                                                  compare
                                                  #(cond (and (nil? %1) %2) null-ret
                                                         (and %1 (nil? %2)) (- null-ret)
                                                         :default (compare %1 %2)))
                                  cmp (if (= direction 1) cmp-with-null (comp - cmp-with-null))]
                              (assert (attribute-keys key) (str "Unknown attribute key: " key " for entity " entity-name))
                              (assert (not (and (options :nulls-first) (options :nulls-last))))
                              (when (:case-insensitive options)
                                (assert string-attr? "Only string attributes can be compared in a case insensitive way"))
                              #(cmp (trafo %1) (trafo %2)))) sorts)

         p-compare (fn [l r comparators]
                     (if (empty? comparators) 0
                         (let [[custom-comparator & comparators] comparators
                             compare-value (custom-comparator l r)]
                           (if (zero? compare-value) (recur l r comparators)
                               compare-value))))]
     (->> (get-collection database entity-name)
          (vals)
          (filter #(execute-condition condition % attribute-keys entity-name))
          (sort #(p-compare %1 %2 comparators))
          (map :db/id)
          (drop (* page per-page))
          (take per-page))))
  ([database entity-name page per-page]
   (get-paginated-collection database entity-name page per-page [] nil)))

