# Sphynx — Master Development Plan

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

Sphynx is intended to become a reliable, offline-first Universal MOBA Companion—not an MLBB-only application and not merely a chatbot.

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

The phase sequence below is controlling. Issue #1, Issue #37, and the MLBB-specific combat-perception scope from the historical Issue #2 have been placed in the phases where their work belongs; their detailed requirements are in Sections 4 and 5.

| Phase | Name | Primary outcome | Planning status |
|---:|---|---|---|
| 1 | Repository Baseline and Project Verification | Establish authoritative repository, branch, build/test baseline, inventory, and evidence-backed current state | **COMPLETE** (2026-11-14) — baseline report `docs/PHASE_1_BASELINE.md`; owner's working copy VERIFIED; versioning decided |
| 2 | Universal Architecture Audit and Foundation | Define shared MOBA core, normalized game-state contract, and adapter boundaries; incorporates Issue #1 | **IN PROGRESS — resumed 2026-11-16** after Phase 4 (paused 2026-11-14 to 2026-11-16). ADR 0001 **accepted by the owner** 2026-11-16. Done and next: `docs/PHASE_2_STATUS.md` |
| 3 | UI/UX Audit and Product Design Blueprint | Complete UX audit, information architecture, design system, screen flows, and interaction specifications | **COMPLETE** (2026-11-15) — owner approved the design for all five sections. Deliverables: `docs/PHASE_3_UX_AUDIT.md` (29 findings, with the owner's phone screenshots), `PHASE_3_INFORMATION_ARCHITECTURE.md`, `PHASE_3_DESIGN_TOKENS.md`, `PHASE_3_STATE_MATRIX.md`, `PHASE_3_SCREEN_SPECS.md` + mockups (`docs/mockups/phase3/`), `PHASE_3_DESIGN_BACKLOG.md` (use cases, prioritized backlog). Open into Phase 4: the pixel-font pick from a phone sample |
| 4 | UI/UX Implementation and Experience Modernization | Implement approved design system and screen-by-screen experience, including all product states | **COMPLETE** (2026-11-16) — all twelve items of `docs/PHASE_3_DESIGN_BACKLOG.md` built (v37.38–v37.58, each CI-built as its own PR) and phone-tested by the owner (last batch v37.52–v37.58 confirmed 2026-11-16). Not done by owner decision: the launcher icon does not follow the theme |
| 5 | Android Reliability and Application Foundation | Stabilize lifecycle, permissions, storage, device capability detection, crash recovery, diagnostics, and compatibility | **APPROVED / PLANNED** |
| 6 | Model-Agnostic Unified Inference Architecture | Establish common inference contracts, role assignment, model lifecycle, streaming, cancellation, health, and backend abstraction | **APPROVED / PLANNED** |
| 7 | LiteRT-LM Backend Integration and Stabilization | Integrate and validate LiteRT-LM through the common inference interface on supported Android devices | **APPROVED / PLANNED; VERIFY EXISTING IMPLEMENTATION** |
| 8 | GGUF / llama.cpp Backend Integration | Add a separately maintained Android native inference backend, including NDK/CMake/JNI/ABI and device validation | **IN PROGRESS** — feasibility VERIFIED on the owner's phone 2026-11-14; backend implementation started (Section 9.1) |
| 9 | Model Lab and Model Management | Deliver model registry, metadata, role assignment, configuration profiles, loading status, and management | **APPROVED / PLANNED** |
| 10 | Model Benchmarking and Evaluation | Establish repeatable Strategic and Tactical evaluations for quality, latency, reliability, memory, thermal behavior, and battery impact | **APPROVED / PLANNED** |
| 11 | Model-Pairing and Device-Aware Recommendations | Compare model pairs and recommend configurations based on device, backend, workload, quality, and resource trade-offs | **APPROVED / PLANNED** |
| 12 | Strategic/Tactical Intelligence and Coordination | Deliver coordinated reasoning, shared context, task arbitration, confidence handling, and staged tactical-autonomy readiness; includes reusable target-state, tracking, and combat-decision logic from Issue #2 | **APPROVED / PLANNED** |
| 13 | MLBB Companion and Game-Specific Intelligence | Complete MLBB integration and companion capabilities; incorporates Issue #37 and the MLBB-specific combat-perception integration from historical Issue #2 | **APPROVED / PLANNED; EXISTING WORK REQUIRES AUDIT** |
| 14 | Universal MOBA Expansion and Additional Game Adapters | Prove adapter contract beyond MLBB and add further game integrations incrementally | **APPROVED / PLANNED** |
| 15 | Advanced Game Intelligence | Expand visual, spatial, temporal, contextual, predictive, adaptive, and evidence-driven intelligence | **APPROVED / PLANNED** |
| 16 | Optimization, Validation, and Production Readiness | Complete compatibility, reliability, security, privacy, performance, usability, documentation, and release gates | **APPROVED / PLANNED** |

### 3.1 Mandatory phase-specific professional role and expertise framework

**Status: APPROVED / PLANNED — roadmap-wide execution standard.**

Every phase must be executed with an explicit professional specialist profile. Claude Code is the primary developer and must adopt the phase's primary role at the start of the phase, while drawing on the supporting roles listed below when their disciplines are involved. These profiles define the required perspective and standards; they do not authorize work outside the phase's approved scope.

#### Mandatory profile requirements for every phase

Before work begins, the responsible agent must read the phase profile, inspect dependencies and current repository evidence, identify risks, and produce a phase execution plan. Each phase report must identify:

1. **Primary role/persona** — the professional identity and level of responsibility appropriate to the phase.
2. **Required expertise** — concrete technologies, disciplines, and domain knowledge.
3. **Supporting specialist roles** — perspectives required for cross-disciplinary review.
4. **Mission and boundaries** — the outcome to deliver and what is explicitly out of scope.
5. **Methodology and engineering standards** — how investigation, design, implementation, and verification are to be performed.
6. **Quality and acceptance criteria** — measurable evidence required to demonstrate completion.
7. **Deliverables** — implementation, tests, documentation, measurements, decisions, and handoff artifacts.

The agent must challenge unsupported assumptions, distinguish verified facts from hypotheses, assess architectural and backward-compatibility effects, prefer incremental and reversible changes, and never claim completion based only on its own narrative. Use evidence appropriate to the phase: repository inspection, reproducible tests, build/CI output, device measurements, source provenance, or owner review.

#### Phase-specific specialist assignments

**Phase 1 — Repository Baseline and Project Verification**
- **Primary role:** Senior Software Auditor and Release/Build Verification Engineer.
- **Expertise:** Git history and branch integrity, Android/Gradle builds, dependency and module inventory, test/CI analysis, reproducibility, release evidence, and technical documentation.
- **Supporting roles:** Android Build Engineer; QA/Test Engineer; Repository and Configuration Auditor.
- **Mission:** Establish an accurate, reproducible baseline of the actual repository and distinguish implemented, partial, experimental, planned, and broken capabilities.
- **Standards and evidence:** Read-only first; preserve local work; verify remotes, branch, commit, worktree, build variants, tests, and environment; date all evidence; do not infer device compatibility from compilation alone.
- **Deliverables:** Baseline report, module/dependency inventory, build and test results, known-risk register, phase mapping, and earliest incomplete authorized task.

**Phase 2 — Universal Architecture Audit and Foundation**
- **Primary role:** Principal Android Software Architect and Modular Systems Architect.
- **Expertise:** Domain-driven design, modular Android/Kotlin architecture, game-agnostic domain models, adapter/plugin boundaries, normalized state contracts, versioning, dependency direction, migration, and deterministic policy boundaries.
- **Supporting roles:** MOBA Domain Systems Analyst; API/Contract Architect; Security and Reliability Engineer; Test Architect.
- **Mission:** Define the shared Universal MOBA Companion core and game-specific adapter contract while preserving MLBB behavior and incorporating migrated Issue #1 requirements.
- **Standards and evidence:** Map current code and game-specific assumptions before refactoring; document architecture decisions and data ownership; use versioned contracts, capability declarations, explicit unsupported states, and contract/regression tests; no speculative large rewrite.
- **Deliverables:** Current-state architecture map, target architecture, normalized game-state schema, adapter contract, MLBB adapter plan, migration sequence, ADRs, and contract-test strategy.

**Phase 3 — UI/UX Audit and Product Design Blueprint**
- **Primary role:** Principal Product Designer and UX Research/Information-Architecture Specialist.
- **Expertise:** Android product UX, interaction design, information architecture, user journeys, accessibility, design systems, data visualization, usability evaluation, and product identity.
- **Supporting roles:** Android Design Systems Architect; Accessibility Specialist; Visual Brand Designer; Product Strategist.
- **Mission:** Audit the complete application and produce a coherent, implementation-ready experience blueprint aligned with the Universal MOBA Companion vision.
- **Standards and evidence:** Base flows on actual capabilities and user tasks; identify all loading, empty, error, permission, recovery, and offline states; distinguish current from planned/experimental features; do not treat illustrative mockups as approved designs.
- **Deliverables:** UX audit, personas/use cases where evidence supports them, information architecture, navigation map, screen specifications, interaction/state matrix, design tokens, accessibility requirements, and prioritized design backlog.

**Phase 4 — UI/UX Implementation and Experience Modernization**
- **Primary role:** Senior Android UI Engineer and Design Systems Implementation Specialist.
- **Expertise:** Kotlin UI implementation, Android layouts and lifecycle, reusable components, theming, responsive/adaptive layouts, animation performance, accessibility semantics, state management, and UI testing.
- **Supporting roles:** Product/UI Designer; Android Architecture Engineer; Accessibility QA; Visual Regression/Test Engineer.
- **Mission:** Implement the approved Phase 3 design systematically across the application without changing unrelated business logic or architecture.
- **Standards and evidence:** Reuse design tokens/components; preserve navigation and state correctness; cover all defined UI states; test different screen sizes, contrast, touch targets, and performance; obtain owner review for major visual direction.
- **Deliverables:** Reusable design system, implemented screens and states, UI tests, screenshots or visual evidence, accessibility findings, and documented deviations.

**Phase 5 — Android Reliability and Application Foundation**
- **Primary role:** Principal Android Platform, Reliability, and Performance Engineer.
- **Expertise:** Android lifecycle, foreground services, permissions, storage, process death, background restrictions, device/API/ABI compatibility, memory/thermal behavior, diagnostics, crash recovery, and privacy/security.
- **Supporting roles:** Android Systems Engineer; Mobile Security Engineer; Performance Engineer; QA/Device-Lab Engineer.
- **Mission:** Establish dependable Android foundations and device-capability awareness for companion, inference, perception, and offline workflows.
- **Standards and evidence:** Validate lifecycle transitions and failure/recovery paths; maintain device/API/ABI matrix; measure resource behavior on representative devices; minimize permissions and data collection; never equate successful compilation with device reliability.
- **Deliverables:** Reliability audit, compatibility matrix, lifecycle/permission design, diagnostics and recovery implementation plan, resource baselines, security/privacy findings, and automated/device tests.

**Phase 6 — Model-Agnostic Unified Inference Architecture**
- **Primary role:** Principal On-Device AI Inference Architect.
- **Expertise:** Inference abstraction, runtime/backend interfaces, model metadata and capability discovery, streaming, cancellation, concurrency, model lifecycle, resource arbitration, role assignment, and observability.
- **Supporting roles:** Android Architecture Engineer; ML Runtime Engineer; AI Systems Architect; Test Architect.
- **Mission:** Establish a stable common inference contract that supports independently selected Strategic and Tactical models and multiple coexisting runtimes.
- **Standards and evidence:** Keep model identity separate from functional role and runtime; define capability negotiation and explicit unsupported behavior; preserve LiteRT-LM and GGUF as coexisting backends; specify lifecycle, error, cancellation, and resource contracts before backend expansion.
- **Deliverables:** Inference architecture, interfaces/contracts, model/runtime capability schema, lifecycle and concurrency design, error taxonomy, test doubles, contract tests, and migration plan.

**Phase 7 — LiteRT-LM Backend Integration and Stabilization**
- **Primary role:** Senior Android On-Device ML and LiteRT-LM Integration Engineer.
- **Expertise:** LiteRT-LM APIs, supported model formats, Android runtime integration, tokenizer/model assets, delegate/device support, memory management, inference lifecycle, and runtime diagnostics.
- **Supporting roles:** Android Native/Platform Engineer; ML Model Deployment Specialist; Performance Engineer; QA Engineer.
- **Mission:** Audit existing work and integrate or stabilize LiteRT-LM as a backend behind the common inference interface.
- **Standards and evidence:** Verify actual API/runtime/model compatibility against authoritative documentation and device tests; keep backend-specific code isolated; test load, inference, cancellation, unload, errors, and offline behavior; do not assume a model format or accelerator is supported without evidence.
- **Deliverables:** Compatibility report, backend implementation/adaptation, supported-model matrix, integration and regression tests, device measurements, and known limitations.

**Phase 8 — GGUF / llama.cpp Backend Integration**
- **Primary role:** Principal Native AI Runtime Engineer specializing in llama.cpp and Android NDK.
- **Expertise:** C/C++, llama.cpp, GGUF metadata and quantization, JNI, Android NDK/ABI, CMake, native memory/resource management, CPU/GPU/Vulkan paths, threading, and mobile inference.
- **Supporting roles:** Android Native Systems Engineer; Model Quantization Specialist; Mobile Hardware Performance Engineer; Native QA/Security Engineer.
- **Mission:** Deliver a separately maintainable GGUF/llama.cpp backend integrated through the shared inference contract.
- **Standards and evidence:** Verify NDK/toolchain/ABI compatibility; isolate native dependencies; handle JNI ownership, cancellation, and cleanup safely; benchmark supported acceleration paths rather than assuming them; validate thermal, memory, and stability behavior on devices.
- **Deliverables:** Native integration architecture, build configuration, JNI boundary, backend implementation, supported ABI/device matrix, native tests, reproducible benchmarks, and troubleshooting documentation.

**Phase 9 — Model Lab and Model Management**
- **Primary role:** AI Developer Tools and Model Lifecycle Product Engineer.
- **Expertise:** Model registries, artifact metadata, model acquisition/import, validation, storage, configuration profiles, runtime compatibility, role assignment, lifecycle UX, and offline asset management.
- **Supporting roles:** AI Inference Architect; Android Storage Engineer; Product Designer; Security/Privacy Engineer.
- **Mission:** Deliver a trustworthy Model Lab for discovering, importing, validating, configuring, assigning, loading, and managing local models.
- **Standards and evidence:** Validate file integrity and compatibility before loading; show provenance, format, quantization, requirements, and limitations; protect user-selected models/settings; make offline and storage behavior explicit; avoid unsafe or opaque downloads.
- **Deliverables:** Model metadata schema/registry, import and validation workflow, model management UI, configuration profiles, lifecycle integration, tests, and user documentation.

**Phase 10 — Model Benchmarking and Evaluation**
- **Primary role:** AI Evaluation Scientist and Mobile Performance Benchmarking Engineer.
- **Expertise:** Experimental design, task-specific evaluation, latency/throughput, time-to-first-token, memory, CPU/GPU utilization, thermal throttling, battery impact, reliability, statistical reporting, and reproducibility.
- **Supporting roles:** ML Evaluation Researcher; Android Performance Engineer; Data Analyst/Statistician; QA Engineer.
- **Mission:** Establish repeatable, fair evaluations for Strategic and Tactical model/runtime configurations across representative devices.
- **Standards and evidence:** Use versioned datasets/fixtures and controlled conditions; separate quality from speed; record model, quantization, backend, device, OS, settings, warm/cold state, sample count, and variance; do not rank configurations from incomparable runs.
- **Deliverables:** Benchmark protocol, task/evaluation sets, harness, raw results, analysis and confidence/variance reporting, device comparisons, regression thresholds, and reproducibility guide.

**Phase 11 — Model-Pairing and Device-Aware Recommendations**
- **Primary role:** AI Systems Optimization Scientist and Model Selection Specialist.
- **Expertise:** Multi-objective optimization, model compatibility, Strategic/Tactical workload characteristics, quantization trade-offs, device profiling, Pareto analysis, recommendation confidence, and constraint-based selection.
- **Supporting roles:** Benchmarking Scientist; On-Device ML Engineer; Android Device-Capability Engineer; Product/UX Designer.
- **Mission:** Recommend suitable model pairs and runtime configurations based on measured device capability, workload, quality, latency, memory, thermal, and battery trade-offs.
- **Standards and evidence:** Base recommendations on Phase 10 comparable measurements and declared compatibility; expose trade-offs and confidence; retain user control; never imply one universal best pair or silently change assignments.
- **Deliverables:** Pairing methodology, device/workload profiles, recommendation engine/specification, explainable recommendation UI, evaluation tests, and evidence-backed configuration catalog.

**Phase 12 — Strategic/Tactical Intelligence and Coordination**
- **Primary role:** Principal Multi-Agent Systems and Game-Decision Orchestration Architect.
- **Expertise:** Agent coordination, hierarchical decision systems, shared state/context, task routing, arbitration, confidence/calibration, temporal reasoning, deterministic safeguards, structured action proposals, and staged autonomy.
- **Supporting roles:** Game AI Researcher; Tactical Decision Systems Engineer; Safety/Policy Engineer; Inference Architect; Evaluation Scientist.
- **Mission:** Coordinate Strategic and Tactical intelligence with shared context, explicit responsibilities, reliable handoffs, and measurable readiness gates for any execution.
- **Standards and evidence:** Separate strategic planning from immediate tactical decisions; prevent conflicting outputs; use structured schemas, confidence/uncertainty, deterministic fallbacks, bounded action policies, cancellation and emergency stop; progress through observation, offline evaluation, shadow mode, supervised execution, and bounded autonomy only after gates pass.
- **Deliverables:** Coordination architecture, role/task contracts, context and arbitration design, confidence policy, safety/action pipeline, staged-autonomy criteria, simulation/evaluation suite, and audit/reporting format.

**Phase 13 — MLBB Companion and Game-Specific Intelligence**
- **Primary role:** Senior MOBA Game Systems Analyst and MLBB Domain Intelligence Engineer.
- **Expertise:** MLBB game mechanics and patch/versioning, heroes/roles/lanes, emblem data, items, drafts, team composition, rotations, objectives, esports match data, source provenance, and Android feature integration.
- **Supporting roles:** MOBA Strategy Analyst; Esports Data Researcher; Data Pipeline Engineer; Android Feature Engineer; UX Designer; QA/Evidence Analyst.
- **Mission:** Implement and validate MLBB-specific companion intelligence, including the migrated Issue #37 scope, strictly within the game-specific layer.
- **Standards and evidence:** Respect the source restrictions in Section 5 (including Wiki-only emblem recommendations and official-source meta statistics); distinguish recommendations from experimental hypotheses; retain patch, source, timestamp, sample size, confidence, and limitations; never fabricate missing data or infer details unsupported by match sources.
- **Deliverables:** MLBB adapter/domain data, emblem Library assets and mappings, meta ingestion/status, recommended and experimental composition/rotation systems, Pro Match Hub, evidence pipeline, tests, and source/update documentation.

**Phase 14 — Universal MOBA Expansion and Additional Game Adapters**
- **Primary role:** Principal Modular Platform Architect and Game Integration Engineer.
- **Expertise:** Adapter SDK/contracts, game-domain modeling, capability negotiation, game-specific data pipelines, version compatibility, integration isolation, conformance testing, and incremental onboarding.
- **Supporting roles:** Game Domain Analyst for each target title; Android Integration Engineer; Data/API Engineer; Contract-Test Architect.
- **Mission:** Prove the shared architecture with additional MOBA adapters without contaminating the universal core with title-specific assumptions.
- **Standards and evidence:** Select target games through owner-approved scope; document source/permission feasibility; implement adapters against versioned contracts; test unsupported features and isolation; do not claim a game is supported until conformance and user-facing workflows are validated.
- **Deliverables:** Adapter onboarding specification, target-game feasibility reports, adapter implementations, capability manifests, conformance/regression suites, and per-game support matrix.

**Phase 15 — Advanced Game Intelligence**
- **Primary role:** Principal Game AI and Multimodal Computer Vision Research Engineer.
- **Expertise:** Visual perception, object/hero/minimap recognition, tracking, spatial and temporal reasoning, multimodal state estimation, prediction, adaptive decision-making, coaching, uncertainty, and evidence-driven learning.
- **Supporting roles:** Computer Vision Specialist; MOBA Strategy Researcher; Temporal/Contextual AI Engineer; Reinforcement/Decision Systems Researcher; Mobile ML Optimization Engineer; Evaluation Scientist.
- **Mission:** Implement the five owner-approved gaming-AI technology work packages across their assigned phases, advancing perception and game intelligence while keeping experimental outputs clearly separated from validated production capabilities.
- **Standards and evidence:** Define measurable datasets and baselines; evaluate precision/recall, tracking, calibration, temporal consistency, decision quality, and device cost as relevant; retain provenance and failure cases; test patch/game transfer; keep experimental features gated and do not overstate Razer or other external technology findings.
- **Deliverables:** Implemented perception and intelligence capabilities, authorized datasets/fixtures, integrated prototypes and production components as readiness permits, evaluation reports, source/provenance records, risk analysis, and explicit validation gates.

**Phase 16 — Optimization, Validation, and Production Readiness**
- **Primary role:** Principal Android Performance, Security, Reliability, and Release Engineer.
- **Expertise:** Release engineering, Android compatibility, performance/thermal/battery optimization, security/privacy review, accessibility, regression strategy, crash/telemetry diagnostics, packaging, signing, and operational readiness.
- **Supporting roles:** Android QA Lead; Mobile Security Auditor; Performance Engineer; Accessibility Specialist; Release/Documentation Manager.
- **Mission:** Demonstrate that the complete product meets release-quality requirements across supported devices, game adapters, inference backends, and user workflows.
- **Standards and evidence:** Use a release checklist and reproducible CI/device evidence; verify permissions, data flows, offline behavior, recovery, compatibility, and performance budgets; resolve or explicitly gate known defects; require owner approval for release and rollout.
- **Deliverables:** Release candidate, compatibility and test reports, security/privacy review, performance results, known-issues register, user/developer documentation, rollback/recovery plan, and release recommendation.

#### Reusable Sphynx Engineering Specialist Framework

These specialist disciplines form a reusable project-wide framework. They are activated as needed by each phase; they do not create new roadmap phases or organizational dependencies.

1. **Architecture and Platform Engineering:** Principal Software Architect, Android Platform Architect, Modular Systems Architect, API/Contract Architect.
2. **Android Product Engineering:** Android UI Engineer, Android Systems Engineer, Storage/Lifecycle Engineer, Accessibility Engineer.
3. **AI/ML and Inference Engineering:** AI Inference Architect, LiteRT-LM Engineer, llama.cpp/Native Runtime Engineer, Model Lifecycle Engineer, Quantization Specialist.
4. **Computer Vision and Perception:** Computer Vision Engineer, Visual Recognition Specialist, Tracking/State Estimation Engineer, Multimodal Systems Researcher.
5. **Game Intelligence and MOBA Domain:** MOBA Systems Analyst, Game AI Researcher, Strategic Analyst, Tactical Decision Engineer, Esports Data Researcher.
6. **Agent Coordination and Safety:** Multi-Agent Architect, Orchestration Engineer, Policy/Safety Engineer, Autonomy Validation Specialist.
7. **Quality, Evaluation, and Reliability:** QA/Test Architect, AI Evaluation Scientist, Performance/Benchmark Engineer, Security/Privacy Auditor, Device Compatibility Engineer.
8. **Product, UX, and Release:** Product Strategist, UX/Information Architect, Visual Brand Designer, Design Systems Specialist, Release Engineer, Technical Writer.

#### Specialist collaboration and decision rules

- The phase's primary specialist is accountable for coordinating supporting perspectives and producing one coherent recommendation.
- Supporting roles are consulted when their discipline materially affects the work; do not simulate unnecessary committees for simple tasks.
- Resolve disagreements using repository evidence, reproducible experiments, authoritative technical documentation, project constraints, and measurable acceptance criteria—not confidence or persona claims.
- Record material trade-offs and deviations in the implementation report and, when appropriate, an Architecture Decision Record.
- The human owner retains authority over scope, architecture changes, priority, branding/product direction, autonomy expansion, and releases.
- Claude Code remains the primary developer; Gemini CLI remains the backup/independent reviewer; ChatGPT remains project coordinator. Role assignment does not change these governance responsibilities.

#### Execution gate

At the beginning of every phase, the agent must: (1) state the assigned primary role and supporting disciplines; (2) read the phase scope, dependencies, and acceptance criteria; (3) verify the current branch/commit/worktree and preserve existing changes; (4) identify unknowns, risks, and required owner decisions; and (5) submit an execution plan before consequential implementation. At phase completion, report evidence against every acceptance criterion and identify unfinished work. A specialist profile does not itself mark a phase approved for implementation or complete.

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

**Original tracking record:** Issue #1 — Establish the Universal MOBA Companion architecture (in the original repository, before the move to sphynx-ai/SPHYNX)

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

- [x] Current MLBB functionality and architecture are mapped before refactoring. (2026-11-14, `docs/PHASE_2_ARCHITECTURE_MAP.md`, read-only, no code changed)
- [x] Shared MOBA concepts are separated from MLBB-specific assumptions. (2026-11-16, v37.64–v37.73: `GameKnowledge`, `TacticalRules`, `FrameReader`, `StrategyGame`, with MLBB's halves in `adapters/mlbb/` and `vision/MlbbScreenReading`; enforced by `ArchitectureRulesTest`; CI-verified. Known remaining coupling, by design until Phase 14: the MLBB-shaped `game/GameState` inputs and `learning` — `docs/ADAPTER_GUIDE.md` section 2)
- [x] A common game-state contract is documented and versioned. (2026-11-14, `docs/contracts/UNIVERSAL_MATCH_STATE.md`, schema version 1, `StateContract` + `StateContractTest` + `MlbbAdapterContractTest`; CI-verified, no behaviour change)
- [x] An MLBB adapter is defined and validated without regressing current behavior. (2026-11-16: `MlbbAdapter` + its rules, readers and questions; `TacticalEngineGoldenTest` (25 situations) unchanged through the split; `MlbbAdapterContractTest` incl. the adapter test kit; CI-verified. Owner phone test of v37.64–v37.76 outstanding)
- [x] Adapter capability and unsupported-state behavior are explicit. (2026-11-16, `docs/ADAPTER_GUIDE.md` section 3: `CapabilitySet`, `DecisionValidator` advisory-only, `null` for unread values via `StateContract`; versioning section 5 with `GameAdapter.adapterVersion`)
- [x] A repeatable test strategy exists for future adapters. (2026-11-16, v37.75: `testing/AdapterContractKit` + `AdapterContractKitTest`, run on MLBB; guide section 6; CI-verified)
- [x] Experimental model outputs cannot bypass deterministic fallbacks or execution policy. (2026-11-16: `ai/DecisionGuard` (v37.74, `DecisionGuardTest`) after the learned blends, then `FountainGuard` and `ActionExecutor`'s safety switches; language-model advice through `DecisionValidator` / `DecisionArbiter`; CI-verified)
- [x] Architecture changes are incremental, reviewed, and documented. (2026-11-14 to 2026-11-16: one PR per step, #10–#19 and #49–#66, ADR 0001 accepted by the owner, `docs/PHASE_2_*.md`, `docs/ADAPTER_GUIDE.md`)

**Scope clarification:** Issue #1 named Gemma 4 E2B, Gemma 3 270M, and Kotlin rules as current implementation boundaries. These are historical implementation details, not permanent model restrictions. The current project direction is model-agnostic. Deterministic Kotlin rules remain valuable safeguards where appropriate.

## 5. Phase 13 work package — MLBB Intelligence and Professional Match Hub (migrated from Issue #37)

**Original tracking record:** Issue #37 — Add MLBB Emblem Sets, Data-Driven Meta Updates, and Pro Match Hub (in the original repository, before the move to sphynx-ai/SPHYNX)

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

### 13.12 Combat perception, minimap/gameplay synchronization, and reliable target engagement

**Historical source record:** Issue #2 — Improve minimap and gameplay-screen synchronization for reliable combat targeting (in the original repository, before the move to sphynx-ai/SPHYNX)

**Purpose:** Resolve target-visibility and combat-range failures caused by relying too heavily on minimap markers. Build a synchronized, confidence-aware target representation from minimap and gameplay-screen evidence, then use it consistently for targeting, movement, skill aiming, and engage/continue/disengage decisions.

**Phase placement and ownership:**
- **Phase 5 — Android foundation:** provide reliable, permission-compliant gameplay-screen capture/observation, lifecycle handling, supported-device compatibility, and resource-aware operation. Do not assume capture/overlay APIs are already available or universally supported.
- **Phase 12 — Universal intelligence:** define game-agnostic target identity, observation fusion, temporal tracking, confidence/uncertainty, combat-state inputs, and decision contracts. Keep perception outputs separate from action authorization.
- **Phase 13 — MLBB adapter:** implement MLBB-specific minimap marker interpretation, hero/creep/monster recognition, monster aggro and hero attack-range semantics, skill-targeting integration, and combat validation.
- **Phase 15 — Advanced intelligence:** advance visual recognition, health-bar/HP-signal analysis, screen-space tracking, occlusion handling, spatial estimation, temporal association, and measured perception quality. Reusable components remain game-agnostic; MLBB mechanics remain in the MLBB adapter.

#### Submilestones

1. Audit the existing minimap workaround and trace how minimap detections currently influence target identity, movement, attack range, and skill aiming. Preserve working behavior and identify the source of the movement regression before changing it.
2. Detect relevant targets directly from the gameplay screen where feasible, including enemy heroes, creeps, and jungle monsters. Evaluate health bars and HP changes as corroborating signals; do not treat them as reliable identifiers unless validated.
3. Fuse minimap and gameplay-screen observations into a consistent target representation with stable identity, position/coordinate space, timestamp, source observations, confidence, and explicit unknown/occluded/stale states.
4. Track targets across frames and tolerate partial/full temporary occlusion. Avoid abrupt target switching, phantom targets, and treating the player's green ring as an enemy/creep marker.
5. Maintain target association when minimap markers overlap or are obscured by the player's green ring by using independent gameplay-screen evidence and temporal continuity where reliable.
6. Estimate relevant combat distances/ranges from calibrated screen/game-state evidence. Respect monster aggro boundaries and the hero's effective attack range; account for uncertainty and do not issue movement based on an untrusted range estimate.
7. Integrate the fused target state into skill aiming and engage, continue-fighting, reposition, and disengage decisions. Preserve deterministic safety fallbacks when perception confidence is low.
8. Add replayable fixtures and tests for marker overlap, occlusion, identity continuity, health-bar/HP signals, range boundaries, and target switching. Validate with approved gameplay scenarios on representative devices.

#### Acceptance criteria

- [ ] Enemy heroes and creeps remain trackable when their minimap markers overlapped by the player's green ring.
- [ ] Gameplay-screen evidence can contribute to enemy hero, creep, and monster detection; health bars/HP changes are used only where reliability is demonstrated.
- [ ] Minimap and gameplay-screen observations are synchronized into a consistent, timestamped target representation with confidence and uncertainty.
- [ ] Target identity persists through temporary marker overlap or occlusion without phantom enemies or abrupt unsupported target switching.
- [ ] The AI does not move outside the relevant monster aggro range or its own hero attack range because of the minimap workaround.
- [ ] Skill targeting remains stable when minimap markers or identifiers are obscured.
- [ ] Engage, continue-fighting, reposition, and disengage decisions use the best available target and range information.
- [ ] Low-confidence, stale, or conflicting observations trigger safe fallback behavior rather than unsupported movement or attacks.
- [ ] Tests cover marker overlap, temporary occlusion, target tracking/identity, health-bar signal handling, and combat-range boundaries.
- [ ] Gameplay validation confirms that the previous workaround's movement regression is resolved on representative supported devices.

**Implementation and safety constraints:** Start with a read-only audit of the current perception and combat architecture. Do not simply extend the existing minimap workaround without diagnosing its movement side effects. Keep reusable target-observation and tracking contracts in the universal core; keep MLBB marker semantics, monster aggro rules, and hero attack-range mechanics in the MLBB adapter. Do not infer precise world coordinates or ranges from pixels without calibration and validation. When observations disagree or confidence is insufficient, preserve the last defensible target state only within a bounded freshness window, then fall back safely. Any future autonomous input remains subject to Phase 12's policy/permission validation and staged-autonomy gates.

## 6. Five approved gaming-AI technology work packages

**Status: OWNER-APPROVED / PLANNED FOR IMPLEMENTATION — all five work packages.**

The owner has authorized implementation of all five work packages. They are committed roadmap scope, not proposed candidates awaiting another approval. They are distributed across the existing phases as submilestones; they do not create new phases, change phase order, or authorize skipping prerequisite architecture, reliability, or validation work.

**Execution rule:** Implement each package when its assigned phase is reached and its dependencies are satisfied. Phase 1 must first establish the current repository baseline. The responsible agent must then inspect existing code and avoid duplicating working capabilities. Each package may be delivered incrementally across its mapped phases. A package is not complete merely because research, a prototype, or one phase's portion is complete; completion requires its acceptance criteria and evidence across the applicable placements. Experimental algorithms may remain explicitly experimental, but the work to build, evaluate, and integrate them is approved.

| Work package | Approved implementation placement | Required implementation scope |
|---|---|---|
| **WP-01 — Visual Intelligence and Computer Vision** | Phases 5, 12, 13, 15 | Build the Android-ready perception pipeline; screen understanding; hero, creep, objective, UI and minimap recognition; marker/object tracking; spatial mapping; temporal association; confidence and uncertainty; calibration and perception validation. Integrate MLBB-specific recognition through its adapter while keeping reusable perception contracts game-agnostic. |
| **WP-02 — Temporal and Contextual Game Intelligence** | Phases 12, 13, 15 | Implement event sequencing, match-phase and progression tracking, temporal state/history, context windows, changing game conditions, state transitions, event correlation, and context-aware interpretation. Preserve timestamps, provenance, confidence, and stale/unknown states. |
| **WP-03 — Advanced Strategic and Tactical Intelligence** | Phases 10, 11, 12, 15 | Implement measurable macro-strategy and tactical decision evaluation; objective prioritization; rotations and draft/context reasoning; prediction; Strategic/Tactical coordination; structured action proposals; and staged autonomy readiness. Autonomous execution remains gated by safety, permissions, game rules, measured performance, and explicit release criteria. |
| **WP-04 — Gaming AI Companion and Coaching Technologies (including Razer research)** | Phases 3, 12, 13, 15 | Turn the approved gaming-AI/Razer technology assessment into product requirements and implement relevant companion/coaching capabilities: contextual assistance, match awareness, coaching feedback, overlays or equivalent Android-compatible presentation, and useful workflow integrations. Verify current capabilities, technical feasibility, platform compatibility, licensing, privacy, and game-policy constraints. Do not assume access to proprietary Razer systems or APIs; implement independently where appropriate and feasible. |
| **WP-05 — Adaptive and Predictive Game Intelligence** | Phases 10, 12, 13, 15 | Implement context-aware recommendations and prediction; feedback capture; adaptation across match phases, compositions, matchups, and patches; confidence and uncertainty; evaluation against baselines; and evidence-based learning. Keep user data and learned state offline-first by default, with transparent controls and no unsupported claims of model learning. |

### Required cross-package implementation outputs

- Architecture and data-flow design showing universal-core versus game-adapter responsibilities.
- Concrete implementation tasks and dependencies under the assigned phases.
- Versioned interfaces, data contracts, provenance, confidence, and failure/unknown-state handling.
- Working code integrated with the app's existing architecture, not research documents alone.
- Unit, contract, integration, regression, and device tests appropriate to each capability.
- Benchmarks and measured quality/resource costs where relevant, including latency, memory, CPU/GPU, thermal behavior, and battery impact.
- Offline-first behavior, privacy/security review, source/licensing review, and compatibility documentation.
- Phase-by-phase progress reports identifying implemented, experimental, blocked, and verified portions.

### Acceptance criteria

- [ ] All five work packages have implementation tasks mapped to their approved phase placements.
- [ ] Existing functionality is audited before replacement; working capabilities are reused and extended.
- [ ] Reusable perception, temporal, intelligence, and adaptation contracts remain game-agnostic; MLBB-specific logic remains in the MLBB adapter.
- [ ] Visual recognition and tracking are evaluated with documented datasets/fixtures, precision/recall, tracking quality, confidence calibration, and failure cases.
- [ ] Temporal/contextual state is tested for ordering, missing events, stale data, interruptions, and match-state transitions.
- [ ] Strategic and Tactical outputs are evaluated against documented baselines and representative scenarios; action execution remains behind the autonomy safety gate.
- [ ] Companion/coaching capabilities derived from Razer or other technology research are supported by current source evidence and implemented only where technically and legally feasible.
- [ ] Adaptive/predictive recommendations are evaluated across relevant patches, roles, compositions, and match contexts, with sample sizes and confidence disclosed.
- [ ] Each capability exposes appropriate confidence, provenance, freshness, limitations, and fallback behavior.
- [ ] Offline operation and Android device resource constraints are validated on representative devices.
- [ ] Tests, measurements, documentation, and integration evidence are recorded before any capability is marked VERIFIED or complete.

**Status semantics:** These five packages are approved for implementation now, but their implementation status remains **APPROVED / PLANNED** until work actually begins. When work begins, update the relevant submilestone to **IN PROGRESS**; mark individual deliverables **VERIFIED** only with reproducible evidence. Experimental components may retain an experimental runtime label without reverting the approved work package itself to PROPOSED.

## 7. Cross-phase UI/UX requirements

UI/UX is a core workstream and product-quality requirement throughout the roadmap:
- Conduct a full application-wide UI/UX and information-architecture audit.
- Establish original Sphynx visual identity and reusable design system: color, typography, spacing, iconography, components, navigation, data visualization, and interaction patterns.
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

**Current phase: Phase 6 — Model-Agnostic Unified Inference Architecture (started 2026-11-22)** — **all steps S1–S8 built in code (v37.94–v38.04); the owner's phone test of the whole phase is pending** (`docs/PHASE_6_INFERENCE_PLAN.md` Section 8), by owner approval of `docs/PHASE_6_INFERENCE_PLAN.md` (S1–S7, plus S8: GGUF vision through an `mmproj` file, Phase 8 scope). **Phase 5 is COMPLETE (owner decision 2026-11-22):** v37.79–v37.92 were phone-tested. The compatibility matrix holds the owner's phone only, and one of the four resource baselines is VERIFIED from an uploaded snapshot (both models loaded: the app uses 2.7 GB, 3.2 GB of 11.1 GB free); the other three are still to measure. Evidence is in `docs/PHASE_5_RELIABILITY_AUDIT.md`. Earlier record: Phase 5 started 2026-11-16, by owner decision ("finish the phase 2 and proceed to next phase"); work resumed 2026-11-21 ("proceed to Phase 5"). Status **IN PROGRESS**: the reliability audit, the start of the compatibility matrix and the ordered plan (P1–P10) are in `docs/PHASE_5_RELIABILITY_AUDIT.md` (v37.79). v37.80–v37.86 (exit history, device profile, memory and heat log, battery check, permissions and data record, lifecycle checklist, start-up time) were phone-tested by the owner on 2026-11-21; the owner's decisions on P5, the backup of keys and emulator CI are in that file's section 7. Phase 5 is nearly done: two criteria remain open, the resource baselines (the snapshot tool is in v37.91) and a compatibility matrix that holds one phone so far. **Phase 6 plan PROPOSED (v37.92):** `docs/PHASE_6_INFERENCE_PLAN.md`, waiting for owner approval. **Phase 2 is COMPLETE (2026-11-16 in code; phone-tested by the owner 2026-11-21, v37.64–v37.78: "All test works fine"):** all its acceptance criteria (Section 4) are met with CI evidence. Evidence and the closing record are in `docs/PHASE_2_STATUS.md`; ADR 0001 was accepted by the owner on 2026-11-16. **Phase 4 is COMPLETE (2026-11-16):** all twelve backlog items of `docs/PHASE_3_DESIGN_BACKLOG.md` (v37.38–v37.58) are built and phone-tested. **Phase 3 is COMPLETE (2026-11-15):** the owner approved the design for all five sections; the audit, information architecture, design tokens, state matrix, screen specs with mockups, and the use cases and prioritized backlog are in `docs/PHASE_3_*.md`. Phase 4 builds it in the order of `docs/PHASE_3_DESIGN_BACKLOG.md`; the owner decided (2026-11-15) that the redesign (the new look) comes first, then the new structure. Phase 1 is COMPLETE. Phase 2 submilestone 1 (current-state architecture map) is done: `docs/PHASE_2_ARCHITECTURE_MAP.md`. Submilestone 2 (target layered architecture): `docs/adr/0001-layered-architecture.md`, accepted by the owner 2026-11-16; its migration (B1–B5) is done and `ArchitectureRulesTest` (baseline 0) blocks any forbidden dependency. Submilestones 4–7 (separating MLBB into its adapter) started 2026-11-16: plan and progress in `docs/PHASE_2_ADAPTER_SPLIT.md`. Submilestone 3 (game-state contract, schema version 1) is done: `docs/contracts/UNIVERSAL_MATCH_STATE.md`, checked by `StateContract`, `StateContractTest` and `MlbbAdapterContractTest`. Evidence, the risk register and the phase mapping are in `docs/PHASE_1_BASELINE.md`. Phase 8 also runs, by owner decision (Section 9.1). Do not infer the active phase from this roadmap alone.

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

### 9.1 Phase 8 evidence (2026-11-14)

**Owner decision (2026-11-14):** start Phase 8 now, after the GGUF feasibility test passed on the phone, before Phases 6–7 are complete. The backend uses the Phase 6 contract code that already exists (`inference/InferenceBackend` and its request, event, status and error types); it is the first runtime built on it.

**Device evidence — VERIFIED (owner's phone, `test/gguf-spike` at `dc89e3c`, CPU only, 4 threads, arm64-v8a with NEON, ARM_FMA, FP16_VA, DOTPROD).** Same question for both runs; source: the app's diagnostics logs uploaded to `recordings-for-debugging` (commits `c1aa582`, `7365593`).

| Model | File | Load | Prompt | Answer | Note |
|---|---|---|---|---|---|
| Qwen2.5 0.5B Instruct Q4_K_M | 462 MB | 0.7 s | 61.2 tok/s | **25.5 tok/s** | Read from a copy: reading the picked file in place failed (the app had no All files access at that moment). Answer repeated itself. |
| Llama 3.2 1B Instruct Q4_K_M | 762 MB | 2.5 s | 74.1 tok/s | **13.4 tok/s** | Read in place; ran while Gemma 4 E2B was loaded on the GPU. Relevant answer. |

**Not yet verified:** heat, battery and stability over a whole match; models above 1B; phones other than the owner's; GPU / Vulkan acceleration (not built); a computer-side run of the native engine with a real model.

**Implementation (IN PROGRESS):** `LlamaCppBackend` (kept-loaded model, streaming, cancel, timeout, CPU check before the library loads), GGUF models added by copying into the app, a test question on the Model page. Not yet assigned to the Strategic or Tactical role (Phases 9, 11 and 12).

## 10. GitHub Issues policy and migration record

The GitHub Issues section is intended primarily for actionable defects and corrective engineering:
- Bugs, regressions, and broken functionality.
- Stability, performance, security, and compatibility defects.
- Concrete implementation blockers requiring investigation or repair.

Product feature requests, architecture initiatives, research projects, and planned integrations belong in this Master Development Plan. If a future feature request is temporarily tracked as an issue, migrate its full scope here before closing it. Add a closure comment explicitly stating that the tracking request was migrated and implementation is **not** thereby complete.

### Migrated source records

- Issue #1 — Universal MOBA Companion architecture (in the original repository, before the move to sphynx-ai/SPHYNX) → Phase 2, Section 4.
- Issue #37 — MLBB Emblem Sets, Data-Driven Meta Updates, and Pro Match Hub (in the original repository, before the move to sphynx-ai/SPHYNX) → Phase 13, Section 5.

The original GitHub records are historical traceability references. Their closure means requirements were transferred into this roadmap, **not** that architecture or MLBB features have been implemented.

## 11. Change control and roadmap governance

- Preserve the 16-phase order unless the owner explicitly approves a change.
- Add capabilities as submilestones under the best-fitting phase; avoid duplicate phases.
- Every submilestone needs scope, dependencies, owner approval where required, acceptance criteria, and evidence.
- Keep exploratory research findings distinct from approved implementation requirements; use evidence gates for technical promotion, not as a reason to defer the five owner-approved gaming-AI work packages.
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