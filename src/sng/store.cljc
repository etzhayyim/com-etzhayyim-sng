(ns sng.store
  "SSoT for the sng (e-methane / Sabatier SNG methanation) actor — the
  carbon-chain-of-custody state of a religious-corp synthetic-methane
  *facility* and its production *batches*, behind a `Store` protocol so the
  backend is a swap (MemStore default ‖ DatomicStore via langchain.db, itself
  swappable to real Datomic Local / kotoba-server).

  Domain = the closed-loop carbon discipline a Sabatier methanation operation
  must hold before a batch may be attested as e-methane (ADR-2605265900,
  sub-ADR of the energy-substrate D-gate 2605263500). Entities:

    facility    — a religious-corp-owned methanation plant: pathway
                  (:sabatier | :biomethane — the two CH₄ pathways that share a
                  combined ≤200 Nm³/day cap), council-approval-level, owner-type.
    batch       — a concrete production run: catalyst-formula, op-temp, op-
                  pressure, nm³ produced, mass-balance-pct, pathway, the
                  feedstock CIDs it cites.
    feedstock   — a cited feedstock lot for a batch (green-H₂ / DAC-CO₂ /
                  biomethane-tail-gas) with its CID + class + kg. commercial-CO₂
                  is NOT a representable class (schema enum) — a fossil-carbon
                  feedstock is excluded by construction (ADR §2, D1+D3).
    storage     — per-LANDS-parcel SNG inventory (Nm³, pressure, type).
    leak-survey — quarterly OGI leak survey (leak-rate-pct, date).
    assessment  — the committed batch-attestation recommendation (output).

  Charter (ADR-0001): integers, not floats (Nm³ in integer litres×10⁻³; dates
  as yyyymmdd ints; temp/pressure/level ints); EAVT ground datoms are
  canonical; the append-only **ledger is the batch genealogy** — immutable
  carbon provenance (H₂ CID → CO₂ CID → catalyst lot → CH₄ batch), the property
  a SaaS or a mutable DB row can't give you. The actor is observe→recommend
  only: it never *attests* a batch the CarbonGovernor would reject and never
  actuates a physical reactor."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (facility [s id])
  (all-facilities [s])
  (batch [s id])
  (all-batches [s])
  (feedstock-of [s id]  "cited feedstock lots for a batch")
  (storage-of [s id]    "per-parcel SNG inventory rows for a facility")
  (leak-survey-of [s id] "quarterly OGI leak-survey rows for a facility")
  (assessment-of [s id] "committed batch-attestation assessment, or nil")
  (ledger [s])
  (record-datom! [s record] "append a carbon-state ground fact to the SSoT")
  (append-ledger! [s fact]  "append one immutable batch-genealogy fact")
  (seed! [s data]           "bulk-seed entity collections (idempotent upsert)"))

;; ───────────────────────── demo data ─────────────────────────

