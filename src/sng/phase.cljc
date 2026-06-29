(ns sng.phase
  "R0→R3 staged rollout (ADR-2605265900 §5 roadmap), gating only the ASSESS ops
  (recommendations). Recording carbon-state ground datoms (the observe function)
  is always on — that is sng's charter (durable EAVT observations of the
  chain-of-custody). The phase only decides how much autonomy the
  *recommendations* have, and can only add caution.

    R0 path-reserved  — record carbon facts; emit NO attestations yet (the cell
                        is import-blocked: a real RuntimeError at load time in a
                        future R0 build; here the phase gate holds every assess).
    R1 bench pilot    — attestations allowed, but always human approval (≤5 Nm³/day).
    R2 assisted       — :batch/attest may auto-commit when clean+confident; a
                        pathway selection still routes to the Council (≤50 Nm³/day).
    R3 supervised     — :batch/attest auto-commits when clean+confident; a
                        :pathway/select is high-stakes and ALWAYS routes to a
                        Council Lv6+≥3 signoff (combined ≤200 Nm³/day, never auto).")

(def record-ops #{:facility/register :batch/register :feedstock/record
                  :storage/record :leak-survey/record})
(def assess-ops #{:batch/attest :pathway/select})

(def phases
  {0 {:label "path-reserved"  :assess #{}            :auto #{}}
   1 {:label "bench-pilot"    :assess assess-ops     :auto #{}}
   2 {:label "assisted"       :assess assess-ops     :auto #{:batch/attest}}
   3 {:label "supervised"     :assess assess-ops     :auto #{:batch/attest}}})

(def default-phase 3)

(defn record-op? [op] (contains? record-ops op))

(defn gate
  "Adjust an assess op's governor disposition for the rollout phase.
  Returns {:disposition kw :reason kw|nil}. :pathway/select is never in :auto,
  so it always escalates (a Council pathway allocation is a human Lv6+≥3 call)."
  [phase {:keys [op]} disposition]
  (let [{:keys [assess auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold disposition)        {:disposition :hold :reason nil}
      (not (contains? assess op))  {:disposition :hold :reason :phase-disabled}
      (and (= :commit disposition)
           (not (contains? auto op))) {:disposition :escalate :reason :phase-approval}
      :else                        {:disposition disposition :reason nil})))

(defn verdict->disposition [v]
  (cond (:hard? v) :hold (:escalate? v) :escalate :else :commit))
