# Phase 2 — Current-State Architecture Map (submilestone 1)

**Roadmap:** `docs/MASTER_DEVELOPMENT_PLAN.md`, Phase 2 and Section 4 (work package migrated from Issue #1), submilestone 1: *inventory current repository modules, dependencies, game-specific assumptions, existing features, and runtime paths before refactoring.*
**Prepared by:** Claude Code, 2026-11-14, on `sphynx-test-branch` at `2a41892` (version 37.20).
**Method:** read-only. Every number below comes from the source at that commit (commands in Section 10). **No code was changed.**
**Builds on:** `docs/ARCHITECTURE_AUDIT.md` (2026-11-07, "Stage 2" proposal), which this map updates: steps 1–4 of that proposal now partly exist (Section 5).

---

## 1. Summary

- **One Android module, 14 packages, 179 Kotlin files, ~36,000 lines.** Package names describe *technical* roles (`vision`, `ai`, `game`) rather than *universal vs game-specific* layers.
- **MLBB knowledge is concentrated, not everywhere.** `game/` (68 MLBB terms per 1,000 lines) and `ai/` (50) carry most of it; `TacticalEngine` (135 hits) and `GameStateTracker` (108) lead. `capture/`, `inference/` and `universal/` are game-neutral.
- **A universal layer already exists but sits beside the live path.** `universal/` defines `UniversalMatchState`, `GameAdapter`, `CapabilitySet`, `Decision`, `DecisionValidator`, `DecisionArbiter`; `adapters/mlbb/MlbbAdapter` maps MLBB's `GameState` into it. Only research and decision-logging code call it. The live AI path (`AiRuntime` → `TacticalEngine`) reads MLBB's `GameState` directly.
- **The core packages depend on each other in cycles** (`ai` ↔ `game` ↔ `vision`), and `core/` — meant to be the base — depends on `ai`, `game`, `vision` and `learning`. `vision/` even reaches into the UI package (`app/IconCache`).
- **AI-model outputs are guarded, but piecemeal.** Each model (fight network, small tactical model, policy network, imitation) has its own safety check; there is no single deterministic validation step after all of them (Section 8).

## 2. Packages today

| Package | Files | Lines | MLBB terms /kLOC | What it holds | Game-neutral? |
|---|---:|---:|---:|---|---|
| `game` | 35 | 7,652 | 68.4 | Game state (`GameState`, `GameStateTracker`, 49 fields), heroes, items, skills, combos, lanes, jungle, map side, towers, scenery map, match log, wiki data | **No — MLBB throughout** |
| `vision` | 26 | 5,741 | 32.7 | Screen-phase reader, HP bars, minimap, monsters vs heroes, camps, bushes, towers, buttons, portraits, buff emblems, two YOLO detectors | No — colours and screen fractions tuned on MLBB at 2712×1220 |
| `ai` | 21 | 5,209 | 50.4 | AI loop (`AiRuntime`), rule-based `TacticalEngine`, `StrategicAdvisor` (Gemma), small tactical model, combos, objective and boss rules, meta and build advice, online LLM clients | Mostly no; `GemmaEngine`, `GroqClient`, `JsonRepair`, `ModelSpeed` are neutral |
| `learning` | 21 | 4,388 | 12.3 | Recorders, datasets, Kaggle training, fight and policy networks, A/B compare, coach, backups | Mixed: plumbing neutral, features MLBB-shaped |
| `app` | 7 | 3,824 | 24.3 | UI: `MainActivity` (3,198 lines, all pages), icon cache (MLBB wiki), charts, hold-"?" help | Mixed: shell neutral, wording MLBB |
| `controller` | 8 | 2,716 | 20.9 | Accessibility gestures, `ActionExecutor`, overlay panel, cast timing, active items | Mixed: gestures neutral, MLBB button map |
| `core` | 17 | 2,650 | 29.8 | Settings, storage, logs, session recorder, GitHub upload, What's new, `Layout` (MLBB button positions), device info | Mostly neutral; `Layout` and parts of `Settings` are MLBB |
| `research` | 13 | 1,368 | 3.6 | Practice gate (Player mode), research modes, benchmark, arbiter experiment | Mostly neutral (uses `universal/`) |
| `models` | 3 | 713 | 22.4 | Model folder import, LiteRT model slots, GGUF library | Neutral apart from MLBB file names |
| `inference` | 12 | 639 | 0.0 | Shared inference contract, GGUF backend | **Yes** |
| `universal` | 10 | 575 | 6.9 | Universal match state, adapter interface, capabilities, decisions, arbiter, validator, target fusion, spatial map | **Yes** (the few hits are in comments) |
| `capture` | 2 | 308 | 0.0 | Screen capture service, frame hub | **Yes** |
| `engines` | 3 | 187 | 5.3 | Decision bridge, decision logging, tactical readiness harness | Yes (through `universal/`) |
| `adapters` | 2 | 184 | 92.3 | `MlbbAdapter` (state → universal, capability set), `MlbbSpatial` (map landmarks) | MLBB by design |

## 3. Dependencies between packages

References from the row package to the column package (imports and fully-qualified uses). `·` = itself, `-` = none.

| uses → | adapters | ai | app | capture | controller | core | engines | game | inference | learning | models | research | universal | vision |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **adapters** | · | - | - | - | - | - | - | 3 | - | - | - | - | 18 | 1 |
| **ai** | - | · | - | 3 | 8 | 34 | 1 | **196** | - | 37 | 3 | 26 | 4 | 50 |
| **app** | - | 35 | · | 4 | 3 | 43 | - | 135 | 1 | 43 | 14 | 7 | - | 32 |
| **capture** | - | 3 | - | · | - | 5 | - | 1 | - | - | - | - | - | - |
| **controller** | - | 23 | - | 3 | · | 31 | - | 19 | - | 1 | - | - | - | 8 |
| **core** | - | **8** | - | 3 | - | · | - | **14** | - | **5** | 1 | 4 | - | **8** |
| **engines** | - | 3 | - | - | - | 2 | · | 1 | - | 2 | - | - | 11 | - |
| **game** | - | **13** | - | 2 | 4 | 40 | - | · | - | 11 | - | 3 | 1 | **63** |
| **inference** | - | - | - | - | - | - | - | - | · | - | - | - | - | - |
| **learning** | - | 19 | - | - | 10 | 89 | - | 71 | - | · | 9 | 1 | - | 29 |
| **models** | - | 8 | - | - | - | 13 | - | - | 13 | 20 | · | - | - | 12 |
| **research** | - | 8 | - | - | 3 | 8 | 3 | 13 | - | 2 | - | · | 18 | 7 |
| **universal** | - | - | - | - | - | - | - | - | - | - | - | - | · | - |
| **vision** | - | 1 | **9** | 1 | 1 | 34 | - | **83** | - | 3 | - | 3 | - | · |

**Findings:**
1. **Clean:** `universal/` and `inference/` depend on nothing in the app; `capture/` almost nothing. These are the right foundation.
2. **Cycles:** `ai ↔ game` (196 / 13), `game ↔ vision` (63 / 83), `ai ↔ vision` (50 / 1). The game state, the perception and the decisions are one tangle.
3. **Inverted base:** `core/` depends upward on `game` (`Lane`, `GameStateTracker`), `ai` (`TacticalDecision`), `vision` (detectors, `FrameProcessor`) and `learning`. Settings and the session recorder know game types.
4. **Perception → UI:** `vision/PortraitMatch` and `vision/EndBoard` use `app/IconCache` (hero portraits from the MLBB wiki).
5. **The universal layer's users** are `adapters`, `engines`, `research`, one call in `ai/TacticalEngine` and one in `game/MapIntel` — never the live decision path.

## 4. Runtime paths

### 4.1 Perception (every captured frame)
`capture/ScreenCaptureService` → `capture/FrameHub.submit` → `vision/FrameProcessor.processNext` (phase reader, HP bars, minimap, monsters, camps, towers, buttons, detectors) → `VisionResult` → `game/GameStateTracker.update(v)` → **MLBB `GameState`** (49 fields: e.g. `spell`, `lane`, `camps`, `towers`, `bossHp`, `bushes`, `towerRing`, `recallBarPct`, `skillBadgePct`, `transformBarPct`).

### 4.2 Decisions (Player mode, `ai/AiRuntime.runTacticOnce`)
1. Read `GameStateTracker.state`; ignore it when older than 1.5 s.
2. **Gate:** `research/ResearchMode.playerVerdict()` — Player acts only in a verified practice match vs Computer; otherwise advice only (`advise(s)`), nothing is touched.
3. Fight choice: `learning/Brain.choose(s)`; optionally replaced by `learning/FightNet` (fight-choice network) or `ai/TacticalAdvisor` (small tactical model), each only after `TacticalAdvisor.check`; research R3 arbiter may pick (`ResearchMode.fightChoice`).
4. `ai/TacticalEngine.decide(s, profile, plan, bias, option)` — the rule engine (1,650 lines).
5. `ai/ComboRunner.apply` (hero combos), then `learning/PolicyNet.blend` or `ImitationPolicy.blend` (learned play style), then `TacticalEngine.filterCasts`.
6. `controller/ActionExecutor.execute` → `controller/AccessibilityController` gestures. **Dry run** (default ON) stops every touch inside `ActionExecutor`.

### 4.3 Strategy
`ai/StrategicAdvisor` asks the strategic model (Gemma 4 E2B through LiteRT-LM `GemmaEngine`, or online models through `GroqClient`) on events and every N seconds; its `plan` feeds `TacticalEngine`. Prompts are built from MLBB `GameState` and MLBB wording.

### 4.4 Knowledge
MLBB data in `game/`: `HeroKit`, `HeroProfile`, `HeroLanes`, `HeroPlaybook`, `SkillBook`, `ComboBook`, `ItemBook`, `ItemClass`, `JungleDb`, `JungleSpots`, `Lanes`, `MapSide`, `WikiData`, `WikiSkills`, `MetaStore`; map landmarks in `assets/spatial/mlbb_5v5.json` (through `adapters/mlbb/MlbbSpatial`); online meta and skills (`ai/MetaAdvisor`, `learning/LibraryLearner`).

### 4.5 Learning and debugging
`learning/` recorders, datasets, Kaggle training (`CloudTrain`), networks (`FightNet`, `PolicyNet`); `core/SessionRecorder`, `core/GitHubUpload`. Gated by `learning/LearnGate` (no learning in Solo practice).

## 5. The universal layer that already exists

| Piece | File | Status |
|---|---|---|
| Universal match state | `universal/UniversalMatchState.kt` — phase, units (`Side`, `UnitKind`, health, screen and map position, confidence), resources, zone, characters, `gameSpecific` map; unknown = `null`, never a guess | Exists, unit-tested; **not versioned** |
| Adapter contract | `universal/GameAdapter.kt` — `gameId`, `displayName`, `capabilities`, `toUniversal(state)` | Exists; no `recognise(frame)`, no knowledge or rules surface yet |
| Capabilities | `universal/Capabilities.kt` — 14 capabilities × 5 statuses (Operational … Unsupported); unlisted = Unsupported | Exists; not shown in the UI |
| Decisions | `universal/Decision.kt`, `DecisionValidator.kt`, `DecisionArbiter.kt`, `DecisionLog.kt`, `TargetFusion.kt`, `CombatRange.kt`, `SpatialMap.kt` | Exist; used by research and logging only |
| MLBB adapter | `adapters/mlbb/MlbbAdapter.kt` (GameState → universal, capability set), `MlbbSpatial.kt` | Exists; called by `ResearchMode`, `ReadinessRun`, `DecisionLogging` |
| Readiness harness | `engines/TacticalReadiness.kt` | Exists |
| Older proposal steps 5–6 (prompts from the universal state; capability-aware UI) | — | **Not done** |

## 6. MLBB-specific assumptions (where they live)

| Kind | Where | Notes |
|---|---|---|
| Heroes, roles, skills, combos | `game/HeroKit`, `HeroProfile`, `HeroLanes`, `HeroPlaybook`, `SkillBook`, `ComboBook`, `WikiSkills`, `RoleProfile` | Hero names and special cases in code (e.g. `HeroKit.isYuZhongBlackDragonFlight` in `TacticalEngine.filterCasts`) |
| Items and battle spells | `game/ItemBook`, `ItemClass`, `controller/ActiveItems`, spell lists in `game/Lanes`, `WikiData` | Retribution, Flicker, Flameshot … as string literals |
| Map, lanes, objectives | `game/Lanes`, `MapSide`, `JungleDb`, `JungleSpots`, `JungleNav`, `Lithowanderer`, `TowerLogic`, `SceneryMap`, `ai/BossRule`, `ObjectiveCommit`; `assets/spatial/mlbb_5v5.json` | EXP / Gold / Mid / Roam / Jungle; Turtle, Lord; buffs |
| State model | `game/GameState` (49 fields), `Phase` (incl. MLBB screens `SHOP`, `SCOREBOARD`, `MAP`, `HERO_LOCKED`) | The live AI's only input |
| Screen and HUD | `core/Layout` (MLBB button map), `vision/ScreenReader`, `ScreenKit`, `MinimapAnalyzer`, `MinimapColors`, `HeroBarRule`, `LowBarRule`, `BuffEmblems`, `ButtonDetector`, `DeathTimers`, `KdaReader`, `LevelReader` | Colours and screen fractions measured on MLBB at 2712×1220 |
| Trained models | `vision/ObjectDetector`, `ScreenDetector` (class lists), notebooks `train_mlbb_detector`, `train_screen_detector`, `train_tactical_llm` | MLBB classes and file names (`mlbb_detector.tflite`, `mlbb_policies.zip`) |
| Match kinds | `game/MatchKind`, `research/PracticeGate`, `ComputerNameMatcher` | Ranked / Classic / practice vs Computer |
| UI | `app/MainActivity` wording, `app/IconCache` (MLBB wiki portraits) | Draft, Build, Meta, Library pages are MLBB |

## 7. Draft placement (for submilestones 2–4; not a decision)

| Future layer | Today's candidates |
|---|---|
| **Platform** (Android, game-free) | `capture/`, `inference/`, `models/` (apart from file names), most of `core/` (storage, logs, recorder, upload, settings store, device info), `controller/AccessibilityController` and the overlay shell |
| **Universal core** (MOBA concepts) | `universal/`, `engines/`, `research/` gates, the neutral parts of `ai/` (`GemmaEngine`, `GroqClient`, `JsonRepair`, `ModelSpeed`, `Deliberation`, `HitWatch`, `FountainGuard`, `Utility`), learning plumbing (`LearnGate`, recorders, `CloudTrain`) |
| **MLBB adapter** | all of `game/`, the MLBB-tuned parts of `vision/`, `core/Layout`, `adapters/mlbb/`, the MLBB knowledge in `ai/` (`TacticalEngine` rules, `BossRule`, `ObjectiveCommit`, `ComboRunner`, `BuildLab`, `MetaAdvisor`) |
| **Needs splitting** | `ai/TacticalEngine` (generic fight logic + MLBB rules), `ai/StrategicAdvisor` (prompt plumbing + MLBB text), `vision/FrameProcessor` (pipeline + MLBB readers), `core/Settings`, `app/MainActivity` |

## 8. Model outputs and the deterministic rules (baseline for submilestone 8)

| Model | Can change | Guard today |
|---|---|---|
| Fight-choice network (`FightNet`) | the fight option | used only if confidence ≥ 0.4 **and** `TacticalAdvisor.check` passes (vetoes all-in under 20% HP, all-in when retreat scores higher, poke without a ranged skill, farming with nothing to farm or at high risk) |
| Small tactical model (`TacticalAdvisor`) | the fight option | the same `check` (`validated`) |
| R3 arbiter (`ResearchMode.fightChoice`) | the fight option | research mode and verified practice only |
| Policy network (`PolicyNet.blend`) | movement and casts | never changes a RETREAT, RECALL or REGEN decision |
| Imitation (`ImitationPolicy.blend`) | movement | skipped for RETREAT, RECALL, REGEN and brain fights |
| Strategic model (Gemma / online) | the plan (`StrategicAdvisor.plan`) | `TacticalEngine` rules apply the plan; no direct touches |
| **All** | — | Player gate (verified practice only) and **Dry run** (default ON) in `ActionExecutor` |

**Gap:** the guards are per model. No single deterministic check validates the **final** decision after every model has contributed, and `universal/DecisionValidator` is not in this path.

## 9. Tests per area (unit tests)

382 in total: game 80 · research 79 · inference 41 · vision 40 · universal 36 · core 30 · ai 19 · adapters 14 · controller 14 · learning 13 · engines 10 · app 3 · models 3. No instrumented tests.
Strongest cover: game logic, research gates, the inference contract and the universal model. Weakest: `ai/` (19 tests for 5,209 lines, including the 1,650-line `TacticalEngine`), the UI, and the AI loop itself (`AiRuntime` has none).

## 10. Risks and recommended next steps

**Risks for the refactor:**
1. `TacticalEngine` and `GameStateTracker` are large, MLBB-dense and thinly tested: refactoring them first would risk MLBB behaviour. They need regression tests (submilestone 9) before they move.
2. The `ai ↔ game ↔ vision` cycles mean a package-by-package move breaks the build; the boundary has to be introduced as interfaces first.
3. `core/` depending on game types blocks a clean platform layer.
4. `UniversalMatchState` is not versioned; adding fields later would silently change meaning for logs and recordings.

**Recommended next submilestones (incremental, no behaviour change, each its own PR):**
- **2 — Game-agnostic core:** write the target-architecture ADR: the four layers of Section 7, the allowed dependency directions (adapter → universal → platform, never back), and how the cycles are cut (interfaces owned by the universal layer).
- **3 — Normalised game-state contract:** version `UniversalMatchState` (schema version, field docs, unknown-vs-absent rules) and add a contract test that `MlbbAdapter.toUniversal` keeps producing it.
- **9 (early) — Regression net:** golden tests for `TacticalEngine.decide` on recorded or synthetic MLBB situations, so the later moves can prove "no MLBB behaviour change".

**Commands used:** package sizes `find <pkg> -name '*.kt' | xargs cat | wc -l`; MLBB terms `grep -oE 'Retribution|Turtle|\bLord\b|Flicker|emblem|Lithowanderer|EXP lane|Gold lane|\bHeroDb\b|\bHeroKit\b|WikiData|Mobile Legends|\bMLBB\b|mlbb_|Purple buff|orange buff|Crimson|Litho|Abyss|Bush|bush'`; dependencies `grep -oE 'com\.sphynx\.<pkg>\.[A-Z]'` per package; runtime paths read from `ai/AiRuntime.runTacticOnce`, `vision/FrameProcessor`, `game/GameStateTracker.update`, `controller/ActionExecutor`.
