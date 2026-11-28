# Phase 12 — Strategic/Tactical Intelligence and Coordination: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-28**, together with Phases 11–16. **Owner decision on autonomy: "Up to supervised mode."** A supervised mode is added, off by default. In it, an action needs the gates to pass, and the owner can stop it at any time. There is no unsupervised (bounded) autonomy. IN PROGRESS.
**Based on:** `sphynx-test-branch` at v38.57, read from the code. No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** Principal Multi-Agent Systems and Game-Decision Orchestration Architect.
- **Supporting roles:** Game AI Researcher; Tactical Decision Systems Engineer; Safety/Policy Engineer; Inference Architect; Evaluation Scientist.
- **Mission:** coordinate Strategic and Tactical intelligence with shared context, explicit responsibilities, reliable handoffs and measurable readiness gates for any execution. This includes 12.1, the Unified Gaming Coach (advice only).
- **Out of scope:**
  - MLBB-specific perception and mechanics (Phase 13);
  - new perception models (Phase 15);
  - any autonomy beyond the supervised mode.

## 2. What exists (verified by reading the code)

| Part | State |
|---|---|
| Shared decision format | `universal/Decision`: kind, source (strategic / tactical / rules / learned), time, time-to-live, confidence, target, rationale. Advice only |
| Arbitration | `universal/DecisionArbiter` (rules first, then confidence) and `universal/DecisionValidator` (expired, unsafe, capability). `ai/DecisionGuard`: the rules' safety decisions can't be overridden, and movements stay valid |
| Fight advice | `TacticalAdvisor` asks the tactical model in fights. Its answer passes `TacticalAdvisor.check` (Kotlin safety checks) before the play loop adopts it (`AiRuntime`) |
| Strategic plan | `StrategicAdvisor.plan` (farm, push, fight, retreat, group or defend; aggression, focus) |
| Comparison | `ai/DecisionLogging`: in logging mode, records every engine's advice next to what was played |
| Existing autonomy | The practice-only player (`PlayerGate`: practice vs Computer, verified) and research mode. Dry run is ON by default |
| Gaps | No coordinator that combines the two jobs into one advice stream. No structured, versioned recommendation record with horizon, urgency, evidence and expiry. No match event history. No staged-autonomy gates with criteria. No shadow-mode agreement record. No audit format. No coach presentation or post-match review from the strategic job |

## 3. Plan (one PR each, version +1 each)

