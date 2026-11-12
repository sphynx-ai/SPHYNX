# Sphynx-Auto-Pilot — Master Development Plan

**Canonical roadmap:** This is the authoritative 16-phase product-development roadmap. Product features, architecture initiatives, research work packages, and planned integrations belong here. GitHub Issues should primarily track defects, regressions, reliability problems, security problems, compatibility failures, and concrete engineering work requiring correction.

| Governance item | Rule |
|---|---|
| Product vision | Offline-first, modular Universal MOBA Companion for Android |
| Initial game integration | Mobile Legends: Bang Bang (MLBB) |
| Architecture principle | One shared companion/intelligence core; multiple game-specific adapters |
| Authority | Human owner approves major architecture, scope, priority, and release decisions |
| Primary developer | Claude Code |
| Backup / independent review | Gemini CLI |
| Project coordination | ChatGPT |
| Phase ordering | Preserve the approved 16-phase sequence; add submilestones without renumbering or reordering phases |

## 1. Status definitions

- **VERIFIED** — supported by direct repository, build, test, CI, or device evidence with a date and source.
- **REPORTED** — stated in an agent report or prior project record, not re-verified in this update.
- **IN PROGRESS** — implementation has started and remains unfinished.
- **BLOCKED** — progress depends on a named blocker.
- **PROPOSED** — candidate work not yet individually authorized for implementation.
- **APPROVED / PLANNED** — owner-approved scope awaiting scheduled implementation.
- **REQUIRES OWNER APPROVAL** — decision reserved for the owner.
- **UNKNOWN / AWAITING VERIFICATION** — current state is not established.

Approval or migration into this roadmap does not mean implementation is complete. Closing a GitHub issue after migration means only that its tracking scope has been transferred here.

## 2. Product vision and non-negotiable principles

Sphynx-Auto-Pilot is intended to become a reliable, offline-first Universal MOBA Companion—not an MLBB-only application and not merely a chatbot.

The product direction includes:
1. Multiple MOBA game integrations through isolated game adapters.
2. A reusable, game-agnostic core for normalized game state, knowledge, reasoning, recommendations, and companion workflows.
3. Distinct Strategic and Tactical AI roles. These are functional roles, not permanently fixed model identities.
4. Model-agnostic assignment of compatible models to either role, with independent runtime/backend choices where supported.
5. Coexisting LiteRT-LM and GGUF/llama.cpp inference backends; neither silently replaces the other.
6. A Model Lab with model registry, benchmarking, configuration, resource measurement, and model-pair recommendations.
7. Advanced visual, spatial, temporal, contextual, and predictive game intelligence.
8. Tactical autonomy as a long-term objective, gated by measured capability, safety, permissions, and owner-controlled rollout.
9. Complete application-wide UI/UX and polished end-to-end companion workflows.
10. Reliable Android lifecycle, memory, thermal, performance, privacy, security, diagnostics, and recovery.

### Strategic and Tactical responsibilities

- **Strategic AI:** longer-horizon match understanding, draft/composition analysis, macro decisions, objective prioritization, rotations, win conditions, and strategic explanations.
- **Tactical AI:** immediate/short-horizon situation interpretation, threat and positioning evaluation, action prioritization, combat opportunities, retreat/survival decisions, and—only after readiness gates—bounded autonomous tactical execution.

Tactical AI is **not permanently advisory-only**. The intended progression is observation → offline decision evaluation → shadow mode → supervised execution → bounded autonomy → expanded autonomy after further validation. Model size or benchmark score alone does not establish readiness.

Autonomous input must pass through a constrained execution layer:

Game Perception → Normalized Game State → Tactical Decision → Structured Action Proposal → Policy/Permission Validation → Action Scheduler → Authorized Game Input Adapter → Execution Monitoring → Outcome Evaluation.

