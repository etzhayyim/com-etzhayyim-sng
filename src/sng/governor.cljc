(ns sng.governor
  "CarbonGovernor — the independent carbon-discipline layer that earns the
  synth-LLM the right to *recommend*. The LLM has no binding notion of which
  feedstocks close the carbon loop, of the open-catalyst mandate, of the
  combined biomethane+SNG cap, of the leak/storage bounds, or of the
  no-actuation charter, so this MUST be a separate system (rules over the EAVT
  ground datoms) able to *reject* a proposal and fall back to HOLD — the sng
  analog of robotaxi's MRC / itonami's airworthiness hold / kyoninka's
  PermitGovernor.

  Charter (ADR-0001): the actor is **observe → recommend only**. It never
  *attests* a batch the CarbonGovernor would reject, never marks a fossil
  feedstock closed-loop, never actuates a reactor. Below, HARD invariants force
  HOLD (no human can approve past commercial-CO₂ or a proprietary catalyst or
  an exceeded cap); a pathway-selection (:pathway/select) is high-stakes and
  ALWAYS routes to a Council Lv6+≥3 signoff even when everything is clean.

  HARD invariants (ADR-2605265900 D1–D5 + §1/§2):
    1. Facility recognized     — the batch names a known religious-corp plant.
    2. Closed-loop carbon only — every cited feedstock :class ∈
                                 {green-H₂, DAC-CO₂, biomethane-tailgas};
                                 commercial-CO₂ is ABSOLUTELY PROHIBITED
                                 (D1+D3+§2(d); schema enum excludes it).
    3. Open catalyst only      — :catalyst-formula = :ni-alumina-open; proprietary
                                 catalysts (HiFUEL R110 …) PROHIBITED (D5).
    4. Aggregate cap           — combined biomethane+SNG ≤ 200 Nm³/day (R3).
    5. Leak bound              — OGI leak survey present and leak-rate ≤ 1%.
    6. Storage bound           — ≤ 500 Nm³/parcel, aggregate ≤ 2,000 Nm³.
    7. Mass balance            — batch mass-balance ≥ 95%.
    8. High-temp council       — op-temp > 350 °C requires council-level ≥ 6
                                 (Lv6+ ≥ 3 signoffs proxy).
    9. No-actuation            — the proposal writes an :assessment, never a
                                 batch attestation grant / reactor activation.
  SOFT:
    10. Confidence floor → escalate.
    11. A pathway selection is high-stakes → ALWAYS Council signoff.

  Op scope: :batch/attest checks 1–9 (the substantive carbon discipline is what
  the attestation *is*); :pathway/select checks only 1, 9 — the biomethane-vs-
  Sabatier allocation is the Council's call (checked at attestation time)."
  (:require [sng.store :as store]))

(def confidence-floor 0.6)
(def default-today 20260629)

;; ───────────────────────── ADR-derived constants ─────────────────────────

