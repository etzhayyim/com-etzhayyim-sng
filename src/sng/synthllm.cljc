(ns sng.synthllm
  "synth-LLM — the contained intelligence node (synthesis advisor). It reads a
  batch's carbon EAVT ground datoms (cited feedstock CIDs, catalyst formula,
  op-temp, mass-balance, facility leak/storage state) and returns a PROPOSAL:
  a batch-attestation recommendation + rationale + the facts it cited, never a
  granted attestation and never a reactor actuation. Every output is censored
  by `sng.governor` before anything is recorded, and a pathway-selection
  recommendation always routes to a Council Lv6+≥3 signoff (charter:
  observe→recommend, no actuation).

  Advisor is injected (mock | real LLM via langchain.model), same as
  robotaxi.ar1 / talent.hrllm / itonami.opsllm / kyoninka.regllm.

  Proposal shape:
    {:recommendation kw   ; :ready-to-attest | :pathway-recommended | :not-ready
     :summary str :rationale str :cites [kw ..]
     :effect :assessment  ; the actor only ever writes an assessment datom
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [sng.store :as store]
            [sng.governor :as gov]))

;; ───────────────────────── deterministic mock ─────────────────────────

(defn- batch-blockers
  "The carbon gaps that stop a *batch attestation* — mirrors what the governor
  will check, so a clean batch yields :ready-to-attest (then auto-commits in
  phase 3) and a deficient one yields :not-ready (the governor also holds)."
  [st bat]
  (let [fac (store/facility st (:facility bat))
        fs  (map :class (store/feedstock-of st (:id bat)))
        total (reduce + 0 (map :nm3 (store/all-batches st)))
        surveys (store/leak-survey-of st (:facility bat))
        max-leak (reduce max 0 (map :leak-rate-pct surveys))
        rows (store/storage-of st (:facility bat))
        storage-total (reduce + 0 (map :nm3 rows))]
    (cond-> []
      (nil? fac)                                           (conj :unknown-facility)
      (some (complement gov/allowed-feedstock-classes) fs) (conj :commercial-co2)
      (not= :ni-alumina-open (:catalyst-formula bat))      (conj :proprietary-catalyst)
      (> total gov/aggregate-cap-nm3)                      (conj :aggregate-cap-exceeded)
      (or (empty? surveys) (> max-leak gov/leak-rate-cap-pct)) (conj :leak-bound)
      (some #(> (:nm3 %) gov/storage-per-parcel-cap) rows) (conj :storage-parcel-cap-exceeded)
      (> storage-total gov/storage-aggregate-cap)          (conj :storage-aggregate-cap-exceeded)
      (< (:mass-balance-pct bat 0) gov/mass-balance-floor) (conj :mass-balance-below-95)
      (and (> (:op-temp bat 0) gov/high-temp-threshold)
           (< (:council-level fac 0) gov/high-temp-council-floor)) (conj :high-temp-without-council))))

(defn- assess-batch [st {:keys [batch]}]
  (let [bat    (store/batch st batch)
        gaps   (batch-blockers st bat)
        ready  (empty? gaps)]
    {:recommendation (if ready :ready-to-attest :not-ready)
     :summary    (str batch " eメタン証書準備: "
                      (if ready "閉鎖循環炭素・触媒・cap・漏洩・貯蔵・mass-balance 全て充足"
                          "未充足"))
     :rationale  (if ready
                   (str "green-H₂+DAC-CO₂ 由来・Ni/γ-Al₂O₃ open・mass-balance≥95%・"
                        "combined≤200Nm³/day・leak≤1%。最終証書は founder/Council 判定。")
                   (str "不足: " (str/join "/" (map name gaps)) "。"))
     :cites      [:facility :feedstock :batch :leak-survey :storage]
     :effect     :assessment
     :confidence (if ready 0.88 0.82)}))

(defn- assess-pathway
  "Pathway allocation (biomethane primary vs Sabatier). This is the Council's
  call (Lv6+≥3 quarterly); the synth-LLM only proposes, citing surplus H₂+DAC
  capacity and the facility's current pathway."
  [st {:keys [batch]}]
  (let [bat (store/batch st batch)
        fac (store/facility st (:facility bat))
        pw  (:pathway fac :sabatier)]
    {:recommendation :pathway-recommended
     :summary    (str (:id fac) " pathway 選定: " (name pw)
                      " (Surplus H₂+DAC ≥50% → Sabatier 優先、廃物枯渇時は biomethane)")
     :rationale  (str "ADR-2605265900 §1: biomethane primary; Sabatier when waste "
                      "exhausted or surplus capacity ≥50%. Same facility never both "
                      "simultaneously without Council per-facility signoff.")
     :cites      [:facility :batch]
     :effect     :assessment
     :confidence 0.8}))

(defn infer [st _today {:keys [op] :as req}]
  (case op
    :batch/attest   (assess-batch st req)
    :pathway/select (assess-pathway st req)
    {:recommendation :unknown :summary "未対応" :rationale (str op)
     :cites [] :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store today request]))

(defn mock-advisor [] (reify Advisor (-advise [_ st today req] (infer st today req))))

(def ^:private system-prompt
  (str "あなたはeメタン(Sabatier SNG)製造の合成助言者です。与えられた事実(施設/"
       "バッチ/原料CID/触媒/漏洩調査/貯蔵)のみに基づき、提案を1つ EDN マップで返します。"
       "EDN だけを出力。\n"
       "キー: :recommendation(:ready-to-attest|:pathway-recommended|:not-ready) "
       ":summary :rationale :cites(使った事実キー) :effect(:assessment 固定) :confidence(0..1)。\n"
       "重要: バッチ証書の付与や反応器の起動は提案しない(observe→recommend)。"))

(defn- facts-for [st {:keys [batch]}]
  (let [bat (store/batch st batch)
        fid (:facility bat)]
    {:batch bat
     :facility (store/facility st fid)
     :feedstock (store/feedstock-of st batch)
     :storage (store/storage-of st fid)
     :leak-survey (store/leak-survey-of st fid)}))

(defn- parse-proposal [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p (update :cites #(vec (or % [])))
            (update :confidence #(if (number? %) (double %) 0.0))
            (update :effect #(or % :noop)))
      {:recommendation :unknown :summary "LLM応答を解釈できません" :rationale (str content)
       :cites [] :effect :noop :confidence 0.0})))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel (Anthropic / OpenAI-compatible
  / mock-model). Output is parsed defensively → an unparseable response is a
  confidence-0 noop the governor will hold/escalate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st _today req]
       (let [resp (model/-generate chat-model
                    [{:role :system :content system-prompt}
                     {:role :user :content (str "操作:" (:op req) " バッチ:" (:batch req)
                                                "\n事実:" (pr-str (facts-for st req)))}]
                    gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t :synthllm-proposal :op (:op request) :batch (:batch request)
   :recommendation (:recommendation proposal) :summary (:summary proposal)
   :rationale (:rationale proposal) :cites (:cites proposal)
   :confidence (:confidence proposal)})
