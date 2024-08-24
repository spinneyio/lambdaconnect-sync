(ns lambdaconnect-sync.db-drivers.memory
  (:require [lambdaconnect-model.data-xml :as xml]
            [lambdaconnect-sync.db-interface :as i]
            [lambdaconnect-model.tools :as t]
            [lambdaconnect-model.utils :as u]
            [clojure.set :refer [intersection union difference]]
            #?(:clj [clojure.spec.alpha :as s] 
               :cljs [cljs.spec.alpha :as s])))

;; A simple in-memory database driver.

;; Database spec

(s/def ::entity-id pos-int?)
(s/def ::max-entity-id pos-int?)
;; we encode relationships as sets
(s/def ::entity map?)
(s/def ::collection-content (s/map-of ::entity-id ::entity))
(s/def ::collection-uuid-index (s/map-of uuid? ::entity-id))

(s/def ::collection (s/keys :req-un [::collection-content ::collection-uuid-index]))
(s/def ::collections (s/map-of string? ::collection))

(s/def ::entities-by-name (s/map-of :lambdaconnect-model.data-xml/entity-name
                                    :lambdaconnect-model.data-xml/entity))

(s/def ::keywords-without-inverses-by-name (s/map-of string? (s/coll-of (s/and keyword? namespace))))

(s/def ::snapshot 
  (s/keys :req-un 
          [::collections
           ::entities-by-name
           ::keywords-without-inverses-by-name])) 

(s/def ::newest-snapshot-idx pos-int?)
(s/def ::snapshots (s/map-of pos-int? ::snapshot))
(s/def ::db-name string?)

(s/def ::memory-database
  (s/keys :req-un 
          [::newest-snapshot-idx
           ::snapshots
           ::max-entity-id
           ::db-name]))

;; Transaction spec

(s/def ::transaction-entity-id (s/or :created string?
                                     :existing ::entity-id))

(defmulti transaction-entry-type first)

