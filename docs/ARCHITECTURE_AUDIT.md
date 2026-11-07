# Sphynx Auto Pilot — Phase One Architecture Audit

| | |
|---|---|
| **Date** | 2026-11-07 |
| **Auditor** | Claude Code (Slot 1, primary developer) |
| **Repository** | `georgenaval1-blip/Sphynx-Auto-Pilot`, `main` @ `d818477` |
| **Purpose** | Establish what exists today, measured against the Universal MOBA Companion vision, before any architectural change. |

## How to read this

Every finding says how it was established:

- **Code**: read in the source at `d818477`.
- **Built**: compiled and unit tested in this session (`assembleDebug`, `testDebugUnitTest`) or by GitHub CI.
- **Device**: run on a phone in a real match. **Nothing in this audit is Device-verified.** No agent has run the app on a device in this coordination period. Any claim that something "works in a match" is therefore REPORTED (from the owner or older code comments), not verified.

Implementation statuses use the owner's scale: **Operational**, **Partially Operational**, **Experimental**, **Planned**, **Unsupported**. Because nothing is device-verified, no capability is rated higher than *Partially Operational*.

---

## 1. Baseline

| Item | State | Evidence |
|---|---|---|
| Language / platform | Kotlin, Android (minSdk 30, target/compile 36), AGP 9.3.1, Gradle 9.6.1, JDK 21 | Code |
| Size | 27,389 lines of Kotlin in one module (`app`), one package `com.example.mlbbresearch` | Code |
| Build | `assembleDebug` succeeds (APK ≈ 89 MB, mostly on-device ML runtimes) | Built + CI |
| Tests | 48 JVM unit tests on synthetic data (vision rules, name matching, wiki parsing, spell logic). No instrumented or device tests | Built + CI |
| CI | GitHub Actions "Android build" builds the APK and runs the tests on every PR and every push to `main` | CI |
| Dependencies | `litertlm-android` 0.19.1 (pinned), `litert` 1.4.2, `tensorflow-lite` 2.17.0, coroutines, AndroidX | Code |
| Repository hygiene | `.gitignore` in place; history cleaned of build output and videos (repository ≈ 1 MB) | Built |

## 2. What the app is today

It is a **single-game (MLBB) Android app with four modes**, switched on by the user (`AiRuntime.playerOn / drafterOn / builderOn / learnOn`):

- **Player:** the app plays the match itself through touch gestures (see §9).
- **Drafter:** pick and ban advice.
- **Builder:** item build advice, using Gemma and the online models.
- **Learn:** records your own play as training data.

The data flow (Code):

```
ScreenCaptureService (MediaProjection) ─► FrameHub ─► FrameProcessor
   ├─ vision/* (≈20 pixel-rule readers + 2 TFLite YOLO detectors) ─► VisionResult
   └─ GameStateTracker.update(VisionResult) ─► GameState            (game/)
                                                   │
   AiRuntime (every frame) ────────────────────────┤
     ├─ StrategicAdvisor ─► GemmaEngine (Gemma 4 E2B, event-driven)  (ai/)
     ├─ TacticalAdvisor  ─► small LLM (Gemma 3 270M, optional, OFF by default)
     ├─ Brain / FightNet / PolicyNet (learned fight choice / policy) (learning/)
     ├─ TacticalEngine (1,563 lines of Kotlin rules) ─► Decision
     └─ ComboRunner ─► ActionExecutor ─► AccessibilityController.dispatchGesture  (controller/)
   OverlayManager: floating panel with what the app sees and thinks
   MainActivity (≈2,500 lines, views built in code): all pages
```

## 3. Module inventory and coupling to MLBB

"MLBB references" counts mentions of MLBB-only concepts (Retribution, Turtle, Lord, emblems, Flicker, HeroDb/HeroKit, "MLBB"). It is a rough indicator of how game-specific a file is.

| Package | Lines | Role | Game-specific? (Code) |
|---|---|---|---|
| `game/` | 6,873 | Game state (`GameState`, `GameStateTracker`), hero, item and skill knowledge, lanes, jungle, towers, match log | **Entirely MLBB.** `GameState` has Turtle/Lord, battle spell, MLBB lanes, bushes, EXP ring and MLBB HUD bars as first-class fields |
| `vision/` | 5,311 | Screen-phase reader, HP bars, minimap, towers, skill buttons, death timers, portraits, 2 YOLO detectors | **Entirely MLBB.** Colour thresholds and screen fractions are tuned on MLBB at the owner's resolution (2712 × 1220) |
| `ai/` | 4,511 | Strategic and tactical advisors, Gemma engine, rule-based tactical engine, combos, build/meta advice, online LLM clients | **Mostly MLBB.** `TacticalEngine` has 159 MLBB references; `GemmaEngine` (2) and `OtherAi` (2) are nearly game-neutral |
| `learning/` | 3,837 | Recording, datasets, Kaggle training, learned fight-choice and policy networks, A/B comparison, coach | Mixed. Recording and training plumbing is generic; the features are MLBB-shaped |
| `app/` | 3,021 | `MainActivity` (all pages), icon cache (MLBB wiki), charts | MLBB wording and wiki URLs throughout |
| `controller/` | 2,302 | Touch execution through an Accessibility service; overlay; cast timing | MLBB button layout |
| `core/` | 782 | Settings, diagnostics, screen layout (MLBB button positions), version | `Layout` is an MLBB button map |
| `models/`, `capture/` | 752 | Model files, screen capture | Generic |

