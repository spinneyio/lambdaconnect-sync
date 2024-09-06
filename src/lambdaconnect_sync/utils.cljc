(ns lambdaconnect-sync.utils
  (:require [clojure.set :refer [union]]
            [lambdaconnect-model.utils :as u]))

(defn join-maps
  ([maps]
   ; The default merge method assumes that the maps contain sets. Use 'merge' for maps inside maps
   (apply (partial u/merge-with union) maps))
  ([merge-func maps]
   (apply (partial u/merge-with merge-func) maps)))

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

#?(:clj (defn stacktrace [e]
          (let [sw (java.io.StringWriter.)
                pw (java.io.PrintWriter. sw)]
            (.printStackTrace e pw)
            (.toString sw))))


#?(:clj (defn exception-description [e]
          (str (class e) " - " (.getMessage e) "\nReceived in: " (stacktrace e)))
   :cljs (defn exception-description [e] (str e)))

