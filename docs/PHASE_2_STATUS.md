# Phase 2 — status and resume point

**Status:** PAUSED by owner decision (2026-11-14, "we will come back to Phase 2 later"). Phase 3 started.
**Last commit with Phase 2 work:** `ab25ef1` on `sphynx-test-branch` (merge of PR #19, version 37.29).

## Done

| Submilestone (roadmap Section 4) | Evidence | PR |
|---|---|---|
| 1 — Current-state map | `docs/PHASE_2_ARCHITECTURE_MAP.md` | #10 |
| 2 — Target architecture | `docs/adr/0001-layered-architecture.md`. Status **Proposed: owner review still pending**. Enforced by `ArchitectureRulesTest` | #11 |
| 3 — Versioned game-state contract | `docs/contracts/UNIVERSAL_MATCH_STATE.md`, `universal/StateContract.kt`, `StateContractTest`, `MlbbAdapterContractTest` (acceptance criterion checked) | #14 |
| 9 (started) — MLBB regression net | `TacticalEngineGoldenTest` with 16 recorded situations (`test/resources/golden/tactical_engine_v1.txt`) and `TacticalEngineResetTest` | #16, #17 |
| ADR migration B1 | `core/Probes.kt` and `app/ProbesWiring.kt`. `Speed`, `TouchSpeed`, `PhoneState`, `VideoRecorder`, `GitHubUpload` and `Settings` no longer use the layers above | #18 |
| ADR migration B2 | `ai/SessionRows.kt`. `SessionRecorder` no longer uses the layers above, so **`core` is clean** | #19 |

**Bug fixed along the way (#17):** `TacticalEngine.reset()` and `ContactDetector.reset()` kept parts of the old match.

**Phone checks outstanding:** the checklist posted after #19 (version 37.29) still needs your phone test:
- AI off and on near a monster;
- the Speed page;
- the session video overlay;
- the debug upload;
- 5v5 auto-recording;
- the scenery picture in the debug export.

## Open, in the order to resume

1. **Owner review of ADR 0001** (four layers, the dependency rule, Probes instead of package moves).
2. **Migration B3 to B5.** 14 forbidden dependencies are left in the `ArchitectureRulesTest` baseline:
   - **B3:**
     - `vision/EndBoard`, `vision/PortraitMatch` → `app/IconCache`: move the portrait cache out of `app`.
     - `capture/ScreenCaptureService` → `ai`, `game`: status callbacks through Probes.
   - **B4:**
     - `models/AutoFolder` → `ai`, `learning`, `vision`: file handlers registered by the code that loads each file.
     - `models/ModelManager` → `ai`.
   - **B5:**
     - `engines/DecisionBridge` and `engines/DecisionLogging` → `ai`, `learning`, `game`, `adapters`: map to universal decisions at the AI-loop edge.
   - **Afterwards:** add rules for `game`, `vision`, `ai` and `learning` once they are split into universal and MLBB parts.
3. **Submilestones 4–8 and 10.** These are the adapter data and mechanics, the MLBB adapter, how future adapters supply data, capability and versioning rules, the final deterministic check on model outputs, and adapter onboarding.
4. **Submilestone 9.** Grow the regression net beyond the 16 situations: recorded situations from real sessions, and `GameStateTracker`.
5. **Open question from the regression net:** `marksman_poke_range`. A marksman with a 50%-HP enemy at poke distance waits for the minion wave. Is that intended caution? Owner to decide.

## How to resume

1. Read this file and ADR 0001.
2. Run the full CI.
3. Check the `ArchitectureRulesTest` baseline count (14).
4. Continue with B3.

Every migration PR must keep `TacticalEngineGoldenTest` green. A deliberate change in a decision updates the golden file in the same PR and says why.
