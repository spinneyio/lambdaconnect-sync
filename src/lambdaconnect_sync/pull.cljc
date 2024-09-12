(ns lambdaconnect-sync.pull
  (:require [lambdaconnect-model.tools :as t]
            [lambdaconnect-model.utils :refer [update-vals merge pmap]]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-model.scoping :as scoping]
            [lambdaconnect-sync.db-drivers.datomic :as datomic-driver]
            [clojure.pprint :refer [pprint]]
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
   scoping-constants ; {:constant/is-here true :constant-hello (delay false)}
   ]
  (assert object "The object cannot be nil")
  (if (nil? scoping-edn) object
      (let [tags (get tags-by-ids (:db/id object))
            replacements (reduce merge {} (map #(:replace-fields (% scoping-edn)) tags))]
        (assert (seq tags) (str "We require tags assigned to the object at this stage: " object))
        (assert (not (nil? scoped-ids)))
        (assert (not (nil? tags-by-ids)))
        (update-vals object 
                     (fn [key value]
                       (let [n (name key)
                             attr (get (:attributes entity) n)
                             rel (get (:relationships entity) n)]
                         (cond attr (if-let [replacement (get replacements (keyword (:name attr)))]
                                      (let [replacement-fn (fn replacement-fn [replacement]
                                                             (cond (and (keyword? replacement) (= (namespace replacement) "constant"))                                                                  
                                                                   (as-> ((-> replacement name keyword) scoping-constants) rs
                                                                     (do 
                                                                       (assert rs (str "Constant: " replacement " not found in constants map."))
                                                                       (replacement-fn (if (delay? rs) @rs rs))))
                                                                   (keyword? replacement) 
                                                                   (let [r-attr (get (:attributes entity) (name replacement))]
                                                                     (assert r-attr (str "Wrong replacement rule: " (:name attr) " -> " replacement))
                                                                     (get object (keyword (:name entity) (:name r-attr))))
                                                                   :default (when-not (nil? replacement)
                                                                              ((t/parser-for-attribute attr) replacement))))]
                                        (replacement-fn replacement))
                                      value)
                               rel (let [scoped-targets (or (get scoped-ids (keyword (:destination-entity rel))) #{})]
                                        ; (println "\n\nSCOPED TARGETS: " key (:name rel) scoped-targets value)
                                     (if (:to-many rel)
                                       (filter #(and scoped-targets (-> % :db/id scoped-targets)) value)
                                       (when (and value (-> (if (map? value) value (first value)) :db/id scoped-targets)) value)))
                               :else value)))))))

; incoming json: {"FIUser" 123 "FIGame" 344} is a dictionary with entity names as keys

(defn- pull-internal
  [{:keys [config
           incoming-json
           internal-user
           snapshot
           entities-by-name
           scoping-edn ; as defined in resources/model/pull-scope.edn (or nil for no scoping) 
           scoping-constants
           slave-mode? ;; If set to true, the syncRevision will be coming from saved db values and not from snapshot's get-sync-revision
           ]}]
  (assert config)
  (assert incoming-json)
  (assert snapshot)
  (assert entities-by-name)
  (assert (or (not scoping-constants) (and scoping-edn (fn? scoping-constants) (not (:constants scoping-edn)))))
  (try    
    (let [scoping-edn-with-constants
          (if scoping-constants 
            (assoc scoping-edn :constants (scoping-constants snapshot internal-user))
            scoping-edn)
          mapping-fun pmap ;; map for debug, pmap for production
          sync-revision (db/get-sync-revision config snapshot)
        [scoped-tags scoped-ids tags-by-ids] 
          (when scoping-edn
            (let [scoped-tags (scoping/scope config snapshot internal-user entities-by-name scoping-edn-with-constants false (set (keys scoping-edn)))]
              [scoped-tags (scoping/reduce-entities scoped-tags) (scoping/inverse-entities scoped-tags)]))
          
          objects (mapping-fun (fn [[entity-kw requested-sync-revision]]
                                 (let [entity-name (name entity-kw)
                                       entity (get entities-by-name entity-name)]
                                   (assert entity (str "You are using an outdated version of the app. The DB model does not contain entity: " entity-kw))
                                   (let [entity-scoped-ids (if (nil? scoping-edn)
                                                             (get scoped-ids (keyword entity-name))
                                                             (or (get scoped-ids (keyword entity-name)) []))
                                         modified-ids (db/get-changed-ids config snapshot entity-name requested-sync-revision entities-by-name entity-scoped-ids)]
                                     [entity-name (db/get-objects-by-ids config entity modified-ids snapshot true)])))
                               incoming-json)]
      (into {} (doall 
                (map (fn [[entity-name objs]]
                       (let [entity (get entities-by-name entity-name)
                                        ; we create all the relationships as empty and later overwrite 
                             proto-object (merge (into {} (map (fn [r] [(mp/datomic-name r) (if (:to-many r) [] nil)]) (vals (:relationships entity))))
                                                 (when-not slave-mode? {:syncRevision sync-revision})
                                                 (into {} (map (fn [a] [(mp/datomic-name a) (:default-value a)]) (filter :default-value (vals (:attributes entity))))))]
                        [entity-name (doall (mapping-fun #(-> %
                                                              (mp/replace-inverses entity)
                                                              ((partial merge proto-object))
                                                              (scoped-object entities-by-name entity scoping-edn scoped-tags scoped-ids tags-by-ids (:constants scoping-edn-with-constants))
                                                              (mp/clojure-to-json entity)
                                                              (as-> obj (if slave-mode?
                                                                          (if-let [sr (:app/syncRevisionFromMaster %)]
                                                                            (assoc obj "syncRevision" sr)
                                                                            obj)
                                                                          obj)))
                                                  objs))])) objects))))
    (catch #?(:clj java.util.concurrent.ExecutionException :cljs js/Error) e (throw #?(:clj (.getCause e) :cljs e)))))
  
  
(defn pull 
  ([params]
   (pull-internal params))
  ([config incoming-json internal-user snapshot entities-by-name scoping-edn]
   (pull-internal {:config config
                   :incoming-json incoming-json
                   :internal-user internal-user
                   :snapshot snapshot
                   :entities-by-name entities-by-name
                   :scoping-edn (dissoc scoping-edn :constants)
                   :scoping-constants (:constants scoping-edn)})))
