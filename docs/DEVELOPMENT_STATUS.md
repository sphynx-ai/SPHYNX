# Sphynx-Auto-Pilot — Master Development Status & Agent Coordination

| | |
|---|---|
| **Repository** | `georgenaval1-blip/Sphynx-Auto-Pilot` |
| **Initialized** | 2026-11-07 |
| **Owner** | Human Project Owner (final authority) |
| **Source** | Converted from the owner's Word document *SPHYNX_AUTO_PILOT_MASTER_DEVELOPMENT_STATUS.docx* (2026-11-07). Content is preserved; sections marked *Added at conversion* are new and are labelled as such. |

### How to read this document — status labels

| Label | Meaning |
|---|---|
| **VERIFIED** | Checked directly against the repository, a build, a test run or CI by the agent named, on the date given. |
| **REPORTED** | Stated in an agent report or a conversation; not re-checked here. |
| **IN PROGRESS** | Work started and not finished. |
| **BLOCKED** | Cannot continue without something named. |
| **PROPOSED** | Suggested, not approved. |
| **REQUIRES OWNER APPROVAL** | Needs the owner's decision before anyone acts. |
| **UNKNOWN / AWAITING VERIFICATION** | Not known, or not yet checked. |

An agent's report is not, by itself, proof that implementation or testing is complete.

---

## 1. Project Identity

**Vision: Universal MOBA Companion.** Sphynx-Auto-Pilot is intended to become a modular Android companion supporting multiple MOBA games through dedicated game adapters and a unified AI architecture. Mobile Legends: Bang Bang is an initial target, not the conceptual limit of the product.

**Core objectives:**

- Universal MOBA compatibility through modular game adapters.
- Strategic and tactical AI assistance, with clearly separated responsibilities.
- Game-state interpretation, game knowledge, and context-aware recommendations.
- Expandable AI model architecture and reliable Android performance.
- Modular, maintainable, and scalable software engineering.

## 2. Three-Agent Hierarchy

### Slot 1 — Claude Code: Primary Developer / Chief Software Engineer

- Lead software implementation and maintain architectural integrity.
- Develop major features, integrate AI engines, and perform debugging and testing.
- Propose engineering and architecture decisions for owner review.
- Report progress, modified files, test evidence, blockers, and next steps.

### Slot 2 — Gemini CLI: Backup Developer / Secondary Software Engineer

- Stand by while Claude leads implementation.
- Investigate GitHub issues, diagnose bugs, and handle approved smaller tasks.
- Perform regression testing and documentation maintenance.
- Take over explicitly authorized work when Claude is unavailable or its token allowance is exhausted.
- Inspect repository state before continuing another agent's work.

### Slot 3 — ChatGPT: Project Manager / Technical Secretary / Coordination Assistant

- Coordinate Claude and Gemini and prepare precise engineering assignments.
- Maintain project status, milestones, blockers, and decision history.
- Review agent reports and distinguish verified results from claims or assumptions.
- Prepare handoff documents, technical reviews, roadmaps, and owner decision briefs.
- Never claim code inspection, builds, tests, or repository access without actually performing them.

### Final Authority — Human Project Owner

The owner has final authority over project direction, priorities, scope changes, major architecture decisions, destructive operations, and release decisions.

## 3. Development Baseline

### 3.1 Historical baseline (as recorded in the owner's document)

The following is historical information from Claude's baseline report, as supplied in the project conversation. The owner's document notes: *it is not a live repository inspection and must be re-verified before being treated as current.* The right-hand column was added at conversion.

