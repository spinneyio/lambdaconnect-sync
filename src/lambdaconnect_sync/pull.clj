(ns lambdaconnect-sync.pull
  (:require [clojure.algo.generic.functor :as functor]
            [lambdaconnect-model.tools :as t]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-model.scoping :as scoping]
            [lambdaconnect-sync.utils :as u]
            [lambdaconnect-sync.db :as db]))

(defn scoped-object
  "Gets object in datomic format but with relationships straightened (this is a hashmap not datomic object now): 
  {:app/updatedAt #inst \"2020-05-28T10:02:14.917-00:00\", 
   :NOUser/usesImperialUnits false, 
   :app/createdAt #inst \"2020-05-28T10:02:14.917-00:00\", 
   :NOUser/gender \"Other\", 
   :NOUser/fullName \"Julek Cesar\", 
   :NOUser/dietitian [{:db/id 17592186045671, :app/uuid #uuid \"ea0b2e3b-d245-448e-ac17-54d97d20c63e\"}], 
   :app/active true, 
   :NOUser/email \"lukasz.kepka+80@spinney.io\", 
   :db/id 17592186045670, :app/uuid #uuid \"61545252-b6da-4431-b958-47b436fddf32\", 
   :NOUser/birthDate #inst \"2020-05-28T09:58:21.862-00:00\", 
   :NOUser/internalUserId #uuid \"5ecf8a64-777d-4c2d-a28c-9f9e69cf92ed\"}"
  [object
   entities-by-name
   entity
   scoping-edn ; as defined in resources/model/pull-scope.edn
   scoped-tags ; {:NOUser.me #{1 2 3}, :NOMessage.mine #{4 5 6}} (:db/id 's are the values in sets)
   scoped-ids  ; {:NOUser #{1 2 3}, :NOMessage #{4 5 6}} (:db/id 's are the values in sets)
   tags-by-ids ; {1 #{:NOUser.me} 2 #{:NOUser.me} ... }
   scoping-constants ; {:constant/is-here true :constant-hello (atom false)}
   ]
  (assert object "The object cannot be nil")
  (if (nil? scoping-edn) object
      (let [tags (get tags-by-ids (:db/id object))
            replacements (reduce merge {} (map #(:replace-fields (% scoping-edn)) tags))]
        (assert (seq tags) (str "We require tags assigned to the object at this stage: " object))
        (assert (not (nil? scoped-ids)))
        (assert (not (nil? tags-by-ids)))
        (into {} (map (fn [[key value]]
                        (let [n (name key)
                              attr (get (:attributes entity) n)
                              rel (get (:relationships entity) n)]
                          (cond attr (if-let [replacement (get replacements (:name attr))]
                                       (let [replacement (cond (and (keyword? replacement) (= (namespace replacement) "constant"))                                                                  
                                                               (as-> (replacement scoping-constants) rs
                                                                 (do 
                                                                   (assert rs (str "Constant: " replacement " not found in constants map."))
                                                                   (if (delay? rs) @rs rs)))
                                                               (keyword? replacement) 
                                                               (let [r-attr (get (:attributes entity) (name replacement))]
                                                                 (assert r-attr (str "Wrong replacement rule: " (:name attr) " -> " replacement))
                                                                 (get object (keyword (:name entity) (:name r-attr))))
                                                               :default replacement)]
                                         [key ((t/parser-for-attribute attr) replacement)])
                                       [key value])
                                rel (let [scoped-targets (or (get scoped-ids (keyword (:destination-entity rel))) #{})]
                                     ; (println "\n\nSCOPED TARGETS: " key (:name rel) scoped-targets value)
                                      (if (:to-many rel)
                                        [key (filter #(and scoped-targets (-> % :db/id scoped-targets)) value)]
                                        [key (when (and value (-> (if (map? value) value (first value)) :db/id scoped-targets)) value)]))
                                :else [key value]))) object)))))

; incoming json: {"FIUser" 123 "FIGame" 344} is a dictionary with entity names as keys

(defn pull
  ([config
    incoming-json
    internal-user
    snapshot
    entities-by-name
    scoping-edn ; as defined in resources/model/pull-scope.edn (or nil for no scoping)
    ]
   (pull config
         incoming-json
         internal-user
         snapshot
         entities-by-name
         scoping-edn 
         {}))
  ([config
    incoming-json
    internal-user
    snapshot
    entities-by-name
    scoping-edn ; as defined in resources/model/pull-scope.edn (or nil for no scoping)
    scoping-constants]
   (let [scoped-tags (when scoping-edn (scoping/scope config snapshot internal-user entities-by-name scoping-edn false (set (keys scoping-edn)) scoping-constants)) ; {:NOUser.me #{1 2 3}, :NOMessage.mine #{4 5 6}} (:db/id 's are the values in sets)
         scoped-ids   (when scoped-tags (scoping/reduce-entities scoped-tags)) ; {:NOUser #{1 2 3}, :NOMessage #{4 5 6}} (:db/id 's are the values in sets)
         tags-by-ids (when scoped-tags (functor/fmap (fn [tags] (set (map second tags))) ; inverses the map, values are now sets
                                                     (group-by first
                                                               (u/mapcat (fn [[tag ids]] (map (fn [id] [id tag]) ids)) scoped-tags))))
         objects (pmap (fn [[entity-kw sync-revision]]
                         (let [entity-name (name entity-kw)
                               entity (get entities-by-name entity-name)]
                           (assert entity (str "You are using an outdated version of the app. The DB model does not contain entity: " entity-kw))
                           (let [entity-scoped-ids (if (nil? scoping-edn)
                                                     (get scoped-ids (keyword entity-name))
                                                     (or (get scoped-ids (keyword entity-name)) []))
                                 modified-ids (db/get-changed-ids config snapshot entity-name sync-revision entities-by-name entity-scoped-ids)]
                             [entity-name  (db/get-objects-by-ids config entity modified-ids snapshot true)])))
                       (vec incoming-json))]
     (into {} (map (fn [[entity-name objs]]
                     (let [entity (get entities-by-name entity-name)
                                        ; we create all the relationships as empty and later overwrite 
                           proto-object (merge (into {} (map (fn [r] [(mp/datomic-name r) (if (:to-many r) [] nil)]) (vals (:relationships entity))))
                                               {:syncRevision ((:basis-t config) snapshot)}
                                               (into {} (map (fn [a] [(mp/datomic-name a) (:default-value a)]) (filter :default-value (vals (:attributes entity))))))]
                       [entity-name (pmap #(-> %
                                               (mp/replace-inverses entity)
                                               ((partial merge proto-object))
                                               (scoped-object entities-by-name entity scoping-edn scoped-tags scoped-ids tags-by-ids scoping-constants)
                                               (mp/clojure-to-json entity))
                                          objs)])) objects)))))

