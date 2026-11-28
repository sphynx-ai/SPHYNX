# Phase 14 — Universal MOBA Expansion: adapter SDK (owner scope: SDK only)

**Status:** **APPROVED by the owner 2026-11-28**, together with Phases 11–16. **Owner decision on scope: "None yet: SDK only".** This phase builds the adapter SDK, the onboarding specification and the conformance suite, with a demo adapter that exists only in the tests. **No second game is added, and no game other than MLBB is claimed as supported.** IN PROGRESS.
**Based on:** `sphynx-test-branch` at v38.72 (built), read from the code. No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** Principal Modular Platform Architect and Game Integration Engineer.
- **Supporting roles:** Android Integration Engineer; Data/API Engineer; Contract-Test Architect. The per-game Game Domain Analysts come in when the owner picks a game.
- **Mission:** prove that the shared architecture can take another MOBA without MLBB assumptions leaking into the universal core.

## 2. What exists (verified by reading the code)

| Part | State |
|---|---|
| Adapter contract | `universal/GameAdapter<S>`: game id, name, capabilities, adapter version, knowledge, `toUniversal` |
| Game knowledge | `universal/GameKnowledge`: characters, matching, roles, range, lanes, spells, items, objectives |
| State contract | `universal/StateContract` checks a universal state; `docs/contracts/UNIVERSAL_MATCH_STATE.md` |
| Capabilities | `universal/Capabilities`: a status per capability; `DecisionValidator` keeps an unusable capability's advice advisory |
| Isolation | `ArchitectureRulesTest`: the layer rules, and no MLBB names in the tactical engine |
| MLBB | `adapters/mlbb/MlbbAdapter`, `MlbbAdapterContractTest` |
| Guide | `docs/ADAPTER_GUIDE.md` |
| Gaps | No machine-readable capability manifest with evidence. No conformance suite that any adapter can run (MLBB's contract test is MLBB-specific). No proof that the core runs with a second adapter. No onboarding specification or feasibility template. No per-game support matrix |

## 3. Plan (one PR each, version +1 each)

| Step | Work |
|---|---|
| K1 | **Capability manifest:** `universal/sdk/CapabilityManifest` (pure, JSON, tested). It holds the game, adapter version, contract version, each capability's status **with its evidence** (a unit test, a phone check, or none), declared unsupported features, and source/permission notes. MLBB's manifest is `adapters/mlbb/MlbbManifest`. The existing conformance kit (`testing/AdapterContractKit`, Phase 2) gains a manifest check: the manifest must match the adapter, and no capability may be claimed usable without evidence. A support-matrix line per game |
| K2 | **A second adapter in the tests only:** a small fictional MOBA (`testing/SdkDemoAdapter`, test sources only, never in the app). It passes the kit and the manifest check, and runs end to end through the universal core (`Coordinator`, `DecisionValidator`, `MatchContext`, `AutonomyGates`) with no MLBB names involved. This proves the core is game-free. An isolation test checks that the `universal` package names no MLBB heroes, spells, objectives or the game's name |
| K3 | **Onboarding specification, feasibility template, support matrix, closing:** `docs/PHASE_14_ADAPTER_ONBOARDING.md` covers the steps to add a game, the owner's approval of the target, the feasibility report template (sources, permissions, terms, capture, game policy), and the conformance gate. The support matrix lists MLBB and no other game. Plus the closing record |

The existing kit (`AdapterContractKit`, Phase 2) is the conformance suite: Phase 14 extends it rather than writing a second one.

## 4. Acceptance criteria (roadmap deliverables)

- [ ] Adapter onboarding specification (K4).
- [ ] Target-game feasibility reports: a template only; there is no target game by the owner's decision (K4).
- [ ] Adapter implementations: MLBB, plus a test-only demo adapter; no new game (K3).
- [ ] Capability manifests (K1). **In code (v38.73):** `universal/sdk/CapabilityManifest` (claims with evidence, unsupported features, source notes, JSON, support-matrix line; tested), `adapters/mlbb/MlbbManifest`, `AdapterContractKit.manifest` (the manifest must match its adapter; no claim without evidence).
- [ ] Conformance and regression suites (K2, K3).
- [ ] Per-game support matrix (K1, K4).

## 5. Risks

- **A test-only adapter is not a second game.** The support matrix and the guide say so, and nothing in the app names another game.