| Item | Recorded baseline | Re-check on 2026-11-07 (Claude Code) |
|---|---|---|
| Repository | georgenaval1-blip/Sphynx-Auto-Pilot | VERIFIED |
| Baseline branch | main | VERIFIED |
| Baseline commit | a3ccf6e (reported) | VERIFIED — `origin/main` is still at `a3ccf6e` |
| Baseline working tree | Clean (reported) | REPORTED (not re-checked from the baseline session) |
| Reported app version | 37.16 | VERIFIED on main — `core/AppVersion.kt` says `37.16`; `app/build.gradle.kts` still has `versionCode = 1`, `versionName = "1.0"` (the inconsistency is fixed by PR #3, not yet merged) |
| README / last commit version | 37.18 | REPORTED — `37.18` is the version on the `v37.18` branch; the README on main does not state a version |
| assembleDebug | Successful (reported) | VERIFIED by Claude Code in this session (clean copy of tracked files) |
| Debug APK | Approximately 89 MB | VERIFIED — 88,936,537 bytes |
| Compiler warnings | 11 (reported) | VERIFIED — 11 |
| Automated tests | None reported | VERIFIED none on main; 48 JVM tests exist on open PR branches (see 5.1) |
| CI pipeline | None reported | VERIFIED none on main; added by open PR #4 |
| Device testing | Not reported | UNKNOWN / AWAITING VERIFICATION — no device testing has been done by any agent in this coordination period |
| Source modifications during baseline assessment | None reported | REPORTED |

### 3.2 Known baseline issues (Added at conversion — VERIFIED 2026-11-07 unless noted)

- `main` tracks build output (1,395 files under `app/build/`, plus `.gradle/`, `build/`) and a ~25 MB video; there is no `.gitignore`. → Fixed by PR #3 (not merged).
- `gradlew` is not executable in Git (mode 100644). → Fixed by PR #4 (not merged).
- `litertlm-android:latest.release` is an unpinned dependency, so builds can change without any code change. → PROPOSED: pin to a specific version (not started).

## 4. AI Engine Configuration

### Strategic Engine — Gemma 4 E2B

Current direction: strategic reasoning and decision support.

### Tactical Engine — Gemma 3 270M

Current direction: experimental tactical processing. It is not yet ready for autonomous actions. Keep it advice-only unless and until it has been validated and explicitly authorized. Do not conflate strategic recommendations with tactical execution.

### 4.1 What the code does today (Added at conversion — VERIFIED by code reading on main, 2026-11-07; not device-tested)

- `ai/GemmaEngine.kt` runs the strategic model (Gemma 4 E2B or 3n E2B, GPU first). `ai/TacticalAdvisor.kt` runs the small tactical model (e.g. Gemma 3 270M, CPU, text only).
- The small tactical model is **off by default** (setting `tactical_llm`, default `false`). The model never sends a touch itself.
- **Finding — REQUIRES OWNER APPROVAL / decision.** When the owner turns `tactical_llm` on **and** the AI player mode is on, the model picks one fight option (all in / poke / farm / retreat / group / rules). If the Kotlin checks agree, that option replaces the option the Kotlin `Brain` chose, and the tactical engine plays it (`ai/AiRuntime.kt`, around lines 252–262). Kotlin safety rules still run afterwards. So, when switched on, the 270M's output *does* steer played actions through the Kotlin gate. That may be stricter than, or in conflict with, "advice-only". No code has been changed. The owner should decide whether this gated use is acceptable or should become display-only.

## 5. Current Development Status

*Owner's document (preserved):*

- **Claude Code:** Active development reported by the owner. The exact current task, branch, commit, modified files, build results, and outstanding work must be obtained from Claude's latest report.
- **Gemini CLI:** Standby / backup role. Its readiness and current repository state must be verified before accepting a task.
- **ChatGPT:** Active project coordination, documentation, prompt preparation, report review, and decision support.

Status labels: VERIFIED, IN PROGRESS, BLOCKED, PROPOSED, REQUIRES OWNER APPROVAL, and UNKNOWN / AWAITING VERIFICATION.

### 5.1 Claude Code — latest state (Added at conversion, 2026-11-07)

Nothing has been merged into `main` yet. Merge order matters: the branches stack (#4 → #5 → #6).

| PR | Branch | Head commit | Content | CI (GitHub Actions `build`) | Status |
|---|---|---|---|---|---|
| #3 | `chore/repo-cleanup` | `1d3ab3d` | `.gitignore`, untrack build output and video (video preserved outside the repo), version 37.18 everywhere, README build note | No run yet (workflow not on main); local `assembleDebug` VERIFIED | REQUIRES OWNER APPROVAL (review / merge) |
| #4 | `ci/android-build` | `b011f6e` | CI workflow (build APK + unit tests on every PR and push to main); `gradlew` executable | VERIFIED success | REQUIRES OWNER APPROVAL |
| #5 | `test/synthetic-unit-tests` | `acdd914` | First 32 JVM unit tests on synthetic data, `TESTING.md` (stacks on #4) | VERIFIED success | REQUIRES OWNER APPROVAL |
| #6 | `feature/spell-icon-retribution-upgrade` | `07b415e` | "Retribution Upgraded to Bloody/Ice/Flame" message; Blessed icons read on your scoreboard row; +16 tests (48 total) (stacks on #5) | VERIFIED success | REQUIRES OWNER APPROVAL; device test pending |

Open owner decisions:

1. Which real screenshots (if any) may be committed as test fixtures. `TESTING.md` on PR #5 lists the minimum needed and why.
2. Whether Gemma's unverified reading of the loading-card spell should stop counting as "verified vs wiki icon" (`GameStateTracker.onLoadingCardRead`). This was left unchanged because fixing it would make the strategic engine ask Gemma about the spell for longer.
3. The Gemma 3 270M gated-control finding (section 4.1).

### 5.2 Gemini CLI

UNKNOWN / AWAITING VERIFICATION — no handoff has occurred.

## 6. Development and Git Safety Rules

1. Repository scope is exclusively Sphynx-Auto-Pilot.
2. Never overwrite another agent's unfinished work.
3. Inspect the current branch, working tree, recent commits, and diffs before implementation.
4. Preserve all uncommitted and untracked work; do not silently discard changes.
5. Use isolated feature branches for engineering tasks where practical.
6. Use pull requests for review; do not push directly to main without owner authorization.
7. Run appropriate builds and tests before reporting completion.
8. Preserve existing working functionality and document behavior changes.
9. Record architectural changes and important decisions with rationale.
10. Never report proposed work as completed work.
11. Explain the consequences and obtain authorization before destructive Git operations.
12. Do not assign both developers overlapping changes to the same files simultaneously.

**Practical notes (Added at conversion):**

- **Build:** `./gradlew assembleDebug`.
- **Tests:** `./gradlew testDebugUnitTest` (requires PR #5 or later).
- **SDK:** Android SDK platform 36 and build-tools 36.0.0; JDK 21.
- **Maven Central rate limits:** they can cause intermittent HTTP 429 errors. Retrying with `--max-workers=1` has worked.

## 7. Agent Handoff Protocol

Every transfer between Claude and Gemini must include the following information:

1. Current repository and branch, plus current commit SHA.
2. Working tree status, including uncommitted and untracked changes.
3. Current development objective and exact authorized scope.
4. Completed tasks and evidence.
5. Unfinished tasks and next steps.
6. Modified files and outstanding changes.
7. Known bugs, blockers, and risks.
8. Build and test commands and their actual results.
9. Architectural decisions and constraints that must be preserved.
10. The exact next authorized task and any owner approval required.

The receiving agent must independently inspect repository state before modifying files. An agent's report is not, by itself, proof that implementation or testing is complete.

## 8. Development Milestones

| Milestone | Recorded status | Evidence / note (Added at conversion) |
|---|---|---|
| Initial architecture assessment | Completed (reported) | Claude baseline report |
| Baseline build verification | Completed (reported) | Re-verified 2026-11-07 (section 3.1) |
| Initial technical roadmap | Completed (reported) | Owner authorized items 1–4 |
| Strategic engine direction | Established | — |
| Tactical engine readiness | Experimental / not ready for autonomous action | See finding in 4.1 |
| Current Claude implementation | In progress; details awaiting latest report | Roadmap items 1–4 implemented in PRs #3–#6; all awaiting owner review; no device testing |
| Gemini backup readiness | Pending verification | — |
| Multi-MOBA adapter expansion | Roadmap / future work | — |
| Universal companion maturity | Long-term objective | — |

Only mark a milestone complete when supported by verifiable evidence.

## 9. Development Session Log

Append a new dated entry after each meaningful assignment. Preserve historical entries; do not overwrite prior records.

**Template**

```
Date:
Agent:
Branch:
Commit:
Task:
Changes made:
Files modified:
Build results:
Test results:
Outstanding issues:
Next recommended task:
```

### 2026-11-07 — Claude Code — roadmap items 1–4

- **Branches / commits:**
  - `chore/repo-cleanup` @ `1d3ab3d` (PR #3)
  - `ci/android-build` @ `b011f6e` (PR #4)
  - `test/synthetic-unit-tests` @ `acdd914` (PR #5)
  - `feature/spell-icon-retribution-upgrade` @ `07b415e` (PR #6)
- **Task:** the owner-approved items:
  1. Repository cleanup.
  2. CI.
  3. Synthetic automated tests and the screenshot-necessity report.
  4. Spell-icon matching and the upgraded Retribution message.
- **Changes made:** see section 5.1.
- **Files modified:**
  - PR #3: `.gitignore`, `core/AppVersion.kt`, `app/build.gradle.kts`, `app/MainActivity.kt` (title line), `README.md`, plus about 1,411 files untracked.
  - PR #4: `.github/workflows/android-build.yml`, and the mode of `gradlew`.
  - PR #5: `app/build.gradle.kts` (test dependencies only), `TESTING.md`, and 8 files under `app/src/test/`.
  - PR #6: `game/GameStateTracker.kt`, `vision/PortraitMatch.kt`, and 2 test files.
- **Build results:**
  - `assembleDebug` succeeded locally on every branch.
  - GitHub CI succeeded on #4, #5 and #6.
  - #3 has no CI run yet.
- **Test results:**
  - 48 JVM tests, 0 failures.
  - Mutation checks confirmed that the tests detect deliberately broken rules.
- **Outstanding issues:**
  - No device testing.
  - Owner decisions listed in 5.1.
  - `litertlm-android` is unpinned.
- **Next recommended task:**
  1. Owner review and merge in the order #4 → #3 → #5 → #6.
  2. Then device-test the Retribution-upgrade message in a real match.

### 2026-11-07 — Claude Code — this document

- **Branch:** `docs/development-status` (from `main` @ `a3ccf6e`).
- **Commit:** none; uncommitted at the owner's instruction.
- **Task:** convert the owner's coordination document into `docs/DEVELOPMENT_STATUS.md`.
- **Files:** `docs/DEVELOPMENT_STATUS.md` (new). No source code, Gradle or version changes.
- **Build / test results:** not applicable (documentation only).

## 10. Architectural Decision Log

| Decision area | Current direction |
|---|---|
| Product vision | Universal MOBA Companion |
| Primary developer | Claude Code |
| Backup developer | Gemini CLI |
| Project manager / technical secretary | ChatGPT |
| Final authority | Human project owner |
| Strategic AI | Gemma 4 E2B |
| Tactical AI | Gemma 3 270M |
| Tactical autonomy | Not authorized; advice-only pending validation |
| Repository scope | Sphynx-Auto-Pilot only |

For each new decision, record its date, rationale, implementation impact, and owner approval where required.

### Decision entries

| Date | Decision | Rationale | Impact | Approval |
|---|---|---|---|---|
| 2026-11-07 | All substantial changes go through feature branches and pull requests into `main`; no direct pushes to `main` | Repository integrity and reviewability | Every change is a PR; CI runs on each PR (once #4 is merged) | Owner approved |
| 2026-11-07 | Personal gameplay screenshots are not committed; tests use synthetic fixtures until the owner chooses specific images | Privacy; reusable tests | Colour-threshold tuning still needs real crops (see `TESTING.md`) | Owner decision (screenshots pending) |
| 2026-11-07 | Autonomous tactical actions stay disabled; no major rewrite; preserve MLBB functionality | Stability over speed | See finding 4.1 for the existing gated use | Owner directive |

## 11. Document Maintenance

This file is the shared development coordination record. Claude Code and Gemini CLI should update relevant sections after completing assigned work. ChatGPT helps maintain consistency, reviews submitted updates, and flags stale or conflicting information. Never overwrite historical session entries. If information cannot be verified, mark it UNKNOWN or AWAITING VERIFICATION.

**Ultimate objective:** Maintain one consistent development history across all three AI agents while protecting the owner's Universal MOBA Companion vision.