(def allowed-feedstock-classes #{:green-h2 :dac-co2 :biomethane-tailgas})
(def aggregate-cap-nm3 200)                 ; combined biomethane+SNG, R3
(def leak-rate-cap-pct 1)
(def storage-per-parcel-cap 500)
(def storage-aggregate-cap 2000)
(def mass-balance-floor 95)
(def high-temp-threshold 350)               ; >350 °C needs Council Lv6+≥3
(def high-temp-council-floor 6)

;; ───────────────────────── invariant checks ─────────────────────────

(defn- facility-violations [fac bat]
  (cond-> []
    (nil? fac)
    (conj {:rule :unknown-facility
           :detail (str "未知の施設 " (:facility bat))})))

(defn- carbon-violations [st bat]
  (let [bad (->> (store/feedstock-of st (:id bat))
                 (map :class)
                 (filter (complement allowed-feedstock-classes)))]
    (when (seq bad)
      [{:rule :commercial-co2
        :detail (str "非閉鎖循環炭素源: " (vec bad)
                     " — 商業CO₂は ABSOLUTELY PROHIBITED (D1+D3+§2(d))")}])))

(defn- catalyst-violations [bat]
  (when (not= :ni-alumina-open (:catalyst-formula bat))
    [{:rule :proprietary-catalyst
      :detail (str "専触媒 " (:catalyst-formula bat)
                   " は PROHIBITED — Ni/γ-Al₂O₃ open-formula 必須 (D5)")}]))

(defn- aggregate-cap-violations [st]
  (let [total (reduce + 0 (map :nm3 (store/all-batches st)))]
    (when (> total aggregate-cap-nm3)
      [{:rule :aggregate-cap-exceeded
        :detail (str "combined biomethane+SNG " total " Nm³/day > cap "
                     aggregate-cap-nm3 " (R3)")}])))

(defn- leak-violations [st fac]
  (let [surveys (store/leak-survey-of st (:id fac))]
    (cond
      (empty? surveys)
      [{:rule :missing-leak-survey :detail "四半期OGI漏洩調査が未実施"}]
      :else
      (let [max-leak (reduce max 0 (map :leak-rate-pct surveys))]
        (when (> max-leak leak-rate-cap-pct)
          [{:rule :leak-rate-exceeded
            :detail (str "漏洩率 " max-leak "% > cap " leak-rate-cap-pct "%")}])))))

(defn- storage-violations [st fac]
  (let [rows (store/storage-of st (:id fac))
        over (filter #(> (:nm3 %) storage-per-parcel-cap) rows)
        total (reduce + 0 (map :nm3 rows))]
    (cond-> []
      (seq over) (conj {:rule :storage-parcel-cap-exceeded
                        :detail (str "parcels over 500 Nm³: " (mapv :parcel over))})
      (> total storage-aggregate-cap) (conj {:rule :storage-aggregate-cap-exceeded
                                             :detail (str "aggregate " total " > "
                                                          storage-aggregate-cap)}))))

(defn- mass-balance-violations [bat]
  (when (< (get bat :mass-balance-pct 0) mass-balance-floor)
    [{:rule :mass-balance-below-95
      :detail (str "mass-balance " (:mass-balance-pct bat) "% < " mass-balance-floor "%")}]))

(defn- high-temp-violations [fac bat]
  (when (> (get bat :op-temp 0) high-temp-threshold)
    (when (< (get fac :council-level 0) high-temp-council-floor)
      [{:rule :high-temp-without-council
        :detail (str (:op-temp bat) "°C > 350 は Council Lv6+≥3 必須、facility level="
                     (:council-level fac))}])))

(defn- actuation-violations [proposal]
  ;; observe→recommend: the actor may write an :assessment datom, never a batch
  ;; attestation grant or a reactor activation.
  (when (not= :assessment (:effect proposal))
    [{:rule :no-actuation
      :detail (str "actor はバッチ証書付与/反応器起動をしない(observe→recommend)。effect="
                   (:effect proposal))}]))

(defn check
  "Censors a synth-LLM proposal for an sng op. Returns
   {:ok? :violations :confidence :hard? :escalate? :high-stakes?}.

   Hard violations force HOLD and cannot be overridden by a human. A
   :pathway/select is always high-stakes → Council Lv6+≥3 signoff even clean.
   opts: {:today yyyymmdd-int}."
  ([request proposal st] (check request proposal st nil))
  ([request proposal st _opts]
   (let [bat  (store/batch st (:batch request))
         fac  (store/facility st (:facility bat))
         base (facility-violations fac bat)
         hard (case (:op request)
                :batch/attest
                (vec (concat base
                             (when fac (carbon-violations st bat))
                             (when fac (catalyst-violations bat))
                             (when fac (aggregate-cap-violations st))
                             (when fac (leak-violations st fac))
                             (when fac (storage-violations st fac))
                             (when fac (mass-balance-violations bat))
                             (when fac (high-temp-violations fac bat))
                             (actuation-violations proposal)))
                :pathway/select
                (vec (concat base (actuation-violations proposal)))
                [])
         conf    (:confidence proposal 0.0)
         low?    (< conf confidence-floor)
         stakes? (= :pathway/select (:op request))
         hard?   (boolean (seq hard))]
     {:ok?          (and (not hard?) (not low?) (not stakes?))
      :violations   hard
      :confidence   conf
      :hard?        hard?
      :escalate?    (and (not hard?) (or low? stakes?))
      :high-stakes? stakes?})))

(defn hold-fact [request verdict]
  {:t :carbon-hold :op (:op request) :batch (:batch request)
   :disposition :hold :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict) :confidence (:confidence verdict)})
