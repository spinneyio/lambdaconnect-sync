(ns lambdaconnect-sync.core
  (:require [lambdaconnect-sync.db :as db]
            [lambdaconnect-sync.hooks :as hooks]
            [lambdaconnect-sync.pull :as pull]
            [lambdaconnect-sync.push :as push]
            [lambdaconnect-sync.serialisers :as serialisers]))

(def push-transaction push/push-transaction)

(def compute-rel-objs-to-fetch push/compute-rel-objs-to-fetch)

(def pull pull/pull)

(def update-ids-in-transaction hooks/update-ids-in-transaction)
(def send-specific-hooks! hooks/send-specific-hooks!)

(def serialise-collection serialisers/serialise-collection)
(def serialise-collection-keywords serialisers/serialise-collection-keywords)

(def get-changed-ids db/get-changed-ids)
(def get-related-objects db/get-related-objects)


;; Takes datomic-relationships and transaction. Datomic-relationships is is a set of all
;; the relationships that can appear in transactions (as keywords)

(def get-ids-from-transaction hooks/get-ids-from-transaction)

;; Extracts all datomic relationships as sets of keywords from datomic schema
(def get-datomic-relationships hooks/get-datomic-relationships)

;; Extracts all datomic relationships as sets of keywords from model
(def get-datomic-relationships-from-model hooks/get-datomic-relationships-from-model)