Controls include action allowlists and limits, state validation, timeouts, cancellation, emergency stop, invalid-action rejection, audit logs, recovery behavior, and user-configurable autonomy. All behavior must comply with applicable game rules, platform restrictions, and anti-cheat policies.

## 3. Approved 16-phase development roadmap

The phase sequence below is controlling. Issue #1 and Issue #37 have been placed in the phases where their work belongs; their detailed requirements are in Sections 4 and 5.

| Phase | Name | Primary outcome | Planning status |
|---:|---|---|---|
| 1 | Repository Baseline and Project Verification | Establish authoritative repository, branch, build/test baseline, inventory, and evidence-backed current state | **REQUIRES CURRENT VERIFICATION** |
| 2 | Universal Architecture Audit and Foundation | Define shared MOBA core, normalized game-state contract, and adapter boundaries; incorporates Issue #1 | **APPROVED / PLANNED** |
| 3 | UI/UX Audit and Product Design Blueprint | Complete UX audit, information architecture, design system, screen flows, and interaction specifications | **APPROVED / PLANNED** |
| 4 | UI/UX Implementation and Experience Modernization | Implement approved design system and screen-by-screen experience, including all product states | **APPROVED / PLANNED** |
| 5 | Android Reliability and Application Foundation | Stabilize lifecycle, permissions, storage, device capability detection, crash recovery, diagnostics, and compatibility | **APPROVED / PLANNED** |
| 6 | Model-Agnostic Unified Inference Architecture | Establish common inference contracts, role assignment, model lifecycle, streaming, cancellation, health, and backend abstraction | **APPROVED / PLANNED** |
| 7 | LiteRT-LM Backend Integration and Stabilization | Integrate and validate LiteRT-LM through the common inference interface on supported Android devices | **APPROVED / PLANNED; VERIFY EXISTING IMPLEMENTATION** |
| 8 | GGUF / llama.cpp Backend Integration | Add a separately maintained Android native inference backend, including NDK/CMake/JNI/ABI and device validation | **APPROVED / PLANNED; VERIFY FEASIBILITY AND IMPLEMENTATION** |
| 9 | Model Lab and Model Management | Deliver model registry, metadata, role assignment, configuration profiles, loading status, and management | **APPROVED / PLANNED** |
| 10 | Model Benchmarking and Evaluation | Establish repeatable Strategic and Tactical evaluations for quality, latency, reliability, memory, thermal behavior, and battery impact | **APPROVED / PLANNED** |
| 11 | Model-Pairing and Device-Aware Recommendations | Compare model pairs and recommend configurations based on device, backend, workload, quality, and resource trade-offs | **APPROVED / PLANNED** |
| 12 | Strategic/Tactical Intelligence and Coordination | Deliver coordinated reasoning, shared context, task arbitration, confidence handling, and staged tactical-autonomy readiness | **APPROVED / PLANNED** |
| 13 | MLBB Companion and Game-Specific Intelligence | Complete MLBB integration and companion capabilities; incorporates Issue #37 | **APPROVED / PLANNED; EXISTING WORK REQUIRES AUDIT** |
| 14 | Universal MOBA Expansion and Additional Game Adapters | Prove adapter contract beyond MLBB and add further game integrations incrementally | **APPROVED / PLANNED** |
| 15 | Advanced Game Intelligence | Expand visual, spatial, temporal, contextual, predictive, adaptive, and evidence-driven intelligence | **APPROVED / PLANNED; SUBMILESTONES PROPOSED** |
| 16 | Optimization, Validation, and Production Readiness | Complete compatibility, reliability, security, privacy, performance, usability, documentation, and release gates | **APPROVED / PLANNED** |

### Phase dependency rules

