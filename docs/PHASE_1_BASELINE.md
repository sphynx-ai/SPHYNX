# Phase 1 — Repository Baseline and Project Verification

**Roadmap:** `docs/MASTER_DEVELOPMENT_PLAN.md`, Phase 1 (deliverables) and Section 9 (checklist).
**Prepared by:** Claude Code, 2026-11-14 (UTC). Replaces the 2026-11-12 draft on branch `docs/phase-1-baseline`, which described the old repository and is out of date.
**Status of this phase:** **IN PROGRESS.** Everything that can be checked from the repository and CI is answered below with evidence. Two items need the owner's phone (Section 2).

Statuses follow the plan's definitions (Section 1 of the plan). A green CI run is **VERIFIED** evidence that the app builds and its unit tests pass. It is not evidence of device behaviour.

---

## 1. Repository, branches and commits (checklist items 1, 3)

| Item | Value | Evidence | Status |
|---|---|---|---|
| Repository | `sphynx-ai/SPHYNX`. Moved from `georgenaval1-blip/Sphynx-Auto-Pilot` on 2026-11-13 with the full history; pull-request numbers restarted at #1 | `git remote -v`, GitHub | VERIFIED |
| `main` head | `162eaf3` (2026-11-14 08:05 +08:00, merge of PR #3: roadmap scope for combat perception) | `git rev-parse origin/main` | VERIFIED |
| `main` CI | **green**: Android build run 43531789404, 2026-11-14 00:05–00:11 UTC, commit `162eaf3` | GitHub Actions | VERIFIED |
| Owner's test branch | `sphynx-test-branch` head `9c8d6e0` (2026-11-15 02:20 +08:00, merge of PR #7) | `git rev-parse` | VERIFIED |
| Test-branch CI | **green**: run 43625350104 (PR #7), 2026-11-14 18:13–18:18 UTC. It built PR #7 merged onto the branch, which is the tree now at `9c8d6e0` | GitHub Actions | VERIFIED |
| Branch relationship | `main` has **no** commits the test branch lacks; the test branch is **84 commits ahead**. They merge without conflicts | `git rev-list --left-right --count`, `git merge-tree` | VERIFIED |
| Open pull requests | none | GitHub | VERIFIED |
| Remote branches | 55, of which 43 are already fully contained in `sphynx-test-branch` | `git branch -r`, `git merge-base --is-ancestor` | VERIFIED |
| Leftover branches | `test/owner-2026-11-10` (old name of the test branch) and `test/gguf-spike` (GGUF phone test) are both fully contained in `sphynx-test-branch`. The cloud session may not delete branches; the owner can | `git merge-base` | VERIFIED |
| Debug uploads | branch `recordings-for-debugging`, last upload `7365593` (2026-11-14). Uploads to the new repository work from the phone | `git log` | VERIFIED |

## 2. Owner's working copy (checklist items 1–2) — AWAITING OWNER

From the owner's terminal output and build logs on 2026-11-14:

| Item | Value | Status |
|---|---|---|
| Folder | `/storage/emulated/0/CodeOnTheGoProjects/SPHYNX` | REPORTED (build log) |
| Remote | `https://github.com/sphynx-ai/SPHYNX` | REPORTED (terminal) |
| Branch, commit | `sphynx-test-branch`; the last confirmed build was `a041aae` (PR #6) | REPORTED (build log) |
| Clean working tree | **unknown**. Code on the Go rewrites `.cg/gradle-sync/*` on every sync, and those files are still committed on `main` (Section 7, risk 2) | AWAITING OWNER |

**To close items 1–2, run in the project folder and send the output:**
```
git status
git log --oneline -1
```
Any local changes the output shows are kept, not overwritten.

## 3. Build and test baseline (checklist item 5)

| Item | Value | Status |
|---|---|---|
| Build system | Gradle 9.6.1 (wrapper), Android Gradle Plugin 9.3.1, Kotlin 2.3.21, Java 17 | VERIFIED (build files) |
| Android levels | `compileSdk 36`, `targetSdk 36`, `minSdk 30` (Android 11) | VERIFIED (build file) |
| App identity | app id `com.sphynx.autopilot`, code package `com.example.mlbbresearch`, **version 37.19 (code 3719)** | VERIFIED; the version has not changed since 2026-11-08 (risk 3) |
| Native build | NDK 29.0.14206865, CMake 3.22.1, `arm64-v8a` only; llama.cpp b11900 vendored, CPU only | VERIFIED (build file, `app/src/main/cpp/README.md`) |
| Inference libraries | LiteRT-LM `0.19.1`, LiteRT `1.4.2`, TensorFlow Lite `2.17.0`, llama.cpp b11900 | VERIFIED (build file) |
| CI | `.github/workflows/android-build.yml`: debug APK including the native build, then all JVM unit tests, on every pull request and every push to `main` | VERIFIED |
| Unit tests | **72 test files, 373 test methods**, all passing in the runs above | VERIFIED (source count, CI) |
| Instrumented / UI tests | **none** (`app/src/androidTest` is empty) | VERIFIED — gap (risk 6) |
| Build in the cloud container | No Android SDK. CI is the build evidence. Plain-Kotlin parts can be unit-tested locally (done for the GGUF backend: 41 tests, 2026-11-14) | VERIFIED |
| Owner's on-device build | builds and installs from Code on the Go; last confirmed with PR #6 on 2026-11-14. One native-build failure ("build.ninja still dirty") was cleared by deleting `app/.cxx` | REPORTED (owner's screenshots and logs) |

## 4. Code inventory (`sphynx-test-branch` at `9c8d6e0`)

One app module (`:app`). **179 Kotlin files, 35,973 lines.** Native: 9 files of our own (356 lines) plus 333 vendored llama.cpp files.

| Package | Files | Lines | What it holds |
|---|---:|---:|---|
| `game` | 35 | 7,652 | MLBB knowledge and game logic: heroes, lanes, jungle, map side, towers, scenery map, game-state tracker |
| `vision` | 26 | 5,741 | Screen perception: HP bars, minimap, monsters vs heroes, camps, buff emblems, stuck memory |
| `ai` | 21 | 5,209 | Tactical engine, strategic advisor (Gemma), boss and objective rules, runtime |
| `learning` | 21 | 4,364 | Training-data recorders, learn gate, cloud training, picture collectors |
| `app` | 7 | 3,824 | UI: mostly `MainActivity` (3,198 lines) with all pages; hold-"?" help |
| `controller` | 8 | 2,716 | Accessibility gestures, action executor, floating panel and its drawn icons |
| `core` | 16 | 2,513 | Settings, storage, logging, session recorder, GitHub upload, What's new |
| `research` | 13 | 1,368 | Practice gate, research modes, benchmark |
| `models` | 3 | 693 | Model folder import, GGUF model library |
| `inference` | 12 | 639 | Shared inference contract; the GGUF backend (`LlamaCppBackend`) is its first implementation |
| `universal` | 10 | 575 | Game-agnostic decision types, arbiter, target fusion |
| `capture` | 2 | 308 | Screen capture service |
| `engines` | 3 | 187 | Engine capability wrappers |
| `adapters` | 2 | 184 | MLBB adapter (early) |

**Libraries:** `tensorflow-lite`, `litert`, `litertlm-android`, `kotlinx-coroutines-android`, `androidx.appcompat`, `constraintlayout`, `material`, `startup-runtime`, `interpolator`. Tests: `junit 4.13.2`, `org.json`.

**Permissions:** accessibility service, overlay (`SYSTEM_ALERT_WINDOW`), screen capture (foreground service, media projection), all-files access (`MANAGE_EXTERNAL_STORAGE`, plus legacy read/write), internet, notifications, vibrate.

**Other checks:** `notebooks/` and `app/src/main/assets/notebooks/` are identical (owner rule) — VERIFIED. No keys or tokens are committed; the only matches are `ghp_...` placeholders in the bundled Claude API documentation — VERIFIED.

## 5. Delivered work mapped to the 16 phases (checklist item 4)

Status is about each phase's **acceptance criteria**, not whether related code exists.

| Phase | What exists today | Status |
|---:|---|---|
| 1 Baseline | This report | IN PROGRESS |
| 2 Universal architecture | `universal/`, `adapters/MlbbAdapter`, `engines/`. Most game logic is still MLBB-specific inside `game/`, `vision/` and `ai/`. No documented, versioned game-state contract | PARTIALLY STARTED; acceptance criteria unmet |
| 3 UI/UX blueprint | Brand identity approved 2026-11-14 (`docs/brand/`: symbol, wordmark, colours, type, rules). No UX audit or design system for the app yet | PARTIALLY STARTED |
| 4 UI/UX implementation | New icon and restyled floating panel (PR #6); hold-"?" descriptions (PR #4). App pages not yet on the brand palette or fonts | PARTIALLY STARTED |
| 5 Android reliability | Storage and all-files access, folder import, crash and diagnostics logs, session recorder with GitHub upload, Performance page, CPU check before native code | PARTIAL; no compatibility matrix |
| 6 Inference architecture | Contract in `inference/`, unit-tested; the GGUF backend implements it. LiteRT-LM (`GemmaEngine` / `LlmSlot`) does not use it yet | PARTIAL |
| 7 LiteRT-LM | Gemma 4 E2B (strategic, on the GPU) and a small tactical model run on the owner's phone | REPORTED working; not through the Phase 6 contract |
| 8 GGUF / llama.cpp | `LlamaCppBackend`, Model-page GGUF section (PR #5). Device feasibility **VERIFIED** 2026-11-14 (Qwen 0.5B 25.5 tok/s, Llama 1B 13.4 tok/s; plan Section 9.1) | IN PROGRESS; PR #5 itself not yet device-tested |
| 9 Model Lab | Model page: LiteRT slots, GGUF library (add, load, remove, test question) | PARTIAL |
| 10 Benchmarking | Model speed and screen-to-touch recorder, practice benchmark, GGUF speed report | PARTIAL |
| 11 Model pairing | — | NOT STARTED |
| 12 Strategic / Tactical coordination | Strategic advisor, tactical engine, small tactical model, arbiter, practice-only Player gate | PARTIAL; autonomy gates not formalised to the plan's ladder |
| 13 MLBB companion | Jungle, lanes, map side, Turtle / Lord rules, items and builds, draft help, scenery calibration, buff-emblem reading. Wiki emblems, official meta tracker and Pro Match Hub not started | PARTIAL |
| 14 Other MOBAs | — | NOT STARTED |
| 15 Advanced intelligence | MLBB-specific vision and map intelligence exist | PARTIAL (MLBB-specific) |
| 16 Production readiness | — | NOT STARTED |

## 6. Device validation outstanding (checklist item 6)

- **What's new:** 68 entries; **30 still flagged `needsDeviceCheck`**. Some cover work the owner has since used (the rename, the new icon and panel) but has not confirmed point by point.
- **Not yet tested on the phone:** PR #5 (GGUF backend), PR #7 (Home page power-button text), and the purple buff colour (PR #1, needs a 5v5 recording with the purple buff).
- **Older, from the previous repository:** PRs #53–#66 and #72–#84 still carry device checklists in What's new.

## 7. Risks found during the baseline (known-risk register)

| # | Risk | Effect | Proposed handling |
|---:|---|---|---|
| 1 | **`main` is 84 commits behind the test branch** | `main` doesn't hold the current app; "only phone-tested work goes to `main`" means a large batch is waiting | Owner confirms the device checks (Section 6); then move tested work to `main` in one reviewed PR |
| 2 | **`main` still tracks `.cg/gradle-sync/*`** (Code on the Go's build cache); the test branch ignores it | Switching branches on the phone fails until the files are reset (seen 2026-11-14) | Fixed automatically when the test branch reaches `main` |
| 3 | **Version stuck at 37.19 / 3719** since 2026-11-08 | Builds can't be told apart by version; only the git commit (recorded in recordings) identifies them | Owner decision: adopt a version bump per merged PR or per phone-tested batch |
| 4 | **Native build state on shared storage** (`app/.cxx`) | "build.ninja still dirty" after native changes; fixed by deleting `app/.cxx` | Watch; if it recurs, move the native build folder off shared storage |
| 5 | **One large UI file** (`MainActivity`, 3,198 lines) | Hard to change safely | Phase 3/4: split only after the UX audit |
| 6 | **No instrumented or UI tests**; device behaviour depends on manual phone checks | Regressions in UI and permissions are found late | Phase 5/16; keep pure logic unit-tested meanwhile |
| 7 | **MLBB assumptions in shared code paths** (`ai/`, `vision/`, `game/`) | Blocks other MOBAs | Phase 2 inventory before any refactor |
| 8 | **Broad permissions** (accessibility, overlay, screen capture, all-files access) | Store and policy review before any public release; privacy | Phase 5 / Phase 16 review |
| 9 | **Model roles hard-wired** (Gemma as strategist; LiteRT not on the contract) | GGUF models can't be used by the AI yet | Phases 6, 7, 9, 11, 12 |
| 10 | **GGUF needs ARMv8.2 (dot product, fp16)** | Older phones can't use GGUF | Handled: refused with a reason (`LlamaCpu`). A compatibility matrix is Phase 5 work |
| 11 | **43 merged branches left on the remote** | Clutter; risk of building from a stale branch | Owner may delete them; no code impact |
| 12 | **Brand: no trademark or look-alike search yet** | Possible conflict before a public release | Before Phase 16 / any publication |

## 8. Earliest incomplete phase and next task (checklist item 7)

- **Earliest incomplete phase: Phase 1.** Only items 1–2 (the owner's working copy) remain open.
- **Owner tasks to close Phase 1:**
  1. Send the output of `git status` and `git log --oneline -1` from the phone's project folder.
  2. Decide on versioning (risk 3).
  3. Optional: delete `test/owner-2026-11-10`, `test/gguf-spike` and other merged branches on GitHub.
- **Then Phase 2, submilestone 1:** inventory the modules, dependencies, MLBB-specific assumptions and runtime paths before any refactor (read-only, report first).

## 9. Report (plan Section 12 format)

1. **Phase / submilestone:** Phase 1 — repository baseline and verification.
2. **Repository / branch / commit:** `sphynx-ai/SPHYNX`; `main` `162eaf3`; `sphynx-test-branch` `9c8d6e0`.
3. **Working tree:** cloud container clean; owner's phone AWAITING (Section 2).
4. **Authorized scope:** owner, 2026-11-14: "Now we can start our Master Development Plan, lets start with phase 1."
5. **Files changed:** this document; the plan's Phase 1 status and Section 9 checkpoint. No code.
6. **Acceptance:** Phase 1 deliverables — baseline report, module and dependency inventory, build and test results, risk register, phase mapping, earliest incomplete task — all present; checklist items 3–7 answered with evidence; items 1–2 open.
7. **Evidence:** CI runs 43531789404 and 43625350104 (dated above); `git` commands listed in each row.
8. **Risks / blockers:** Section 7. Blocker for closing Phase 1: the owner's working-copy check.
9. **Decisions / deviations:** none. No phase reordering; no code changed.
10. **Next task / owner decisions:** Section 8.
