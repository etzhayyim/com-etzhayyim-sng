(ns sng.sim
  "Demo: drive e-methane (Sabatier SNG) batch attestation through one SynthesisActor.

    ingest      register a new facility + a batch (observe → datoms)
    attest bt-jp    clean Sabatier (green-H₂+DAC-CO₂, Ni/γ-Al₂O₃ open, 320 °C,
                    mass-balance 96%) → CarbonGovernor passes → phase 3 auto-commit
    attest bt-comm  commercial-CO₂ feedstock → HARD HOLD (ABSOLUTELY PROHIBITED)
    attest bt-prop  proprietary catalyst (HiFUEL R110) → HARD HOLD (D5)
    attest bt-hot   380 °C at council-level 5 → HARD HOLD (high-temp-without-council)
    pathway bt-jp   pathway selection → high-stakes → Council signoff (interrupt)
                    → founder seat approves → pathway assessment recorded
    phase 0         batch/attest in path-reserved phase → held (phase-disabled)

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [sng.store :as store]
            [sng.synthesis :as syn]))

(defn- line [& xs] (println (apply str xs)))

(defn- drive [actor tid req phase approve?]
  (let [res (g/run* actor {:request req :context {:phase phase}} {:thread-id tid})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  Council Lv6+≥3 サインオフ — pathway review (reason: "
                (-> res :state :audit last :reason) ")")
          (let [r2 (g/run* actor {:approval {:status (if approve? :approved :rejected)
                                             :by "founder-seat-1"}}
                           {:thread-id tid :resume? true})]
            (line "   ▶  " (if approve? "承認" "却下") " → " (get-in r2 [:state :disposition]))
            r2))
      (do (line "   → " (get-in res [:state :disposition])
                (when-let [pr (-> res :state :audit last :phase-reason)] (str " (" pr ")")))
          res))))

(defn -main [& _]
  (let [st    (store/seed-db)
        actor (syn/build st)]

    (line "── ingest (observe → EAVT 炭素連鎖 datoms) ──")
    (drive actor "i1" {:op :facility/register :facility "fac-sg"
                       :value {:id "fac-sg" :name "Singapore methanation plant"
                               :pathway :sabatier :council-level 7
                               :owner-type :religious-corp}} 3 true)
    (drive actor "i2" {:op :batch/register :batch "bt-sg"
                       :value {:id "bt-sg" :facility "fac-sg" :pathway :sabatier
                               :catalyst-formula :ni-alumina-open :op-temp 320
                               :op-pressure 8 :nm3 40 :mass-balance-pct 96}} 3 true)
    (line "  registered facilities: " (mapv :id (store/all-facilities st)))

    (line "\n── batch/attest bt-jp (clean Sabatier) ──")
    (drive actor "a-jp" {:op :batch/attest :batch "bt-jp"} 3 true)

    (line "\n── batch/attest bt-comm (commercial-CO₂) ──")
    (drive actor "a-comm" {:op :batch/attest :batch "bt-comm"} 3 true)

    (line "\n── batch/attest bt-prop (proprietary catalyst) ──")
    (drive actor "a-prop" {:op :batch/attest :batch "bt-prop"} 3 true)

    (line "\n── batch/attest bt-hot (380°C, council-level 5) ──")
    (drive actor "a-hot" {:op :batch/attest :batch "bt-hot"} 3 true)

    (line "\n── pathway/select bt-jp (Council 決定、高stakes) ──")
    (drive actor "p-jp" {:op :pathway/select :batch "bt-jp"} 3 true)

    (line "\n── 段階導入: batch/attest を phase 0 (path-reserved) で ──")
    (drive actor "a-p0" {:op :batch/attest :batch "bt-jp"} 0 true)

    (line "\n── バッチジェネアロジー台帳 (append-only; 炭素トレーサビリティ) ──")
    (doseq [f (store/ledger st)] (line "  " (store/ledger-line f)))

    (line "\n── バックエンド差し替え: DatomicStore でも同一契約 ──")
    (let [ds (store/datomic-seed-db) da (syn/build ds)]
      (drive da "d1" {:op :batch/attest :batch "bt-jp"} 3 true)
      (line "  DatomicStore assessment bt-jp: " (:recommendation (store/assessment-of ds "bt-jp"))))
    (line "\ndone.")))
