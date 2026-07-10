(ns sng.governor-contract-test
  "The carbon-discipline contract as executable tests — sng's analog of
  robotaxi's safety_contract_test / itonami's governor_contract_test /
  kyoninka's governor_contract_test. Invariant: the actor never records a batch
  attestation the CarbonGovernor would reject, never auto-approves a pathway
  selection, and always records observations."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [sng.store :as store]
            [sng.synthllm :as synthllm]
            [sng.synthesis :as syn]))

(defn- fresh [] (let [s (store/seed-db)] [s (syn/build s)]))
(defn- ctx [phase] {:phase phase})

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context (ctx phase)} {:thread-id tid}))

(defn- last-basis [s] (-> (store/ledger s) last :basis))

(deftest ingest-always-records
  (testing "observe path records a ground datom regardless of phase"
    (let [[s actor] (fresh)
          res (run actor "i" {:op :feedstock/record :batch "bt-jp"
                              :value {:class :green-h2 :cid "bafytest" :kg 5}} 0)]
      (is (= :record (get-in res [:state :disposition])))
      (is (some #(= :green-h2 (:class %)) (store/feedstock-of s "bt-jp"))))))

(deftest clean-batch-auto-commits
  (testing "a clean Sabatier batch auto-commits in phase 3 (not high-stakes)"
    (let [[s actor] (fresh)
          res (run actor "a" {:op :batch/attest :batch "bt-jp"} 3)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :ready-to-attest (:recommendation (store/assessment-of s "bt-jp"))))
      (is (= :auto (:by (store/assessment-of s "bt-jp")))))))

(deftest commercial-co2-is-held-and-unoverridable
  (testing "bt-comm: commercial-CO₂ feedstock — ABSOLUTELY PROHIBITED (D1+D3+§2(d))"
    (let [[s actor] (fresh)
          res (run actor "c" {:op :batch/attest :batch "bt-comm"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:commercial-co2} (last-basis s)))
      (is (nil? (store/assessment-of s "bt-comm")) "nothing recorded on hold"))))

(deftest proprietary-catalyst-is-held
  (testing "bt-prop: proprietary catalyst (HiFUEL R110) — PROHIBITED (D5)"
    (let [[s actor] (fresh)
          res (run actor "p" {:op :batch/attest :batch "bt-prop"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:proprietary-catalyst} (last-basis s)))
      (is (nil? (store/assessment-of s "bt-prop"))))))

(deftest high-temp-without-council-is-held
  (testing "bt-hot: 380 °C at council-level 5 (< Lv6+≥3)"
    (let [[s actor] (fresh)
          res (run actor "h" {:op :batch/attest :batch "bt-hot"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:high-temp-without-council} (last-basis s)))
      (is (nil? (store/assessment-of s "bt-hot"))))))

(deftest aggregate-cap-exceeded-is-held
  (testing "combined biomethane+SNG > 200 Nm³/day cap → HOLD (R3)"
    (let [[s actor] (fresh)]
      ;; demo total = 4×40 = 160; register two more 40-Nm³ batches → 240 > 200
      (run actor "e1" {:op :batch/register :batch "bt-x1"
                       :value {:id "bt-x1" :facility "fac-jp" :pathway :sabatier
                               :catalyst-formula :ni-alumina-open :op-temp 320
                               :op-pressure 8 :nm3 40 :mass-balance-pct 96}} 3)
      (run actor "e2" {:op :batch/register :batch "bt-x2"
                       :value {:id "bt-x2" :facility "fac-jp" :pathway :sabatier
                               :catalyst-formula :ni-alumina-open :op-temp 320
                               :op-pressure 8 :nm3 40 :mass-balance-pct 96}} 3)
      (run actor "e3" {:op :feedstock/record :batch "bt-x1"
                       :value {:class :green-h2 :cid "bafyx1h2" :kg 20}} 3)
      (run actor "e4" {:op :feedstock/record :batch "bt-x1"
                       :value {:class :dac-co2 :cid "bafyx1co2" :kg 110}} 3)
      (let [res (run actor "a" {:op :batch/attest :batch "bt-x1"} 3)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:aggregate-cap-exceeded} (last-basis s)))))))

(deftest pathway-select-always-interrupts
  (testing "a pathway selection is high-stakes → always Council signoff, never auto"
    (let [[s actor] (fresh)
          r1 (run actor "p" {:op :pathway/select :batch "bt-jp"} 3)]
      (is (= :interrupted (:status r1)) "pathway is high-stakes → always human")
      (let [r2 (g/run* actor {:approval {:status :approved :by "founder-seat-1"}}
                       {:thread-id "p" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :pathway-recommended (:recommendation (store/assessment-of s "bt-jp"))))
        (is (= "founder-seat-1" (:approved-by (store/assessment-of s "bt-jp"))))))))

(deftest no-actuation-invariant
  (testing "a proposal that tries to grant an attestation / actuate a reactor is held"
    (let [[s _] (fresh)
          bad-adv (reify synthllm/Advisor
                    (-advise [_ _ _ _] {:recommendation :ready-to-attest :effect :grant-attestation
                                        :summary "x" :rationale "x" :cites [] :confidence 0.9}))
          a2 (syn/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :batch/attest :batch "bt-jp"} :context (ctx 3)}
                      {:thread-id "na"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-actuation} (last-basis s))))))

(deftest phase0-disables-attestations
  (let [[s actor] (fresh)
        res (run actor "p0" {:op :batch/attest :batch "bt-jp"} 0)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) last :phase-reason)))))

(deftest missing-phase-context-does-not-grant-max-autonomy
  ;; default-phase is the fallback both when :phase is entirely absent
  ;; from context (sng.synthesis) and when an unrecognized phase number
  ;; is passed (phase/gate). It used to be 3 -- the most permissive
  ;; tier, where :batch/attest auto-commits -- so a caller that simply
  ;; forgot to set :phase silently got MAXIMUM autonomy instead of the
  ;; safe "start narrow" default this namespace's own docstring
  ;; promises.
  (testing "omitting :phase from context still requires human approval on a clean batch attest"
    (let [[s actor] (fresh)
          res (g/run* actor {:request {:op :batch/attest :batch "bt-jp"} :context {}}
                      {:thread-id "mp"})]
      (is (not= :commit (get-in res [:state :disposition]))
          "a clean batch attest must not auto-commit when :phase is unset")
      (is (nil? (store/assessment-of s "bt-jp")) "SSoT untouched without explicit phase"))))

(deftest reject-signoff-holds
  (testing "a Council rejection records a hold, not a pathway authorization"
    (let [[s actor] (fresh)
          _  (run actor "r" {:op :pathway/select :batch "bt-jp"} 3)
          r2 (g/run* actor {:approval {:status :rejected :by "founder-seat-1"}}
                     {:thread-id "r" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/assessment-of s "bt-jp"))))))
