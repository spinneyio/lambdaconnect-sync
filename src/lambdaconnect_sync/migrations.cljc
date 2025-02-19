(ns lambdaconnect-sync.migrations
  (:require [clojure.set :as s]
            [lambdaconnect-model.tools :as t]))

(defn model-migration-tx-builder
  "For each entity a function that takes a datomic object as an argument and produces
   migration transatcion, eg. {:db/id 4 :NOUser/name \"aa\"} -> [[:db/add 4 :NOUser/address \"7 Way Street\"]]

  The result takes a form:

  {\"NOUser\" f1
  \"NOAddress\" f2 ...}

  "
  [old-entities-by-name
   entities-by-name]
  (->> entities-by-name
       (map (fn [[entity-name entity]]
              ;; Relationship validation
              (let [old-rels (-> old-entities-by-name
                                 (get entity-name)
                                 :relationships
                                 (vals)
                                 (set))
                    new-rels (-> entity
                                 :relationships
                                 (vals)
                                 (set))
                    diff (s/difference old-rels new-rels)]
                (when-not (empty? diff)
                  (throw (ex-info (str "Migration failed: Relationships can never be modified or removed. Entity: '"
                                       entity-name
                                       "' edited or missing relationships '" diff "'.")
                                  {:old-entities-by-name old-entities-by-name
                                   :entities-by-name entities-by-name
                                   :entity entity
                                   :difference diff}))))
              ;; Relationship optionality validation
              (doseq [[relationship-name relationship] (:relationships entity)]
                (when-not (:optional relationship)
                  (throw (ex-info (str "Migration failed: Non-optional relationships are not allowed. Entity: '"
                                       entity-name
                                       "' relationship '" relationship-name "'.")
                                  {:old-entities-by-name old-entities-by-name
                                   :entities-by-name entities-by-name
                                   :entity entity
                                   :relationship relationship}))))
              ;; Attributes

              (let [old-attrs (-> old-entities-by-name
                                  (get entity-name)
                                  :attributes)
                    new-attrs (:attributes entity)
                    old-names (-> old-attrs keys set)
                    new-names (-> new-attrs keys set)
                    diff (s/difference old-names new-names)]
                ;; No removal
                (when-not (empty? diff)
                  (throw (ex-info (str "Migration failed: Attributes can never be removed. Entity: '"
                                       entity-name
                                       "' removed attributes '" diff "'.")
                                  {:old-entities-by-name old-entities-by-name
                                   :entities-by-name entities-by-name
                                   :entity entity
                                   :difference diff})))
                ;; Modified attributes
                (doseq [[attribute old-attribute] (map (fn [n] [(get old-attrs n)
                                                                (get new-attrs n)])
                                                       (s/intersection old-names new-names))]
                  (assert attribute old-attribute)
                  ;; Warning - migrations do not ever fail for validation changes
                  ;; It is the devloper's responsibility to make sure new validation
                  ;; logic is compatible with the old model.
                  (let [unmodifiable-keys [:optional
                                           :type]]
                    (when (not= (select-keys attribute unmodifiable-keys)
                                (select-keys old-attribute unmodifiable-keys))
                      (throw (ex-info
                              (str "Migration failed: [:optional :type] of an attribute cannot be changed. Entity: '"
                                   entity-name
                                   "' attribute '" (:name attribute) "'.")
                              {:old-entities-by-name old-entities-by-name
                               :entities-by-name entities-by-name
                               :entity entity
                               :attribute attribute})))))
                ;; New attributes               
                (let [old-entity (get old-entities-by-name entity-name)]
                  [entity-name
                   ;; we only check/migrate new attributes for exitsting entities
                   (when old-entity
                     (->>
                      (s/difference new-names old-names)
                      (map #(get-in entity [:attributes %]))
                      (keep (fn [added]
                              (when (and (not (:optional added))
                                         (not (:default-value added)))
                                (throw (ex-info (str "Migration failed: New non-optional attributes must have a default value. Entity: '"
                                                     entity-name
                                                     "' attribute '" (:name added) "'.")
                                                {:old-entities-by-name old-entities-by-name
                                                 :entities-by-name entities-by-name
                                                 :entity entity
                                                 :attribute added})))
                              (when (not (:optional added))
                                (fn [obj] [:db/add
                                           (:db/id obj)
                                           (t/datomic-name added)
                                           (:default-value added)]))))
                      (#(when (seq %) (apply juxt %)))))]))))
       (filter second)
       (into {})))
