(ns lambdaconnect-sync.db-interface)


(defprotocol IDatabaseDriver
  "Database driver protocol. Allows lambdaconnect-sync to interact with arbitrary database.
   Entity or relationship refers to objects taken from entities-by-name."
  (speculate [this snapshot transaction]
    "Returns a new snapshot with transaction applied to it (temporarily)")
  
  (objects-by-ids [this snapshot entity ids fetch-inverses?]
    "Fetches objects that match the entity and ids (as any sequence). Objects that do not conform to entity will not be returned.
     The format is:

     {:app/uuid #\"some-uuid\"
      :app/createdAt #some-date
      :app/updatedAt #some-date
      :NOUser/someAttribute #some-value
      :NOUser/someRelationshipToMany [{:app/uuid #uuid :db/id #id} ...]
      :NOUser/someRelationshipToOne {:app/uuid #uuid :db/id #id}
      :NOLocation/_thisIsSomeInverseRelationshipToMany [{:app/uuid #uuid :db/id #id} ...] ;; if fetch-inverse? is true
      :NOLocation/_thisIsSomeInverseRelationshipToOne [{:app/uuid #uuid :db/id #id}] ;; if fetch-inverse? is true. Note that inverses are a collection even in to-one case.
     }

     Please note that it is not guaranteed what collection holds relationships. It might be a vector, list, lazy coll or even a set (since relationships are never ordered).")
  
  (objects-by-uuids [this snapshot entity uuids fetch-inverses?]
    "Fetches objects that match the entity and uuids. Objects that do not conform to entity will not be returned. 
     Returns them in the same format as objects-by-ids.")
  
  (check-uuids-for-entity [this snapshot entity-name uuids]
    "Returns a subset of uuids (uuids is a sequence, retval is a set) that belong to given entity.")  
  
  (id-for-uuid [this snapshot entity uuid]
    "Returns db id for uuid/entity pair, or nil if there is any mismatch.")
  
  (sync-revision [this snapshot]
    "Returns sync revision of a snapshot.")

  (as-of [this snapshot sync-revision]
    "Returns database as if it was viewed through given sync revision.")

  (misclassified-objects [this snapshot entity uuids]
    "Returns objects (in the form given by objects-by-ids with inverse:false) 
     that do not belong to entity but are found in uuids map.")

  (related-objects [this snapshot relationship uuids]
    "For a given relationship and list of uuids, 
     returns a set of uuids (or nil) that represent objects refering to the 
     ones in uuids collection through relationship.")

  (referenced-objects [this snapshot relationship uuids]
    "For a given relationship and list of uuids, 
     returns a set of uuids (or nil) that represent objects referenced by the 
     ones in uuids collection through relationship.")

  (changed-ids [this snapshot entity sync-revision scoped-ids]
    "Returns internal ids of entities that have been modified 
     since sync-revision version of db. If scoped-ids is present, only a subset of 
     scoped-ids is returned. ")

  (uuids-for-ids [this snapshot ids] 
    "Returns uuids for given internal ids."))

