# com-etzhayyim-sng — Design

- Status: accepted (R0 scaffold; Council ratification of ADR-2605265900 pending)
- Date: 2026-06-29
- Org: etzhayyim
- Derivation: promoted from `20-actors/hikari/cells/sng_sabatier/` (path-reserved in ADR-2605265900 §5) to a standalone Tier-B actor, following the `kamado` precedent (D-gate sub-ADR → standalone actor).
- Relates to: robotaxi-actor (AR1 ⊣ SafetyGovernor), gftd-talent-actor (HR-LLM ⊣ PolicyGovernor), ai-gftd-itonami (ops-LLM ⊣ CertGovernor), com-etzhayyim-kyoninka (reg-LLM ⊣ PermitGovernor); `.cursor/rules/always/actor-pattern-rule.mdc`.

## 1. Why this domain needs an actor, not a chatbot

"Is this batch e-methane?" is a liability-bearing carbon-discipline question,
not a model question. An LLM asked directly will hallucinate closed-loop
compliance, conflate commercial CO₂ with DAC CO₂, and (as a tool-using agent)
can record an attestation on its own judgement. For a molecule that claims
net-atmospheric-carbon Δ ≤ 0 (D3), that is unacceptable: the proof *is* the
chain-of-custody, and a mutable DB row cannot give you immutable carbon
provenance. So sng is built as the **fifth instance of the workspace actor
pattern**: contain the intelligence, govern it independently, keep an
append-only genealogy.

## 2. Topology (langgraph-clj StateGraph)

One supervised run = one operation. Two flows share one auditable graph:

```
ingest (record path):  intake → record → END
    facility / batch / feedstock CID / storage / leak-survey → EAVT ground
    datoms. Always on, never an LLM call, never an actuation (observe charter).

assess path:  intake → advise → govern → decide → commit | hold | request-approval
    synth-LLM proposes; CarbonGovernor censors; phase gate adds caution;
    a :pathway/select always interrupts for a Council Lv6+≥3 signoff.
```

No unbounded inner loop; `interrupt-before #{:request-approval}` is the
human-in-the-loop seam. A durable outer loop (lease/tick/budget/crash
recovery), if ever needed for a long-running methanation campaign, lives
*outside* the StateGraph — the graph stays a bounded 1-run-1-op unit.

## 3. The three injection seams (swap, core invariant)

- **Store** — `MemStore` (default) ‖ `DatomicStore` (langchain.db `:db-api`,
  swappable to real Datomic Local / kotoba-server pod). Carbon EAVT datoms are
  canonical; the ledger is the append-only batch genealogy.
- **Advisor** — `synthllm/mock-advisor` (deterministic) ‖ `synthllm/llm-advisor`
  on a real `langchain.model` (Anthropic / OpenAI-compatible / mock-model).
- **Phase** — R0 path-reserved → R3 supervised. Only *adds* caution.

The core invariant under all three: *the actor never records a batch
attestation the CarbonGovernor would reject, and never actuates a reactor.*

## 4. CarbonGovernor — the carbon invariants

Hard (force HOLD, unoverridable):

1. **facility recognized** — the batch names a known religious-corp plant.
2. **closed-loop carbon only** — every cited feedstock `:class ∈
   {green-H₂, DAC-CO₂, biomethane-tailgas}`; commercial-CO₂ is
   ABSOLUTELY PROHIBITED (D1+D3+§2(d); the schema enum excludes it by
   construction, the governor rejects it on sight).
3. **open catalyst only** — `:catalyst-formula = :ni-alumina-open`;
   proprietary catalysts (Johnson-Matthey HiFUEL R110 …) PROHIBITED (D5).
4. **aggregate cap** — combined biomethane+SNG ≤ 200 Nm³/day (R3).
5. **leak bound** — OGI leak survey present and leak-rate ≤ 1 %.
6. **storage bound** — ≤ 500 Nm³/parcel, aggregate ≤ 2,000 Nm³.
7. **mass balance** — batch mass-balance ≥ 95 %.
8. **high-temp council** — op-temp > 350 °C requires council-level ≥ 6
   (Lv6+ ≥ 3 signoffs proxy).
9. **no-actuation** — `:effect` must be `:assessment`.

Soft: 10. confidence floor → escalate; 11. a pathway selection is
high-stakes → ALWAYS Council Lv6+≥3 signoff.

`:batch/attest` checks 1–9 (the substantive carbon discipline *is* the
attestation); `:pathway/select` checks only 1, 9 — the biomethane-vs-Sabatier
allocation is the Council's call (checked at attestation time).

## 5. Phase gate (R0→R3 staged rollout)

| R | label | assess | auto | cap |
|---|---|---|---|---|
| 0 | path-reserved | ∅ | ∅ | import-blocked (RuntimeError at load in a future R0 build) |
| 1 | bench-pilot | attest+pathway | ∅ | ≤ 5 Nm³/day |
| 2 | assisted | attest+pathway | :batch/attest | ≤ 50 Nm³/day |
| 3 | supervised | attest+pathway | :batch/attest | combined ≤ 200 Nm³/day |

`:pathway/select` is never in `:auto`, so it always escalates. Recording
carbon-state ground datoms is always on (the observe charter is not phased).

## 6. The ledger = batch genealogy

Every commit/hold/record appends to an append-only ledger: H₂ CID → CO₂ CID →
catalyst lot → CH₄ batch → attestation. This is the immutable carbon
provenance a SaaS or a mutable DB row cannot give you — the property that
makes the attestation auditable across years and Council seats.

## 7. kotoba-server (kotobase.net) — optional sovereign ledger

Give the actor its own Ed25519 identity (`.sng/identity.edn`, gitignored) and
bind the store to `langchain.kotoba-db/kotoba-api`. The same `Store` record
runs in-mem, on real Datomic, or on the kotoba-server pod — the `MemStore ≡
DatomicStore` contract test is the guarantee. Per `ai-gftd-itonami/src/itonami/cacao.clj`:
the actor's key-derived IPNS name is its graph; owner hand-off and shared
tokens are not required.

## 8. What is real vs. illustrative

Real and tested: the actor topology, the CarbonGovernor invariants, the
Council pathway signoff, the R0→R3 phase gate, the append-only batch-genealogy
ledger, the backend-swap contract. Illustrative: the demo
facilities/batches/feedstock CIDs (curate with the catalysis-chemist Council
seat) and the deterministic mock synth-LLM (swap for a real `langchain.model`
to productionize).
