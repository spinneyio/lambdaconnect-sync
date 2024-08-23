(ns lambdaconnect-sync.hooks
  (:require [clojure.string :as string]
            [lambdaconnect-sync.db :as db]
            [lambdaconnect-sync.db-drivers.datomic :as datomic-driver]
            [lambdaconnect-model.utils :refer [pmap]]
            [clojure.set :as set]))

(defn get-datomic-relationships [config snapshot]
  (->>  ((:q config)
         '[:find ?i :where
           [?e :db/valueType :db.type/ref]
           [?e :db/ident ?i]]
         snapshot)
        (mapv first)
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

(defn- transaction? 
  "If we can extract ids from something, it is a transaction!"
  [get-ids-from-entry datomic-relationships arg]
  (and (sequential? arg)
       (seq (reduce 
             set/union #{} 
             (map (partial get-ids-from-entry datomic-relationships) 
                  arg)))))

(defn- transactor-function? 
  "Heuristics for transactor functions. 
   We assume that if sth is an unknown vector it is a transactor function call. 
   Then, if one or more of its arguments are transactions, we include these transactions in our computation. "
  [get-ids-from-entry datomic-relationships entry]
  (and (vector? entry) 
       (seq (filter (partial transaction? get-ids-from-entry datomic-relationships) (rest entry)))))

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
      
      (transactor-function? get-ids-from-entry datomic-relationships entry)           
      (reduce set/union #{} 
              (map (fn [subentry] 
                     (if (sequential? subentry)
                       (reduce 
                        set/union #{} 
                        (map (partial get-ids-from-entry datomic-relationships) 
                             subentry))
                       #{})) 
                   (rest entry)))
      :else #{})))

(defn- get-ids-from-transaction [datomic-relationships transaction]
  (->> transaction
       (map (partial get-ids-from-entry datomic-relationships))
       (reduce set/union)
       (filter integer?)
       set))

(defn- get-existing-ids [config snapshot ids]
  (->> ((:q config)
        '[:find ?e
          :in $ [?e ...]
          :where [?e :app/uuid]]
        snapshot ids)
       (mapv first)))

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
      (assoc entry 1 (get id-to-correct-id id id)))
    (#{:db/cas :db.fn/cas} (first entry))
    (let [[op id attr old-val val] entry
          modified-entry (if (contains? datomic-relationships attr)
                           (let [correct-old-val (get id-to-correct-id old-val old-val)
                                 correct-val (get id-to-correct-id val val)]
                             [op (get id-to-correct-id id id) attr correct-old-val correct-val])
                           (assoc entry 1 (get id-to-correct-id id id)))]
      (if (string? (get id-to-correct-id id id))
        ;; If the object did not exist before (a string as an ID) we need to swap cas with add
        [:db/add (get id-to-correct-id id id) attr (get id-to-correct-id val val)]
        modified-entry))
    
    (transactor-function? get-ids-from-entry datomic-relationships entry)
    
    (concat [(first entry)] (map (fn [arg] (if (transaction? get-ids-from-entry datomic-relationships arg)
                                             (map (partial update-ids-in-entry datomic-relationships id-to-correct-id) arg)
                                             arg)) (rest entry)))
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

(defn send-specific-hooks!
  "This function takes a map of {\"entity-name\" {uuid1 obj1 uuid2 obj2 uuid3 obj3]} 
  
  It uses the snapshot to re-fetch all the objects using just their uuids (the obj1 obj2 obj3 etc. are ignored).
  Snapshot would most likely be a speculated db (after applying push transaction).  
  
  It then calls the hook-fun for each object, expecting it to return transaction pairs (message-queue and main db).
  This way the creation/update of push objects can have some db-related side effects.

  The hook functions are instructed of the current state of the speculated db (snapshot), state of the db before speculation (before-snapshot),
  object to consider, user that has triggered the push and finally some shared state that can be used to track advanced changes between calls (necessarily an atom!).

  Finally, if the finaliser function is provided, it is called with final transactions and shared state and provides its own transaction/mq-transaction pair state.
  
  At the very end, a transaction or a map {:transaction mq-transaction} is being returned."
  ([config before-snapshot snapshot interesting-objects entities-by-name user hook-fun] 
   (send-specific-hooks! config before-snapshot snapshot interesting-objects entities-by-name user hook-fun (atom {}) {:transaction [] :mq-transaction []} nil))

  ([config before-snapshot snapshot interesting-objects entities-by-name user hook-fun common-state] 
   (send-specific-hooks! config before-snapshot snapshot interesting-objects entities-by-name user hook-fun common-state {:transaction [] :mq-transaction []} nil))

  ([config before-snapshot snapshot interesting-objects entities-by-name user hook-fun common-state previous-results finaliser-fun]
   (let [config (if (:driver config)
                  config
                  (assoc config :driver (datomic-driver/->DatomicDatabaseDriver config)))
         shared-state (or common-state (atom {}))
         result (reduce #(merge-with concat %1 %2) 
                        {:transaction [] :mq-transaction []} 
                        (map (fn [[entity-name object-dict]]
                               (let [entity (get entities-by-name entity-name)
                                     objects (db/get-objects-by-uuids config entity (or (keys object-dict) []) snapshot)]
                                 (reduce #(merge-with concat %1 %2) 
                                         {:transaction [] :mq-transaction []} 
                                         (pmap (fn [object]
                                                 (let [result (hook-fun user object entity snapshot before-snapshot shared-state)]
                                                   (if (map? result) result {:transaction result :mq-transaction []}))) objects)))) 
                             (vec interesting-objects)))
         result (merge-with concat previous-results result)
         result (if finaliser-fun (finaliser-fun user result snapshot before-snapshot shared-state) result)]
     result)))
