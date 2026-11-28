# Phase 12 — Coordination architecture

How Sphynx's engines are combined into one coaching stream, and how any action from that stream is gated. The code lives in `universal/coach/` and `universal/autonomy/` (game-free), with the app's wiring in `ai/`.

```
 engines (each gives a universal Decision)            universal/coach                     out
 ─────────────────────────────────────────            ───────────────                     ───
 strategic model  → StrategicAdvisor.plan ─┐
 tactical model   → TacticalAdvisor        ├─► Coordinator ─► Recommendation stream ─► panel (budget 2, advice)
 learned networks → Brain / FightNet       │     ↑ DecisionArbiter / DecisionValidator   ├► audit log (shadow mode)
 Kotlin rules     → TacticalEngine         ┘     ↑ MatchContext (events, staleness)      └► supervised mode (C5):
                                                                                            only if the switch is on AND
                                                                                            AutonomyGates says SUPERVISED,
                                                                                            through TacticalAdvisor.check,
                                                                                            DecisionGuard and PlayerGate
```

## Responsibilities (contracts)

| Part | Responsibility | Never does |
|---|---|---|
| Strategic job | Plans over a long horizon: plan, aggression, focus, draft and build | Decide a fight moment |
| Tactical job | Advises the fight moment: one option from a fixed list | Change the plan |
| Rules (`TacticalEngine`, `DecisionGuard`) | Safety decisions, which nothing overrides | — |
| `Coordinator` | Combines the engines and keeps the two horizons apart. Settles conflicts by explicit rules (`DecisionArbiter`: safety first, then confidence). Reports a near tie without a rule as a **conflict**. Gives **no recommendation** when advice is weak (under 40%), stale or conflicting. Ranks by urgency, confidence and horizon within a budget of 2 | Act; invent advice |
| `Recommendation` | A versioned record (schema 1): horizon, kind, urgency, confidence, source, created, expires, target, evidence, experimental | Hold a model's free text as the record |
| `MatchContext` (C2) | Timestamped match events with provenance and staleness | — |
| `AutonomyGates` (C3) | The stage reached by measured evidence and the reason for any failed criterion | Unlock bounded autonomy (locked by the owner) |

## Confidence policy

- An engine that gives no confidence counts as 0.5, the same as `DecisionArbiter`.
- Advice under **40%** is not shown.
- Two different advices within **10 points** of each other, with no rule deciding, are a conflict: "no recommendation", with the reason.
- Advice from an engine whose capability is not usable for the game (`DecisionValidator` ADVISORY_ONLY) is shown marked **experimental** and is never actionable.
- Expired advice is dropped by the validator.