(defmethod transaction-entry-type :db/add [_]
  (s/tuple #{:db/add} ::transaction-entity-id keyword? any?))

(defmethod transaction-entry-type :db/retract [_]
  (s/tuple #{:db/retract} ::entity-id keyword? any?))

(defmethod transaction-entry-type :db/cas [_]
  (s/tuple #{:db/cas} ::transaction-entity-id keyword? any? any?))

(defmethod transaction-entry-type :db.fn/cas [_]
  (s/tuple #{:db.fn/cas} ::transaction-entity-id keyword? any? any?))

(defmethod transaction-entry-type :db/retractEntity [_]
  (s/tuple #{:db/retractEntity} ::entity-id))

(defmethod transaction-entry-type nil [_]
  (s/map-of keyword? any?))

(s/def ::transaction-entry (s/multi-spec transaction-entry-type first))
(s/def ::transaction (s/coll-of ::transaction-entry))

(defn create-db [name entities-by-name]
  {:post [(s/valid? ::memory-database %)]}
  {:db-name name
   :newest-snapshot-idx 1
   :max-entity-id 1
   :snapshots {1 {:collections {}
                  :entities-by-name entities-by-name
                  :keywords-without-inverses-by-name 
                  (update-vals entities-by-name
                               (fn [entity]
                                 (map t/datomic-name 
                                      (concat (vals (:attributes entity))
                                              (vals (:datomic-relationships entity))))))}}})

(defn apply-transaction 
  [snapshot transaction]
  {:pre [(s/valid? ::memory-database snapshot) (s/valid? ::transaction transaction)]
   :post [(s/valid? ::memory-database %)]}
  ;; stub
  snapshot)

(defrecord MemoryDatabaseDriver [config]
  i/IDatabaseDriver
  (speculate [_ database transaction]
    (apply-transaction database transaction))

  (objects-by-ids [_ database entity ids fetch-inverses?]
    (let [current-snapshot (get-in [:snapshots
                                    (:newest-snapshot-idx database)])
          content (get-in current-snapshot 
                          [:collections
                           (:name entity)
                           :collection-content])
          all-keys (when (not fetch-inverses?)
                     (get-in current-snapshot
                             [:keywords-without-inverses-by-name
                              (:name entiry)]))

          entities (if fetch-inverses? 
                     (map #(get-in content %) ids)
                     (map #(select-keys (get-in content %) all-keys)))]
      entities))

  (objects-by-uuids [this database entity uuids fetch-inverses?]
    (let [index (get-in database [:snapshots
                                  (:newest-snapshot-idx database)
                                  :collections
                                  (:name entity)
                                  :collection-uuid-index])]
      (i/objects-by-ids this database entity (map index uuids) fetch-inverses?)))

  (check-uuids-for-entity [_ database entity-name uuids]
    (let [content (get-in database [:snapshots
                                    (:newest-snapshot-idx database)
                                    :collections
                                    (:name entity)
                                    :collection-content])]
    (filter content uuids)))  

  (id-for-uuid [_ database entity uuid]
    (let [index (get-in database [:snapshots
                                  (:newest-snapshot-idx database)
                                  :collections
                                  (:name entity)
                                  :collection-uuid-index])]
      (index uuid)))

  (sync-revision [_ database]
    (:newest-snapshot-idx database))

  (as-of [_ database sync-revision]
    (assert (<= sync-revision (:newest-snapshot-idx database)))
    (let [basis-revision (->> database
                              :snapshots
                              keys
                              (filter #(<= sync-revision %))
                              (concat [1])
                              (apply max))]
      (-> database
          (assoc :newest-snapshot-idx basis-revision)
          (update :snapshots (fn [snapshots] 
                               (select-keys snapshots 
                                            (filter #(<= % basis-revision) 
                                                    (keys snapshots))))))))

  (misclassified-objects [this database entity uuids]
    (let [entities (-> database 
                       (get-in [:snapshots
                                (:newest-snapshot-idx database)
                                :entities-by-name])
                       (dissoc (:name entity))
                       (vals))]
      (u/mapcat #(i/objects-by-uuids this database % uuids false) entities)))

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
                                         (:entity-name relationship)
                                         :collection-uuid-index]))
                               (map #(hash-map :db/id %2 :app/uuid %1))
                               (set))
            target-key (t/datomic-name relationship)]
        (if (:to-many relationship)
          (->> source-contents
               (filter #(seq (intersection destination-index (or (target-key %) #{}))))
               (map :app/uuid))
          (->> source-contents
               (filter #(destination-index (target-key %)))
               (map :app/uuid))))))

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
            target-key (t/datomic-name relationship)]
        (set (u/mapcat #(let [rel (target-key %)]
                          (cond (sequential? rel) (map :app/uuid rel)
                                rel [rel]
                                :default []))
                       source-objects)))))
  
  (changed-ids [_ database entity sync-revision scoped-ids]
    (let [newest-snapshot-idx (:newest-snapshot-idx database)
          snapshots-to-consider (->> database
                                    :snapshots
                                    (filter #(let [idx (first %)]
                                               (and (> sync-revision idx))
                                                  (>= newest-snapshot-idx idx)))
                                    (map second))

          content-for-snapshot (fn [current-snapshot]
                                 (let [content (get-in current-snapshot 
                                                       [:collections
                                                        (:name entity)
                                                        :collection-content])]
                                   (if scoped-ids 
                                     (select-keys content scoped-ids)
                                     content)))
          
          basis-revision (->> database
                              :snapshots
                              keys
                              (filter #(<= sync-revision %))
                              (concat [1])
                              (apply max))

          basis-snapshot-content (content-for-snapshot
                                  (get-in database [:snapshots basis-revision]))]
      (second 
       (reduce (fn [[last-unchanged-content changed-ids] new-content]
                 (let [old-keys (set (keys last-unchanged-content))
                       new-keys (set (keys new-content))]
                   [new-content (union changed-ids 
                                       (difference old-keys new-keys)
                                       (difference new-keys old-keys)
                                       (set (filter #(not= (last-unchanged-content %)
                                                           (new-content %))
                                                    (intersection old-keys new-keys))))])) 
               [basis-snapshot-content #{}] (map content-for-snapshot snapshots-to-consider)))))

;; this could be tremendously accelerated if additional indices were added
  (uuids-for-ids [_ database ids]
    (let [mega-collection (->> (get-in database [:snapshots 
                                                 (:newest-snapshot-idx database) 
                                                 :collections])
                               (vals)
                               (map :collection-content)
                               (apply u/merge))]
      (map #(-> % mega-collection :app/uuid) ids))))

