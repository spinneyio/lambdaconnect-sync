(ns lambdaconnect-sync.integrity-constraints
  (:require [clojure.set :as s]
            [lambdaconnect-sync.db :as db]))

(defn- set-last [coll x]
  ; Replace last element of coll by x
  (conj (pop coll) x))

(defn get-uuids-from-json [entity-name push-input]
  (into #{} (map :app/uuid (get push-input entity-name))))

(defn check-many [config push-input snapshot entity-name relation-name destination-entity-name]
  (let [uuids (->> entity-name
                   (get push-input)
                   (map (fn [object] ((keyword entity-name relation-name) object)))
                   (apply concat)
                   (map :app/uuid)
                   (into #{}))
;        _ (println "!!!! Check many uuids " uuids)
;        _ (println "CHECK MANY!!!!!! " uuids push-input)
        uuids-from-json (db/check-uuids-for-entity config uuids destination-entity-name snapshot)
        uuids-from-db (get-uuids-from-json destination-entity-name push-input)
        all-uuids  (s/union uuids-from-json uuids-from-db #{nil})
 ;       _ (println uuids-from-json uuids-from-db all-uuids)
        ]
    [entity-name relation-name (s/subset? uuids all-uuids) (s/difference uuids all-uuids)]))

(defn check-one [config push-input snapshot entity-name relation-name destination-entity-name]
  (let [uuids (->> entity-name
                   (get push-input)
                   (map (fn [object] ((keyword entity-name relation-name) object)))
                   (map :app/uuid)
                   (into #{}))
;        _ (println "!!!! Check one uuid" uuids)
 ;       _ (println "CHECK ONE!!!!!! " uuids push-input)
        uuids-from-json (get-uuids-from-json destination-entity-name push-input)
        uuids-from-db (db/check-uuids-for-entity config uuids destination-entity-name snapshot)
        all-uuids  (s/union uuids-from-json uuids-from-db #{nil})]
    [entity-name relation-name (s/subset? uuids all-uuids) (s/difference uuids all-uuids)]))

(defn check [config push-input snapshot [relation-name destination-entity-name to-many entity-name]]
  (if to-many
    (check-many config push-input snapshot entity-name relation-name destination-entity-name)
    (check-one config push-input snapshot entity-name relation-name destination-entity-name)))

(defn check-foreign-key [config push-input snapshot relations]
  (let [check-all-entity-relations  (fn [x] (filter (fn [rel] (let [[_ _ valid-uuids? _difference] (check config push-input snapshot rel)] (not valid-uuids?))) x))
        get-all-invalid-uuids  (fn [x] (map (fn [rel] (let [[entity relation _valid-uuids? difference] (check config push-input snapshot rel)] (when-not (empty? difference) [entity relation difference]))) x))
        invalid-relations-by-entities  (filter seq (map check-all-entity-relations relations))
        invalid-uuids (filter (fn [res] (and res (not= #{nil} (last res)))) (apply concat (map get-all-invalid-uuids relations)))
        filtered-invalid-uuids (map (fn [l] (set-last l (keep identity (last l)))) invalid-uuids)
        invalid-entities (map (fn [entity-rels] (last (first entity-rels))) invalid-relations-by-entities)
        invalid-relations (map (fn [entity-rels] (map #(first %) entity-rels)) invalid-relations-by-entities)
        response (into {} (map vector invalid-entities invalid-relations))
        result (empty? response)]
    [result response filtered-invalid-uuids]))

(defn entity-names [entities-by-name]
  (filter #(not= % "NOSynchData") (keys entities-by-name)))

(defn relations [entities-by-name]
  (let [names (entity-names entities-by-name)
        relation-maps-with-names  (map #(list (:relationships (get entities-by-name %)) %) names)
        relation-maps-values-with-names  (map (fn [[map name]] (list (vals map) name)) relation-maps-with-names)]
    (map
     (fn [[list-rels name]]
       (map #(flatten (list (vals (select-keys % [:name :destination-entity :to-many])) name)) list-rels))
     relation-maps-values-with-names)))
