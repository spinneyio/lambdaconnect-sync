(ns tests
    (:require
     [lambdaconnect-sync.test.hooks]
     [cljs.analyzer.api :as ana-api]    
     [clojure.pprint :refer [pprint]]
     [cljs.test :refer-macros [run-tests run-all-tests]
                   :refer [empty-env]]))
	
(enable-console-print!)

(defn test-all []  
  (let [output (with-out-str 
                 (run-all-tests #".*\.test\..*" 
                                
                                (merge (empty-env) {:formatter #(if (instance? js/Error %) 
                                                                  (with-out-str 
                                                                    (-> % 
                                                                        (.-stack) 
                                                                        (pprint)))
                                                                  (pr-str %))})
                                ))
        failures (->> output 
                      (re-find #"([0-9]+) failures")
                      last
                      int)
        errors (->> output 
                    (re-find #"([0-9]+) errors")
                    last
                    int)]
    (clj->js {:success (= 0 (+ failures errors))
              :output output})))
