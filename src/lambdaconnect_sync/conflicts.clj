(ns lambdaconnect-sync.conflicts
  (:require [clojure.set :refer [difference union]]
            [lambdaconnect-sync.db :as db]
            [lambdaconnect-model.core :as mp]))

(defn override-creation-attributes
  "This function is applied to a freshly created object to set up its initial state. It is executed after the object was decoded from the input JSON.
   Note that the object will not contain any relationships nor the :db/id"
  [object _internal-user _snapshot _entity now]
  (-> object
      (assoc :app/createdAt now)
      (assoc :app/updatedAt now)))

(defn override-update-attributes
  "This function is applied to an object that was just updated to alter its final state."
  [object _internal-user _snapshot _entity now]
  (-> object
      (dissoc :app/createdAt) ; we no longer accept updates to createdAt after the object was created
      (assoc :app/updatedAt now)))

(defn resolve-modification-conflicts
  "This should throw an exception if the update is not allowed
   and solve any merge conflicts. This function must be pure as it is not certain if the database will be saved or rolled back."
  [config old-object new-object entity _internal-user snapshot]
  (let [update-active #(if (and old-object
                                (not (:app/active old-object))
                                (not (nil? (:app/active old-object)))
                                (:app/active %))
                         (assoc % :app/active false) ; we do not allow re-enabling objects    
                         %)
        resolve-field-conflicts (fn [new]
                                  (if-let [sync-revision (:app/syncRevision new-object)]
                                    (if (and old-object (not (string? (:db/id old-object)))) ; old-object can be a freshly created one - then there is no conflict possible 
                                      (if-let [base-snapshot ((:as-of config) snapshot sync-revision)]
                                        (if-let [base-object (mp/replace-inverses (first (db/get-objects-by-ids config entity [(:db/id old-object)] base-snapshot true)) entity true)]
                                        ; so here we go, an update operation and three objects to compare:
                                        ; 
                                        ; base-object - what the user has last seen - the original value (for them)
                                        ; old-object - the object that sits in the db right now
                                        ; new-object - the new version of object the user wants to push
                                        ;
                                        ; Now the conflict resolution goes like this:
                                        ; 1. If the base-object and new-object have the same value - old-object wins (because old-object cannot be older than base-object
                                        ; 2. If the base-object and new-object have different values (the user MEANT to update)
                                        ;     a) If it is an attribute or to-one relationship 
                                        ;        --> We take updatedAt (before mangling) from new-object and old-object - the one whitch is more recent wins
                                        ;     b) If it is a to-many relationship
                                        ;        --> We take A = new-object \ base-object, B = base-object \ new-object and take:
                                        ;                (old-object u A) \ B 
                                          (merge
                                           (into {} (map (fn [attr-or-to-one]
                                                           (let [kw (mp/datomic-name attr-or-to-one)]
                                                             (when (contains? new kw)
                                                               (if (or (= (kw base-object) (kw new))    ; 1. (attribute)
                                                                       (and
                                                                        (:destination-entity attr-or-to-one) ; we make sure it is a relationship
                                                                        (= (:app/uuid (kw base-object)) ; 1. (relationship)
                                                                           (:app/uuid (kw new)))))
                                                                 (do
                                                                   ((:log config) (str (:name attr-or-to-one) " - Conflict resolution case I - selected old: '" (kw base-object) "', '" (kw new) "', '" (kw old-object) "'"))
                                                                   [kw (kw old-object)]) ; 1.
                                                                 (let [old-updated (:app/updatedAt old-object)
                                                                       new-updated (:app/updatedAt new)]
                                                                   (assert (and old-updated new-updated)
                                                                           (str "We should have updatedAt for old and new versions of the object already. Old object: " old-object " new object: "  new-object))
                                                                   (if (.after old-updated new-updated) ; 2. a)
                                                                     (do
                                                                       ((:log config) (str (:name attr-or-to-one) " - Conflict resolution case IIa - selected old: '" (kw base-object) "', '" (kw new) "', '" (kw old-object) "'"))
                                                                       [kw (kw old-object)])
                                                                     (do
                                                                       ((:log config) (str (:name attr-or-to-one) " - Conflict resolution case IIa - selected new: '" (kw base-object) "', '" (kw new) "', '" (kw old-object) "'"))
                                                                       [kw (kw new)])))))))
                                                         (concat (vals (:attributes entity)) (filter #(not (:to-many %)) (vals (:relationships entity))))))
                                           (into {} (map (fn [rel-to-many]
                                                           (let [kw (mp/datomic-name rel-to-many)]
                                                             (when (contains? new-object kw) ; 2. b)
                                                               (let [old-set (set (map (fn [m] {:app/uuid (:app/uuid m)}) (kw old-object))) ; we get rid of :db/id since we dont have it in new-set
                                                                     base-set (set (map (fn [m] {:app/uuid (:app/uuid m)}) (kw base-object))) ; we get rid of :db/id since we dont have it in new-set
                                                                     new-set (set (kw new))
                                                                     A (difference new-set base-set)
                                                                     B (difference base-set new-set)]
                                                                 ((:log config) (str (:name rel-to-many) " - Conflict resolution case IIb: " (vec (kw base-object)) ", " (vec (kw new)) ", " (vec (kw old-object))))
                                                                 ((:log config) (str (:name rel-to-many) " - SETS: " old-set ", " base-set ", " new-set ", " A ", " B))
                                                                 [kw (vec (difference (union old-set A) B))])))) (filter :to-many (vals (:relationships entity))))))
                                          new) new) new) new))]
    ((:log config) (str "Before conflict resolution: " new-object))
    ((:log config) (str "After conflict resolution: " (-> new-object
                                                          resolve-field-conflicts
                                                          update-active)))
    (-> new-object
        resolve-field-conflicts
        update-active)))

