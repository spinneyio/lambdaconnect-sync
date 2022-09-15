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


 (defn- get-ids-attrs-to-vals-from-entry
   "Returns vector of vectors in the form: [[id attr] v]."
   [entry]
   (let [operator (first entry)]
     (cond
       (map? entry)
       (let [id (:db/id entry)]
         (->> entry
              (mapv (fn [[attr v]] [[id attr] v]))))
       (= :db/add operator)
       (let [[_ id attr val] entry]
         [[[id attr] val]])
       (= :db/retract operator)
       (let [[_ id attr _val] entry]
         [[[id attr] nil]])
       (#{:db/cas :db.fn/cas} operator)
       (let [[_ id attr _old new] entry]
         [[[id attr] new]])
       :else [])))

(defn- get-ids-attrs-to-vals-from-transaction [transaction]
  (->> transaction
       (mapcat get-ids-attrs-to-vals-from-entry)
       (into {})))

(defn get-colliding-ids-attrs [transaction1 transaction2]
  (let [ids-attrs-to-vals1 (get-ids-attrs-to-vals-from-transaction transaction1)
        ids-attrs-to-vals2 (get-ids-attrs-to-vals-from-transaction transaction2)
        ids-attrs1 (set (keys ids-attrs-to-vals1))
        ids-attrs2 (set (keys ids-attrs-to-vals2))
        common-ids-attrs (set/intersection ids-attrs1 ids-attrs2)
        not-equal-vals? (fn [id-attr]
                          (let [v1 (get ids-attrs-to-vals1 id-attr)
                                v2 (get ids-attrs-to-vals2 id-attr)]
                            (not= v1 v2)))]
    (filter not-equal-vals? common-ids-attrs)))

(defn update-colliding-entry [colliding-ids-attrs entry]
  (let [ids-attrs-to-vals (get-ids-attrs-to-vals-from-entry entry)
        ids-attrs (->> ids-attrs-to-vals
                       (map first)
                       set)
        colliding-ids-attrs-from-entry (set/intersection
                                        ids-attrs
                                        colliding-ids-attrs)]
    (cond
      (empty? colliding-ids-attrs-from-entry)
      entry
      (map? entry)
      (apply dissoc entry colliding-ids-attrs-from-entry)
      (vector? entry)
      nil
      :else
      nil)))

(defn concat-hooks-push-transactions [hooks-transaction push-transaction]
  (let [colliding-ids-attrs (set (get-colliding-ids-attrs
                                  hooks-transaction
                                  push-transaction))]
    (if (empty? colliding-ids-attrs)
      (concat hooks-transaction push-transaction)
      (let [update-entry (partial update-colliding-entry colliding-ids-attrs)
            updated-push-transaction (keep update-entry push-transaction)]
        (concat hooks-transaction updated-push-transaction)))))