| Step | Work | Changes play? |
|---:|---|---|
| C1 | **Coordination contracts:** `universal/coach/Recommendation` is a versioned record: type, horizon (strategic / tactical), urgency, confidence, evidence, source, expiry. A model's free text is never the record itself. `universal/coach/Coordinator` is pure and tested. It turns the engines' `Decision`s into one stream through the existing `DecisionArbiter` / `DecisionValidator`. It keeps strategic guidance and tactical suggestions apart, reports unresolved conflicts as conflicts (not as two pieces of advice), and gives **"no recommendation"** when evidence is weak, stale or conflicting. It ranks by urgency, confidence and relevance within a display budget. Plus the architecture document | No |
| C2 | **Shared match context and event history** (WP-02 portion): `universal/coach/MatchContext` holds timestamped events (deaths, kills, objectives, towers, phase changes) with provenance and staleness. It handles ordering and missing events. The MLBB adapter feeds it from `GameState` changes. Pure parts tested | No |
| C3 | **Staged-autonomy gates:** `universal/autonomy/AutonomyGates` is pure and tested. The stages are OBSERVE → OFFLINE_EVAL → SHADOW → SUPERVISED (→ BOUNDED, **locked** by the owner's decision). Each stage has measured criteria: Phase 10 quality and unsafe share for the job, shadow-mode agreement and unsafe-disagreement rates over enough decisions, and the owner's switch. The gate reports which criterion fails | No |
| C4 | **Shadow mode and audit log:** in matches, the coordinator runs beside the play loop and only records what it would advise next to what was played. The record holds agreement, unsafe disagreements, the inputs and the result, in `research/coach_audit.jsonl`, exported to Recordings (debug-data rule). The panel shows the coach's top advice (budget 2, advice only). This also feeds the C3 gates | Only the panel's text |
| C5 | **Supervised mode (OFF by default):** a switch on the Play page. When it is on **and** the C3 gate for the tactical job reaches SUPERVISED, the coordinator's tactical recommendation may be played. It still passes `TacticalAdvisor.check`, the `DecisionGuard` rules and the existing `PlayerGate` (practice only). Otherwise the play loop runs exactly as today. The emergency stop, and turning the switch off, end it at once. Every supervised action is in the audit log | Only when the owner turns it on and the gates pass |
| C6 | **Post-match coaching (12.1 A):** after a match, the strategic model is asked for a short review of that match's own recorded evidence: deaths, objectives and events from C2. The review is labelled with its evidence and confidence, shown on the Coach page and exported. Advice only | No |
| C7 | **Simulation and evaluation suite, guide and closing:** replayable scenario fixtures (conflicts, stale advice, low confidence, unsafe advice, gate transitions) run through the coordinator and the gates in unit tests. Plus the guide, the phone checklist and the closing record | No |

## 4. Acceptance criteria (roadmap deliverables)

- [ ] Coordination architecture and role/task contracts (C1). **In code (v38.58):** `docs/PHASE_12_COORDINATION.md`, `universal/coach/Recommendation` (schema 1, JSON; tested), `universal/coach/Coordinator` (two horizons kept apart, `DecisionArbiter` first, near-tie conflict reported, ranked within a budget of 2; tested in `CoordinatorTest`).
- [ ] Context and arbitration design (C1, C2). **In code (v38.59):** `universal/coach/MatchContext` (ordered, de-duplicated, bounded event history with source and confidence; clock jumps over 30 s recorded as gaps; stale after 5 s without observation; summary; tested), `adapters/mlbb/MlbbEvents` (match start / end, you down / back, enemies and allies down from the death timers, objective seen / taken from its bar; only what was read; tested), `ai/CoachRuntime` (fed with each fresh screen reading in `AiRuntime`; reads only).
- [ ] Confidence policy with a safe "no recommendation" default (C1). **In code (v38.58):** under 40% not shown, a tie within 10 points without a rule is a conflict, advisory-only advice is shown as experimental and never actionable, expired advice is dropped (`CoordinatorTest`).
- [ ] Safety/action pipeline and staged-autonomy criteria (C3, C5). **In code (v38.60):** `universal/autonomy/AutonomyGates` (observe → offline evaluation / shadow → supervised; bounded locked by the owner's decision; each failing criterion named; tested), `docs/PHASE_12_AUTONOMY_GATES.md` (thresholds and definitions).
- [ ] Shadow mode and audit/reporting format (C4). **In code (v38.61):** `CoachRuntime.coach` (the coordinator on the engines' advice each moment), `CoachRuntime.shadow` (once a second in the play loop, next to `DecisionLogging`: agreement and unsafe disagreement counted per tactical model in `Shadow`, one JSON line per decision in `files/research/coach_audit.jsonl`, rotated at 4 MB; never changes the played option), the companion advice shows the coach's lines (budget 2), the Debug data page's *Export the coach audit now* (`Recordings/diagnostics-coach-audit-<time>.jsonl`).
- [ ] Supervised mode behind the gates, off by default, stoppable (C5). **In code (v38.62):** `Cfg.supervisedOn` (default OFF), `CoachRuntime.evidence` / `gate` (the tactical model's newest rankable fight test, its shadow counts, the switch; every 10 s), `CoachRuntime.supervisedChoice` (null unless the switch is on, the gate allows SUPERVISED and `TacticalAdvisor.check` accepts; called in `AiRuntime` after shadow mode, inside the practice-only play loop; `DecisionGuard` and the emergency stop still apply), the Play page's *Supervised coach (practice only)* switch with the gate's stage and what is missing.
- [ ] Coaching (12.1): in-match advice with a budget, post-match review (C4, C6).
- [ ] Simulation/evaluation suite (C7).

## 5. Risks

- **Changing play:** only C5 changes play, only with the owner's switch and passing gates, and only inside the existing practice-only `PlayerGate`. With the switch off, the play loop is unchanged; a unit test checks this.
- **Gate thresholds are judgement:** for example at least 200 shadow decisions, agreement of 70% or more, unsafe disagreements of 2% or less, and a Phase 10 tactical unsafe share of 5% or less on a reviewed set. They are constants in the gate document and can be changed.
- **Panel space:** the coach line has a budget of two items and leaves the panel's own lines alone.