- Phase 1 establishes the verified baseline before implementation claims or large-scale changes.
- Phase 2 defines boundaries that Phases 6–15 must respect. Work must be incremental and backward-compatible; no large rewrite before mapping dependencies and game-specific assumptions.
- Phases 3 and 4 are core product work, not optional polish. UI/UX covers the whole app, not only new AI screens.
- Phase 5 supplies Android reliability and device-awareness foundations needed by inference, perception, and companion workflows.
- Phases 6–11 establish the model/runtime platform before broad AI orchestration and device-aware model selection.
- Phase 12 defines Strategic/Tactical cooperation and evidence-based tactical-autonomy gates.
- Phase 13 develops MLBB capabilities using the universal boundaries from Phase 2.
- Phase 14 expands to other MOBAs only when an adapter and validation exist. Do not claim support before that.
- Phase 15 builds on verified perception, game-state, knowledge, and inference foundations.
- Phase 16 is a release gate, not a substitute for testing during earlier phases.

## 4. Phase 2 work package — Universal MOBA Companion Architecture (migrated from Issue #1)

**Original tracking record:** [Issue #1 — Establish the Universal MOBA Companion architecture](https://github.com/georgenaval1-blip/Sphynx-Auto-Pilot/issues/1)

**Purpose:** Evolve the current MLBB implementation toward a Universal MOBA Companion while retaining MLBB as the first supported game.

### Submilestones

1. Inventory current repository modules, dependencies, game-specific assumptions, existing features, and runtime paths before refactoring.
2. Define the game-agnostic core for shared MOBA concepts and decision support.
3. Define a normalized game-state representation and documented contract.
4. Separate title-specific data and mechanics into game adapters.
5. Define the MLBB adapter and preserve existing MLBB behavior through it.
6. Document how future adapters supply heroes, roles, lanes, items, maps, objectives, game modes, rules, events, and game-specific data.
7. Define adapter capability declarations, unsupported-feature behavior, versioning, and validation strategy.
8. Ensure model outputs cannot bypass deterministic rules, policy checks, or safety controls.
9. Add contract and regression tests proving MLBB behavior remains intact.
10. Define onboarding for future game adapters without claiming unsupported game compatibility.

### Acceptance criteria

- [ ] Current MLBB functionality and architecture are mapped before refactoring.
- [ ] Shared MOBA concepts are separated from MLBB-specific assumptions.
- [ ] A common game-state contract is documented and versioned.
- [ ] An MLBB adapter is defined and validated without regressing current behavior.
- [ ] Adapter capability and unsupported-state behavior are explicit.
- [ ] A repeatable test strategy exists for future adapters.
- [ ] Experimental model outputs cannot bypass deterministic fallbacks or execution policy.
- [ ] Architecture changes are incremental, reviewed, and documented.

**Scope clarification:** Issue #1 named Gemma 4 E2B, Gemma 3 270M, and Kotlin rules as current implementation boundaries. These are historical implementation details, not permanent model restrictions. The current project direction is model-agnostic. Deterministic Kotlin rules remain valuable safeguards where appropriate.

## 5. Phase 13 work package — MLBB Intelligence and Professional Match Hub (migrated from Issue #37)

**Original tracking record:** [Issue #37 — Add MLBB Emblem Sets, Data-Driven Meta Updates, and Pro Match Hub](https://github.com/georgenaval1-blip/Sphynx-Auto-Pilot/issues/37)

**Purpose:** Build an integrated, patch-aware MLBB intelligence system connecting emblem knowledge, official meta data, team compositions, team rotations, and professional-match evidence.

### 13.1 Emblem knowledge and Library

- Add available emblem systems, sets, talents, attributes, and configuration options to the MLBB knowledge/Library experience.
- Use **only** the specified Mobile Legends Wiki Emblems page as the source for recommended emblem sets/configurations: https://mobile-legends.fandom.com/wiki/Emblems
- Extract and display supplied emblem icons in the in-app Library with correct mapping, source attribution, and update metadata.
- Do not supplement recommendations with external guides, pro builds, statistics, or model-generated assumptions.
- If the specified Wiki does not provide a detail, mark it unavailable rather than inventing it.
- Keep emblem data separate from UI presentation so it can be updated safely.

### 13.2 Official MLBB meta tracker

- Use the official MLBB Rank page as the source for available pick, win, and ban statistics: https://www.mobilelegends.com/rank
- Display source, reporting period, rank/role/lane dimensions when available, and last successful refresh.
- Distinguish source-reported statistics from app-generated interpretation.
- Expose stale, missing, delayed, or unavailable data clearly; never fabricate metrics.
- Keep acquisition behind a replaceable provider layer and verify source/API feasibility and terms before implementation.

### 13.3 Recommended team composition

Generate coherent five-hero compositions, not merely five individually strong heroes. Where data supports it, account for role/lane coverage, draft order, current patch/meta performance, synergy, counters, damage profile, engage/disengage, durability, objective control, existing picks/bans, enemy picks, rank/data segment, and game mode. Explain evidence, confidence, strengths, weaknesses, win conditions, and likely counters.

### 13.4 Experimental team composition discovery

- Explore unconventional and emerging five-hero combinations, synergies, and role flexibility.
- Compare hypotheses against established compositions when comparable evidence exists.
- Keep experimental results separate from recommended compositions.
- Record patch, context, sample size, outcomes, limitations, and confidence.
- Do not promote a hypothesis to recommended status until it meets validation criteria.

### 13.5 Recommended team rotation intelligence

Recommend rotations based on current patch, team composition, matchup, game phase, objectives, queue coordination, and available game-state evidence.

Candidate concepts to evaluate—not hardcoded universal rules—include:
- 1-2-2 and situational 1-3-1/funnel structures.
- 4-1/deathball and split-pressure patterns.
- Purple Buff first, Orange Buff first, Lithowanderer priority, and conditional invade paths.
- Solo, Duo/Trio, coordinated five-player, and professional-play contexts.
- Wave management, lane pressure, vision, gank timing, Turtle/Lord setup, invades, and cross-map trades.

For each recommendation explain opening plan, role responsibilities, route/target lane, timing window, objective priority, prerequisites, risks, and fallback actions. Verify current patch mechanics and objective timers before encoding rules.

### 13.6 Experimental team rotation discovery

- Explore alternative rotations, jungle paths, lane assignments, and objective trades.
- Compare candidates with established and professional-match baselines where comparable data exists.
- Label discoveries as hypotheses until supported by sufficient evidence.
- Record patch, composition, matchup, queue context, sample size, results, confidence, and limitations.
- Never present speculative rotations as proven optimal strategies.

### 13.7 Professional Match Hub

Build an extensible MLBB esports hub with:
- Regional/country organization, including MPL Philippines and other supported official MPL regions.
- International events when relevant data is available, including M World/M-Series, Esports World Cup, and SEA Games.
- Live, upcoming, and completed match states.
- Schedules, teams, tournament/stage, results, and match details where available.
- Official live-watch destinations; embed only where permitted, otherwise link to official streams.
- Match analysis pages with available drafts, picks/bans, game results, game-by-game details, and supported statistics.
- Timezone-aware local schedule display and handling for postponed, cancelled, delayed, and rescheduled matches.
- Clear source and timestamp for schedules, results, streams, and statistics.

Prefer official tournament, league, team, and broadcaster sources. Respect terms, copyright, and streaming/embed restrictions.

### 13.8 Professional match evidence pipeline

Where source data supports it, extract draft context (picks, bans, roles, compositions, draft order); match context (tournament, region, patch, teams, duration, side, available game state); strategic patterns (lane assignments, rotations, jungle pathing, objective setups, invades, ganks, wave management, cross-map trades); outcomes; and provenance (source, match ID/link, timestamp, patch, completeness).

Do not infer movement, exact timing, or decision-making from data that does not expose those details. Draft/result-only data supports only draft/result-level conclusions.

### 13.9 Validation and promotion pipeline

Every discovered pattern must retain supporting matches/links, source provenance, patch/date range, sample size, comparison baseline, outcomes, confidence, limitations, and last analysis timestamp.

Use appropriate minimum evidence thresholds and statistical safeguards. Avoid overfitting to one match, team, tournament, patch, or small sample. Distinguish correlation from causation and professional patterns from ranked effectiveness. Promote experimental strategies to recommended status only after defined validation and review. Keep the evidence trail so recommendations remain explainable and reversible.

### 13.10 Searchable strategy tags and metadata

Use consistent tags across compositions and rotations, including patch/version and date range; early/mid/late game; queue context (solo, duo/trio, coordinated five-player, professional); roles, lanes, hero picks; strategy type; draft condition, matchup, objective focus; evidence level (experimental, emerging, validated, outdated); and region/tournament/team context. Tags are metadata, not evidence or confidence substitutes.

### Phase 13 acceptance criteria

- [ ] Emblem recommendation data comes only from the specified Wiki page, with attribution and update metadata.
- [ ] Supplied emblem icons are mapped and displayed correctly in the Library.
- [ ] Official-source pick, win, and ban rates are shown when available, with freshness and unavailable states.
- [ ] Recommended compositions are coherent five-hero drafts with role coverage and evidence-backed explanations.
- [ ] Experimental compositions are separate, traceable, and confidence-labelled.
- [ ] Recommended rotations account for composition, matchup, game phase, objectives, lane/wave state, and queue coordination where available.
- [ ] Experimental rotations remain hypotheses until validated.
- [ ] Rotation concepts are checked against current patch mechanics and evidence.
- [ ] Pro Match Hub supports regional competitions, MPL Philippines, other supported MPL regions, and relevant international events.
- [ ] Live/upcoming/completed states, timezone handling, official watch links, and available match analysis work correctly.
- [ ] Professional match analysis feeds recommended and experimental composition/rotation systems with provenance and limits.
- [ ] Evidence thresholds, sample sizes, confidence, patch awareness, and promotion rules are documented and tested.
- [ ] Strategy tags are searchable and consistent.
- [ ] Provider/data adapters are modular; missing, stale, and incomplete data is never fabricated.
- [ ] Loading, empty, error, stale-data, confidence, and timezone states are handled.
- [ ] Relevant tests and documentation are added.

**Implementation gate:** Start with read-only investigation of current architecture, data sources, UI, and source/API feasibility. Do not assume referenced pages expose stable public APIs. This package remains MLBB-specific within the game integration layer and must not introduce MLBB assumptions into the universal core.

### 13.11 Emblem icon assets and in-app Library integration

**User-provided reference file:** Emblems _ Mobile Legends_ Bang Bang Wiki _ Fandom.mht  
**Canonical source page:** https://mobile-legends.fandom.com/wiki/Emblems

- Treat the supplied MHTML capture as the reference asset package for emblem icons and page content. Inspect and extract relevant icon assets rather than displaying the saved webpage as the app interface.
- Identify and extract relevant emblem icons and associated visual assets. Preserve the original icon-to-emblem mapping and Wiki terminology.
- Store assets in an organized MLBB integration asset location with consistent filenames and a documented mapping/manifest.
- Exclude unrelated webpage chrome, advertisements, tracking pixels, and unrelated images.
- Preserve suitable image quality and transparency where present. Visually verify each asset and its mapping.
- Include Wiki attribution and source/capture date where available.
- If the MHTML file is unavailable to the implementation environment, request the asset or an extracted bundle; do not substitute guessed icons.
- Display the icons in the in-app Library alongside correct names and Wiki-backed details. Make entries discoverable and connected to the relevant emblem information/configuration.
- Keep presentation consistent with the app's navigation/design system and ensure offline availability through bundled/cached assets where practical.
- Recommended emblem sets/configurations must be derived exclusively from the specified Mobile Legends Wiki page. Do not use other websites, community guides, pro builds, statistics, or model-generated assumptions.
- If the Wiki lacks enough information for a recommendation, state that no Wiki-supported recommendation is available. Do not invent or externally supplement it.
- Keep Wiki facts distinct from app-generated presentation/explanation. This Wiki-only restriction applies to emblem data and recommendations, not the separately defined sources for meta, team composition, rotations, or esports analysis.

Additional acceptance criteria:
- [ ] Supplied MHTML capture is used as the reference package for emblem icon extraction.
- [ ] Only relevant emblem assets are extracted; unrelated webpage/ad/tracking assets are excluded.
- [ ] Each icon is verified against its Wiki name and mapped correctly.
- [ ] Icons and corresponding entries are displayed in the in-app Library.
- [ ] Library entries expose relevant Wiki-backed details and source attribution.
- [ ] Emblem assets remain available offline when the associated Library content is available offline.
- [ ] Every recommended emblem configuration is supported exclusively by the specified Wiki page.
- [ ] Missing Wiki-supported recommendations are explicitly marked unavailable rather than fabricated.

## 6. Five approved gaming-AI technology work packages

These were approved for integration as **proposed submilestones** within existing phases. They do not create new phases or change phase order. Each needs a scoped implementation proposal and acceptance criteria before implementation.

| Work package | Proposed placement | Scope |
|---|---|---|
| Visual Intelligence and Computer Vision | Phases 5, 12, 13, 15 | Screen understanding, object/hero/minimap recognition, tracking, spatial awareness, confidence and perception validation |
| Temporal and Contextual Game Intelligence | Phases 12, 13, 15 | Event sequences, match progression, temporal state, context windows, changing game conditions |
| Advanced Strategic and Tactical Intelligence | Phases 10, 11, 12, 15 | Macro reasoning, tactical decision quality, model coordination, prediction, readiness-gated autonomy |
| Gaming AI Companion and Coaching Research | Phases 3, 12, 13, 15 | Evaluate relevant companion/coaching products and technologies, including Razer-related developments; translate validated opportunities into requirements |
| Adaptive and Predictive Game Intelligence | Phases 10, 12, 13, 15 | Context-aware recommendations, prediction, adaptation, feedback, confidence, and evidence-based learning |

Research findings are not implementation authorization. Each submilestone must state opportunity, source/evidence, product fit, feasibility, dependencies, risks, and measurable acceptance criteria.

## 7. Cross-phase UI/UX requirements

UI/UX is a core workstream and product-quality requirement throughout the roadmap:
- Conduct a full application-wide UI/UX and information-architecture audit.
- Establish original Sphynx Auto Pilot visual identity and reusable design system: color, typography, spacing, iconography, components, navigation, data visualization, and interaction patterns.
- Redesign screens consistently, including onboarding, setup, home/command center, game center, live companion, AI intelligence, Model Lab, knowledge/library, performance, analytics, research, and settings as validated by the audit.
- Cover loading, empty, error, recovery, permission, confirmation, progress, and notification states.
- Include accessibility, readable contrast, touch targets, responsive layouts, and performance-conscious motion.
- Clearly distinguish Operational, Partially Operational, Experimental, Planned, and Unsupported capabilities.
- Show model/runtime status, data freshness, evidence, confidence, and autonomy state clearly.
- Illustrative mockups are not approved final design without owner review.

## 8. Cross-phase Android, testing, and quality requirements

- Maintain a documented device/API/ABI compatibility matrix and validate on real devices.
- Measure memory, CPU, inference latency, time-to-first-token, throughput, thermal behavior, battery impact, and stability where feasible.
- Validate model loading/unloading, lifecycle, cancellation, foreground/background transitions, crash recovery, and resource contention.
- Preserve offline-first behavior and make network-dependent data sources explicit.
- Add unit, contract, integration, regression, and device tests appropriate to each capability.
- Use synthetic fixtures by default. Real gameplay screenshots/recordings require owner approval.
- Make unsupported devices/features explicit; a successful compile alone does not prove compatibility.
- Protect user data and credentials; document permissions, retention, and external data flows.
- Test failure paths and recovery, not only successful paths.

## 9. Current execution checkpoint

**Current phase: UNKNOWN / AWAITING VERIFICATION.** Do not infer the active phase from this roadmap alone.

The latest GitHub main branch observed while preparing this document was commit 0652ebfb1a6916a3a478d0b488405f11517eca7a (2026-11-12; merge of PR #35). The repository's existing docs/DEVELOPMENT_STATUS.md contains historical reports and milestone records, including a separate earlier milestone sequence. Those historical records must not be mistaken for this 16-phase product roadmap or a current live status check.

Before assigning the next implementation phase:
1. Confirm the owner's active clone, branch, commit, and remote.
2. Check clean/dirty working-tree state and preserve local changes.
3. Reconcile current source, open PRs, recent merges, and existing documentation.
4. Map delivered functionality to the 16 phases and acceptance criteria.
5. Re-run appropriate build/tests and capture dated evidence; old results do not prove current status.
6. Identify physical-device validation still outstanding.
7. Report the earliest incomplete phase and exact next authorized task.

No phase is complete solely because a GitHub issue was closed or a planning document was written.

## 10. GitHub Issues policy and migration record

The GitHub Issues section is intended primarily for actionable defects and corrective engineering:
- Bugs, regressions, and broken functionality.
- Stability, performance, security, and compatibility defects.
- Concrete implementation blockers requiring investigation or repair.

Product feature requests, architecture initiatives, research projects, and planned integrations belong in this Master Development Plan. If a future feature request is temporarily tracked as an issue, migrate its full scope here before closing it. Add a closure comment explicitly stating that the tracking request was migrated and implementation is **not** thereby complete.

### Migrated source records

- [Issue #1 — Universal MOBA Companion architecture](https://github.com/georgenaval1-blip/Sphynx-Auto-Pilot/issues/1) → Phase 2, Section 4.
- [Issue #37 — MLBB Emblem Sets, Data-Driven Meta Updates, and Pro Match Hub](https://github.com/georgenaval1-blip/Sphynx-Auto-Pilot/issues/37) → Phase 13, Section 5.

The original GitHub records are historical traceability references. Their closure means requirements were transferred into this roadmap, **not** that architecture or MLBB features have been implemented.

## 11. Change control and roadmap governance

- Preserve the 16-phase order unless the owner explicitly approves a change.
- Add capabilities as submilestones under the best-fitting phase; avoid duplicate phases.
- Every submilestone needs scope, dependencies, owner approval where required, acceptance criteria, and evidence.
- Keep proposed research separate from approved implementation.
- Keep MLBB-specific logic inside its adapter and game-specific data layer.
- Keep Strategic recommendations distinct from Tactical execution.
- Do not hardcode model identities as permanent role assignments.
- Do not silently replace an inference backend or change user-selected models.
- Preserve existing working functionality; prefer incremental changes over rewrites.
- Use isolated branches and pull requests; do not assign Claude and Gemini overlapping edits to the same files.
- Require appropriate tests and review before marking work complete.
- Record architecture decisions, owner approvals, blockers, and meaningful development changes.

## 12. Required implementation report format

For each phase/submilestone, the responsible agent reports:
1. Phase and exact submilestone.
2. Repository, branch, and commit SHA.
3. Working-tree status.
4. Authorized scope and owner approval reference.
5. Files changed and summary.
6. Completed versus unfinished acceptance criteria.
7. Build, test, CI, and device evidence with dates.
8. Known defects, risks, and blockers.
9. Architectural decisions and deviations.
10. Exact next task and owner decision required.

An agent report is not proof by itself. Require reproducible evidence before changing a status to VERIFIED or COMPLETED.

---

**Document control:** This file is the canonical 16-phase product roadmap. docs/DEVELOPMENT_STATUS.md remains the engineering status and agent-coordination record; it should link to this plan rather than duplicate or redefine its phase sequence.