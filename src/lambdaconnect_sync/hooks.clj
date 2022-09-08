(ns lambdaconnect-sync.hooks
  (:require [clojure.string :as string]
            [clojure.set :as set]))

(defn get-datomic-relationships [config snapshot]
  (->>  ((:q config)
         '[:find [?i ...] :where
           [?e :db/valueType :db.type/ref]
           [?e :db/ident ?i]]
         snapshot)
        (filter #(not (string/starts-with? (namespace %) "db")))
        set))

(defn- ids-from-map-entry [datomic-relationships entry]
  (let [attributes (-> entry keys set)
        relationships (set/intersection attributes datomic-relationships)
        relationship-ids (->> relationships
                              vec
                              (select-keys entry)
                              vals
                              set)]
    (set/union relationship-ids #{(:db/id entry)})))

(defn get-ids-from-entry [datomic-relationships entry]
  (let [operator (first entry)]
    (cond
      (map? entry)
      (ids-from-map-entry datomic-relationships entry)
      (#{:db/add :db/retract} operator)
      (let [[_ id attr val] entry]
        (if (contains? datomic-relationships attr)
          (set [id val])
          #{id}))
      (#{:db/cas :db.fn/cas} operator)
      (let [[_ id attr old new] entry]
        (if (contains? datomic-relationships attr)
          (set [id old new])
          #{id}))
      (#{:db/retractEntity} operator)
      (let [[_ id] entry]
        #{id})
      :else #{})))

(defn- get-ids-from-transaction [datomic-relationships transaction]
  (->> transaction
       (map (partial get-ids-from-entry datomic-relationships))
       (reduce set/union)
       (filter integer?)
       set))

(defn- get-existing-ids [config snapshot ids]
  ((:q config)
   '[:find [?e ...]
     :in $ [?e ...]
     :where [?e :app/uuid]]
   snapshot ids))

(defn- get-id-to-temporary-id [config snapshot ids]
  (->> ((:q config)
        '[:find ?e ?uuid
          :in $ [?e ...]
          :where [?e :app/uuid ?uuid]]
        snapshot ids)
       (map (fn [[id uuid]] [id (.toString uuid)]))
       (into {})))

(defn get-id-to-correct-id [config snapshot-before snapshot ids]
  (let [ids-existing-in-snapshot-before (->> ids
                                             vec
                                             (get-existing-ids config snapshot-before))
        id-to-existing-id (into {} (map
                                    vector
                                    ids-existing-in-snapshot-before
                                    ids-existing-in-snapshot-before))
        non-existing-ids (->> ids-existing-in-snapshot-before
                              set
                              (set/difference ids)
                              vec)
        id-to-temporary-id (get-id-to-temporary-id config snapshot non-existing-ids)]
    (merge id-to-existing-id id-to-temporary-id)))

(defn update-ids-in-entry [datomic-relationships id-to-correct-id entry]
  (cond
    (map? entry)
    (let [attributes (-> entry keys set)
          relationships (set/intersection attributes datomic-relationships)
          relationships-entry (->> relationships
                                   vec
                                   (select-keys entry)
                                   (map (fn [[k v]] [k (get id-to-correct-id v v)]))
                                   (into {}))
          id-entry (when-let [id (:db/id entry)]
                     {:db/id (get id-to-correct-id id id)})]
      (merge entry relationships-entry id-entry))
    (#{:db/add :db/retract :db/retractEntity} (first entry))
    (let [[_ id attr val] entry
          entry (if (contains? datomic-relationships attr)
                  (conj (pop entry) (get id-to-correct-id val val))
                  entry)]
      (assoc entry 1 (get id-to-correct-id id)))
    (#{:db/cas :db.fn/cas} (first entry))
    (let [[op id attr old-val val] entry]
      (if (contains? datomic-relationships attr)
        (let [correct-old-val (get id-to-correct-id old-val old-val)
              correct-val (get id-to-correct-id val val)]
          [op id attr correct-old-val correct-val])
        entry))
    :else entry))

(defn update-ids-in-transaction [config datomic-relationships transaction snapshot-before snapshot]
  (let [ids (get-ids-from-transaction datomic-relationships transaction)
        id-to-correct-id (get-id-to-correct-id config snapshot-before snapshot ids)
        update-entry (partial update-ids-in-entry datomic-relationships id-to-correct-id)]
    (doall (map update-entry transaction))))