**Conclusion:** there is **no universal layer and no game adapter boundary today.** The good parts are reusable, but they are interleaved with MLBB specifics. Examples are the event-driven Gemma scheduling, the dual model slots, frame capture, the overlay, the recording and training pipeline, and the unit-test harness. Universal and game-specific logic are not separated anywhere.

## 4. AI engines (Code)

| Engine | What exists | Status |
|---|---|---|
| **Strategic — Gemma 4 E2B** (`GemmaEngine`, `StrategicAdvisor`, `Deliberation`) | Runs on device through LiteRT-LM, GPU first, accepts any Gemma `.litertlm` model. Event-driven: Gemma is asked on events (match start, deaths, objectives), not on every frame. Prompts are MLBB-specific text and answers are parsed from JSON (`DecisionParser`). Also reads screens (vision prompts) for the loading screen, scoreboards and player names. | **Partially Operational.** It builds; device behaviour is REPORTED only. No measured latency, no confidence scoring, no structured-output validation beyond JSON parsing |
| **Tactical — Gemma 3 270M** (`TacticalAdvisor`) | Optional second model slot (CPU, text only), **off by default** (`tactical_llm` = false). In a fight it is asked about 3 times a second and picks one of 6 fixed options. Kotlin checks must agree before the option is used. | **Experimental.** No accuracy measurement, no readiness criteria. **When it is switched on and Player mode is on, its choice does steer played actions** (finding already recorded in `DEVELOPMENT_STATUS.md` §4.1) |
| Rule-based tactical engine (`TacticalEngine`, `Utility`, `ComboRunner`) | The dependable decision layer: movement, targets, skills, retreat, objectives | Partially Operational (REPORTED); all MLBB |
| Learned networks (`Brain`, `FightNet`, `PolicyNet`) | TFLite networks trained in the notebooks from your recorded play; blended with the rules | Experimental |
| Online LLMs (`GroqClient`, `OtherAi`) | Groq, Gemini and Cerebras with web search, used by the meta, build and combo advisors. Each needs the user's own API key | Partially Operational; sends text only (no images found in these clients) |

Engine coordination is ad hoc. The steps are interleaved inside `AiRuntime`'s per-frame block: Brain → FightNet or 270M → TacticalEngine → ComboRunner → policy blend. There is no shared decision format, no confidence values, no timeouts or health model, and no conflict-resolution step. The safety rules live inside `TacticalEngine`.

## 5. Computer vision (Code)

- **Reading the screen:** almost all of it is hand-written pixel rules (colours and screen fractions). They are fast and need no model, but they are tied to MLBB's look and resolution, and every rule had to be tuned by hand.
- **Learned detectors:** 2 TFLite YOLO detectors (minimap objects and screen objects). They are trained in the notebooks on the owner's screenshots.
- **Game recognition:** none. The app assumes MLBB. `ScreenReader` classifies about 15 MLBB screens by colour.
- **Spatial and temporal understanding:** basic. Enemies are tracked across frames (`Track`), the minimap is placed on a learned "scenery map", and camera-to-minimap offsets are calibrated.
- **Unit tests:** cover a handful of rules on synthetic frames. Real-image tuning is untested (see `TESTING.md`).

## 6. Knowledge (Code)

Knowledge is scraped from the MLBB fandom wiki: hero, item, skill and jungle pages and icons, kept in caches on the phone. It also uses hand-written profiles (`HeroProfile`, `RoleProfile`, `ComboBook`, `ItemBook`) and pages the owner saved. It is not versioned, not structured per game, and partly embedded in prompts. There is no universal MOBA knowledge layer.

## 7. User interface (Code)

There is one `MainActivity` (about 2,500 lines) that builds every page in code, plus a floating overlay. The pages cover Player, Draft, Build, Meta, Library, Speed, Logs, Setup and calibration. The wording is MLBB-specific. The UI does not separate operational from experimental features.

## 8. Performance (not measured)

No performance numbers exist in the repository: model load time, inference latency, memory, CPU, battery or frame rate. A `Speed` page and `DeviceStats` collect some of these at run time, but no baseline has been recorded. **Every performance figure is UNKNOWN until it is measured on a device.**

## 9. Finding: autonomous play of live matches

When the user switches on Player mode, the app controls the game itself. `TacticalEngine` decides, then `ActionExecutor` → `AccessibilityController.dispatchGesture` moves the joystick and presses skills, spells and items during live online matches (Code).

