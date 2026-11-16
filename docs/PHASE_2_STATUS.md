# Phase 2 — status and resume point

**Status:** RESUMED on 2026-11-16 by owner decision, after Phase 4 was completed and phone-tested. (Paused 2026-11-14 to 2026-11-16 for Phases 3 and 4.)
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

1. ~~Owner review of ADR 0001~~ **Done: accepted by the owner on 2026-11-16.**
2. **Migration B3 to B5.** 14 forbidden dependencies were left in the `ArchitectureRulesTest` baseline when Phase 2 resumed:
   - ~~**B3**~~ **done in v37.61:** `vision/EndBoard` and `vision/PortraitMatch` read the hero / item pictures through
     `core.Probes` (`heroIconFile`, `itemIconFile`, `loadIcon`), and `capture/ScreenCaptureService` reports
     `onCaptureStarted`, `onCaptureStopped` and `emergencyStop` through Probes (wired in `app/ProbesWiring`).
     Baseline: 14 → 10 entries.
   - ~~**B4**~~ **done in v37.62:** `models/ModelHooks` holds the calls `ModelManager` and `AutoFolder` make to the
     engines (language models, tactical networks, detectors, online backups); `app/ProbesWiring.wireModels` plugs them
     in. Baseline: 10 → 6 entries.
   - ~~**B5**~~ **done in v37.63:** `DecisionBridge` and `DecisionLogging` (with their tests) moved from `engines` to
     `ai`, the AI-loop edge where the app's outputs are mapped to universal decisions. Baseline: 6 → **0**: every ruled
     package (`universal`, `inference`, `core`, `capture`, `models`, `engines`) now uses only what ADR 0001 allows.
   - **Next:** add rules for `game`, `vision`, `ai` and `learning` once they are split into universal and MLBB parts
     (submilestones 4–5).
3. **Submilestones 4–8 and 10.** These are the adapter data and mechanics, the MLBB adapter, how future adapters supply data, capability and versioning rules, the final deterministic check on model outputs, and adapter onboarding.
4. **Submilestone 9.** Grow the regression net beyond the 16 situations: recorded situations from real sessions, and `GameStateTracker`.
5. **Regression-net question `marksman_poke_range`: decided by the owner on 2026-11-16, "poke instead".** A marksman with a 50%-HP enemy at poke distance should poke rather than wait for the minion wave. Done in v37.60: `TacticalEngine.marksmanPoke` (one enemy around, at 60% HP or less, in poke reach, not under its tower; the marksman healthy and safe: survival 35+), golden line updated in the same PR.

## How to resume

1. Read this file and ADR 0001.
2. Run the full CI.
3. Check the `ArchitectureRulesTest` baseline count (14).
4. Continue with B3.

Every migration PR must keep `TacticalEngineGoldenTest` green. A deliberate change in a decision updates the golden file in the same PR and says why.
