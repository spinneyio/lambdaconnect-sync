(ns lambdaconnect-sync.push
  (:require [lambdaconnect-sync.utils :as u]
            [lambdaconnect-model.core :as mp]
            [lambdaconnect-model.tools :as t]
            [clojure.algo.generic.functor :as functor]
            [clojure.set :refer [subset? difference intersection union]]
            [clojure.spec.alpha :as spec]
            [lambdaconnect-sync.db :as db]
            [lambdaconnect-model.scoping :as scoping]
            [lambdaconnect-sync.integrity-constraints :as i]
            [lambdaconnect-sync.conflicts :as conflicts]))

(defn- fmap
  [f m]
  (into (empty m) (for [[k v] m] [k (f v)])))

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
                                 (assert inverse "Logic error")
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
    ((:log config) "-------------- OBJS TO FETCH ------------------")
    ((:log config) "PHASE1: " @phase1)
    ((:log config) "PHASE2: " @phase2)
    ((:log config) "PHASE3: " @phase3)
    ((:log config) "PHASE4: " phase4)
    ((:log config) result)
    ((:log config) "-------------- /OBJS TO FETCH ------------------")
    result))


(defn- fetch-and-create-objects
  "Creates two similar collections: {entity-name : {uuid1: entity1, uuid2: entity2, ... }} which either hold entity maps fetched from the db (existing)
   or objects to be created (with only basic info set, e.g. uuid, created-at, etc... )"
  [config
   objects-to-fetch
   entities-by-name
   snapshot]

  (let [vecfetch (vec objects-to-fetch)
        existing (into {} (pmap (fn [[entity-name uuids]]
                                  (let [description (get entities-by-name entity-name)]
                                    [entity-name (into {}
                                                       (map (fn [o] [(:app/uuid o) (mp/replace-inverses o description true)])
                                                            (db/get-objects-by-uuids
                                                             config
                                                             description
                                                             uuids
                                                             snapshot
                                                             true)))]))
                                vecfetch))
        created (into {} (pmap (fn [[entity-name uuids]]
                                 (let [existing-uuids (set (keys (get existing entity-name)))
                                       entity (get entities-by-name entity-name)]
                                   [entity-name (into {} (map (fn [uuid]
                                                                [uuid
                                                                 {:app/uuid uuid
                                                                  :db/id (.toString uuid)
                                                                  (t/unique-datomic-identifier entity) true}])
                                                              (filter #(not (existing-uuids %)) uuids)))])) vecfetch))]
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
    ((:log config) "---------------------- LEFT ------------------")
    ((:log config) (:entity-name relationship) " - " (:name relationship))
    ((:log config) "db-id " db-id " orig-value " orig-value " new value " new-value)
                                        ;    ((:log config) "TARGET OBJECTS " target-objects)
                                        ;   ((:log config) "SOURCE OBJECTS " source-objects)
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
      ((:log config) "RESULT: " result)
      ((:log config) "---------------------- /LEFT ------------------"))
    result))

(defn- right-relationship-transaction
  "horrible case - we need to see into the objects that reference us:"
  [config snapshot db-id relationship orig-value new-value entities-by-name rel-objects uuid]
  (when true
    ((:log config) "---------------------------- RIGHT ------------------------------")
    ((:log config) "db-id " db-id " orig-value " orig-value " new value " new-value)
;    ((:log config) "TARGET OBJECTS " rel-objects)
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
                  ;; TODO: sprawdzić czy na pewno ta zmiana jest ok
                  old-reference ((:pull config) snapshot '[*] [:app/uuid old-referenced-uuid])
                  transactions (if (not= (:app/uuid existing-object) (:app/uuid new-object))
                                 (filter identity [(when existing-object
                                                     [:db/retract (:db/id existing-object) inverse-name db-id])
                                                   (when new-object
                                                     (if (:db/id old-reference)
                                                       [:db/cas (:db/id new-object) inverse-name (:db/id old-reference) db-id]
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
      ((:log config) "RESULT: " result)
      ((:log config) "---------------------------- /RIGHT ------------------------------"))
    result))

(defn- check-for-duplicates
  [config
   input ; datomic-validated input dictionary of the form {"entity one" [{:app/uuid #uuid"dasdas" ....} ...] "entity two" [{:app/uuid #uuid"dasdsa" ...} ...] ...}
   entities-by-name ; schema dictionary
   snapshot ; database snapshot to work with
   ]
  (let [uuids-by-entity-name (fmap #(map :app/uuid %) input)
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
                                       _ ((:log config) "DB OBJECT: " db-object)
                                       _ ((:log config) "OBJECT:: " object)
                                       db-id (:db/id db-object)
                                       db-uuid (:app/uuid db-object)
                                        ; --------- attributes -------

                                       conflict-resolved-object (conflicts/resolve-modification-conflicts config db-object object entity internal-user snapshot)

                                       _ ((:log config) "CONFLICT R:: " conflict-resolved-object)

                                       overriden-object ((if (created-entities-uuids db-uuid)
                                                           conflicts/override-creation-attributes
                                                           conflicts/override-update-attributes)
                                                         conflict-resolved-object internal-user snapshot entity now)

                                       _ ((:log config) "OVERRIDEN R:: " overriden-object)

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
                                                       _ ((:log config) "TRANS ORIG:: " orig-value)
                                                       _ ((:log config) "TRANS DBO-R:: " dbo-r)
                                                       _ ((:log config) "TRANS TARGET-O:: " target-objects)
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
   (internal-push-transaction config incoming-json internal-user snapshot entities-by-name scoped-push-input-for-user (java.util.Date.)))
  ([config incoming-json internal-user snapshot entities-by-name scoped-push-input-for-user now]
   ((:log config) "=========---------===========--------- PUSH ----------============-----------============")
   ((:log config) "INCOMING: " incoming-json)
   ((:log config) "%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%")
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
                                                    (spec/explain-str json-obj-spec transformed))
                                            transformed)) objects)))]) incoming-json))
         [input scoped-uuids] (scoped-push-input-for-user mapped-input internal-user entities-by-name snapshot)
         rel-objs-to-fetch (compute-rel-objs-to-fetch config input entities-by-name snapshot)
         input-objs-to-fetch (functor/fmap (fn [v] (set (map :app/uuid v))) input)
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
           _ ((:log config) "FULL TRANSACTION: " transaction)
           [foreign-keys-in-db response invalid-uuids] (i/check-foreign-key config input snapshot (i/relations entities-by-name))
           error-string (str "You are going to create object refering to non existing entity. \n"
                             "Entities with invalid relations: "  response " \n"
                             "Invalid uuids: " (vec invalid-uuids) " \n"
                             "Push after scoping: " input " \n")
           error-info {:wrong-relations response :invalid-uuids invalid-uuids :input-after-scope input}]

       (check-for-duplicates config input entities-by-name snapshot)
       ((:log config) "=========---------===========--------- /PUSH ----------============-----------============")
       (if foreign-keys-in-db
         [transaction [created-objects updated-objects] scoped-uuids]
         (do
           ((:log config) error-string)
           (throw (ex-info (str "You are going to create object refering to non existing entity. \n" (dissoc error-info :input-after-scope)) error-info))))))))

(defn send-specific-hooks!
  "Creates a transaction or a map {:transaction mq-transaction}"
  [config before-snapshot snapshot interesting-objects entities-by-name user hook-fun]
  (let [result (reduce #(merge-with concat %1 %2) 
                       {:transaction [] :mq-transaction []} 
                       (map (fn [[entity-name object-dict]]
                              (let [entity (get entities-by-name entity-name)
                                    objects (db/get-objects-by-uuids config entity (or (keys object-dict) []) snapshot)]
                                (reduce #(merge-with concat %1 %2) 
                                        {:transaction [] :mq-transaction []} 
                                        (pmap (fn [object]
                                                (let [result (hook-fun user object entity snapshot before-snapshot)]
                                                  (if (map? result) result {:transaction result :mq-transaction []}))) objects)))) (vec interesting-objects)))]
    (if (seq (:mq-transaction result))
      result
      (:transaction result))))

(defn send-specific-update-hooks!
  "Creates a transaction or a map {:transaction mq-transaction}"
  [config before-snapshot snapshot interesting-objects entities-by-name user hook-fun]
  (let [result (reduce #(merge-with concat %1 %2) 
                       {:transaction [] :mq-transaction []} 
                       (map (fn [[entity-name object-dict]]
                              (let [entity (get entities-by-name entity-name)
                                    objects (db/get-objects-by-uuids config entity (or (keys object-dict) []) snapshot)]
                                (reduce #(merge-with concat %1 %2) 
                                        {:transaction [] :mq-transaction []} 
                                        (pmap (fn [object]
                                                (let [result (hook-fun user object entity snapshot before-snapshot)]
                                                  (if (map? result) result {:transaction result :mq-transaction []}))) objects)))) (vec interesting-objects)))]
    (if (seq (:mq-transaction result))
      result
      (:transaction result))))



; -------------------- scope push helpers ----------------------

(defn update-boolean-permissions [l r] (or l r))

(defn update-writable-fields [l-m r-m l r]
  (cond
    (and (seq l) (seq r)) (vec (set (concat l r)))
    (not= l-m r-m) (vec (set (concat l r)))
    :else []))

(defn update-protected-fields [l-m r-m l r]
  (cond
    ; de Morgan's law:
    (and (seq l) (seq r)) (vec (intersection (into #{} l) (into #{} r)))
    (not= l-m r-m) (vec (set (concat l r)))
    :else []))

(defn merge-fields [perms entity]
  (let [{w-f :writable-fields p-f :protected-fields} perms
        all-fields (into #{} (concat (keys (:attributes entity)) (keys (:relationships entity))))
        all-fields-except-protected (difference all-fields (into #{} p-f))
        writable-fields (vec (set (concat all-fields-except-protected w-f)))]
    (if (and w-f p-f)
      (-> perms
          (dissoc :protected-fields)
          (assoc :writable-fields writable-fields))
      perms)))

(defn merge-permissions [permissions]
  (reduce (fn [perms {c :create m :modify w :writable-fields p :protected-fields}]
            (let [p-m (:modify perms)]
              (-> perms
                  (update :create (partial update-boolean-permissions c))
                  (update :modify (partial update-boolean-permissions m))
                  (update :writable-fields (partial update-writable-fields p-m m w))
                  (update :protected-fields (partial update-protected-fields p-m m p))))) (first permissions) permissions))

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
  (let [merged-permissions (merge-permissions permissions)
        merged-permissions (into {} (filter (fn [[k v]] (or (boolean? v) (seq v))) merged-permissions))
        final-permissions (-> merged-permissions
                              (#(merge-fields % entity))
                              (#(if (:writable-fields %) (update % :writable-fields set) %))
                              (#(if (:protected-fields %) (update % :protected-fields set) %)))]
    ;; updatedAt is a special field that should always be allowed to be modified whenever any modification is possible
    (if (:modify final-permissions)
      (-> final-permissions 
          (#(if (:writable-fields %) (update % :writable-fields (fn [w] (conj w "updatedAt"))) %))
          (#(if (:protected-fields %) (update % :protected-fields (fn [w] (disj w "updatedAt"))) %)))
      final-permissions)))


; -------------------- new scoped push -------------------------

(defn- speculate [config snapshot transaction]
  (:db-after ((:with config) snapshot transaction)))

(defn push-transaction
  ([config incoming-json internal-user snapshot entities-by-name scoping-edn now]
   (push-transaction config incoming-json internal-user snapshot entities-by-name scoping-edn
                     (internal-push-transaction config incoming-json internal-user snapshot entities-by-name (fn [i _ _ _] [i {}]) now)
                     {:rejected-objects {} :rejected-fields {}} nil))

  ([config incoming-json internal-user snapshot entities-by-name scoping-edn]
   (push-transaction config incoming-json internal-user snapshot entities-by-name scoping-edn
                     (internal-push-transaction config incoming-json internal-user snapshot entities-by-name (fn [i _ _ _] [i {}]))
                     {:rejected-objects {} :rejected-fields {}} nil))

  ([config incoming-json internal-user snapshot entities-by-name scoping-edn push-output rejections cached-snapshot-tags]
   (let [entity (get entities-by-name (first (keys incoming-json)))
         [transaction [created-objects updated-objects] _] push-output
         new-db (speculate config snapshot transaction) ; we simulate the transaction to simplify tag discovery and never look at the ugly incoming-json
                                                        ; the following permissions come from the speculated db snapshot (new-db)
         scoped-tags (future (when scoping-edn (functor/fmap (fn [ids] (set (db/uuids-for-ids config new-db ids))) ; we need uuids not ids this time
                                                             (scoping/scope config new-db internal-user entities-by-name scoping-edn true)))) ; {:NOUser.me #{#uuid1 #uuid2 #uuid3}, :NOMessage.mine #{#uuid4 #uuid5 #uuid6}} (:app/uuid's are the values in sets)
                                        ; the following permissions come from the original db snapshot
         scoped-tags-snapshot (future (or cached-snapshot-tags (when scoping-edn (functor/fmap (fn [ids] (set (db/uuids-for-ids config snapshot ids))) ; we need uuids not ids this time
                                                                                               (scoping/scope config snapshot internal-user entities-by-name scoping-edn true))))) ; {:NOUser.me #{#uuid1 #uuid2 #uuid3}, :NOMessage.mine #{#uuid4 #uuid5 #uuid6}} (:app/uuid's are the values in sets)

         tags-by-ids (when @scoped-tags (functor/fmap (fn [tags] (set (map second tags))) ; inverses the map, values are now sets
                                                      (group-by first
                                                                (u/mapcat (fn [[tag ids]] (map (fn [id] [id tag]) ids))
                                                                          (u/join-maps [@scoped-tags @scoped-tags-snapshot])))))]
     (if (not @scoped-tags) [transaction [created-objects updated-objects] {:rejected-objects {}
                                                                            :rejected-fields {}}]
         (let ;scoping starts here
          [_ ((:log config) (str "----> ORIGINAL TRANSACTION: " (vec transaction)))
           _ ((:log config) (str "---------------------------------------------"))
           _ ((:log config) (str "TAGS: " tags-by-ids))

           permissions-for-object (fn [uuid] ; a helper that takes an uuid and returns the merged permissions map (an object can belong to multiple tags)
                                    (let [tags (get tags-by-ids uuid)
                                          permissions (map #(:permissions (get scoping-edn %)) tags)]
                                      (combined-permissions-for-object permissions entity)))

           objects-from-entry (fn [entry]
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
                                        :else (assert false (str "Unknown transaction entry: " entry)))))


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

           created-uuids (set (u/mapcat keys (vals created-objects)))
           updated-uuids (set (u/mapcat keys (vals updated-objects)))

           rejected-objects (functor/fmap #(filter (fn [o] (let [permissions (or (permissions-for-object (:app/uuid o)) {})
                                                                 {:keys [modify create] :or {modify false create false}} permissions]
                                                             (cond (created-uuids (:app/uuid o)) (not create)
                                                                   (updated-uuids (:app/uuid o)) (not modify)
                                                                   :else (assert false (str "Logic error - object not found" o))))) %) 
                                          (functor/fmap #(if (empty? %) [] (vals %)) (merge-with merge created-objects updated-objects)))


           rejected-id-candidates (functor/fmap #(set (map :db/id %)) rejected-objects)
              ;; _ ((:log config) "Rejected candidates: " rejected-id-candidates)

           [transaction-after-object-rejections true-rejection-ids rejected-statements-by-id] 
           (let [rejected-ids (apply union (vals rejected-id-candidates))
                 new-transaction (filter (comp not (partial objects-in-entry? rejected-ids)) transaction)
                 rejected-statements (vec (difference (set transaction) (set new-transaction)))
                 rejected-objects-per-statement (map objects-from-entry rejected-statements)
                 possible-rejected-ids (apply union (map set rejected-objects-per-statement))
                 rejected-ids (intersection rejected-ids possible-rejected-ids)

                 rejected-statements-per-object (functor/fmap 
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
           objects-to-filter-fields (functor/fmap
                                     #(filter
                                       (fn [o]
                                         (let [permissions (or (permissions-for-object (:app/uuid o)) {})
                                               {:keys [modify protected-fields writable-fields] :or {modify false}} permissions]
                                           (and modify (or protected-fields writable-fields)))) %)
                                     (functor/fmap #(if (empty? %) [] (vals %)) updated-objects))

           excise-field-from-transaction (fn [entity object-id transaction field-name]  ; object-id is assumed to be internal db 
                                           ;; Returns transaction with operations modifying a field agains permissions excised.
                                           (let [attribute (get (:attributes entity) field-name)
                                                 relationship (get (:relationships entity) field-name)
                                                 datomic-relationship (get (:datomic-relationships entity) field-name)
                                                 inverse-relationship (when relationship (get (:relationships (get entities-by-name (:inverse-entity relationship))) (:inverse-name relationship)))]
                                             (assert (or attribute relationship) (str "Logic error: " (:name entity) " obj-id " object-id " trans: " (vec transaction) " field-name: " field-name))
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
                                                                 check-entry (if attribute check-attribute-entry check-relationship-entry)]
                                                             (case op
                                                               :db/add (let [[_ id datomic-field target] entry] (check-entry op id datomic-field nil target))
                                                               :db/retract (let [[_ id datomic-field target] entry] (check-entry op id datomic-field nil target))
                                                               :db/cas (let [[_ id datomic-field old-target new-target] entry] (check-entry op id datomic-field old-target new-target))
                                                               :db.fn/cas (let [[_ id datomic-field old-target new-target] entry] (check-entry op id datomic-field old-target new-target))
                                                               true)))) transaction)))
           [transaction-after-field-rejections field-rejections] (letfn [(reject-fields [t entity object remaining-fields rejections]
                                                                           (if-let [field (first remaining-fields)]
                                                                             (let [new-transaction (excise-field-from-transaction entity (:db/id object) t field)
                                                                                   excised? (not= (count t) (count new-transaction))]
                                                                               (recur new-transaction
                                                                                      entity
                                                                                      object
                                                                                      (rest remaining-fields)
                                                                                      (if excised? (update-in rejections [(:name entity) (:app/uuid object)] 
                                                                                                              #(conj (or % #{}) 
                                                                                                                     {:field field
                                                                                                                      :rejected-statements (vec (difference (set t) (set new-transaction)))})) 
                                                                                          rejections)))
                                                                             [t rejections]))
                                                                         (reject-objects [t entity remaining-objects rejections]
                                                                           (if-let [object (first remaining-objects)]
                                                                             (let [permissions (or (permissions-for-object (:app/uuid object)) {})
                                                                                   {:keys [protected-fields writable-fields] :or {protected-fields #{} writable-fields #{}}} permissions
                                                                                   fields-to-remove (vec (if (seq protected-fields) protected-fields
                                                                                                             (difference
                                                                                                              (set (concat (keys (:attributes entity)) (keys (:relationships entity))))
                                                                                                              writable-fields)))
                                                                                   [new-transaction new-rejections] (reject-fields t entity object fields-to-remove rejections)
                                                                                   excised? (not= (count t) (count new-transaction))]
                                                                               (recur new-transaction
                                                                                      entity
                                                                                      (rest remaining-objects)
                                                                                      (if excised? (update-in new-rejections [(:name entity) (:app/uuid object)] 
                                                                                                              #(conj % {:tags (get tags-by-ids(:app/uuid object))
                                                                                                                        :permissions permissions})) rejections)))
                                                                             [t rejections]))
                                                                         (reject-entities [t remaining-objects rejections]
                                                                           (if-let [[entity-name objects] (first remaining-objects)]
                                                                             (let [[new-transaction new-rejections] (reject-objects t (get entities-by-name entity-name) objects rejections)
                                                                                   excised? (not= (count t) (count new-transaction))]
                                                                               (recur new-transaction
                                                                                      (rest remaining-objects)
                                                                                      (if excised? new-rejections rejections)))
                                                                             [t rejections]))]
                                                                   (reject-entities transaction-after-object-rejections (vec objects-to-filter-fields) {}))]
           (if (and (empty? rejected-object-info)
                    (empty? field-rejections))
             (let [ids-in-transaction (apply union (map objects-from-entry transaction))
                   filtered-created-objects (functor/fmap #(into {} (filter (fn [[k o]] (ids-in-transaction (:db/id o))) %)) created-objects)
                   filtered-updated-objects (functor/fmap #(into {} (filter (fn [[k o]] (ids-in-transaction (:db/id o))) %)) updated-objects)]
               ((:log config) "-----------------------------")
               ((:log config) (str "REJECTIONS: " rejections))
               ((:log config) (str "FINAL TRANSACTION: " (vec transaction)))
               ((:log config) "-----------------------------")
               ((:log config) (str "OBJECTS:" filtered-created-objects filtered-updated-objects))
               ((:log config) "=======================================")
               [transaction [filtered-created-objects filtered-updated-objects] rejections]) ; we have removed all that was needed in the former steps
             (recur config incoming-json internal-user snapshot entities-by-name scoping-edn [transaction-after-field-rejections [created-objects updated-objects] {}]
                    {:rejected-objects (merge-with union rejected-object-info (:rejected-objects rejections))
                     :rejected-fields (merge-with #(merge-with union %1 %2) field-rejections (:rejected-fields rejections))}
                    @scoped-tags-snapshot)))))))
