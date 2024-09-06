(ns lambdaconnect-sync.push
  (:require [lambdaconnect-sync.utils :as u]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-model.tools :as t]
            [lambdaconnect-model.utils :as mu]
            [clojure.set :refer [subset? difference intersection union]]
            [clojure.spec.alpha :as spec]
            [lambdaconnect-sync.db :as db]
            [lambdaconnect-model.scoping :as scoping]
            [clojure.pprint :refer [pprint]]
            #?(:clj [lambdaconnect-model.utils :refer [log-with-fn]])
            [lambdaconnect-sync.integrity-constraints :as i]
            [lambdaconnect-sync.db-drivers.datomic :as datomic-driver]
            [lambdaconnect-sync.conflicts :as conflicts])
  #?(:cljs (:require-macros [lambdaconnect-model.macro :refer [future]]
                            [lambdaconnect-model.utils :refer [log-with-fn]])))

(def pmap mu/pmap)

(defn compute-rel-objs-to-fetch
  "Creates a map of {:umbrella [uuid1, uuid2, ...], :brick [buuid1, buudi2, ...]}. 
   The map represents all objects that should be fetched to have the input data complete."
  [config input entities-by-name snapshot]

    ; Fetch objects from db that refer to any object from the input through relationships
  (let [phase1 (future (u/join-maps
                        (map (fn [[_ entity]]
                               (u/join-maps
                                (map (fn [relationship]
                                       {(:name entity)
                                        (set (db/get-related-objects
                                              config
                                              relationship
                                              (map :app/uuid (get input (:destination-entity relationship)))
                                              snapshot))})
                                     (vals (:datomic-relationships entity))))) entities-by-name)))
        ; Fetch objects from one-to-one and one-to-many relationships that are already referenced by db version of objects we want to modify

        phase2 (future (u/join-maps
                        (map (fn [[_ entity]]
                               (u/join-maps
                                (map (fn [relationship]
                                       {(:destination-entity relationship)
                                        (set (db/get-referenced-objects
                                              config
                                              relationship
                                              (map :app/uuid (get input (:name entity)))
                                              snapshot))})
                                     (vals (:datomic-relationships entity))))) entities-by-name)))

        phase3 (future (u/join-maps
                        (map (fn [[entity-name objects]]
                               (let [entity-description (get entities-by-name entity-name)
                                     relationships (vals (:relationships entity-description))
                                     attributes (vals (:attributes entity-description))
                                     valid-names (set (concat [:app/uuid :app/createdAt :app/updatedAt :app/active]
                                                              (map mp/datomic-name (concat relationships attributes))))]
                                 (u/join-maps
                                  (map (fn [object]
                                         (let [all-keys (set (keys object))]
                                           (assert (subset? all-keys valid-names)
                                                   (str "The input object of type " entity-name
                                                        " has some unsupported fields among those: " (set (keys object))
                                                        " supported: " valid-names))
                                           (let [valid-relationships (filter #(contains? all-keys (mp/datomic-name %)) relationships)
                                                 uuids-for-relationships (u/join-maps
                                                                          (map (fn [rel]
                                                                                 (let [rel-seq ((mp/datomic-name rel) object)]
                                                                                   {(:destination-entity rel)
                                                                                    (if rel-seq
                                                                                      (if (sequential? rel-seq) (set (map :app/uuid rel-seq)) #{(:app/uuid rel-seq)})
                                                                                      #{})}))
                                                                               valid-relationships))]
                                             uuids-for-relationships)))
                                       objects))))
                             (vec input))))


        tmp-union (u/join-maps [@phase1 @phase2 @phase3])

        ; Now we need to look into objects that are referenced by one-to-one relationships of objects that we do need to modify
        ; This is needed to support re-attaching objects to different objects without providing the old version in json  
        phase4 (u/join-maps
                (map (fn [[_ entity]]
                       (u/join-maps
                        (map (fn [relationship]
                               (let [inverse (-> entities-by-name
                                                 (get (:inverse-entity relationship))
                                                 :relationships
                                                 (get (:inverse-name relationship)))]
                                 (assert inverse (str "Logic error in xml model file - no inverse: " (into {} relationship)))
                                 (if (and (not (:to-many relationship))
                                          (not (:to-many inverse)))
                                   {(:name entity)
                                    (set (db/get-reciprocal-objects
                                          config
                                          (when (get (:datomic-relationships entity) (:name relationship)) relationship)
                                          (when-not (get (:datomic-relationships entity) (:name relationship)) inverse)
                                          (get tmp-union (:destination-entity relationship))
                                          snapshot))}
                                   {})))
                             (vals (:relationships entity))))) entities-by-name))

     ; Fetch objects that are referred to objects from the input
        result (u/join-maps [@phase1 @phase2 @phase3 phase4])]
    (log-with-fn (:log config) "-------------- OBJS TO FETCH ------------------")
    (log-with-fn (:log config) "PHASE1: " @phase1)
    (log-with-fn (:log config) "PHASE2: " @phase2)
    (log-with-fn (:log config) "PHASE3: " @phase3)
    (log-with-fn (:log config) "PHASE4: " phase4)
    (log-with-fn (:log config) "RESULT: " result)
    (log-with-fn (:log config) "-------------- /OBJS TO FETCH ------------------")
    result))


(defn- fetch-and-create-objects
  "Creates two similar collections: {entity-name : {uuid1: entity1, uuid2: entity2, ... }} which either hold entity maps fetched from the db (existing)
   or objects to be created (with only basic info set, e.g. uuid, created-at, etc... )"
  [config
   objects-to-fetch
   entities-by-name
   snapshot]
  (let [existing (into {} (pmap (fn [[entity-name uuids]]
                                  (let [description (get entities-by-name entity-name)]
                                    [entity-name (into {}
                                                       (map (fn [o] [(:app/uuid o) (mp/replace-inverses o description true)])
                                                            (db/get-objects-by-uuids
                                                             config
                                                             description
                                                             uuids
                                                             snapshot
                                                             true)))]))
                                objects-to-fetch))
        created (into {} (pmap (fn [[entity-name uuids]]
                                 (let [existing-uuids (set (keys (get existing entity-name)))
                                       entity (get entities-by-name entity-name)]
                                   [entity-name (into {} (map (fn [uuid]
                                                                [uuid
                                                                 {:app/uuid uuid
                                                                  :db/id (str uuid)
                                                                  (t/unique-datomic-identifier entity) true}])
                                                              (filter #(not (existing-uuids %)) uuids)))])) 
                               objects-to-fetch))]
    (log-with-fn (:log config) "-------------- FETCHED: ------------------")
    (log-with-fn (:log config) "CREATED: " created)
    (log-with-fn (:log config) "EXISTING: " existing)
    (log-with-fn (:log config) "-------------- /FETCHED: ------------------")
    [created existing]))

(defn- cas [db-id attrib old-value new-value]
  (if (string? db-id) ; cas doesnt like newly created objects. temporary ids are strings, real ones are numbers
    [:db/add db-id attrib new-value]
    (if (not= new-value nil)
      [:db/cas db-id attrib old-value new-value]
      (if (not= old-value nil)
        [:db/retract db-id attrib old-value]
        []))))

(defn- left-relationship-transaction
  "simple case - this object is responsible for maintaining the relationship"
  [config _snapshot db-id relationship orig-value new-value entities-by-name target-objects source-objects]
  (when true
    (log-with-fn (:log config) "---------------------- LEFT ------------------")
    (log-with-fn (:log config) (:entity-name relationship) " - " (:name relationship))
    (log-with-fn (:log config) "db-id " db-id " orig-value " orig-value " new value " new-value)
                                        ;    (log-with-fn (:log config) "TARGET OBJECTS " target-objects)
                                        ;   (log-with-fn (:log config) "SOURCE OBJECTS " source-objects)
    )
  (let [result
        (if (:to-many relationship)
                                        ; to-many
          (let [existing (set (map  #(:db/id (get target-objects (:app/uuid %))) orig-value))
                new (set (map #(:db/id (get target-objects (:app/uuid %))) new-value))
                rel-name (mp/datomic-name relationship)]
            (concat
             (map (fn [id]
                    [:db/add db-id rel-name id]) (difference new existing))
             (map (fn [id]
                    [:db/retract db-id rel-name id]) (difference existing new))))
          ; to-one
          (let [inverse (->  entities-by-name
                             (get (:inverse-entity relationship))
                             :relationships
                             (get (:inverse-name relationship)))
                _ (assert inverse)
                existing (:db/id (get target-objects (:app/uuid orig-value)))
                d-name (mp/datomic-name relationship)
                new (:db/id (get target-objects (:app/uuid new-value)))
                 ; one-to-one unplugging the old one
                old-related (when-not (or (:to-many inverse) (not new))
                              (:db/id (first (filter #(not= (:db/id %) db-id) (filter #(= (:db/id (d-name %)) new) (vals source-objects))))))

                rel-name (mp/datomic-name relationship)]
            (if (not= existing new)
              (vec
               (filter identity
                       [(if new
                          (if existing
                            [:db/cas db-id rel-name existing new]
                            [:db/add db-id rel-name new])
                          [:db/retract db-id rel-name existing])
                        (when old-related ; implicitly new is also present
                          [:db/retract old-related rel-name new])]))
              [])))]
    (when true
      (log-with-fn (:log config) "RESULT: " result)
      (log-with-fn (:log config) "---------------------- /LEFT ------------------"))
    result))

(defn- right-relationship-transaction
  "horrible case - we need to see into the objects that reference us:"
  [config snapshot db-id relationship orig-value new-value entities-by-name rel-objects uuid]
  (when true
    (log-with-fn (:log config) "---------------------------- RIGHT ------------------------------")
    (log-with-fn (:log config) (str "db-id: '" db-id "' relationship: '" (:name relationship) "' orig-value: '" orig-value "' new value: '" new-value "'"))
;    (log-with-fn (:log config) "TARGET OBJECTS " rel-objects)
    )

  (let [result
        (let [inverse-relationship (get-in entities-by-name [(:inverse-entity relationship) :datomic-relationships (:inverse-name relationship)])
              inverse-name (mp/datomic-name inverse-relationship)]
          (cond
                                        ; many-to-many from the side that is not persisted ; the first line is very inefficient - maybe we should ask datomic for help here?        
            (and (:to-many relationship) (:to-many inverse-relationship))
            (let [existing-objects (filter #((set (map :app/uuid (inverse-name %))) uuid) (vals rel-objects))
                  new-objects (map #(get rel-objects (:app/uuid %)) new-value)
                  new-ids (set (map :app/uuid new-objects))
                  old-ids (set (map :app/uuid existing-objects))
                  removals (difference old-ids new-ids)
                  additions (difference new-ids old-ids)
                  transactions (concat
                                (map (fn [o] [:db/retract (:db/id o) inverse-name db-id]) (filter #(removals (:app/uuid %))  existing-objects))
                                (map (fn [o] [:db/add  (:db/id o) inverse-name db-id]) (filter #(additions (:app/uuid %))  new-objects)))]
              transactions)
                                        ; one-to-one from the side that is not persisted
            (and (not (:to-many relationship)) (not (:to-many inverse-relationship)))
            (let [existing-object (first (filter #(= uuid (:app/uuid (inverse-name %))) (vals rel-objects)))
                  new-object (get rel-objects (:app/uuid new-value))
                  old-referenced-uuid (:app/uuid (inverse-name new-object))
                  old-reference-entity (get entities-by-name (:inverse-entity relationship))
                  old-reference (db/get-id-for-uuid config snapshot old-reference-entity old-referenced-uuid)
                  transactions (if (not= (:app/uuid existing-object) (:app/uuid new-object))
                                 (filter identity [(when existing-object
                                                     [:db/retract (:db/id existing-object) inverse-name db-id])
                                                   (when new-object
                                                     (if old-reference
                                                       [:db/cas (:db/id new-object) inverse-name old-reference db-id]
                                                       [:db/add (:db/id new-object) inverse-name db-id]))]) [])]
              transactions)
                                        ; many-to-one from the many side
            (and (:to-many relationship) (not (:to-many inverse-relationship)))
            (let [existing-objects (filter #(= (:app/uuid (inverse-name %)) uuid) (vals rel-objects))
                  new-objects (map #(get rel-objects (:app/uuid %)) new-value)
                  new-ids (set (map :app/uuid new-objects))
                  old-ids (set (map :app/uuid existing-objects))
                  removals (difference old-ids new-ids)
                  additions (difference new-ids old-ids)
                  transactions (concat
                                (map (fn [o] [:db/retract (:db/id o) inverse-name db-id]) (filter #(removals (:app/uuid %)) existing-objects))
                                (map (fn [o] [:db/add (:db/id o) inverse-name db-id]) (filter #(additions (:app/uuid %))  new-objects)))]
              transactions)
            :else []))]
    (when true
      (log-with-fn (:log config) "RESULT: " result)
      (log-with-fn (:log config) "---------------------------- /RIGHT ------------------------------"))
    result))

(defn- check-for-duplicates
  [config
   input ; datomic-validated input dictionary of the form {"entity one" [{:app/uuid #uuid"dasdas" ....} ...] "entity two" [{:app/uuid #uuid"dasdsa" ...} ...] ...}
   entities-by-name ; schema dictionary
   snapshot ; database snapshot to work with
   ]
  (let [uuids-by-entity-name (mu/map-keys #(map :app/uuid %) input)
        uuids (apply concat (vals uuids-by-entity-name))] ; first: json-json duplicates
    (assert (= (count (set uuids)) (count uuids)) (str "Duplicates found in input json: "
                                                       (let [groups (group-by identity uuids)
                                                             duplicates (map first (filter #(> (count %) 1) (vals groups)))]
                                                         (reduce #(str %1 ", " %2) "" duplicates))))
    (doseq [[entity-name uuids] uuids-by-entity-name]
      (let [entity (get entities-by-name entity-name)
            {:keys [misclasified without-ident]} (db/get-invalid-objects config snapshot entity uuids)
            without-ident-uuids-string (reduce #(str %1 ", " %2) "" without-ident)
            misclasified-uuids-string (reduce #(str %1 ", " %2) "" misclasified)]
        (assert (empty? without-ident) (str "Objects without ident__: " without-ident-uuids-string))
        (assert (empty? misclasified) (str "Wrong type of entity passed in input json - the uuids already exist in the db under different entities: " misclasified-uuids-string))))))


(defn- update-changed-objects-transaction
  [config
   internal-user ; whole user entity map from datomic
   objects ; all the objects fetched from the db - this is a map with keys in entity names and values being maps of uuid -> entity map from db (or new entity map created)
   input ; datomic-validated input dictionary of the form {"entity one" [{:app/uuid #uuid"dasdas" ....} ...] "entity two" [{:app/uuid #uuid"dasdsa" ...} ...] ...}
   entities-by-name ; schema dictionary
   snapshot ; database snapshot to work with
   created-entities-uuids ; a set of uuids of objects that are to be created
   now]
  (into #{}
        (apply concat
               (map (fn [[entity-name entity-objects]]
                      (let [entity (get entities-by-name entity-name)
                            attribute-names (map mp/datomic-name (filter #(not (t/special-unmodifiable-attribs (:name %))) (vals (:attributes entity))))
                            special-attribute-names (map mp/datomic-name (filter #(and (not (t/fake-attribs (:name %)))
                                                                                      (t/special-unmodifiable-attribs (:name %))) (vals (:attributes entity))))
                            relationship-names (map mp/datomic-name (vals (:relationships entity)))]
                        (apply
                         concat
                         (pmap (fn [object]
                                 (let [db-object (get-in objects [entity-name (:app/uuid object)])
                                       _ (log-with-fn (:log config) "DB OBJECT: " db-object)
                                       _ (log-with-fn (:log config) "OBJECT:: " object)
                                       db-id (:db/id db-object)
                                       db-uuid (:app/uuid db-object)
                                        ; --------- attributes -------

                                       conflict-resolved-object (conflicts/resolve-modification-conflicts config db-object object entity internal-user snapshot)

                                       _ (log-with-fn (:log config) "CONFLICT R:: " conflict-resolved-object)

                                       overriden-object ((if (created-entities-uuids db-uuid)
                                                           conflicts/override-creation-attributes
                                                           conflicts/override-update-attributes)
                                                         conflict-resolved-object internal-user snapshot entity now)

                                       _ (log-with-fn (:log config) "OVERRIDEN R:: " overriden-object)

                                       db-a (select-keys overriden-object attribute-names)
                                       db-s-a (select-keys overriden-object special-attribute-names)
                                       dbo-a (select-keys db-object attribute-names)
                                       dbo-s-a (select-keys db-object special-attribute-names)
                                       modified-attributes (filter (fn [[k v]] (not= (k dbo-a) v)) (vec db-a))
                                       modified-s-attributes (filter (fn [[k v]] (not= (k dbo-s-a) v)) (vec db-s-a))
                                       attribute-modification-transaction (map (fn [[k v]]
                                                                                 (cas db-id k (k dbo-a) v))
                                                                               modified-attributes)
                                       special-modification-transaction (if (or (seq attribute-modification-transaction)
                                                                                (> (count modified-s-attributes) 1)) ;not onlu updatedAt  
                                                                          (map (fn [[k v]]
                                                                                 (cas db-id k (k dbo-s-a) v))
                                                                               modified-s-attributes)
                                                                          [])
                                        ; ------ relationships -------
                                       o-r (select-keys overriden-object relationship-names)
                                       dbo-r (select-keys db-object relationship-names)
                                       relationship-modification-transaction
                                       (apply concat
                                              (map (fn [[rel-name rel-value]]
                                                     (let [orig-value (get dbo-r rel-name)
                                                           relationship (get (:relationships entity) (name rel-name))
                                                           datomic-relationship (get (:datomic-relationships entity) (name rel-name))
                                                           target-objects (get objects (:destination-entity relationship))
                                                           source-objects (get objects (:name entity))]
                                                       _ (log-with-fn (:log config) "---------------")
                                                       _ (log-with-fn (:log config) "TRANS ORIG:: " orig-value)
                                                       _ (log-with-fn (:log config) "TRANS DBO-R:: " dbo-r)
                                                       _ (log-with-fn (:log config) "TRANS TARGET-O:: " target-objects)
                                                       (if datomic-relationship
                                        ; simple case - this object is responsible for maintaining the relationship
                                                         (left-relationship-transaction config snapshot db-id datomic-relationship orig-value rel-value entities-by-name target-objects source-objects)
                                        ; horrible case - we need to see into the objects that reference us:
                                                         (right-relationship-transaction config snapshot db-id relationship orig-value rel-value entities-by-name target-objects db-uuid)))) (vec o-r)))]
                                   (concat attribute-modification-transaction relationship-modification-transaction special-modification-transaction))) entity-objects)))) (vec input)))))

; ==================================================================

; The import process goes as follows:
;
; 1. Acquire the current db snapshot, internal user object and entities-by-name from the parser
; 2. Pass the incoming json (as a string) to the push-transaction method
; 3. push-transaction would return the transaction and a data structure holding all the new objects created
; 4. Perform the transaction and acquire the new db snapshot
; 5. pass the new snapshot together with created objects to send-creation-hooks!

(defn internal-push-transaction
  ([config incoming-json internal-user snapshot entities-by-name scoped-push-input-for-user]
   (internal-push-transaction config incoming-json internal-user snapshot entities-by-name scoped-push-input-for-user #?(:clj (java.util.Date.) :cljs (js/Date.))))
  ([config incoming-json internal-user snapshot entities-by-name scoped-push-input-for-user now]
   (log-with-fn (:log config) "=========---------===========--------- PUSH ----------============-----------============")
   (log-with-fn (:log config) "INCOMING: " incoming-json)
   (log-with-fn (:log config) "%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%")
   (let [; Step one - mapping of the incoming json into our model + validation
         mapped-input (into {}
                            (map (fn [[entity-name objects]]
                                   [entity-name
                                    (let [entity (get entities-by-name entity-name)]
                                      (assert entity (str "You are using an outdated version of the app. DB model does not contain entity: " entity-name))
                                      (set
                                       (map
                                        (fn [json-obj]
                                          (let [transformed (mp/json-to-clojure json-obj entity)
                                                json-obj-spec (mp/spec-for-name (:name entity))]
                                            (assert (spec/valid? json-obj-spec transformed)
                                                    (str "Spec failed: "
                                                         (spec/explain-str json-obj-spec transformed)
                                                         "\n for object: " transformed
                                                         "\n spec: " json-obj-spec))                                            
                                            transformed)) objects)))]) incoming-json))
         [input scoped-uuids] (scoped-push-input-for-user mapped-input internal-user entities-by-name snapshot)
         rel-objs-to-fetch (compute-rel-objs-to-fetch config input entities-by-name snapshot)
         input-objs-to-fetch (mu/map-keys (fn [v] (set (map :app/uuid v))) input)
         objects-to-fetch (u/join-maps [rel-objs-to-fetch input-objs-to-fetch])]
     (assert (subset? (set (keys input)) (set (keys entities-by-name)))
             (str "Some of the entities from input: "
                  (set (keys input))
                  " are not present in the model: "
                  (set (keys entities-by-name))))
     (let [[created-objects updated-objects] (fetch-and-create-objects config objects-to-fetch entities-by-name snapshot)
           all-objects (u/join-maps merge [created-objects updated-objects])
           pure-created-objects (apply concat (map vals (vals created-objects)))
           created-uuids (set (map :app/uuid pure-created-objects))
           updated-all-objects (update-changed-objects-transaction config internal-user all-objects input entities-by-name snapshot created-uuids now)
           transaction (concat pure-created-objects (vec (set updated-all-objects)))
           _ (log-with-fn (:log config) "FULL TRANSACTION: " transaction)
           [foreign-keys-in-db response invalid-uuids] (i/check-foreign-key config input snapshot (i/relations entities-by-name))
           error-string (str "You are going to create object refering to non existing entity. \n"
                             "Entities with invalid relations: "  response " \n"
                             "Invalid uuids: " (vec invalid-uuids) " \n"
                             "Push after scoping: " input " \n")
           error-info {:wrong-relations response :invalid-uuids invalid-uuids :input-after-scope input}]

       (check-for-duplicates config input entities-by-name snapshot)       
       (if foreign-keys-in-db
         (do 
           (log-with-fn (:log config) "=========---------===========--------- /PUSH ----------============-----------============")
           [transaction [created-objects updated-objects] scoped-uuids])
         (do
           (log-with-fn (:log config) error-string)
           (throw (ex-info (str "You are going to create object refering to non existing entity. \n" (dissoc error-info :input-after-scope)) error-info))))))))

; -------------------- scope push helpers ----------------------

(spec/def ::writable-fields (spec/coll-of string?))
(spec/def ::protected-fields (spec/coll-of string?))
(spec/def ::modify boolean?)
(spec/def ::create boolean?)

(spec/def ::permission (spec/keys :req-un [::modify ::create] :opt-un [::writable-fields ::protected-fields]))

(defn- merge-permissions [permissions entity]
  {:pre [(spec/valid? (spec/coll-of ::permission) permissions) (spec/valid? :lambdaconnect-model.data-xml/entity entity)]
   :post [(spec/valid? ::permission %)]}
  (let [all-fields (into #{} (concat (keys (:attributes entity)) (keys (:relationships entity))))
        ;; Simplified permissions always have writable-fields only.
        simplified-permissions (map (fn [{:keys [writable-fields protected-fields modify] 
                                          :as permission}]
                                      (when writable-fields
                                        (assert (subset? (set writable-fields) all-fields)
                                                (str (set writable-fields) " is not a subset of " all-fields)))
                                      (when protected-fields
                                        (assert (subset? (set protected-fields) all-fields)
                                                (str (set protected-fields) " is not a subset of " all-fields)))
                                      (-> permission
                                          (select-keys [:create :modify])
                                          (assoc :writable-fields 
                                                 (cond 
                                                   (not modify) (do 
                                                                  (when-not (nil? writable-fields)
                                                                    (println "Warning - writable fields is not nil even though modify is false: " writable-fields))
                                                                  (when-not (nil? protected-fields)
                                                                    (println "Warning - protected fields is not nil even though modify is false: " protected-fields))
                                                                  #{})
                                                   (or writable-fields protected-fields)
                                                   (difference (set (or writable-fields all-fields))
                                                               (set (or protected-fields #{})))
                                                   :default all-fields)))) permissions)]
    (if (seq simplified-permissions)
      {:modify (reduce #(or %1 %2) (map :modify simplified-permissions))
       :create (reduce #(or %1 %2) (map :create simplified-permissions))
       :writable-fields (apply union (map :writable-fields simplified-permissions))}
      {:modify false
       :create false})))
  
(defn combined-permissions-for-object
  "Given a sequence of permissions of the form 
  {:create ^Bool 
   :modify ^Bool
   :protected-fields [^String]
   :writable-fields [^String]}

  produces a single permission of the same form that is a combination of these, where:
  
  1. create and modify are the most permissive of all the permissions
  2. protected-fields is an intersection of all protected fields (again, most permissive models)
  3. writable-fields is an union of all writable-fields, most permissive model.
  
  Therefore, we assume that permissions are keys that open certain gates instead of closing them. 
  If someone has write permissions to something it trumps all the lesser permissions."

  [permissions entity]
  (spec/assert (spec/coll-of ::permission) permissions)
  (let [final-permissions (merge-permissions permissions entity)]
    ;; updatedAt is a special field that should always be allowed to be modified whenever any modification is possible
    (if (:modify final-permissions)
      (-> final-permissions 
          (#(if (seq (:writable-fields %)) (update % :writable-fields (fn [w] (conj w "updatedAt"))) %)))
      final-permissions)))

; -------------------- new scoped push -------------------------


(defn push-transaction
  ([config incoming-json internal-user snapshot entities-by-name scoping-edn now]
   (let [config (if (:driver config)
                  config
                  (assoc config :driver (datomic-driver/->DatomicDatabaseDriver config)))]
     (push-transaction config incoming-json internal-user snapshot entities-by-name scoping-edn
                       (internal-push-transaction config incoming-json internal-user snapshot entities-by-name (fn [i _ _ _] [i {}]) now)
                       {:rejected-objects {} :rejected-fields {}} nil)))

  ([config incoming-json internal-user snapshot entities-by-name scoping-edn]
   (let [config (if (:driver config)
                  config
                  (assoc config :driver (datomic-driver/->DatomicDatabaseDriver config)))]
     (push-transaction config incoming-json internal-user snapshot entities-by-name scoping-edn
                       (internal-push-transaction config incoming-json internal-user snapshot entities-by-name (fn [i _ _ _] [i {}]))
                       {:rejected-objects {} :rejected-fields {}} nil)))

  ([config incoming-json internal-user snapshot entities-by-name original-scoping-edn push-output rejections cached-snapshot-tags]
   (when (:constants original-scoping-edn) (assert (fn? (:constants original-scoping-edn))))
   (let [entity (get entities-by-name (first (keys incoming-json)))
         [transaction [created-objects updated-objects] _] push-output
         new-db (db/speculate config snapshot transaction) ; we simulate the transaction to simplify tag discovery and never look at the ugly incoming-json
                                                        ; the following permissions come from the speculated db snapshot (new-db)

         scoping-edn (if (:constants original-scoping-edn)
                       (update original-scoping-edn :constants #(% snapshot internal-user))
                       original-scoping-edn)

         new-scoping-edn (if (:constants original-scoping-edn)
                           (update original-scoping-edn :constants #(% new-db internal-user))
                           original-scoping-edn)

         scoped-tags (future ; {:NOUser.me #{#uuid1 #uuid2 #uuid3}, :NOMessage.mine #{#uuid4 #uuid5 #uuid6}} (:app/uuid's are the values in sets)
                       (when scoping-edn 
                         (mu/map-keys (fn [ids] (set (db/uuids-for-ids config new-db ids))) ; we need uuids not ids this time
                                       (scoping/scope config new-db internal-user entities-by-name new-scoping-edn true)))) 
                                        ; the following permissions come from the original db snapshot
         scoped-tags-snapshot (future ; {:NOUser.me #{#uuid1 #uuid2 #uuid3}, :NOMessage.mine #{#uuid4 #uuid5 #uuid6}} (:app/uuid's are the values in sets)
                                (or cached-snapshot-tags 
                                    (when scoping-edn 
                                      (mu/map-keys 
                                       (fn [ids] (set (db/uuids-for-ids config snapshot ids))) ; we need uuids not ids this time
                                       (scoping/scope config snapshot internal-user entities-by-name scoping-edn true))))) 
         
         tags-by-ids (when @scoped-tags 
                       (scoping/inverse-entities (u/join-maps [@scoped-tags @scoped-tags-snapshot])))]
     (if (not @scoped-tags) [transaction [created-objects updated-objects] {:rejected-objects {}
                                                                            :rejected-fields {}}]
         (let ;scoping starts here
          [_ (log-with-fn (:log config) (str "----> ORIGINAL TRANSACTION: " (vec transaction)))
           _ (log-with-fn (:log config) (str "---------------------------------------------"))
           _ (log-with-fn (:log config) (str "ALL TAGS: " tags-by-ids))


           constants (:constants scoping-edn)
           constant-value #(if (and (keyword? %)
                                    (= (namespace %) "constant"))
                             (let [value (get constants (keyword (name %)))] 
                               (assert (contains? constants (keyword (name %))) (str "Constant " % " not present in constants map: ." constants))
                               (if (delay? value) @value value))
                             %)
           process-constants-for-permissions (fn [permissions]
                                               (-> permissions
                                                   (update :modify constant-value)
                                                   (update :create constant-value)
                                                   (update :writable-fields constant-value)
                                                   (update :protected-fields constant-value)
                                                   (update :writable-fields #(map constant-value %))
                                                   (update :protected-fields #(map constant-value %))
                                                   (update :writable-fields flatten)
                                                   (update :protected-fields flatten)
                                                   (select-keys (keys permissions))))
          
           permissions-for-object (memoize (fn [uuid] ; a helper that takes an uuid and returns the merged permissions map (an object can belong to multiple tags)
                                             (let [tags (get tags-by-ids uuid)
                                                   _ (log-with-fn (:log config) (str "PERMISSIONS TAGS for UUID: " uuid " - " tags))
                                                   permissions (map #(->> % 
                                                                          (get scoping-edn)
                                                                          (:permissions)                                                                  
                                                                          (process-constants-for-permissions)) tags)
                                                   entity (when (seq tags) (get entities-by-name (last (re-find #"(.*)\..+" (name (first tags))))))
                                                   result (if entity (combined-permissions-for-object permissions entity)
                                                              {:create false :modify false :writable-fields []})]
                                               (log-with-fn (:log config) (str "PERMISSIONS to process: " uuid " - " (vec permissions)))
                                               (log-with-fn (:log config) (str "PERMISSIONS result: " uuid " - " (vec result)))
                                               result)))

           field-replacements-for-object (memoize (fn [uuid] ; a helper that takes an uuid and returns the merged replacements map (an object can belong to multiple tags)
                                                    (let [tags (get tags-by-ids uuid)
                                                          replacements (map #(some->> %
                                                                                      (get scoping-edn)
                                                                                      (:replace-fields)
                                                                                      ;; We ignore replacements that eventually replace fields with contents of themselves (e.g. {:field-name :field-name})
                                                                                      (filter (fn [[k v]] (not= k (constant-value v))))
                                                                                      (map first)
                                                                                      (map name)
                                                                                      (set)) tags)]
                                                      (assert (or (<= (count replacements) 1)
                                                                  (empty? (apply clojure.set/intersection replacements)))
                                                              (str "The tags " (set tags) " have duplicated replace-fields. There is no way to determine which one to use: " (apply clojure.set/intersection replacements)))
                                                      (apply clojure.set/union replacements))))

           objects-from-entry (memoize (fn [entry]
                                         (let [ids-from-entry (fn [op id attr old-val val] ; returns false if excision is needed
                                                                (let [src-entity (get entities-by-name (namespace attr))]
                                                                  (set (remove nil? (concat [id]
                                                                                            (when (and (not (nil? src-entity)) ; if it is a relationship
                                                                                                       (not (nil? (get (:datomic-relationships src-entity) (name attr)))))
                                                                                              [old-val val]))))))]
                                           (cond (map? entry) #{(:db/id entry)} ; creation entry
                                                 (#{:db/add :db/retract} (first entry)) (let [[op id attr val] entry] (ids-from-entry op id attr nil val)) ; add attribute or relationship
                                                 (#{:db/cas :db.fn/cas} (first entry)) (let [[op id attr old new] entry] (ids-from-entry op id attr old new))
                                                 (#{:db/retractEntity} (first entry)) #{(second entry)} ; we allow all the other known ops
                                                 :else (assert false (str "Unknown transaction entry: " entry))))))


           objects-in-entry? (fn [object-ids entry] ; object-ids is a set
                               (let [check-entry (fn [op id attr old-val val] ; returns false if excision is needed
                                                   (let [src-entity (get entities-by-name (namespace attr))]
                                                     (or (object-ids id) ; if we modify object directly 
                                                         (and
                                                          (and (not (nil? src-entity)) ; if it is a relationship
                                                               (not (nil? (get (:datomic-relationships src-entity) (name attr)))))
                                                          (or (object-ids old-val) ; and our id is mentioned in it
                                                              (object-ids val))))))]
                                 (cond (map? entry) (object-ids (:db/id entry)) ; creation entry
                                       (#{:db/add :db/retract} (first entry)) (let [[op id attr val] entry] (check-entry op id attr nil val)) ; add attribute or relationship
                                       (#{:db/cas :db.fn/cas} (first entry)) (let [[op id attr old new] entry] (check-entry op id attr old new))
                                       (#{:db/retractEntity} (first entry)) (let [[_ id] entry] (object-ids id))
                                       :else (assert false (str "Unknown transaction entry: " entry)))))

           created-uuids (set (mu/mapcat keys (vals created-objects)))
           updated-uuids (set (mu/mapcat keys (vals updated-objects)))

           ;; ***************** OBJECT REJECTIONS *************************

           rejected-objects (mu/map-keys #(filter (fn [o] (let [permissions (or (permissions-for-object (:app/uuid o)) {})
                                                                 {:keys [modify create] :or {modify false create false}} permissions]
                                                             (log-with-fn (:log config) (str "REJECTING? " (:app/uuid o)  permissions))
                                                             (cond (created-uuids (:app/uuid o)) (not create)
                                                                   (updated-uuids (:app/uuid o)) (not modify)
                                                                   :else (assert false (str "Logic error - object not found" o))))) %) 
                                          (mu/map-keys #(if (empty? %) [] (vals %)) (merge-with merge created-objects updated-objects)))


           rejected-id-candidates (mu/map-keys #(set (map :db/id %)) rejected-objects)
           _ (log-with-fn (:log config) "Reject candidates: " rejected-id-candidates)

           [transaction-after-object-rejections true-rejection-ids rejected-statements-by-id] 
           (let [rejected-ids (apply union (vals rejected-id-candidates))
                 new-transaction (filter (comp not (partial objects-in-entry? rejected-ids)) transaction)
                 rejected-statements (vec (difference (set transaction) (set new-transaction)))
                 rejected-objects-per-statement (map objects-from-entry rejected-statements)
                 possible-rejected-ids (apply union (map set rejected-objects-per-statement))
                 rejected-ids (intersection rejected-ids possible-rejected-ids)

                 rejected-statements-per-object (mu/map-keys 
                                                 #(set (map :statements %))
                                                 (group-by :object 
                                                           (apply concat (map (fn [objects statements]
                                                                                (map (fn [object] {:object object :statements statements}) objects)) 
                                                                              rejected-objects-per-statement rejected-statements))))                 
                 ]
             [new-transaction 
              rejected-ids
              rejected-statements-per-object])

           rejected-object-info (into {} (map (fn [[k v]]
                                                (let [output (map (fn [o] [(:app/uuid o) (:db/id o)]) (filter #(true-rejection-ids (:db/id %)) v))]
                                                  (when (seq output) [k (set (map (fn [[uuid id]] 
                                                                                    {:uuid uuid 
                                                                                     :tags (get tags-by-ids uuid)
                                                                                     :final-permissions (permissions-for-object uuid)
                                                                                     :rejected-statements (get rejected-statements-by-id id)}) output))]))) rejected-objects)) ; out

           ;; ***************** FIELD REJECTIONS *************************

           objects-to-filter-fields (mu/map-keys
                                     #(filter
                                       (fn [o]
                                         (let [permissions (or (permissions-for-object (:app/uuid o)) {})
                                               {:keys [modify protected-fields writable-fields] :or {modify false}} permissions
                                               field-replacements (or (field-replacements-for-object (:app/uuid o)) #{})]
                                           (and modify (or protected-fields writable-fields (seq field-replacements))))) %)
                                     (mu/map-keys #(if (empty? %) [] (vals %)) updated-objects))

           _ (log-with-fn (:log config) "Objects to reject fields: " objects-to-filter-fields)

           excise-field-from-transaction (fn [entity object-id transaction field-name]  ; object-id is assumed to be internal db 
                                           ;; Returns transaction with operations modifying a field against permissions excised.
                                           ;; Gets fired if we are certain the excision should happen
                                           (let [attribute (get (:attributes entity) field-name)
                                                 relationship (get (:relationships entity) field-name)
                                                 datomic-relationship (get (:datomic-relationships entity) field-name)
                                                 inverse-relationship (when relationship (get (:relationships (get entities-by-name (:inverse-entity relationship))) (:inverse-name relationship)))]
                                             (assert (or attribute relationship) 
                                                     (str "Scoping field '" field-name "' failed for entity '" (:name entity) "', obj-id: '" object-id "'. Such a field does not exist in the model. Make sure your scoping files are correct and scoping constants employed are correct as well. transaction: " (vec transaction)))
                                             (filter (fn [entry]
                                                       (or (map? entry) ; we do not filter created objects here
                                                           (let [op (first entry)
                                                                 datomic-filter-field (mp/datomic-name (or attribute datomic-relationship inverse-relationship))
                                                                 check-attribute-entry (fn [op id datomic-field old-target new-target] ; returns false if excision is needed
                                                                                         (not (and (= id object-id) (= datomic-filter-field datomic-field))))
                                                                 check-relationship-entry (fn [op id datomic-field old-target new-target]
                                                                                            (not (and (= datomic-filter-field datomic-field)
                                                                                                      (or (and datomic-relationship (= id object-id)) ; the straight relationship
                                                                                                          (and (not datomic-relationship) ; the inverse 
                                                                                                               (or (= old-target object-id)
                                                                                                                   (= new-target object-id)))))))
                                                                 check-entry (if attribute check-attribute-entry check-relationship-entry)
                                                                 result (case op
                                                                          :db/add (let [[_ id datomic-field target] entry] (check-entry op id datomic-field nil target))
                                                                          :db/retract (let [[_ id datomic-field target] entry] (check-entry op id datomic-field nil target))
                                                                          :db/cas (let [[_ id datomic-field old-target new-target] entry] (check-entry op id datomic-field old-target new-target))
                                                                          :db.fn/cas (let [[_ id datomic-field old-target new-target] entry] (check-entry op id datomic-field old-target new-target))
                                                                          true)]
                                                             (when (not result) 
                                                               (log-with-fn (:log config) (str "!!! Excising field: " field-name " - " object-id " entry: " entry)))
                                                             result
                                                             ))) transaction)))

           [transaction-after-field-rejections field-rejections] 
           (letfn [(reject-fields [t entity object remaining-fields rejections field-replacements excised-anything?]
                     (if-let [field (first remaining-fields)]
                       (let [new-transaction (excise-field-from-transaction entity (:db/id object) t field)
                             excised? (and (not= (count t) (count new-transaction))
                                           ;; We create a rejection if something was excised and it was not a field that has been replaced
                                           ;; We can add additional logic here to not report rejections of fields that happened when 
                                           ;; modifying scoped relations. 
                                           (not (field-replacements field)))]
                         (when (and (not= (count t) (count new-transaction))
                                           ;; We create a rejection if something was excised and it was not a field that has been replaced
                                           ;; We can add additional logic here to not report rejections of fields that happened when 
                                           ;; modifying scoped relations. 
                                           (field-replacements field))
                           (log-with-fn (:log config) (str "Silently removed replacement: " t " - " new-transaction " - " field " - " field-replacements)))
                         (recur new-transaction
                                entity
                                object
                                (rest remaining-fields)
                                (if excised?
                                  (update-in rejections [(:name entity) (:app/uuid object)] 
                                             #(conj (or % #{}) 
                                                    {:field field
                                                     :rejected-statements (vec (difference (set t) (set new-transaction)))})) 
                                  rejections)
                                field-replacements
                                (or excised? excised-anything?)))
                       (do
                        (when excised-anything? 
                          (log-with-fn (:log config) (str "Excised something: " object " - " (doall t) " - " rejections)))
                       [t rejections excised-anything?])))
                   
                   (reject-objects [tx entity remaining-objects rejections]
                     (if-let [object (first remaining-objects)]
                       (let [permissions (or (permissions-for-object (:app/uuid object)) {})
                             field-replacements (or (field-replacements-for-object (:app/uuid object)) #{})
                             _ (log-with-fn (:log config) (str "Looking at rejecting from: " (:app/uuid object) " permissions: - " permissions " - " field-replacements))
                             {:keys [protected-fields writable-fields] :or {protected-fields #{} writable-fields #{}}} permissions
                             fields-to-remove (vec (union field-replacements
                                                          (if (seq protected-fields) protected-fields                                                            
                                                              (difference
                                                               (set (concat (keys (:attributes entity)) (keys (:relationships entity))))
                                                               writable-fields))))
                             _ (log-with-fn (:log config) (str "Fields to remove: " (doall fields-to-remove)))
                             [new-transaction new-rejections excised-anything?] 
                             (reject-fields tx entity object fields-to-remove rejections field-replacements false)]
                         (recur new-transaction
                                entity
                                (rest remaining-objects)
                                ;; We create a rejection if something was excised and it was not a field that has been replaced (see logic in reject-fields)
                                (if excised-anything?
                                  (update-in new-rejections [(:name entity) (:app/uuid object)] 
                                             #(conj % {:tags (get tags-by-ids(:app/uuid object))
                                                       :permissions permissions})) 
                                  rejections)))
                       [tx rejections]))
                   
                   (reject-entities [tx remaining-objects rejections]
                     (if-let [[entity-name objects] (first remaining-objects)]
                       (let [[new-transaction new-rejections] (reject-objects tx (get entities-by-name entity-name) objects rejections)]
                         (recur new-transaction
                                (rest remaining-objects)
                                new-rejections))
                       [tx rejections]))]
             (reject-entities transaction-after-object-rejections (vec objects-to-filter-fields) {}))]
           (if (and (empty? rejected-object-info)
                    (= (count transaction-after-field-rejections) (count transaction)))
             (let [;; Filtering transactions for objects that only contain updatedAt (we do not want those)                                      
                   objects-to-excise (->> transaction
                                          (u/group-by-keysets objects-from-entry)
                                          (filter (fn [[k v]] (and (= (count v) 1)
                                                                   (= :db/cas (ffirst v))
                                                                   (= :app/updatedAt (nth (first v) 2)))))
                                          (keys)
                                          (set))
                   _ (log-with-fn (:log config) (str "REMOVING updatedAt only: " objects-to-excise))
                   transaction (filter #(not (seq (clojure.set/intersection objects-to-excise (objects-from-entry %)))) transaction)

                   ids-in-transaction (apply union (map objects-from-entry transaction))
                   filtered-created-objects (mu/map-keys #(into {} (filter (fn [[k o]] (ids-in-transaction (:db/id o))) %)) created-objects)
                   filtered-updated-objects (mu/map-keys #(into {} (filter (fn [[k o]] (ids-in-transaction (:db/id o))) %)) updated-objects)]
               (log-with-fn (:log config) "-----------------------------")
               (log-with-fn (:log config) (str "REJECTIONS: " rejections))
               (log-with-fn (:log config) (str "FINAL TRANSACTION: " (vec transaction)))
               (log-with-fn (:log config) "-----------------------------")
               (log-with-fn (:log config) (str "OBJECTS:" filtered-created-objects filtered-updated-objects))
               (log-with-fn (:log config) "=======================================")
               ;; we have removed all that was needed in the former steps
               [transaction [filtered-created-objects filtered-updated-objects] rejections])
             (do 
               (log-with-fn (:log config) "Recurring because not all rejections have been complete.")
               (log-with-fn (:log config) "Rejections: " rejections)
               (recur config incoming-json internal-user snapshot entities-by-name original-scoping-edn 
                      [transaction-after-field-rejections [created-objects updated-objects] {}]
                      {:rejected-objects (merge-with union rejected-object-info (:rejected-objects rejections))
                       :rejected-fields (merge-with #(merge-with union %1 %2) field-rejections (:rejected-fields rejections))}
                      @scoped-tags-snapshot))))))))
