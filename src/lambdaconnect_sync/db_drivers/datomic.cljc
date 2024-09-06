(ns lambdaconnect-sync.db-drivers.datomic
  (:require [lambdaconnect-sync.db-interface :as i]
            [lambdaconnect-model.tools :as t]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as sets]))

(defn invoke [f & args] (apply f args))

;; See the interface for explanation of all the methods
;; Datomic API comes in two varieties, peer and clinet.
;; They are identical but come from a different namespace. Therefore config is still needed to 
;; use the right ones.

(defrecord DatomicDatabaseDriver [config]
  i/IDatabaseDriver
  (speculate [this snapshot transaction]
    (:db-after ((:with config) snapshot transaction)))

  (objects-by-ids [_ snapshot entity ids fetch-inverses?]
    (let [datomic-relationships (set (vals (:datomic-relationships entity)))
          relationships (set (vals (:relationships entity)))
          inverses (sets/difference relationships datomic-relationships)
          fields-to-fetch (set (concat [:db/id]
                                       (map #(keyword "app" %) t/special-attribs)
                                       (map t/datomic-name (vals (:attributes entity)))
                                       (map (fn [r] {[(t/datomic-name r) :limit nil] [:app/uuid :db/id]})
                                            datomic-relationships)
                                       (if fetch-inverses?
                                         (map (fn [r] {[(t/datomic-inverse-name r) :limit nil] [:app/uuid :db/id]})
                                              inverses) [])))
          ret-val ((:pull-many config) snapshot fields-to-fetch ids)]
      ret-val))

  (objects-by-uuids [this snapshot entity uuids fetch-inverses?]
    (let [ids (->> ((:q config)
                   '[:find ?e
                     :in $ [?uuid ...]
                     :where [?e :app/uuid ?uuid]]
                   snapshot uuids)
                  (mapv first))]
     (i/objects-by-ids this snapshot entity ids fetch-inverses?)))

  (check-uuids-for-entity [_ snapshot entity-name uuids]
    (when (seq uuids)
      (let [entity-keyword (keyword entity-name "ident__")]
        (->> ((:q config)
              '[:find ?uuid
                :in $ [?uuid ...] ?attr
                :where
                [?e :app/uuid ?uuid]
                [?e ?attr]]
              snapshot uuids entity-keyword)
             (mapv first)
             set))))  

  (id-for-uuid [_ snapshot entity uuid]
    (when uuid
      (assert entity)
      (:db/id ((:pull config) snapshot '[:db/id] [:app/uuid uuid]))))

  (sync-revision [_ snapshot]
    ((:basis-t config) snapshot))

  (as-of [_ snapshot sync-revision]
    ((:as-of config) snapshot sync-revision))

  (misclassified-objects [_ snapshot entity uuids]
    (->> ((:q config)
          '[:find (pull ?e [*])
            :in $ ?id [?uuid ...]
            :where
            [?e :app/uuid ?uuid]
            (not [?e ?id])]
          snapshot (t/unique-datomic-identifier entity) uuids)
         (mapv first)))

  (related-objects [_ snapshot relationship uuids]
    (when (seq uuids)
      (->> ((:q config)
            '[:find ?result
              :in $ [?uuid ...] ?relationship
              :where 
              [?target :app/uuid ?uuid]
              [?source ?relationship ?target]
              [?source :app/uuid ?result]]
            snapshot uuids (t/datomic-name relationship))
           (mapv first))))

  (referenced-objects [_ snapshot relationship uuids]
    (when (seq uuids)
      (->> ((:q config)
            '[:find ?result
              :in $ [?uuid ...] ?relationship
              :where 
              [?source :app/uuid ?uuid]
              [?source ?relationship ?target]
              [?target :app/uuid ?result]]
            snapshot uuids (t/datomic-name relationship))
           (mapv first))))
  
  (changed-ids [this snapshot entity sync-revision scoped-ids]
    (let [name  (t/unique-datomic-identifier entity)
          db-now snapshot
          db-changes ((:history config) snapshot)
          changed-object-ids (if scoped-ids
                               ((:q config)
                                '[:find ?e
                                  :in $ $changes ?defining-name ?since [?e ...] ?tx->t
                                  :where
                                  ($changes or
                                            [?e _ _ ?tx]
                                            [_ _ ?e ?tx])
                                  [(lambdaconnect-sync.db-drivers.datomic/invoke ?tx->t ?tx) ?t]
                                  [(>= ?t ?since)]]
                                db-now db-changes name sync-revision scoped-ids (:tx->t config))
                               ((:q config)
                                '[:find ?e
                                  :in $ $changes ?defining-name ?since ?tx->t
                                  :where
                                  [$ ?e ?defining-name]
                                  ($changes or
                                            [?e _ _ ?tx]
                                            [_ _ ?e ?tx])
                                 [(lambdaconnect-sync.db-drivers.datomic/invoke ?tx->t ?tx) ?t]
                                  [(>= ?t ?since)]]
                                db-now db-changes name sync-revision (:tx->t config)))]
      (mapv first changed-object-ids)))

  (uuids-for-ids [_ snapshot ids]
      (->> ((:q config)
        '[:find ?uuid
          :in $ [?id ...]
          :where
          [?id :app/uuid ?uuid]]
        snapshot ids)
       (mapv first))))

