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
| Reported app version | 37.16 | VERIFIED at `a3ccf6e`: `core/AppVersion.kt` said `37.16`, `app/build.gradle.kts` said `versionName = "1.0"`. Now `37.18` everywhere (PR #3, merged) |
| README / last commit version | 37.18 | REPORTED — `37.18` is the version on the `v37.18` branch; the README on main does not state a version |
| assembleDebug | Successful (reported) | VERIFIED by Claude Code in this session (clean copy of tracked files) |
| Debug APK | Approximately 89 MB | VERIFIED — 88,936,537 bytes |
| Compiler warnings | 11 (reported) | VERIFIED — 11 |
| Automated tests | None reported | VERIFIED none at `a3ccf6e`; `main` now has 48 JVM tests (see 5.1) |
| CI pipeline | None reported | VERIFIED none at `a3ccf6e`; added by PR #4 (merged) |
| Device testing | Not reported | UNKNOWN / AWAITING VERIFICATION — no device testing has been done by any agent in this coordination period |
| Source modifications during baseline assessment | None reported | REPORTED |

### 3.2 Known baseline issues (Added at conversion — VERIFIED 2026-11-07 unless noted)

- `main` tracked build output (1,395 files under `app/build/`, plus `.gradle/`, `build/`) and a ~25 MB video, and had no `.gitignore`. → Fixed by PR #3 (merged). On 2026-11-07 the history was also rewritten, with owner approval, to remove both videos and all old build output from every branch; see 5.3.
- `gradlew` was not executable in Git (mode 100644). → Fixed by PR #4 (merged).
- `litertlm-android:latest.release` was an unpinned dependency, so builds could change without any code change. → Fixed by PR #8 (merged): pinned to `0.19.1`, which is what `latest.release` resolved to on 2026-11-07, so the build did not change.

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

### 5.1 Claude Code — latest state (updated 2026-11-07, after PR #8 and the history rewrite)

**Commit hashes:** all hashes in this section are from **after** the history rewrite of 2026-11-07 (see 5.3). Older log entries in section 9 keep the hashes they were written with; 5.3 maps each old hash to its new one.

**`main` @ `3152b10`** (merge of PR #8):

- **GitHub CI:** the workflow "Android build" ran on the push to `main` (run #16) and succeeded. The commit before it, `237d203` (the rewritten main), passed CI (run #14). VERIFIED.
- **Local build:** Claude Code built a fresh copy of the rewritten `main` (`237d203`). `assembleDebug` succeeds and `testDebugUnitTest` runs 48 tests with 0 failures. PR #8 was also built locally: same 48 tests pass and the APK is the same size as main's. VERIFIED.
- **Device testing:** NOT TESTED.

Merge order on `main` (VERIFIED from the commit history): #4 (`a565512`) → #5 (`8a76ba0`) → #3 (`6cf44f7`) → #6 (`8eb5c49`) → #7 (`237d203`) → #8 (`3152b10`).

CI results below were recorded before the rewrite, so most quote the **old** commit hashes GitHub ran them on. Each old hash maps to a new one in 5.3.

| PR | Branch | Content | CI on the PR (GitHub Actions "Android build") | CI on `main` after the merge | Status |
|---|---|---|---|---|---|
| #4 | `ci/android-build` | CI workflow: builds the APK and runs the unit tests on every PR and every push to main; `gradlew` made executable | Run #1 on `b011f6e`: success | Run #4 on `7c7be9c`: success | **Merged** (VERIFIED) |
| #5 | `test/synthetic-unit-tests` | First 32 JVM unit tests on synthetic data; `TESTING.md` | Run #2 on `acdd914`: success | Run #6 on `cc09561`: **cancelled**, because the #3 merge right after it started a newer run (the workflow cancels older runs). The same code was then tested by run #8 | **Merged** (VERIFIED) |
| #3 | `chore/repo-cleanup` | `.gitignore`; build output and video no longer tracked; version 37.18 everywhere | Run #5 on `2c4d702`: success. Run #7 on `70a8e45` (#3 combined with the unit tests): success; it finished about two minutes after the merge | Run #8 on `ed878ba`: success | **Merged** (VERIFIED) |
| #6 | `feature/spell-icon-retribution-upgrade` | "Retribution Upgraded to Bloody/Ice/Flame" message; Blessed icons read on your own scoreboard row; 16 more tests (48 in total) | Run #3 on `07b415e`: success | Run #9 on `e4204d9`: success | **Merged** (VERIFIED); device test NOT TESTED |
| #7 | `docs/development-status` | This document | Run #10 on `41a39a2`: cancelled, superseded by the next push. Run #11 on `a96379a`: success | Run #12 on `e45c7bd`: success. After the rewrite, run #14 on `237d203`: success | **Merged** (VERIFIED) |
| #8 | `build/pin-litertlm` | `litertlm-android` pinned to `0.19.1` (was `latest.release`) | Run #13 on `e7a5c3d` (before the rewrite): success. Run #15 on `894226e` (after): success | Run #16 on `3152b10`: success | **Merged** (VERIFIED) by Claude Code at the owner's request, after CI passed |

Open owner decisions:

1. Which real screenshots (if any) may be committed as test fixtures. `TESTING.md` (on `main` since PR #5) lists the minimum needed and why.
2. Whether Gemma's unverified reading of the loading-card spell should stop counting as "verified vs wiki icon" (`GameStateTracker.onLoadingCardRead`). This was left unchanged because fixing it would make the strategic engine ask Gemma about the spell for longer.
3. The Gemma 3 270M gated-control finding (section 4.1).

### 5.2 Gemini CLI

UNKNOWN / AWAITING VERIFICATION — no handoff has occurred.

### 5.3 History rewrite of 2026-11-07 (owner-approved)

- **What was removed:** both videos (`VID_20261031_123317.mp4`, ~25 MB, and `VID_20261031_123911.mp4`, ~21 MB, from the `Videos` branch) and all old build output (`app/build/`, `build/`, `.gradle/`, including ~7 debug APKs of ~89 MB each). They were removed from the history of all 18 branches, using `git filter-repo`.
- **Owner approval:** scope, the `Videos` branch and the force-push were all confirmed by the owner before anything was pushed.
- **Result (VERIFIED):**
  - The repository shrank from about 283 MB to about 1.1 MB, and the largest remaining file is 158 KB.
  - No removed path remains in any commit.
  - Every branch has exactly the same current files as before.
  - Only the two "Add files via upload" commits that added the videos were dropped. All other commits, including every merge commit, are kept.
  - The rewritten main builds and passes all 48 tests.
  - All 18 branches were force-pushed, each checked against the commit it was expected to replace. GitHub's branches matched the rewritten history afterwards.
- **Consequences:**
  - Every commit hash changed.
  - **Any clone made before the rewrite must be deleted and cloned again.** This includes the owner's and Gemini CLI's. Pulling into an old clone would bring the removed files back.
- **Not removed:** GitHub's read-only references for PRs #3–#7 (`refs/pull/N/head`) still point to old commits, so the videos and APKs can still be downloaded through them. Only GitHub Support can purge these. That is a pending owner action.
- **Backup:** a git bundle of all 18 branches as they were before the rewrite (297 MB). SHA-256: `f419039117ce6f7e105cf8327260d6aedbba2158fa0f449b44adb4c7665d232b`. It was given to the owner to store outside GitHub. Do not push it, or anything restored from it, to this repository.

Old → new commit hashes for every hash this document mentions:

| Old | New | Commit |
|---|---|---|
| `a3ccf6e` | `0289237` | chore: add AI experiment issue form (the baseline) |
| `1d3ab3d` | `e021d81` | chore: stop tracking build outputs… (PR #3) |
| `2c4d702` | `ade021f` | Merge main (CI workflow) into chore/repo-cleanup |
| `70a8e45` | `7b1d080` | Merge main (unit tests) into chore/repo-cleanup |
| `b011f6e` | `3ff2d7a` | ci: build the debug APK… (PR #4) |
| `acdd914` | `b41f085` | test: first JVM unit tests… (PR #5) |
| `07b415e` | `ebf22c6` | feat: "Retribution Upgraded to <kind>"… (PR #6) |
| `f5bd09f` | `5a25ba7` | docs: add master development status… (PR #7) |
| `41a39a2` | `851562d` | docs: update development status after PRs #3–#6… |
| `a96379a` | `82ef771` | docs: re-verify development status… |
| `e7a5c3d` | `894226e` | build: pin litertlm-android to 0.19.1 (PR #8) |
| `7c7be9c` | `a565512` | Merge pull request #4 |
| `cc09561` | `8a76ba0` | Merge pull request #5 |
| `ed878ba` | `6cf44f7` | Merge pull request #3 |
| `e4204d9` | `8eb5c49` | Merge pull request #6 |
| `e45c7bd` | `237d203` | Merge pull request #7 |

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
- **Tests:** `./gradlew testDebugUnitTest`.
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
| Current Claude implementation | In progress; details awaiting latest report | Roadmap items 1–4 merged into `main` (PRs #3–#6); dependency pin (PR #8) and history cleanup done. Build and tests VERIFIED; device testing pending |
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
  - *Update 2026-11-07, added later:* #3 did get CI runs once the workflow reached `main`. Run #5 on `2c4d702` and run #7 on `70a8e45` both succeeded (see 5.1). The text above is kept as it was recorded.
- **Test results:**
  - 48 JVM tests, 0 failures.
  - Mutation checks confirmed that the tests detect deliberately broken rules.
- **Outstanding issues:**
  - No device testing.
  - Owner decisions listed in 5.1.
  - `litertlm-android` is unpinned.
- **Next recommended task:**
  1. Owner review and merge in the order #4 → #3 → #5 → #6. Done: merged on 2026-11-07 in the order #4, #5, #3, #6.
  2. Then device-test the Retribution-upgrade message in a real match.

### 2026-11-07 — Claude Code — this document

- **Branch:** `docs/development-status` (from `main` @ `a3ccf6e`).
- **Commit:** none; uncommitted at the owner's instruction.
  - *Update, added later:* committed as `f5bd09f` and pushed after the owner authorised it ("commit and push the status doc"). PR #7 was opened from it.
- **Task:** convert the owner's coordination document into `docs/DEVELOPMENT_STATUS.md`.
- **Files:** `docs/DEVELOPMENT_STATUS.md` (new). No source code, Gradle or version changes.
- **Build / test results:** not applicable (documentation only).

### 2026-11-07 — Claude Code — merges verified

- **Branch / commit:** `main` @ `e4204d9`.
- **Task:** keep the open PRs mergeable while the owner merged them, then verify `main`.
- **Changes made:**
  - Merged `main` into `chore/repo-cleanup` twice (`2c4d702`, `70a8e45`), so that its CI would run with the workflow and test it together with the unit tests.
  - No conflicts.
- **Build results:** `assembleDebug` on `main` @ `e4204d9` succeeds (fresh copy).
- **Test results:** 48 tests, 0 failures.
- **Outstanding issues:**
  - No device testing yet.
  - Owner decisions listed in 5.1.
  - `litertlm-android` is not pinned to a version.
  - The ~25 MB video is still in the Git history before PR #3. Removing it from history would mean rewriting history, which needs the owner's approval.
- **Next recommended task:**
  1. Device test of the Retribution-upgrade message.
  2. Then the owner chooses the next roadmap item.

### 2026-11-07 — Claude Code — PR #7 status re-verification (owner assignment)

- **Branch / commit:** `docs/development-status`; this commit follows `41a39a2`. `main` @ `e4204d9`.
- **Task:** documentation maintenance only. Bring this file in line with the verified GitHub state after PRs #3–#6 were merged.
- **Evidence checked:**
  - GitHub: PR #7 is open; #3–#6 are merged.
  - The list of "Android build" workflow runs #1–#10.
  - The `main` commit history.
  - `git merge-tree` of PR #7 against `main`: no conflicts.
- **Changes made:**
  - Section 5.1 now records the per-PR and on-`main` CI runs, the merge order, and PR #7's commits.
  - The earlier "merged before its last CI run finished" wording for #3 is replaced with its actual successful runs (#5, #7).
  - The `TESTING.md` location is corrected.
  - Dated update notes were added to two earlier log entries, whose original text is kept.
- **Files modified:** `docs/DEVELOPMENT_STATUS.md` only.
- **Build / test results:** none run for this change (documentation only). The CI results quoted above are GitHub's own.
- **Outstanding issues:** unchanged. No device testing; the owner decisions in 5.1; `litertlm-android` not pinned; the video still in the history before PR #3.
- **Next recommended task:** the owner reviews and merges PR #7. Then the device test of the Retribution-upgrade message.
  - *Update, added later:* PR #7 was merged. Its CI run #10 (noted above as in progress) was cancelled when the next push started run #11, which succeeded.

### 2026-11-07 — Claude Code — litertlm pin (PR #8) and history rewrite

- **Branch / commit:** `build/pin-litertlm` (PR #8), merged as `3152b10`. The history rewrite applied to all 18 branches.
- **Task 1, PR #8 (owner request):**
  - Changed `litertlm-android` from `latest.release` to `0.19.1`, the version `latest.release` resolved to that day.
  - Merged by Claude Code at the owner's request, once CI passed.
- **Task 2, history rewrite (owner request; scope and force-push confirmed by the owner):** removed both videos and all old build output from every branch's history. Details, verification and the old → new hash table are in 5.3.
- **Files modified:** PR #8: `app/build.gradle.kts` (one dependency line plus a comment). The rewrite changed no current file on any branch.
- **Build results:**
  - PR #8, local: `assembleDebug` succeeds and the APK has the same size as main's.
  - PR #8, GitHub: CI succeeded on runs #13 and #15.
  - Rewritten main, local: `assembleDebug` succeeds.
- **Test results:** 48 tests, 0 failures, both on PR #8 and on the rewritten main.
- **Outstanding issues:**
  - No device testing.
  - The owner decisions in 5.1.
  - GitHub Support needs to purge `refs/pull/3`–`7`, which still hold the removed files.
  - All old clones must be re-cloned.
- **Next recommended task:** the device test of the Retribution-upgrade message. Then the owner chooses the next roadmap item.

### 2026-11-07 — Claude Code — Phase One architecture audit (Universal MOBA Companion prompt)

- **Branch / commit:** `docs/phase1-audit`, from `main` @ `d818477`.
- **Task:** Phase One of the owner's Universal MOBA Companion prompt. Audit the existing project before any architectural change.
- **Output:** `docs/ARCHITECTURE_AUDIT.md`. It covers:
  - the baseline, module inventory and how tied each module is to MLBB;
  - the actual state of the AI engines and of vision, knowledge and the UI;
  - the capability support matrix (MLBB plus 6 other games, all Unsupported);
  - the gaps against the vision;
  - a proposed incremental Stage 2.
- **Key findings:**
  - There is no universal layer or game-adapter boundary yet.
  - Nothing has been verified on a device.
  - No performance baseline has been measured.
  - Player mode plays live matches autonomously through Accessibility gestures. Owner decision needed; Claude Code will not extend autonomous in-match control.
- **Files modified:** `docs/ARCHITECTURE_AUDIT.md` (new) and this entry. No code changes.
- **Build / test results:** none run for this change (documentation only). The baseline figures quoted come from earlier verified runs.
- **Next recommended task:** owner decisions in audit §13, then Stage 2, step 1 (universal model package and capability registry, with no behaviour change).

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
| Tactical AI status | Gemma 3 270M is no longer Experimental (owner, 2026-11-09): its advice may be used like the other engines', always after the Kotlin checks and the validator |
| Repository scope | Sphynx-Auto-Pilot only |

For each new decision, record its date, rationale, implementation impact, and owner approval where required.

### Decision entries

| Date | Decision | Rationale | Impact | Approval |
|---|---|---|---|---|
| 2026-11-07 | All substantial changes go through feature branches and pull requests into `main`; no direct pushes to `main` | Repository integrity and reviewability | Every change is a PR; CI runs on each PR and on every push to `main` | Owner approved |
| 2026-11-07 | Personal gameplay screenshots are not committed; tests use synthetic fixtures until the owner chooses specific images | Privacy; reusable tests | Colour-threshold tuning still needs real crops (see `TESTING.md`) | Owner decision (screenshots pending) |
| 2026-11-07 | Autonomous tactical actions stay disabled; no major rewrite; preserve MLBB functionality | Stability over speed | See finding 4.1 for the existing gated use | Owner directive |
| 2026-11-07 | Pin `litertlm-android` to an exact version (`0.19.1`); upgrades go in their own PR | A new LiteRT-LM release could change how the Gemma models load and run without any code change | Builds are reproducible; upgrades must be made on purpose | Owner requested (PR #8) |
| 2026-11-07 | Remove both videos and all old build output from the Git history of all branches (rewrite + force-push) | Repository size (283 MB → 1.1 MB) and removal of personal videos | All commit hashes changed; old clones must be re-cloned; GitHub PR refs #3–#7 still hold the old files until GitHub Support purges them | Owner approved scope and force-push |
| 2026-11-09 | Remove the Experimental label from the small tactical model (Gemma 3 270M); MLBB `TACTICAL_ADVICE` = Partially Operational | Owner decision; the approved readiness test (200+ moments) has not been run yet | Its advice is accepted by the validator and can win the arbiter; Kotlin checks and validator rejections still apply; R4 readiness test stays available as a measurement | Owner decision |

## 11. Document Maintenance

This file is the shared development coordination record. Claude Code and Gemini CLI should update relevant sections after completing assigned work. ChatGPT helps maintain consistency, reviews submitted updates, and flags stale or conflicting information. Never overwrite historical session entries. If information cannot be verified, mark it UNKNOWN or AWAITING VERIFICATION.

**Ultimate objective:** Maintain one consistent development history across all three AI agents while protecting the owner's Universal MOBA Companion vision.
