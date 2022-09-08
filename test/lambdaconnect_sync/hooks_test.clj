(ns lambdaconnect-sync.hooks-test
  (:require [clojure.test :refer [deftest is testing]]
            [lambdaconnect-sync.hooks :as hooks]))

(deftest get-ids-from-entry
  (let [relationships #{:rel}]
    (testing "map entry"
      (is (= #{1} (hooks/get-ids-from-entry relationships {:db/id 1})))
      (is (= #{1} (hooks/get-ids-from-entry relationships {:db/id 1 :attr 2})))
      (is (= #{1 2} (hooks/get-ids-from-entry relationships {:db/id 1 :rel 2})))
      (is (= #{1 2} (hooks/get-ids-from-entry relationships {:db/id 1 :rel 2 :attr 3}))))
    (testing ":db/add entry"
      (is (= #{3} (hooks/get-ids-from-entry relationships [:db/add 3 :attr 4])))
      (is (= #{3 4} (hooks/get-ids-from-entry relationships [:db/add 3 :rel 4]))))
    (testing ":db/cas entry"
      (is (= #{3} (hooks/get-ids-from-entry relationships [:db/cas 3 :attr 4 5])))
      (is (= #{3 4 5} (hooks/get-ids-from-entry relationships [:db/cas 3 :rel 4 5]))))
    (testing ":db/retract entry"
      (is (= #{6} (hooks/get-ids-from-entry relationships [:db/retract 6 :attr 7])))
      (is (= #{6 7} (hooks/get-ids-from-entry relationships [:db/retract 6 :rel 7]))))
    (testing ":db/retractEntity entry"
      (is (= #{8} (hooks/get-ids-from-entry relationships [:db/retractEntity 8]))))))