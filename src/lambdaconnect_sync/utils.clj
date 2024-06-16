(ns lambdaconnect-sync.utils
  (:require [clojure.set :refer [union]]))

(defn mapcat
  ; We need our own implementation, see http://clojurian.blogspot.com/2012/11/beware-of-mapcat.html
  ([f coll] 
   (lambdaconnect-sync.utils/mapcat f coll (lazy-seq [])))
  ([f coll acc]
   (if (empty? coll)
     acc
     (recur f (rest coll) (lazy-seq (concat acc (f (first coll))))))))

(defn join-maps
  ([maps]
   ; The default merge method assumes that the maps contain sets. Use 'merge' for maps inside maps
   (join-maps union maps))
  ([merge-func maps]
   ; The maps are merged using the merge-func function ('merge' for maps?, 'union' for sets?).
   (cond
     (-> maps first not) {}
     (-> maps next  not) (first maps)
     (-> maps next next not) (merge-with merge-func (first maps) (second maps))
     :else (recur merge-func (conj (-> maps next next) (merge-with merge-func (first maps) (second maps)))))))

(defn group-by-keysets 
  "Takes a collection and a function that either returns a single key or a set of keys.
   Creates a map keyed with all the keys and vectors of elements matching keys as values."
  [f coll]  
  (persistent!
   (reduce
    (fn [ret x]
      (let [keyset (f x)
            keyset (if (set? keyset) keyset #{keyset})]
        (reduce #(assoc! %1 %2 (conj (get %1 %2 []) x)) ret keyset)))
    (transient {}) coll)))
