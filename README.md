# com-etzhayyim-sng （e-methane / Sabatier SNG methanation artificial organism）

A **closed-loop synthetic-methane manufacturing artificial organism** — the
carbon-chain-of-custody attestation a religious-corp Sabatier methanation
operation must hold before a batch may be recorded as e-methane, per
**ADR-2605265900** (Sabatier SNG from green-H₂ + DAC-CO₂, sub-ADR of the
energy-substrate D-gate 2605263500).

Sabatier methanation: **CO₂ + 4 H₂ → CH₄ + 2 H₂O** (ΔH = −165 kJ/mol
exothermic, 250–400 °C over Ni/γ-Al₂O₃). Stoichiometry per kg SNG ≈ 0.5 kg H₂
+ 2.75 kg CO₂. This actor was **promoted from a path-reserved cell under
`hikari`** (`20-actors/hikari/cells/sng_sabatier/`, never scaffolded) to a
standalone Tier-B actor — the same derivation `kamado` took from the
energy-substrate D-gate lineage.

Platform vocabulary:

- **kotoba** is the sovereign data/compute substrate: CID, Datom log, WASM,
  auth and network primitives.
- **kototama** is the common organism/actor platform and runtime adapter layer.
- **app-aozora** is the AT Protocol product boundary: PDS, AppView, XRPC,
  lexicons, feeds/search and profile publication.
- **com-etzhayyim-sng** is the domain organism. It may surface as an AT
  Protocol actor, but it does not run its own PDS.

The current runnable topology uses
[`langgraph-clj`](../../com-junkawasaki/langgraph-clj) StateGraph as the
orchestration backend (portable `.cljc`, supervised run, `interrupt-before`
human-in-the-loop, Datomic/in-mem checkpoints), in the same governed shape as
the reference actors: **robotaxi-actor** (AR1 ⊣ SafetyGovernor) /
**gftd-talent-actor** (HR-LLM ⊣ PolicyGovernor) / **ai-gftd-itonami**
(ops-LLM ⊣ CertGovernor) / **com-etzhayyim-kyoninka** (reg-LLM ⊣
PermitGovernor).

> **Why an actor layer?** "Is this batch e-methane?" is not a model question —
> it is a question of binding carbon discipline: whether every cited feedstock
> closes the loop (green-H₂ + DAC-CO₂, not commercial flue gas), whether the
> catalyst is open-formula Ni/γ-Al₂O₃ (not a proprietary Johnson-Matthey lot),
> whether the combined biomethane+SNG cap ≤ 200 Nm³/day holds, whether the leak
> rate ≤ 1 %, whether > 350 °C operation has a Council Lv6+≥3 signoff. A
> language model can *advise* on all of this; it must never be the thing that
> says "attested." This project seals the synthesis advisor (synth-LLM) into
> one node and wraps it with an independent **CarbonGovernor** that enforces
> the carbon invariants and routes every pathway selection to a Council
> Lv6+≥3 signoff.

See [`docs/DESIGN.md`](docs/DESIGN.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md).

## The core contract

```
carbon facts (facility · batch · feedstock CIDs · catalyst · leak · storage)
        │
        ▼
   ┌──────────┐    proposal     ┌────────────────┐
   │ synth-LLM│ ──────────────▶ │ CarbonGovernor │  (independent system)
   │ (sealed) │  readiness +    │ carbon invariants│
   └──────────┘  cited facts    └───────┬────────┘
                            commit/hold ◀┴▶ escalate
                               │            │
                            assessment   Council Lv6+≥3
                            datom        signoff (pathway)
```

**The actor never records a batch attestation the CarbonGovernor would reject,
and never grants an attestation or actuates a reactor** — observe → recommend
only. That single invariant is the sng analog of robotaxi's safety contract.

## Demo batches (illustrative)

| id | feedstock | catalyst | op-temp | council-lvl | outcome |
|----|-----------|----------|---------|-------------|---------|
| `bt-jp` | green-H₂ + DAC-CO₂ | Ni/γ-Al₂O₃ open | 320 °C | 7 | clean → auto-attest (phase 3) |
| `bt-comm` | **commercial-CO₂** | Ni/γ-Al₂O₃ open | 320 °C | 7 | HARD HOLD (ABSOLUTELY PROHIBITED) |
| `bt-prop` | green-H₂ + DAC-CO₂ | **HiFUEL R110** | 320 °C | 7 | HARD HOLD (D5 proprietary) |
| `bt-hot` | green-H₂ + DAC-CO₂ | Ni/γ-Al₂O₃ open | **380 °C** | 5 | HARD HOLD (high-temp-without-council) |

The rulebook is **data** (`sng.store/demo-data`): a cited feedstock or a
catalyst lot is an EAVT ground datom, not a code change.

## Run

```bash
clojure -M:dev:run     # drive batches through one SynthesisActor
clojure -M:dev:test    # the carbon contract as executable tests
clojure -M:lint        # clj-kondo (errors fail)
```

Demo walks: ingest a new facility+batch → `bt-jp` clean Sabatier (governor
passes → phase-3 auto-commit) → `bt-comm` commercial-CO₂ (HARD HOLD) →
`bt-prop` proprietary catalyst (HARD HOLD) → `bt-hot` 380 °C without council
(HARD HOLD) → `pathway/select` (high-stakes → Council signoff interrupt →
founder seat approves → recorded) → phase-0 path-reserved (held) → the
append-only batch-genealogy ledger → the same contract on `DatomicStore`.

## Layout

| File | Actor / role |
|---|---|
| `src/sng/store.cljc` | SSoT — facilities · batches · feedstock CIDs · storage · leak-survey; `MemStore` ‖ `DatomicStore` (langchain.db `:db-api`); append-only ledger |
| `src/sng/synthllm.cljc` | **synth-LLM** — the contained intelligence node (synthesis advisor); mock ‖ real LLM via `langchain.model` |
| `src/sng/governor.cljc` | **CarbonGovernor** — independent carbon invariants; HOLD on commercial-CO₂, proprietary catalyst, cap exceeded, leak, storage, high-temp-without-council, no-actuation |
| `src/sng/phase.cljc` | R0→R3 staged rollout (path-reserved → supervised); pathway/select is never auto |
| `src/sng/synthesis.cljc` | **SynthesisActor** — the langgraph-clj StateGraph (1 run = 1 op) |
| `src/sng/sim.cljc` | demo driver |
| `test/sng/governor_contract_test.clj` | the carbon invariant, executable |
| `test/sng/store_contract_test.clj` | `MemStore ≡ DatomicStore` |

## Status

Reference design + runnable skeleton. The demo batches/facilities are
illustrative (the carbon rulebook comes from ADR-2605265900 and its feedstock
ADRs 2605263601/2605264600/2605263803), and the synth-LLM is a deterministic
mock. The **actor topology, the carbon invariants, the Council pathway
signoff, the R0→R3 phase gate, and the append-only batch-genealogy ledger are
real and tested.** Productionizing means (1) curating the per-facility
chain-of-custody with the catalysis-chemist Council seat, (2) swapping
`synthllm/mock-advisor` for `llm-advisor` on a real `langchain.model`, and (3)
optionally binding the store to kotoba-server (kotobase.net) so the ledger is
an actor-signed CACAO graph (see ADR-0001).
