# Phase 1 — Repository Baseline and Project Verification

**Roadmap:** `docs/MASTER_DEVELOPMENT_PLAN.md`, Phase 1 (section 9 checklist).
**Prepared by:** Claude Code (primary developer), 2026-11-12.
**Status of this phase:** **IN PROGRESS.** Items 1–2 need the owner's phone; everything else is answered below with evidence.

Statuses follow the plan's definitions (section 1). A CI run is VERIFIED evidence for "builds and unit tests pass" only — not for device behaviour.

---

## 1. Repository, branches and commits (checklist items 1, 3)

| Item | Value | Evidence | Status |
|---|---|---|---|
| Repository | `georgenaval1-blip/Sphynx-Auto-Pilot` | GitHub | VERIFIED |
| `main` head | `4aa9649` (2026-11-12 20:23 +08:00, "docs: add canonical 16-phase master development plan") | `git rev-parse origin/main` | VERIFIED |
| `main` CI | **green** — Android build run #144, 2026-11-12 12:23–12:27 UTC, commit `4aa9649` | GitHub Actions run 43217474175 | VERIFIED |
| Owner's test branch | `test/owner-2026-11-10` head `234e8a2` (2026-11-12 19:47 +08:00, merge of PR #66) | `git rev-parse` | VERIFIED |
| Test branch CI | **green** on its last PR head (`9e14708`, PR #66, 2026-11-12 06:16–06:20 UTC) | Actions run 43166913544 | VERIFIED |
| Branch relationship | test branch is **29 commits ahead / 25 behind** `main`; they **merge cleanly** (`git merge-tree`, no conflicts) | `git rev-list --left-right --count` | VERIFIED |
| What `main` has that the test branch lacks | #31, #33 and #35 merge resolutions, #32 docs, the Master Development Plan, project skills (#36) — no app behaviour beyond what the test branch already contains | `git log` | VERIFIED |
| What the test branch has that `main` lacks | PRs **#53–#66** (2026-11-12 fixes and debug tooling) — **not yet tested on a phone** | `git log` | VERIFIED |
| Open PRs | **#34** only (llama.cpp spike, draft, "do not merge") | GitHub | VERIFIED |
| Remote branches | 53 (many are merged feature branches; cleanup is optional, owner's call) | `git branch -r` | VERIFIED |
| Recordings branch | `recordings-for-debugging` (renamed from `recordings`; the old `recordings` branch still exists and needs the owner to delete it) | `git ls-remote` | VERIFIED |

**Reconciliation action (proposed, Phase 1):** merge `main` into `test/owner-2026-11-10` so the owner's build carries the plan, the docs and the #31/#33/#35 resolutions. It merges without conflicts.

## 2. Owner's working copy (checklist items 1–2) — AWAITING OWNER

The owner builds on the phone in Code on the Go. Only the owner can confirm:

1. Which branch is checked out (expected: `test/owner-2026-11-10`).
2. Which commit (`git log --oneline -1`; expected `234e8a2` or later).
3. Whether the working tree is clean (`git status`). Any local changes must be kept, not overwritten.

## 3. Build and test baseline (checklist item 5)

| Item | Value | Status |
|---|---|---|
| Build system | Gradle (Kotlin DSL), Android Gradle Plugin; `compileSdk 36`, `minSdk 30`, `targetSdk 36`; app `37.19` (`versionCode 3719`) | VERIFIED (build file) |
| Inference dependencies | LiteRT-LM `litertlm-android 0.19.1`, LiteRT `1.4.2`, TensorFlow Lite `2.17.0` | VERIFIED (build file) |
| CI | `.github/workflows/android-build.yml` — assembles the app and runs JVM unit tests on every PR and push to `main` | VERIFIED |
| Unit tests | 59 test files on the test branch (48 on `main`); all passing in the runs above | VERIFIED (CI) |
| Local build in this environment | not possible (no Android SDK in the Claude Code container); CI is the build evidence | — |
| Owner's on-device build (Code on the Go) | builds reported working through PR #52 | REPORTED |
| Build identity in recordings | every recording now records the build's git commit (PR #62) | VERIFIED (code), device AWAITING |

## 4. Code inventory (test branch `234e8a2`)

223 Kotlin source files, one app module, package `com.example.mlbbresearch` (app id `com.sphynx.autopilot`).

| Package | Files | Lines | What it holds |
|---|---:|---:|---|
| `game` | 35 | 7,585 | MLBB knowledge and game logic: heroes, lanes (`HeroLanes`, `LaneLogic`), jungle (`JungleDb`, `JungleSpots`, `JungleNav`, `Lithowanderer`), map side, match kind, towers, scenery map, state tracker |
| `vision` | 23 | 5,509 | Screen perception: HP bars, minimap, monsters vs heroes (`HeroBarRule`, `CampRule`), bushes, towers, frame processor |
| `ai` | 20 | 5,154 | Tactical engine, strategic advisor (Gemma), boss / objective rules, touch pacing, runtime |
| `learning` | 18 | 3,955 | Recorders for training data, cloud training, picture collectors |
| `app` | 5 | 3,440 | UI (mostly one `MainActivity` with pages) |
| `controller` | 7 | 2,497 | Accessibility gestures, action executor, overlay |
| `core` | 16 | 2,287 | Settings, storage, logging, session recorder, GitHub upload, What's new |
| `research` | 13 | 1,355 | Practice gate, research mode (R3 arbiter, R5 A/B), benchmark |
| `universal` | 10 | 575 | Game-agnostic decision types, arbiter, target fusion |
| `models` | 2 | 492 | Model import folder |
| `capture` | 2 | 307 | Screen capture service |
| `inference` | 7 | 281 | Runtime-independent inference contracts (#33) — not wired in yet |
| `engines` | 3 | 187 | Engine capability wrappers |
| `adapters` | 2 | 184 | MLBB adapter (early) |

## 5. Delivered work mapped to the 16 phases (checklist item 4)

Status is about the phase's **acceptance criteria**, not about whether code exists.

| Phase | What exists today | Status |
|---:|---|---|
| 1 | This report | IN PROGRESS |
| 2 Universal architecture | `universal/` (decision types, arbiter, fusion), `adapters/MlbbAdapter`, `engines/`. Most game logic is still MLBB-specific inside `game/`, `vision/`, `ai/`. No documented, versioned game-state contract. | PARTIALLY STARTED; acceptance criteria unmet |
| 3 UI/UX blueprint | No audit or design system yet | NOT STARTED |
| 4 UI/UX implementation | Working UI, ~3,400 lines in one activity; floating panel; no design system | NOT STARTED (per plan definition) |
| 5 Android reliability | Storage / all-files access, import folder, crash log, diagnostics, device info / Performance page, session recorder with debug data and GitHub upload (#62–#65) | PARTIAL; no compatibility matrix |
| 6 Inference architecture | Contracts in `inference/` (#33), unit-tested; app still uses `LlmSlot` / `GemmaEngine` directly | PARTIAL |
| 7 LiteRT-LM | Gemma 4 E2B (strategic) and a small tactical model run through LiteRT-LM 0.19.1 on the owner's phone | REPORTED working; not through the Phase 6 interface |
| 8 llama.cpp / GGUF | Spike PR #34 (draft, untested) | IN PROGRESS (spike only) |
| 9 Model Lab | Model page (import, load, status) | PARTIAL |
| 10 Benchmarking | Model speed + screen-to-touch recorder (#35), practice benchmark scorecards (#31) | PARTIAL |
| 11 Model pairing | — | NOT STARTED |
| 12 Strategic/Tactical coordination | Strategic advisor (Gemma), tactical engine (Kotlin rules), small tactical model advice, R3 arbiter (safety-first since #56), practice-only Player gate (autonomy only in verified practice vs Computer) | PARTIAL; autonomy readiness gates not formalised to the plan's ladder |
| 13 MLBB companion | Extensive: jungle routes and monster naming, lanes / recommended lanes per hero, map side, Turtle / Lord rules, items and builds, draft help, scenery calibration. Issue #37 items (Wiki emblems, official meta tracker, Pro Match Hub) not started. | PARTIAL; existing work needs the plan's audit |
| 14 Other MOBAs | — | NOT STARTED |
| 15 Advanced intelligence | Vision and map intelligence exist in MLBB form (monster / hero bars, minimap, lane side, boss icons) | PARTIAL (MLBB-specific) |
| 16 Production readiness | — | NOT STARTED |

## 6. Device validation outstanding (checklist item 6)

- **PRs #53–#66** (2026-11-12): all need a device test. The checklist is in the 2026-11-12 handover message and in What's new.
- **PR #34** (llama.cpp spike): untested.
- **What's new flags:** 43 entries still carry `needsDeviceCheck = true`, including PRs #18–#52 that the owner has since reported as tested. **Finding:** the flags are stale; after the owner confirms, the tested entries should be cleared so the list means "still to test".
- **Plan section 8 note:** the plan asks for synthetic fixtures by default; real gameplay screenshots / recordings need owner approval. The owner has supplied and approved gameplay screenshots and recordings (2026-11-10 to 2026-11-12); this approval should be recorded in the plan.

## 7. Risks found during the baseline

1. **Branch divergence.** The phone build (test branch) lacks the plan and docs from `main`; `main` lacks the untested #53–#66. Fix: merge `main` into the test branch (clean), and keep moving tested work to `main` in batches.
2. **One large UI file** (`MainActivity`, ~3,400 lines) — a Phase 3/4 concern, not to be split before the UX audit.
3. **MLBB assumptions in shared code paths** (lanes, jungle, map geometry inside `ai/` and `vision/`) — the Phase 2 inventory must map them before any refactor.
4. **No local build in the developer container** — every change relies on CI; device behaviour relies on the owner.
5. **Model identities are hard-wired in places** (Gemma as strategist). The plan makes role assignment model-agnostic (Phases 6, 9) — record as a known gap, not a defect.

## 8. Earliest incomplete phase and next task (checklist item 7)

- **Earliest incomplete phase: Phase 1** (items 1–2: the owner's working copy).
- **Next tasks:**
  1. Owner: confirm branch, commit and clean state of the phone's working copy.
  2. Owner decision: approve merging `main` into `test/owner-2026-11-10` (reconciliation, no conflicts).
  3. Owner: device-test #53–#66 with the checklist; confirm which older What's new flags can be cleared.
- **Then Phase 2, submilestone 1:** inventory modules, dependencies, MLBB-specific assumptions and runtime paths before any refactor (read-only, report first).

## 9. Report (plan section 12 format)

1. **Phase / submilestone:** Phase 1 — baseline and verification.
2. **Repository / branch / commit:** `georgenaval1-blip/Sphynx-Auto-Pilot`, `main` `4aa9649`; test branch `234e8a2`.
3. **Working tree:** developer container clean; owner's phone AWAITING.
4. **Authorized scope:** owner, 2026-11-12: "analyze [the plan], make it your own project … start again at Phase 1 with our progress and changes preserved."
5. **Files changed:** this document only (no code).
6. **Acceptance:** checklist items 3–7 answered with evidence; items 1–2 open.
7. **Evidence:** CI runs cited above (dated); git commands listed.
8. **Risks / blockers:** section 7; blocker for closing Phase 1 = owner confirmation.
9. **Decisions / deviations:** none — no phase reordering, no code changed.
10. **Next task / owner decisions:** section 8.
