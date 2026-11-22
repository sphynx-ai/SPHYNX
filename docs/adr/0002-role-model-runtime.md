# ADR 0002 — A job, the model doing it, and the runtime running it are separate

| | |
|---|---|
| **Status** | **Proposed** 2026-11-22. This step (S1) only needs the owner's acceptance; S2–S8 build it |
| **Phase** | 6 — Model-Agnostic Unified Inference Architecture, step S1 (`docs/PHASE_6_INFERENCE_PLAN.md`, approved 2026-11-22) |
| **Builds on** | ADR 0001 (layers): `inference` is L0 platform and may use nothing above it |
| **Enforced by** | `ArchitectureRulesTest` (a new rule from S4: outside the router, no code names `GemmaEngine`, `TacticalLlmEngine` or a backend class) |

## Context

The Phase 6 audit (plan Section 2) found:
- **The job is the model is the runtime.** The strategic job is always the `GemmaEngine` slot, which is LiteRT-LM. The tactical job is always the `TacticalLlmEngine` slot, also LiteRT-LM. A GGUF model can't do either job; it only answers the Model page's test question.
- **Two code paths.** `LlamaCppBackend` follows the common `InferenceBackend` contract. LiteRT-LM runs through `ai/LlmSlot`, which is not on the contract.
- **No single owner.** About 45 places ask the slots directly (`ready`, `state`, `visionOk`, `ask`): the advisers, the speed recorder, the Model page, the end-of-match reader, and Phase 5's `PressurePolicy`.

The roadmap says model identity must be kept separate from functional role and runtime, and that LiteRT-LM and GGUF stay as coexisting backends.

## Decision

### Three separate things

| Term | Meaning | Type |
|---|---|---|
| **Role** (job) | What the app needs answered: `STRATEGIC` (longer-horizon reasoning, draft, build, may need images) or `TACTICAL` (short, fast, text) | `inference.ModelRole` (exists) |
| **Model** | One model file and its settings: id, display name, size, context, accelerators, whether it wants images; an optional **vision projector** (`mmproj`) for a GGUF model (S8) | `inference.ModelConfiguration` (exists, gains `projector` in S8) |
| **Runtime** | The engine that runs a model: LiteRT-LM or llama.cpp | `inference.InferenceBackend` (exists; LiteRT-LM joins in S2) |

An **assignment** links one role to one model on one runtime. A model may be assigned to either role, if its runtime can load it. The same model can't serve both roles at once in Phase 6: one loaded copy per role, to keep memory predictable.

### One router owns the roles

`RoleRouter` (L0 platform, package `inference.roles`; it holds no game knowledge, so it may use nothing above it) is the only code that:
- holds the current assignment for each role, persisted in settings;
- loads, unloads and reloads a role's model, and reports `status(role)` as a `RuntimeStatus`;
- answers `capabilities(role)`: images, streaming, maximum output tokens. Images are true only when the loaded model really accepts them: a LiteRT-LM multimodal model, or (S8) a GGUF model with its projector loaded;
- runs `ask(role, request)`, which refuses a request the role can't serve (for example an image to a text-only model) with `InferenceError.InvalidRequest` and a reason, instead of degrading silently;
- keeps a **load sentinel** (S6): a role whose model was loading when the app died is marked `CrashSuspected` and not loaded again by itself (owner, 2026-11-22). The Model page offers Try again.

Callers ask a **role**. They never name a slot, a model or a runtime.

### The default assignment is today's

- **Strategic:** the LiteRT-LM model from `Input/` (Gemma), GPU first, images when it loads with vision.
- **Tactical:** the small LiteRT-LM model, CPU, text only.

With this default, every step from S2 to S6 must give the same answers, the same speed and the same fallbacks as today. The golden decision tests and the owner's 5v5 phone test check this.

### What stays where (ADR 0001 layers)

| Piece | Layer | Package |
|---|---|---|
| Contracts, backends (`LlamaCppBackend`, new `LiteRtLmBackend`) | L0 | `inference` (`LiteRtLmBackend` wraps `ai/LlmSlot` through a small engine interface, so `inference` keeps no LiteRT-LM types in its contracts) |
| `RoleRouter`, assignment, sentinel, capability checks | L0 | `inference.roles` (pure where possible; unit- and contract-tested with fake backends) |
| Which questions to ask (prompts), when | L2 / L3 | unchanged: advisers in `ai`, MLBB questions in the adapter |
| Role picker (S7) | L3 | `app` (Model page) |
| Pressure step-down | L0 policy + wiring | `core/PressurePolicy` slots become roles (S5) |

## Consequences

- **Good:**
  - any compatible model can do either job;
  - GGUF becomes a real backend for play (S7) and gets images through `mmproj` (S8);
  - one contract kit tests both runtimes;
  - one place to look for "what is loaded and why not";
  - the crash-on-load loop is gone (S6).
- **Cost:** about 45 call sites move to the router (S4), in small PRs with identical behaviour.
- **Risk:** LiteRT-LM's single-thread rule. `LiteRtLmBackend` must keep `LlmSlot`'s own dispatcher. The contract kit checks with a fake engine that calls never overlap.
- **Not decided here:** which model is recommended for which phone (Phase 11), the model registry UI (Phase 9) and benchmarks (Phase 10).

## Alternatives considered

1. **Keep two fixed slots and only add a GGUF option to each slot.** Smaller, but it keeps the job tied to code, and a third runtime or a third job would need the same work again. Rejected.
2. **One generic "LLM" with no roles.** It loses the Strategic / Tactical split the roadmap requires (different budgets, different capabilities). Rejected.
3. **Load the same model for both roles.** It would save memory on small phones, but it changes timing (both jobs queue on one runtime). Left for Phase 11's device-aware profiles.
