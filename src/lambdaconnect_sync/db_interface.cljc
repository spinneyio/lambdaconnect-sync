(ns lambdaconnect-sync.db-interface)

;; entity or relationship refers to objects taken from entities-by-name 

(defprotocol IDatabaseDriver

  ;; Returns a new snapshot with transaction applied to it (temporarily)
  (speculate [this snapshot transaction])

  ;; Fetches objects that match the endity and ids (as any sequence).
  ;; The format is 
  ;; {:app/uuid #"some-uuid"
  ;;  :app/createdAt #some-date
  ;;  :app/updatedAt #some-date
  ;;  :NOUser/someAttribute #some-value
  ;;  :NOUser/someRelationshipToMany [{:app/uuid #uuid :db/id #id} ...]
  ;;  :NOUser/someRelationshipToOne {:app/uuid #uuid :db/id #id}
  ;;  :NOLocation/thissomeInverseRelationshipToMany [{:app/uuid #uuid :db/id #id} ...] ;; if fetch-inverse? is true
  ;;  :NOLocation/thissomeInverseRelationshipToOne [{:app/uuid #uuid :db/id #id}] ;; if fetch-inverse? is true
  ;; }
  (objects-by-ids [this snapshot entity ids fetch-inverses?])

  ;; Fetches objects that match the enetity and uuids. Returns them in the same format as 
  ;; objects-by-ids
  (objects-by-uuids [this snapshot entity uuids fetch-inverses?])

  ;; Returns a subset of uuids (uuids is a sequence, retval is a set) that belong to given entity.
  (check-uuids-for-entity [this snapshot entity-name uuids])  

  ;; Returns db id for uuid/entity pair
  (id-for-uuid [this snapshot entity uuid])

  ;; Returns sync revision of a snapshot
  (sync-revision [this snapshot])

  ;; Returns database as if it was viewed through gives sync revision
  (as-of [this snapshot sync-revision])

  ;; Returns objects (in the form given by objects-by-ids with inverse:false) 
  ;; that do not belong to entity but are found in uuids map
  (misclassified-objects [this snapshot entity uuids])

  ;; For a given relationship and list of uuids, 
  ;; returns a set of uuids (or nil) that represent objects refering to the 
  ;; ones in uuids collection through relationship.
  (related-objects [this snapshot relationship uuids])

  ;; For a given relationship and list of uuids, 
  ;; returns a set of uuids (or nil) that represent objects referenced by the 
  ;; ones in uuids collection through relationship.

  (referenced-objects [this snapshot relationship uuids])

  ;; Returns internal ids of entities that have been modified 
  ;; since sync-revision version fo db. If scoped-ids is present, only a subset of 
  ;; scoped-ids is returned
  (changed-ids [this snapshot entity sync-revision scoped-ids])

  ;; Returns uuids for given internal ids
  (uuids-for-ids [this snapshot ids]))