- **Risk:** automated play in online PvP matches is against MLBB's terms of service (account bans), and it is unfair to the other players in those matches. It is also the highest-risk code path in the app.
- **Fit with the vision:** the Universal MOBA Companion vision does not need it. Every phase of the vision is about understanding, advice and analysis.
- **Recommendation (REQUIRES OWNER DECISION):**
  - Make the companion advisory: overlays, alerts, pre-match and post-match analysis.
  - Do not build the universal architecture's "action layer" for in-match control of live online games.
  - The existing Player mode is not removed or changed without the owner's decision. **Claude Code will not extend autonomous in-match control.**

## 10. Capability support matrix (2026-11-07)

Statuses use the owner's scale. The **Evidence** column says how far each one is proven.

| Capability | MLBB | Evidence (MLBB) | Wild Rift | LoL | Dota 2 | Honor of Kings | Arena of Valor | Pokémon Unite |
|---|---|---|---|---|---|---|---|---|
| Game recognition (which game is running) | Unsupported (assumed MLBB) | Code | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Screen / phase recognition | Partially Operational | Code, unit tests (loading screen only) | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Own hero identification | Partially Operational | Code; spell logic unit-tested | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| HP bars / heroes on screen | Partially Operational | Code | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Minimap analysis | Partially Operational | Code (pixel rules + YOLO) | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Skill / cooldown state | Partially Operational | Code | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Item / gold reading | Experimental | Code (via Gemma) | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Strategic assistance (Gemma 4 E2B) | Partially Operational | Code, build | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Tactical assistance (Gemma 3 270M) | Experimental | Code | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Draft / build advice | Partially Operational | Code | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Post-match analysis | Experimental | Code (match log, evaluator) | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported | Unsupported |
| Autonomous in-match control | Exists; see §9 | Code | — | — | — | — | — | — |

## 11. Gaps against the Universal MOBA Companion vision

1. There is no universal game-state representation. `GameState` is MLBB's.
2. There is no game-adapter boundary and no game recognition.
3. There is no capability registry. The app cannot say what it can and cannot read for a game.
4. There is no shared decision format: no confidence, timeouts, health checks or conflict resolution between the engines.
5. The 270M engine has no readiness criteria and no accuracy measurement.
6. Knowledge is not structured per game and is not versioned.
7. No performance baseline has been measured.
8. There are no device or real-image tests.
9. The UI is MLBB-specific and does not label experimental features.

## 12. Proposed Stage 2: universal foundation (PROPOSED — needs owner approval)

The approach is incremental and changes no behaviour. Each step is its own PR with unit tests. MLBB keeps working exactly as today throughout.

1. **Universal model, new package `universal/`:**
   - `UniversalMatchState`: phase; self, allies and enemies as `UnitState` (role, HP, position, confidence); objectives; lanes or zones; resources; cooldowns as a map; and an uncertainty value on every field.
   - `Capability` enum and `CapabilitySet`, with the owner's statuses.
   - `GameAdapter` interface: `id`, `capabilities`, `recognise(frame)`, `toUniversal(...)`.
   - All pure Kotlin, game-free and unit tested.
2. **First adapter, `adapters/mlbb/MlbbAdapter`:**
   - A pure mapping from today's `GameState` to `UniversalMatchState`, plus MLBB's capability set taken from §10.
   - Existing code keeps running unchanged; the adapter runs alongside it. Tests cover the mapping.
3. **Engine contract, `universal/Decision`:**
   - A structured recommendation (action kind, target, rationale, confidence, source engine, time to live).
   - A `DecisionValidator` that checks recommendations against the state and capabilities.
   - Gemma's and the 270M's answers are parsed into this format and logged. **Advice only.**
4. **Tactical-engine readiness harness:**
   - Recorded or synthetic scenarios are run through the 270M offline, measuring answer validity, agreement with the rules, and latency.
   - Written readiness criteria come out of it. Until the owner approves those criteria, the 270M stays advice-only.
5. **Strategic prompts from the universal state:**
   - Gemma's prompts are built from `UniversalMatchState` plus adapter-supplied game terms, instead of hard-coded MLBB text. Before and after are compared on recorded situations.
6. **Capability-aware UI:** the companion labels each feature Operational, Experimental or Unsupported for the active game.

**Explicitly not proposed:**
- No rewrite of the vision code.
- No other-game adapters until the MLBB adapter proves the boundary.
- No model replacement.
- No change to autonomous control (§9).

## 13. Owner decisions needed before Stage 2

1. **Autonomous play (§9):** keep Player mode as it is, or turn it into an advisory mode that only displays its decisions. Either way, it will not be extended.
2. **Start Stage 2, step 1** (the universal model package and capability registry, with no behaviour change).
3. Still open from `DEVELOPMENT_STATUS.md`:
   - the 270M gated-control question;
   - whether Gemma's loading-card spell reading counts as verified;
   - which real screenshots may be used as test fixtures.
