(ns lambdaconnect-sync.test.basic
  (:require  [datomic.api :as d]
             [clojure.spec.gen.alpha :as gen]
             [lambdaconnect-model.core :as mp]))

(defn get-mobile-sync-config []
  {:log (constantly nil)
   :as-of d/as-of
   :pull d/pull
   :pull-many d/pull-many
   :q d/q
   :history d/history
   :tx->t d/tx->t
   :with d/with
   :basis-t d/basis-t})

(def mobile-sync-config (get-mobile-sync-config))
(def conn (atom nil))

(defn setup-basic-test-environment [model-path generators f]
  (let [db-name "datomic:mem://test-db"
        entities-by-name (mp/entities-by-name model-path)]    
    (d/create-database db-name)

    (reset! conn (d/connect db-name))
    @(d/transact @conn [{:db/ident              :app/uuid
                         :db/valueType          :db.type/uuid
                         :db/cardinality        :db.cardinality/one
                         :db/unique             :db.unique/identity
                         :db/doc                "UUID for an object in the system"}
                        
                        {:db/ident              :app/createdAt
                         :db/valueType          :db.type/instant
                         :db/cardinality        :db.cardinality/one
                         :db/doc                "Creation time"}
                        
                        {:db/ident              :app/updatedAt
                         :db/valueType          :db.type/instant
                         :db/cardinality        :db.cardinality/one
                         :db/doc                "Last update time"}
                        
                        {:db/ident              :app/active
                         :db/valueType          :db.type/boolean
                         :db/cardinality        :db.cardinality/one
                         :db/doc                "If false, it means the entity was deleted"}])
    @(d/transact @conn (mp/datomic-schema entities-by-name))   
    (mp/specs entities-by-name generators)
    (try (f)
         (finally           
           (d/delete-database db-name)
           (reset! conn nil)))))