(defn demo-data
  "Four facilities + four batches exercising every CarbonGovernor branch:
    bt-jp    clean Sabatier (green-H₂ + DAC-CO₂, Ni/γ-Al₂O₃ open, 320 °C, mass-
             balance 96%) → governor passes → Council pathway sign-off interrupt
             → founder seat approves → attestation recorded.
    bt-comm  commercial-CO₂ feedstock (industrial flue gas) → HARD HOLD on
             :commercial-co2 (ABSOLUTELY PROHIBITED, D1+D3+§2(d)).
    bt-prop  proprietary catalyst (Johnson-Matthey HiFUEL R110) → HARD HOLD on
             :proprietary-catalyst (D5, open-formula-only).
    bt-hot   380 °C operation at a facility whose council-approval-level is 5
             (< Lv6 + ≥3 signoffs) → HARD HOLD on :high-temp-without-council."
  []
  {:facilities
   {"fac-jp"
    {:id "fac-jp" :name "お台場メタン化プラント" :pathway :sabatier
     :council-level 7 :owner-type :religious-corp}
    "fac-comm"
    {:id "fac-comm" :name "(不良)商業CO₂混入プラント" :pathway :sabatier
     :council-level 7 :owner-type :religious-corp}
    "fac-prop"
    {:id "fac-prop" :name "(不良)専触媒プラント" :pathway :sabatier
     :council-level 7 :owner-type :religious-corp}
    "fac-hot"
    {:id "fac-hot" :name "(不良)高温・評議会不足プラント" :pathway :sabatier
     :council-level 5 :owner-type :religious-corp}}
   :batches
   {"bt-jp"
    {:id "bt-jp" :facility "fac-jp" :pathway :sabatier
     :catalyst-formula :ni-alumina-open :op-temp 320 :op-pressure 8
     :nm3 40 :mass-balance-pct 96}
    "bt-comm"
    {:id "bt-comm" :facility "fac-comm" :pathway :sabatier
     :catalyst-formula :ni-alumina-open :op-temp 320 :op-pressure 8
     :nm3 40 :mass-balance-pct 95}
    "bt-prop"
    {:id "bt-prop" :facility "fac-prop" :pathway :sabatier
     :catalyst-formula :hifuel-r110-proprietary :op-temp 320 :op-pressure 8
     :nm3 40 :mass-balance-pct 96}
    "bt-hot"
    {:id "bt-hot" :facility "fac-hot" :pathway :sabatier
     :catalyst-formula :ni-alumina-open :op-temp 380 :op-pressure 10
     :nm3 40 :mass-balance-pct 96}}                            ; >350 °C
   :feedstock
   {"bt-jp"
    [{:class :green-h2         :cid "bafyreih2green000000000000000000000000000" :kg 20}
     {:class :dac-co2          :cid "bafyreico2dac0000000000000000000000000000" :kg 110}]
    "bt-comm"
    ;; commercial-CO₂ (industrial flue gas) — NOT a representable closed-loop
    ;; class; the governor rejects it on sight (D1+D3, §2 ABSOLUTELY PROHIBITED).
    [{:class :commercial-co2   :cid "bafyreifluecomm00000000000000000000000000" :kg 110}
     {:class :green-h2         :cid "bafyreih2green000000000000000000000000000" :kg 20}]
    "bt-prop"
    [{:class :green-h2         :cid "bafyreih2green000000000000000000000000000" :kg 20}
     {:class :dac-co2          :cid "bafyreico2dac0000000000000000000000000000" :kg 110}]
    "bt-hot"
    [{:class :green-h2         :cid "bafyreih2green000000000000000000000000000" :kg 20}
     {:class :dac-co2          :cid "bafyreico2dac0000000000000000000000000000" :kg 110}]}
   :storage
   {"fac-jp"  [{:parcel "parcel-jp-1" :nm3 300 :pressure 8 :type :low-pressure}]
    "fac-comm" [{:parcel "parcel-comm-1" :nm3 300 :pressure 8 :type :low-pressure}]
    "fac-prop" [{:parcel "parcel-prop-1" :nm3 300 :pressure 8 :type :low-pressure}]
    "fac-hot"  [{:parcel "parcel-hot-1" :nm3 300 :pressure 8 :type :low-pressure}]}
   :leak-survey
   {"fac-jp"   [{:date 20260331 :leak-rate-pct 0} {:date 20260630 :leak-rate-pct 0}]
    "fac-comm" [{:date 20260331 :leak-rate-pct 0} {:date 20260630 :leak-rate-pct 0}]
    "fac-prop" [{:date 20260331 :leak-rate-pct 0} {:date 20260630 :leak-rate-pct 0}]
    "fac-hot"  [{:date 20260331 :leak-rate-pct 0}]}})            ; missing Q2 survey

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (facility [_ id] (get-in @a [:facilities id]))
  (all-facilities [_] (sort-by :id (vals (:facilities @a))))
  (batch [_ id] (get-in @a [:batches id]))
  (all-batches [_] (sort-by :id (vals (:batches @a))))
  (feedstock-of [_ id] (get-in @a [:feedstock id] []))
  (storage-of [_ id] (get-in @a [:storage id] []))
  (leak-survey-of [_ id] (get-in @a [:leak-survey id] []))
  (assessment-of [_ id] (get-in @a [:assessments id]))
  (ledger [_] (:ledger @a))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :facility     (swap! a update-in [:facilities id] merge value)
      :batch        (swap! a update-in [:batches id] merge value)
      :feedstock    (swap! a update-in [:feedstock id] (fnil conj []) value)
      :storage      (swap! a update-in [:storage id] (fnil conj []) value)
      :leak-survey  (swap! a update-in [:leak-survey id] (fnil conj []) value)
      :assessment   (swap! a assoc-in [:assessments id] value)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (seed! [s data] (swap! a merge (select-keys data
                                              [:facilities :batches :feedstock
                                               :storage :leak-survey])) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :assessments {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────────

(def ^:private schema
  {:facility/id      {:db/unique :db.unique/identity}
   :batch/id         {:db/unique :db.unique/identity}
   :ledger/seq       {:db/unique :db.unique/identity}
   :assessment/batch {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :rec/batch        {:db/valueType :db.type/ref}    ; feedstock rows
   :rec/facility     {:db/valueType :db.type/ref}})  ; storage/leak rows

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api` map
;; {:q :transact! :db :pull :entid}. langchain.db/api (in-process EAVT) and
;; langchain.kotoba-db/kotoba-api (kotoba-server XRPC, e.g. kotobase.net) both
;; implement it, so the same record runs on either by construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- pull* [{:keys [api conn]} pattern eid] ((:pull api) ((:db api) conn) pattern eid))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (facility [this id]
    (when-let [m (pull* this [:facility/id :facility/edn] [:facility/id id])]
      (when (:facility/id m) (dec* (:facility/edn m)))))
  (all-facilities [this]
    (->> (q* this '[:find [?id ...] :where [?e :facility/id ?id]])
         (map #(facility this %)) (sort-by :id)))
  (batch [this id]
    (when-let [m (pull* this [:batch/id :batch/edn] [:batch/id id])]
      (when (:batch/id m) (dec* (:batch/edn m)))))
  (all-batches [this]
    (->> (q* this '[:find [?id ...] :where [?e :batch/id ?id]])
         (map #(batch this %)) (sort-by :id)))
  (feedstock-of [this id] (->> (q* this '[:find [?v ...] :in $ ?bid :where
                                           [?e :batch/id ?bid] [?r :rec/batch ?e]
                                           [?r :rec/kind :feedstock] [?r :rec/edn ?v]] id)
                               (mapv dec*)))
  (storage-of [this id] (->> (q* this '[:find [?v ...] :in $ ?fid :where
                                         [?e :facility/id ?fid] [?r :rec/facility ?e]
                                         [?r :rec/kind :storage] [?r :rec/edn ?v]] id)
                             (mapv dec*)))
  (leak-survey-of [this id] (->> (q* this '[:find [?v ...] :in $ ?fid :where
                                            [?e :facility/id ?fid] [?r :rec/facility ?e]
                                            [?r :rec/kind :leak-survey] [?r :rec/edn ?v]] id)
                                 (mapv dec*)))
  (assessment-of [this id]
    (dec* (q* this '[:find ?p . :in $ ?bid :where [?e :batch/id ?bid]
                     [?x :assessment/batch ?e] [?x :assessment/edn ?p]] id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :facility   (tx* s [{:facility/id id :facility/edn (enc value)}])
      :batch      (tx* s [{:batch/id id :batch/edn (enc value)}])
      :assessment (tx* s [{:assessment/batch [:batch/id id] :assessment/edn (enc value)}])
      :feedstock  (tx* s [{:rec/batch [:batch/id id] :rec/kind kind :rec/edn (enc value)}])
      :storage    (tx* s [{:rec/facility [:facility/id id] :rec/kind kind :rec/edn (enc value)}])
      :leak-survey (tx* s [{:rec/facility [:facility/id id] :rec/kind kind :rec/edn (enc value)}])
      nil)
    s)
  (append-ledger! [s fact]
    (tx* s [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}]) fact)
  (seed! [s data]
    (doseq [[id f] (:facilities data)]  (record-datom! s {:kind :facility :id id :value f}))
    (doseq [[id b] (:batches data)]     (record-datom! s {:kind :batch :id id :value b}))
    (doseq [[id rows] (:feedstock data) row rows]
      (record-datom! s {:kind :feedstock :id id :value row}))
    (doseq [[id rows] (:storage data) row rows]
      (record-datom! s {:kind :storage :id id :value row}))
    (doseq [[id rows] (:leak-survey data) row rows]
      (record-datom! s {:kind :leak-survey :id id :value row}))
    s))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (default Datomic-
  shaped store; verifiable offline). For the kotoba-server pod (kotobase.net),
  bind the same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see ADR-0001 / docs/DESIGN.md)."
  ([] (datomic-store nil))
  ([data] (let [s (->DatomicStore d/api (d/create-conn schema))]
            (when data (seed! s data)) s)))

(defn datomic-seed-db [] (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line [{:keys [op batch disposition basis]}]
  (str/join " · " [(name (or disposition :record)) (str "op=" op)
                   (str "batch=" batch) (str "basis=" (pr-str basis))]))
