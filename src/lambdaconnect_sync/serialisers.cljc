(ns lambdaconnect-sync.serialisers
  (:require [lambdaconnect-sync.db :as db]
            [clojure.set :as set]
            [lambdaconnect-model.tools :as t]
            [lambdaconnect-sync.db-drivers.datomic :as datomic-driver]
            [lambdaconnect-model.core :as mp]))

(defn- solve-enum [config snapshot enum]
  (if (keyword? enum)
    enum
    (->> enum
         :db/id
         ((:pull config) snapshot [:db/ident])
         :db/ident)))

(defn serialise-collection
  ([config snapshot entity whitelist ids] (serialise-collection config snapshot entity whitelist ids false))

  ([config snapshot entity whitelist ids simple-boolean]
   (let [config (if (:driver config)
                  config
                  (assoc config :driver (datomic-driver/->DatomicDatabaseDriver config)))
         whitelist-fields (fn [o] (apply (partial dissoc o) (set/difference (set (keys o)) (set (concat whitelist ["active" "createdAt" "updatedAt" "uuid"])))))
         objects (db/get-objects-by-ids config entity ids snapshot true)
         proto-object (merge
                       (into {} (map (fn [r] [(t/datomic-name r) (if (:to-many r) [] nil)]) (vals (:relationships entity))))
                       (into {} (map (fn [a] [(t/datomic-name a) (:default-value a)]) (filter #(and (:default-value %) (not (:optional %))) (vals (:attributes entity))))))
         results (map #(-> %
                           (mp/replace-inverses entity)
                           ((partial merge proto-object))
                           (mp/clojure-to-json
                            entity
                            (fn [attr]  (cond (and simple-boolean (= (:type attr) :db.type/boolean)) identity ; different boolean parsing for graphql
                                              (= (:type attr) :db.type/ref) (partial solve-enum config snapshot)
                                              :else (t/inverse-parser-for-attribute attr)))
                            t/inverse-parser-for-relationship)
                           (whitelist-fields)) objects)]
     results)))

(defn serialise-collection-keywords
  ([config snapshot entity whitelist ids] 
   (serialise-collection-keywords config snapshot entity whitelist ids false))
  
  ([config snapshot entity whitelist ids simple-boolean]
   (map (fn [o] (into {} (map (fn [[k v]] [(keyword k) v]) o)))
        (serialise-collection config snapshot entity whitelist ids simple-boolean))))


