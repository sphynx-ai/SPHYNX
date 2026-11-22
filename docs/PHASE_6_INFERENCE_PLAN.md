# Phase 6 — Model-Agnostic Unified Inference Architecture: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-22** (S1–S7, in order; S8 added by the owner's answer). IN PROGRESS.
**Date:** 2026-11-21, Claude Code, by reading `sphynx-test-branch` (v37.91). No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** Principal On-Device AI Inference Architect.
- **Supporting roles:** Android Architecture Engineer; ML Runtime Engineer; AI Systems Architect; Test Architect.
- **Mission:** a stable common inference contract that supports independently selected Strategic and Tactical models and multiple coexisting runtimes (LiteRT-LM and GGUF / llama.cpp).
- **Out of scope:**
  - new LiteRT-LM features (Phase 7);
  - new GGUF features (Phase 8, which runs by owner decision);
  - the Model Lab UI, registry screens and benchmarks (Phases 9–10);
  - device-aware recommendations (Phase 11);
  - any change to how the AI plays (Phase 12).

## 2. What exists (verified by reading the code)

| Part | State | Where |
|---|---|---|
| Common contracts | **Done:** backend interface, runtime states with legal transitions, model configuration with checks, request, events with the one-terminal-event rule (`EventCollector`), error taxonomy (incl. `CrashSuspected`, unused), load plan (GPU → CPU → text only), `ModelRole` enum (unused) | `inference/*.kt` (pure, unit-tested) |
| GGUF backend | **On the contract:** `LlamaCppBackend` (load, streaming generate, cancel, unload, CPU guard) | `inference/LlamaCppBackend.kt`, `models/GgufLibrary.kt` |
| LiteRT-LM | **Not on the contract.** `ai/LlmSlot` with two fixed slots: `GemmaEngine` is strategic (GPU first, images) and `TacticalLlmEngine` is tactical (CPU, text) | `ai/GemmaEngine.kt` |
| Role assignment | **Hard-wired.** Callers name the slot directly. Strategic callers: `StrategicAdvisor`, `ComboFetcher`, `ModelSpeed`, `vision/EndBoard`. Tactical callers: `TacticalAdvisor`, `research/ReadinessRun`. The Model page reads both slots' status. GGUF has no role: it is used only by the Model page's test question | ~45 call sites, mostly `ready` / `state` / `visionOk` reads |
| Lifecycle under pressure | Phase 5 `PressurePolicy` unloads and reloads by slot (tactical, GGUF, strategic) | `core/PressurePolicy`, `app/ProbesWiring` |
| Speed records | `ModelSpeed` / `ModelSpeedLog` per call | `ai/ModelSpeed*.kt` |

## 3. Gaps

| # | Gap | Effect |
|---:|---|---|
| G1 | LiteRT-LM is not behind `InferenceBackend` | Two different code paths for two runtimes; contract tests cover GGUF only |
| G2 | The model is tied to the role and the runtime (strategic = the Gemma slot = LiteRT-LM) | A GGUF model cannot be the tactical model, and the strategic model cannot run on another runtime. This is against the roadmap rule "keep model identity separate from functional role and runtime" |
| G3 | No single owner of "which model serves which role, and is it ready" | Callers, the Model page, `PressurePolicy` and the speed recorder each ask the slots on their own |
| G4 | No load sentinel: `CrashSuspected` exists, but nothing sets it | A model that crashes the app on load (GPU driver, large GGUF) is loaded again at every start |
| G5 | Capability negotiation is implicit (`visionOk` read directly) | A text-only model given the strategic role would silently get image questions |
| G6 | Text and status come from different places per runtime (`state` strings vs `RuntimeStatus`) | The Model page and recordings describe the runtimes differently |

## 4. Plan (one PR each, version +1 each, behaviour identical unless marked)

