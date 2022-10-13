(ns lambdaconnect-sync.db
  (:require [lambdaconnect-model.tools :as t]
            [clojure.set :as sets]))

(defn invoke [f & args] (apply f args))

(defn get-objects-by-ids
  ([config entity ids snapshot] 
   (get-objects-by-ids config entity ids snapshot false))
  ([config entity ids snapshot fetch-inverses]
   (let [datomic-relationships (set (vals (:datomic-relationships entity)))
         relationships (set (vals (:relationships entity)))
         inverses (sets/difference relationships datomic-relationships)
         fields-to-fetch (set (concat [:db/id]
                                      (map #(keyword "app" %) t/special-attribs)
                                      (map t/datomic-name (vals (:attributes entity)))
                                      (map (fn [r] {(t/datomic-name r) [:app/uuid :db/id]})
                                           datomic-relationships)
                                      (if fetch-inverses
                                        (map (fn [r] {(t/datomic-inverse-name r) [:app/uuid :db/id]})
                                             inverses) [])))
         ret-val ((:pull-many config) snapshot fields-to-fetch ids)]
     ret-val)))

(defn get-objects-by-uuids
  ([config entity uuids snapshot] 
   (get-objects-by-uuids config entity uuids snapshot false))
  ([config entity uuids snapshot fetch-inverses]
   (let [ids (->> ((:q config)
                   '[:find ?e
                     :in $ [?uuid ...]
                     :where [?e :app/uuid ?uuid]]
                   snapshot uuids)
                  (mapv first))]
     (get-objects-by-ids config entity ids snapshot fetch-inverses))))

(defn get-misclasified-objects [config snapshot entity uuids]
  (->> ((:q config)
        '[:find ?uuid
          :in $ ?id [?uuid ...]
          :where
          [?e :app/uuid ?uuid]
          (not [?e ?id])]
        snapshot (t/unique-datomic-identifier entity) uuids)
       (mapv first)))

(defn get-related-objects
  "Fetches the objects that refer to the list of uuids by their relationship"
  [config relationship uuids snapshot]
  (when (seq uuids)
    (->> ((:q config)
          '[:find ?result
            :in $ [?uuid ...] ?relationship
            :where [?target :app/uuid ?uuid]
            [?source ?relationship ?target]
            [?source :app/uuid ?result]]
          snapshot uuids (t/datomic-name relationship))
         (mapv first))))

(defn get-referenced-objects
  [config relationship uuids snapshot]
  (when (seq uuids)
    (->> ((:q config)
          '[:find ?result
            :in $ [?uuid ...] ?relationship
            :where [?source :app/uuid ?uuid]
            [?source ?relationship ?target]
            [?target :app/uuid ?result]]
          snapshot uuids (t/datomic-name relationship))
         (mapv first))))

(defn get-reciprocal-objects
  [config relationship inverse uuids snapshot]
  (when (seq uuids)
    (->> (if relationship
           ((:q config)
            '[:find ?result
              :in $ [?uuid ...] ?relationship
              :where
              [?target :app/uuid ?uuid]
              [?source ?relationship ?target]
              [?source :app/uuid ?result]]
            snapshot uuids (t/datomic-name relationship))

           ((:q config)
            '[:find ?result
              :in $ [?uuid ...] ?relationship
              :where
              [?target :app/uuid ?uuid]
              [?target ?relationship ?source]
              [?source :app/uuid ?result]]
            snapshot uuids (t/datomic-name inverse)))
         (mapv first))))


(defn get-changed-ids
  ([config
    snapshot
    entity-name
    transaction-since-number
    entities-by-name]
   (get-changed-ids config snapshot entity-name transaction-since-number entities-by-name nil))
  ([config
    snapshot
    entity-name
    transaction-since-number
    entities-by-name
    scoped-ids]
   (let [name (get (t/defining-attributes entities-by-name) entity-name)
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
                                 [(lambdaconnect-sync.db/invoke ?tx->t ?tx) ?t]
                                 [(>= ?t ?since)]]
                               db-now db-changes name transaction-since-number scoped-ids (:tx->t config))
                              ((:q config)
                               '[:find ?e
                                 :in $ $changes ?defining-name ?since ?tx->t
                                 :where
                                 [$ ?e ?defining-name]
                                 ($changes or
                                           [?e _ _ ?tx]
                                           [_ _ ?e ?tx])
                                 [(lambdaconnect-sync.db/invoke ?tx->t ?tx) ?t]
                                 [(>= ?t ?since)]]
                               db-now db-changes name transaction-since-number (:tx->t config)))]
     (mapv first changed-object-ids))))

(defn uuids-for-ids [config snapshot ids]
  (->> ((:q config)
        '[:find ?uuid
          :in $ [?id ...]
          :where
          [?id :app/uuid ?uuid]]
        snapshot ids)
       (mapv first)))
