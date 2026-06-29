(ns sng.store-contract-test
  "Backend-swap contract: MemStore ≡ DatomicStore. The same seed + the same
  reads must agree, and the actor's attest/pathway verdicts must be identical on
  either backend — the property that lets the kotoba-server pod (kotobase.net)
  drop in via langchain.kotoba-db with the same record."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [sng.store :as store]
            [sng.synthesis :as syn]))

(defn- attest [actor tid bid]
  (g/run* actor {:request {:op :batch/attest :batch bid} :context {:phase 3}}
          {:thread-id tid}))

(deftest mem-and-datomic-reads-agree
  (testing "the two backends return equal domain reads for the demo data"
    (let [m (store/seed-db) d (store/datomic-seed-db)]
      (is (= (mapv :id (store/all-facilities m))
             (mapv :id (store/all-facilities d))))
      (is (= (mapv :id (store/all-batches m))
             (mapv :id (store/all-batches d))))
      (is (= (store/facility m "fac-jp") (store/facility d "fac-jp")))
      (is (= (store/batch m "bt-jp") (store/batch d "bt-jp")))
      (is (= (set (store/feedstock-of m "bt-comm"))
             (set (store/feedstock-of d "bt-comm"))))
      (is (= (set (store/storage-of m "fac-jp")) (set (store/storage-of d "fac-jp"))))
      ;; multi-row child reads are order-independent (datalog has no inherent order)
      (is (= (set (store/leak-survey-of m "fac-jp")) (set (store/leak-survey-of d "fac-jp")))))))

(deftest verdicts-match-across-backends
  (testing "clean → commit on both; commercial-CO₂ → hold on both"
    (let [ma (syn/build (store/seed-db))
          da (syn/build (store/datomic-seed-db))]
      (is (= :commit
             (get-in (attest ma "m1" "bt-jp") [:state :disposition])
             (get-in (attest da "d1" "bt-jp") [:state :disposition])))
      (is (= :hold
             (get-in (attest ma "m2" "bt-comm") [:state :disposition])
             (get-in (attest da "d2" "bt-comm") [:state :disposition]))))))