| Step | Work | Gap | Changes play? |
|---:|---|---|---|
| S1 | **ADR 0002 — role, model and runtime are separate.** A role (`STRATEGIC`, `TACTICAL`) is served by an *assignment* (model configuration + runtime). Callers ask the role, never a runtime. The router owns lifecycle, capability checks and pressure. | G2, G3 | No (document) |
| S2 | **`LiteRtLmBackend`:** wrap `LlmSlot` behind `InferenceBackend` (same load plan, threads and fallbacks), with a fake engine for contract tests; one `InferenceContractKit` that both backends pass | G1, G6 | No |
| S3 | **`RoleRouter`:** `ask(role, request)`, `status(role)`, `capabilities(role)` (images, streaming, max tokens), `load / unload(role)`. The default assignment is today's: strategic is Gemma on LiteRT-LM, tactical is the small LiteRT-LM model | G3, G5 | No |
| S4 | **Callers move to the router:** strategic callers, then tactical, then the Model page reads; image questions are asked only when the role's capabilities say images. The `TacticalEngineGoldenTest` and the decision tests stay unchanged | G2, G5 | No (same answers; a text-only strategic model would get text-only questions) |
| S5 | **`PressurePolicy` and the speed recorder through the router** (slots become roles; GGUF is no longer a separate slot) **Owner addition (2026-11-22), step down to a smaller model:** when "Step down gently" frees a role's model, the role gets the next smaller compatible model that is available instead of nothing. Candidates are any runtime's models (LiteRT-LM files in `Input/`, the GGUF library); the largest model that is still smaller is tried first; the strategic role takes only picture-reading models; when the pressure is over, the original model comes back. Selection rule in code from v37.98 (`inference/roles/StepDown`, `StepDownTest`); **wired in v38.01** (`ai/Roles.stepDown` / `restore`, the pressure slots are the roles). "Only warn me" never unloads or replaces a model (owner, 2026-11-22: needed for testing a model at full performance) | G3 | **Yes, with the owner's step-down addition** (same order: tactical first, strategic last) |
| S6 | **Load sentinel:** a model that was loading when the app died is not loaded by itself again; the Model page explains `CrashSuspected` and offers "Try again" (uses Phase 5 `ExitHistory`) | G4 | Model page only |
| S7 | **Role assignment setting (minimal):** on the Model page, choose which loaded model serves Strategic and which serves Tactical (LiteRT-LM or GGUF). The default stays today's assignment. The full registry and Model Lab are Phase 9. **Owner 2026-11-22:** a GGUF model may serve either role; a text-only strategic model gets text-only questions until S8 gives it images | G2 | **Yes — approved** |
| S8 | **GGUF vision through a multimodal projector (owner 2026-11-22).** A GGUF model is paired with its separate `mmproj` GGUF file, so the vision part is downloaded only when it is needed. This needs llama.cpp's `mtmd` library, vendored from the same pinned tag b11900 (it is left out today, `app/src/main/cpp/README.md`), image input over JNI, and the pairing in `GgufLibrary`. The role's capabilities say "images" only when the projector loaded. Native work, so it is **Phase 8 scope**, done after S3 so the router's capabilities already exist. Device-checked on the owner's phone | G5 | Yes (a GGUF strategic model can read images) |

Every step adds contract or unit tests. The phone check after S4 and S7 is a 5v5 and a Solo practice, as for Phase 2.

## 5. Acceptance criteria (from the roadmap deliverables)

- [ ] Inference architecture and ADR 0002 accepted by the owner (S1). **Proposed in v37.94:** `docs/adr/0002-role-model-runtime.md`.
- [ ] Both runtimes pass one contract test kit (load, generate, stream, cancel, unload, errors, one terminal event) (S2). **Done in code (v37.95):** `inference/LiteRtLmBackend`, `ai/LlmSlotPort`, `testing/InferenceContractKit`, `InferenceContractKitTest` (both runtimes over fakes).
- [ ] No caller outside the router names a runtime or a slot; an `ArchitectureRulesTest` rule enforces it (S4). **Done in code (v37.99–v38.00):** `ai/Roles` over `RoleRouter`; `RolesOnlyTest` enforces it.
- [ ] Role, model identity and runtime are independent: any compatible model can serve either role (S3, S7).
- [ ] Capability negotiation is explicit: an unsupported request is refused with a reason, not silently degraded (S3, S4). **S3 in code (v37.97):** `inference/roles/RoleRouter` (`RoleRouterTest`); callers move in S4.
- [ ] A model that crashed the app on load is not reloaded by itself (S6). **Done in code (v38.02):** `inference/roles/LoadSentinel` (`LoadSentinelTest`), used by `ai/Roles.load`.
- [ ] Play is unchanged with the default assignment: golden tests, plus the owner's phone test.

## 6. Risks

- **LiteRT-LM's thread rules:** `LlmSlot` runs everything on one dispatcher per slot. The wrapper must keep that, or the GPU path can break. Mitigation: S2 changes no threading, and the contract kit tests it with a fake.
- **Vision:** only the LiteRT-LM Gemma slot reads images today. A GGUF model is text only. S4 makes that explicit instead of assuming it.
- **Memory:** two big models on one phone. The router never loads a second model for a role without unloading the first, and Phase 5's step-down still applies.

## 7. Owner decisions (2026-11-22)

1. Plan S1–S7 **approved**, in order.
2. **GGUF roles:** either role; vision through a paired `mmproj` file (S8).
3. **S6:** a model that was loading when the app crashed is **not reloaded**; the Model page says so and offers Try again.
4. **Phase 5:** closed.

The questions as first asked:

1. **Approve this plan** (S1–S7, in this order).
2. **S7:** should a GGUF model be allowed to serve a role in Phase 6 (minimal picker on the Model page), or should that wait for the Phase 9 Model Lab?
3. **Phase 5 close:** after the four resource snapshots (v37.91), may Phase 5 be marked complete with the compatibility matrix holding the owner's phone only?
