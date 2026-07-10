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

(def default-phase
  "The phase used when `context` carries no :phase at all
  (sng.synthesis: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase NUMBER
  (`(get phases phase (get phases default-phase))`). This is directly
  reachable by any ordinary caller that simply omits :phase -- not just
  malformed/malicious input -- so it must be the MOST CONSERVATIVE
  phase, never the most permissive: 'can only add caution' (this
  namespace's own docstring) has to hold for a MISSING phase too, not
  only an explicitly-set low one. This was 3 (supervised, the most
  permissive tier -- :batch/attest can auto-commit) until a live check
  confirmed a caller who forgets :phase silently got maximum autonomy
  instead of the safe default -- the same accidental-fail-open shape
  already found and fixed this session in the shared talent.phase
  template this actor's own README names as its reference lineage
  (gftd-talent-actor), plus its siblings newscaster.phase, wami.phase,
  and kyoninka.phase, which all inherited the same bug. 1 (bench-pilot)
  matches those fixes and the sibling ports that already chose it
  correctly (tsumugu.phase / shiropico.phase) -- :pathway/select remains
  unaffected either way (never in any phase's :auto set)."
  1)

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
