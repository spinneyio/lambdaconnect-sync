(ns lambdaconnect-sync.db
  (:require [lambdaconnect-model.tools :as t]
            [clojure.set :as sets]
            [lambdaconnect-sync.db-interface :as i]))


(defn uuid [] #?(:clj (java.util.UUID/randomUUID)
                 :cljs (random-uuid)))

(defn speculate [config snapshot transaction]
  (i/speculate (:driver config) snapshot transaction))

(defn get-objects-by-ids
  ([config entity ids snapshot] 
   (get-objects-by-ids config entity ids snapshot false))
  ([config entity ids snapshot fetch-inverses]
   (i/objects-by-ids (:driver config) snapshot entity ids fetch-inverses)))

(defn check-uuids-for-entity [config uuids entity-name snapshot]
  (i/check-uuids-for-entity (:driver config) snapshot entity-name uuids))

(defn get-id-for-uuid [config snapshot entity uuid]
  (i/id-for-uuid (:driver config) snapshot entity uuid))

(defn get-sync-revision [config snapshot]
  (i/sync-revision (:driver config) snapshot))

(defn as-of [config snapshot sync-revision]
  (i/as-of (:driver config) snapshot sync-revision))

(defn get-objects-by-uuids
  ([config entity uuids snapshot] 
   (get-objects-by-uuids config entity uuids snapshot false))
  ([config entity uuids snapshot fetch-inverses]
   (i/objects-by-uuids (:driver config) snapshot entity uuids fetch-inverses)))

(defn get-misclasified-objects [config snapshot entity uuids]
  (i/misclassified-objects (:driver config) snapshot entity uuids))

(defn object-with-ident? [object]
  (let [attr-names (->> object keys (map name) set)]
    (contains? attr-names "ident__")))

(defn get-objects-without-ident [objects]
  (filter (complement object-with-ident?) objects))

(defn get-invalid-objects [config snapshot entity uuids]
  (let [misclasified-objects (get-misclasified-objects config snapshot entity uuids)
        objects-without-ident (get-objects-without-ident misclasified-objects)
        misclasified-objects-uuids (doall (map :app/uuid misclasified-objects))
        objects-without-ident-uuids (doall (map :app/uuid objects-without-ident))]
    {:misclasified misclasified-objects-uuids
     :without-ident objects-without-ident-uuids}))

(defn get-related-objects
  "Fetches the objects that refer to the list of uuids by their relationship"
  [config relationship uuids snapshot]
  (i/related-objects (:driver config) snapshot relationship uuids)
)

(defn get-referenced-objects
  [config relationship uuids snapshot]
  (i/referenced-objects (:driver config) snapshot relationship uuids))

(defn get-reciprocal-objects
  [config relationship inverse uuids snapshot]
  (assert (or relationship inverse))
  (assert (not (and relationship inverse)))
  (if relationship 
    (get-related-objects config relationship uuids snapshot)
    (get-referenced-objects config inverse uuids snapshot)))

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
   (i/changed-ids (:driver config) snapshot (get entities-by-name entity-name) transaction-since-number scoped-ids)))

(defn uuids-for-ids [config snapshot ids]
  (i/uuids-for-ids (:driver config) snapshot ids))
