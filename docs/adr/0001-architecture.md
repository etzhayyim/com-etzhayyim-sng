# ADR-0001 — com-etzhayyim-sng architecture (e-methane Sabatier SNG actor)

- Status: accepted
- Date: 2026-06-29
- Org: etzhayyim
- Relates to: ADR-2605265900 (SNG Sabatier D-gate R0), ADR-2605263500
  (energy-substrate parent D-gate), ADR-2605263601 (green-H₂),
  ADR-2605264600 (DAC-CO₂), ADR-2605263803 (biomethane);
  com-etzhayyim-kyoninka (reg-LLM ⊣ PermitGovernor), kamado (D-gate →
  standalone-actor precedent); `.cursor/rules/always/actor-pattern-rule.mdc`.

## Context

ADR-2605265900 evaluates Sabatier methanation (CO₂ + 4 H₂ → CH₄ + 2 H₂O over
Ni/γ-Al₂O₃) as a closed-loop synfuel pathway and conditions it
**CONDITIONALLY PERMITTED** pending Council ratification, with a combined
biomethane+SNG ≤ 200 Nm³/day cap, an open-catalyst mandate, a green-H₂+DAC-CO₂
-only feedstock chain, and a Council Lv6+≥3 pathway-selection review. As
written, that ADR path-reserved the implementation as a cell under the `hikari`
energy actor (`20-actors/hikari/cells/sng_sabatier/`, lexicons under
`com.etzhayyim.hikari.*`). That cell was never scaffolded.

A methanation operation is a liability-bearing carbon-discipline decision
across many batches, facilities and Council seats — wider than a single energy
cell. The `kamado` actor already established the precedent of promoting a
D-gate sub-ADR pathway to its own Tier-B actor (kamado's G1 gate cites the
parent 2605263500 D3 directly). We follow that precedent for SNG.

## Decision

Build sng as a **standalone Tier-B actor** — the **fifth instance of the
workspace actor pattern** (after robotaxi / gftd-talent / itonami / kyoninka),
not a hikari cell. Concretely:

1. **Containment + independent governor + immutable ledger.** The synthesis
   advisor (**synth-LLM**) is sealed into one node and returns *proposals only*
   (`:recommendation` + rationale + cited facts, `:effect :assessment`). An
   independent **CarbonGovernor** censors every proposal against the ADR's hard
   carbon invariants over the EAVT ground datoms and dispositions it
   commit / hold / human-approval. Single invariant: *the actor never records
   a batch attestation the governor would reject, and never grants an
   attestation or actuates a reactor.* Every commit/hold/record appends to an
   append-only ledger (the batch genealogy).

2. **langgraph-clj StateGraph, 1 run = 1 operation.** No unbounded inner loop;
   `interrupt-before #{:request-approval}` is the human-in-the-loop seam — a
   pathway selection always pauses for a Council Lv6+≥3 signoff, even when the
   governor is fully clean (high-stakes).

3. **Three injection seams.** Store (`MemStore` ‖ `DatomicStore`), Advisor
   (`mock-advisor` ‖ `llm-advisor` on `langchain.model`), Phase (R0→R3). The
   core is invariant under all three.

4. **Store is `:db-api`-driven.** The store talks to its backend only through
   the langchain.db `{:q :transact! :db :pull :entid}` map; `langchain.db/api`
   and `langchain.kotoba-db/kotoba-api` both implement it, so the same record
   runs in-memory, on real Datomic, or on the kotoba-server pod. Enforced by a
   `MemStore ≡ DatomicStore` contract test.

5. **The carbon rulebook is data, not code.** Feedstock classes, catalyst
   formulas, council-approval-levels, leak/storage caps and aggregate caps are
   attributes of EAVT ground datoms / governor constants sourced from the ADR.
   Adding a batch or a feedstock CID is a `:batch/register` /
   `:feedstock/record` transaction reviewed by the catalysis-chemist Council
   seat — no code change. `commercial-co2` is not a representable closed-loop
   class (schema enum), so a fossil-carbon feedstock is excluded by
   construction.

6. **Integers, not floats** (charter): Nm³, pressure, op-temp, council-level,
   mass-balance-pct and leak-rate-pct as integers; dates as `yyyymmdd`
   integers compared to an injected `:today — keeping the whole actor
   `.cljc`-portable (JVM / SCI / cljs / WASM) with no date/decimal libraries.

7. **Lexicon re-namespace.** The four SNG lexicons ADR-2605265900 §6
   pre-declared under `com.etzhayyim.hikari.*` move to `com.etzhayyim.sng.*`
   (`sngBatchAttestation`, `sngStorageInventory`, `sngPathwaySelectionRecord`,
   `silenSngReview`), matching the kamado pattern (`com.etzhayyim.kamado`).
   The ADR-2605265900 §6 table is amended accordingly.

## The nine invariants (CarbonGovernor)

facility recognized · closed-loop carbon only · open catalyst only · aggregate
cap ≤ 200 Nm³/day · leak ≤ 1 % with quarterly OGI · storage ≤ 500 Nm³/parcel
and ≤ 2,000 aggregate · mass-balance ≥ 95 % · high-temp (> 350 °C) needs
council-level ≥ 6 · no-actuation (`:effect` must be `:assessment`). Plus soft
rules: confidence floor → escalate; a pathway selection is always high-stakes
→ Council Lv6+≥3.

`:batch/attest` checks all nine; `:pathway/select` checks only facility +
no-actuation (the allocation is the Council's call).

## Consequences

- A clean batch auto-attests in phase 3 (the governor IS the guarantee); a
  deficient one is held with the exact violated rules in the ledger, and the
  hold cannot be overridden by a human (you cannot approve past commercial-CO₂
  or a proprietary catalyst or an exceeded cap).
- A pathway selection never auto-approves — it interrupts for a named Council
  Lv6+≥3 signoff (founder seat until the Council is seated).
- The synth-LLM can be upgraded (or swapped to a real model) without touching
  the carbon guarantees; the guarantees live in the governor and the data.
- The feedstock/catalyst rulebook is illustrative until curated with the
  catalysis-chemist Council seat; because it is data, that curation is a
  reviewed transaction, not a refactor.

## Follow-ups

- Register the repo in the west manifest via a single-entry GitHub-API clean
  commit (`manifest/repos.edn` → regenerate `west.yml`), pin == repo HEAD —
  same procedure as the other actors. RAD identity journal at
  `orgs/etzhayyim/root/80-data/kotoba-rad/sng.identity.journal.edn`.
- Amend ADR-2605265900 §5/§6 to note the promotion (cell path-reserved
  withdrawn; lexicon ns `com.etzhayyim.sng.*`), and add the standalone-actor
  ADR `2606290000-sng-standalone-actor-r0.md` to the root ADR index.
- Optional sovereign ledger on kotoba-server (kotobase.net): give the actor
  its own Ed25519 identity (`.sng/identity.edn`, gitignored) and bind the store
  to `langchain.kotoba-db`, per `ai-gftd-itonami/src/itonami/cacao.clj`.
- Curate the per-facility chain-of-custody (green-H₂ CID / DAC-CO₂ CID / Ni
  catalyst lot provenance) with the catalysis-chemist Council seat; extend the
  feedstock/catalyst taxonomies as needed.
