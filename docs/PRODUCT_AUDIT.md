# Sphynx Auto Pilot — Phase 0 and Phase 1: Repository Verification and Complete Product Audit

**Directive:** owner's Master Project Development and Evolution Directive (2026-11-10).
**Author:** Claude Code.
**Mode:** read-only. No source changed; not pushed.

**Status labels:**

| Label | Meaning |
|---|---|
| VERIFIED | Checked against code, a build or a test run. |
| DEVICE-CONFIRMED | Reported working by the owner on the POCO X6 Pro. |
| REQUIRES TESTING | Merged but not confirmed on a device. |
| PROPOSED | Suggested, not approved. |
| UNKNOWN | Not known. |

## 0. Repository and workspace (VERIFIED 2026-11-10)

| Item | State |
|---|---|
| Repository | `georgenaval1-blip/Sphynx-Auto-Pilot` (only target) |
| `main` | `716af66`, version 37.19 / 3719, app id `com.sphynx.autopilot`. Builds and installs with Code on the Go (DEVICE-CONFIRMED 2026-11-09) |
| Open PR | #29 "small tactical model no longer Experimental": CI green, waiting for the owner's merge decision |
| Local only | `docs/universal-compatibility-audit`: Stage 1 compatibility audit, hybrid-inference Phase Zero audit, this file. Not pushed (owner instruction) |
| Working tree | clean. No uncommitted user work |
| Tests | 168 on `main` (169 on PR #29), all passing |
| Owner build path | Code on the Go on the phone. **Hard constraint: no change may break it** |

## 1. What the application is (VERIFIED by code reading)

- **About 30,100 lines of Kotlin in 130 files.**

  | Area | Lines | Files |
  |---|---|---|
  | `game/` | 6,957 | 28 |
  | `vision/` | 5,322 | 21 |
  | `ai/` | 4,569 | 14 |
  | `learning/` | 3,884 | 17 |
  | `app/` | 3,128 | 5 |
  | `controller/` | 2,387 | 6 |
  | `core/` | 1,381 | 11 |
  | `research/` | 744 | 9 |
  | `universal/` | 575 | 10 |
  | `models/` | 496 | 2 |
  | `capture/` | 307 | 2 |
  | `engines/` | 187 | 3 |
  | `adapters/mlbb/` | 1 adapter + spatial | — |

- **UI:** one `MainActivity` of **2,596 lines**. Every screen is built in code: no XML layouts, Fragments, Compose or ViewModel.
  - 12 top tabs on a horizontally scrolling bar, plus swipe between pages: Home, What's new, Player, Draft, Build, Meta, Library, Model, Learn, Speed, Logs, Setup.
  - A floating overlay (`OverlayManager`) for use inside the game.
- **Theme:** a dark palette in code (`T.BG`, `SURF`, `ACCENT` …), with card, chip and header helpers. There is no design-system document.
- **Identity:** the launcher icon resource is still named `ic_mlbb`. The app is called Sphynx Auto Pilot (PR #25).

## 2. Feature inventory with real status

| Feature | Where | Status |
|---|---|---|
| Screen capture + colour vision (HP bars, minimap, HUD, scoreboard) | `capture/`, `vision/` | Working on the owner's phone (recordings, 2026-11-08). Accuracy: misreads found and fixed in #22, #26 and #28 — REQUIRES TESTING |
| Player mode (Kotlin tactical engine plays the hero) | `ai/TacticalEngine` + `controller/` | DEVICE-CONFIRMED running. Behaviour quality is under issue #2 (open) |
| Aim release fix, misread-enemy filter, scoreboard fix | #28 | REQUIRES TESTING |
| Drafter (standard) / Ranked autonomous draft | `ai/`, draft page | Exists. **Ranked needs Gemma loaded.** UNKNOWN how well it works on the device |
| Builder (items, quick buy) | build page, `QuickBuySwap` | Exists. **Needs Gemma loaded to start.** UNKNOWN on the device |
| Meta / Library (online meta, hero and item knowledge, icons) | `game/`, `learning/LibraryLearner` | Exists; needs online keys for some parts. UNKNOWN |
| Strategic LLM (Gemma 4 E2B, LiteRT-LM, GPU→CPU) | `ai/GemmaEngine` | REPORTED "ready on GPU" earlier; not re-measured |
| Tactical LLM (Gemma 3 270M) | `TacticalAdvisor` | Loads (CPU). Readiness test never run (under 200 moments). PR #29 removes the Experimental label |
| FightNet / PolicyNet / YOLO detectors (TFLite, optional files) | `learning/`, `vision/` | Exist; depend on trained files. UNKNOWN |
| Learning: Brain bandit, Coach, play-like-me, live-match recorder, cloud training (Kaggle) | `learning/` | Exist. UNKNOWN on the device |
| Universal layer (state, capabilities, decision contract, arbiter, validator, target fusion, combat range, spatial map) | `universal/`, `adapters/mlbb/` | VERIFIED by unit tests. Partly used live (fusion, combat range, arbiter in research mode) |
| Research mode R1–R5 (practice gate, arbiter experiment, moments, readiness, A/B) | `research/` | Gate DEVICE-CONFIRMED both ways (2026-11-08). R3–R5 need practice data |
| Session recorder (data + video, auto-stop, ● REC) | `core/SessionRecorder`, `VideoRecorder` | DEVICE-CONFIRMED: 13 recordings received 2026-11-08 |
| Storage folder, all-files access, new app id | #24, #25 | DEVICE-CONFIRMED (migration "everything OK") |
| What's new page | #23 | REQUIRES TESTING (layout never seen) |
| Speed page (CPU, GPU, RAM, storage, latency charts) | `SpeedViews`, `DeviceStats` | Exists; display only |

## 3. UI/UX audit (VERIFIED by code reading; visual review needs the owner's screenshots)

1. **Information architecture:**
   - The 12 tabs mix *using* the app (Home, Player), *configuring* it (Setup, Model, Player settings) and *studying* (Meta, Library, Learn).
   - **Player** alone has 19 sections.
   - **Duplicates:** "scenery and minimap synchronisation" is on both Player and Setup; "Turtle & Lord" appears twice on Player; vision tuning sits on Home.
2. **Settings sprawl:** 99 settings, 31 switches and 72 buttons spread across pages, with no search and no grouping by importance.
3. **Accessibility:**
   - **0 content descriptions** for screen readers;
   - nav buttons are 38 dp high, under the usual 48 dp touch target;
   - long texts carry information that could be visual.
4. **Feedback states:** loading, empty and error states are mostly plain text lines. Status text sits next to controls rather than in a consistent status area.
5. **Transparency:** feature status (Operational, Experimental …) exists in the code (`Capabilities`) but is **not shown in the UI**.
6. **Strengths to keep:**
   - a consistent dark card style;
   - hero and item icons;
   - a live preview of what the AI sees;
   - floating controls for use in the game;
   - plain-language texts written for the owner.
7. **Structural constraint:** a single 2,600-line activity makes any redesign risky. The safest path is to split pages into separate classes first, with no visual change, and then redesign page by page.

## 4. Mapping existing pages to the directive's proposed areas (PROPOSED)

| Proposed area | Existing content that moves there |
|---|---|
| Home / Command Center | Home's status, run switches, preview, recent actions; the What's new card |
| Live Companion | Player mode run and status, floating window, research run |
| Game Center (per game: MLBB first) | Draft, Build, Meta, Library, Player-page game knowledge (hero, spell, lane, jungle, turtle and lord) |
| AI Intelligence Center + Model Lab | Model page (files, settings, fight advisers on vs off), strategic and tactical status |
| Research | Research mode, A/B results, readiness test, session recorder |
| Analytics | Learn page's per-hero and per-role results, match log |
| Performance Center | Speed page |
| Settings | Setup (permissions, screen, calibration, storage), all behaviour switches grouped |
| Diagnostics | Logs |

## 5. Gaps against the directive

| Gap | Severity | Directive phase |
|---|---|---|
| UI in one activity; no design system; no accessibility; no capability display | High | 2–3 |
| No crash-loop guard, memory budget or thermal handling; Builder and Ranked need an LLM | High | 4 |
| LiteRT-LM types used directly; no backend interface, registry, streaming or GGUF | High | 5–8 |
| No benchmarking, model pairing or Model Lab | Medium | 9–10 |
| Strategic and tactical coordination exists only as the arbiter (research) | Medium | 11 |
| Perception accuracy still being fixed (issue #2); no confidence calibration | High | 12 |
| Autonomy levels, action proposals and a validation pipeline are not formalised. Player mode acts directly (owner: keep as is) | High | 13 |
| Only the MLBB adapter; second adapter not started | Expected | 16 |

## 6. Tactical autonomy boundary (Claude Code's position, stated in advance)

Claude Code will build the autonomy levels (0–5), the structured action proposals, the policy and validation layer, shadow execution and the readiness gates.

**Autonomous execution by these new systems is enabled only in practice matches against Computer**, behind the existing practice gate. It is not extended to matches with other real players, which the directive itself places under game rules and anti-cheat policies. Player mode keeps its current, owner-decided behaviour.

## 7. Recommended order (dependency-checked)

1. **Owner decisions (this report):** see §8.
2. **Phase 2 — product blueprint and design direction:**
   - a written spec: areas, navigation, design tokens, component list;
   - 2–3 visual directions as static mock-ups for the owner to choose from.

   No code.
3. **Phase 3a — UI foundation with no visual change:**
   - split `MainActivity` into one class per page;
   - add shared components, the capability display and accessibility labels;
   - the build in Code on the Go is checked by the owner.

   Then redesign page by page, as approved.
4. **Phase 4 — Android reliability:** crash-loop guard, memory and thermal admission, Builder and Ranked fallbacks.
5. **Phases 5–8 — inference:** contracts → llama.cpp spike → LiteRT-LM stabilisation → registry and Model Lab. The hybrid-inference Phase Zero audit already covers these.

**Issue #2's device re-test and R4/R5 data collection run in parallel.** They need the owner's play time, not code.

## 8. Decisions needed from the owner

1. Merge PR #29 (270M no longer Experimental): now, or after a Code on the Go build?
2. Push the local documentation branch (three audit docs) as a docs-only PR?
3. Approve **Phase 2**: product blueprint and 2–3 design directions as static mock-ups, no code.
4. UI technology for the redesign:
   - keep the current code-built Views (lowest risk for Code on the Go); or
   - move to Jetpack Compose (bigger change; needs confirming that Code on the Go builds it).
5. Confirm the autonomy boundary in §6.
