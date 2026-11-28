# Phase 12 — Staged-autonomy gates

Sphynx moves through the roadmap's stages only on measured evidence. The code is `universal/autonomy/AutonomyGates`. A stage is reached when every criterion of it, and of the stages before it, holds. The gate names the first criterion that fails.

| Stage | Reached when | What it allows |
|---|---|---|
| **observe only** | always | the coach's advice is shown; nothing is played from it |
| **evaluated offline** / **shadow mode** | Phase 10 (job's set): **≥ 30** rankable answers, set **reviewed** by the owner, unsafe share **≤ 5%**, mean score **≥ 0.50** | the coordinator runs beside the play loop and its advice is compared with what was played (C4); nothing is played from it |
| **supervised** | shadow mode: **≥ 200** compared decisions, agreement **≥ 70%**, unsafe disagreements **≤ 2%**; **and** the owner's supervised-mode switch is on | the coordinator's tactical advice may be played (C5), still through `TacticalAdvisor.check`, `DecisionGuard` and the practice-only `PlayerGate`; the emergency stop or the switch ends it at once |
| **bounded autonomy** | **locked** (owner, 2026-11-28: "up to supervised mode") | nothing: no evidence unlocks it; a later owner decision is needed |

**Definitions:**

- An **unsafe disagreement** is a shadow decision where the coordinator advised a fight (*engage*, *poke* or *take objective*) while the rules or the play loop retreated, or where it advised what `DecisionValidator` rejects.
- **Agreement** means the coordinator's tactical advice is the same kind as what was played.

The thresholds are judgement, kept as constants in one place. The owner can change them in a PR.
