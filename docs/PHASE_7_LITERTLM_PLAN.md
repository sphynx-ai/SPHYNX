# Phase 7 — LiteRT-LM Backend Integration and Stabilization: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-23** (L1–L9; the detectors are added to L8). IN PROGRESS: all steps are built back to back and phone-tested once at the end (continuous-phases rule).
**Based on:** `sphynx-test-branch` at v38.05, by reading the code. No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** Senior Android On-Device ML and LiteRT-LM Integration Engineer.
- **Supporting roles:** Android Native/Platform Engineer; ML Model Deployment Specialist; Performance Engineer; QA Engineer.
- **Mission:** audit the existing LiteRT-LM work and stabilize it behind the common inference interface built in Phase 6.
- **Out of scope:**
  - the NPU as a default (owner, 2026-11-23). It comes only as the experimental switch in step L8 (owner, 2026-11-23);
  - new model features;
  - GGUF (Phase 8);
  - the Model Lab and registry (Phase 9);
  - benchmarks across models (Phase 10).

## 2. What exists (verified by reading the code)

| Part | State |
|---|---|
| Library | `com.google.ai.edge.litertlm:litertlm-android:0.19.1`, pinned since 2026-11-07 (PR #8) |
| Slots | `ai/LlmSlot` (`GemmaEngine` strategic, `TacticalLlmEngine` tactical). Each has its own single thread. |
| Load order | Image + text on the GPU, then image + text on the CPU, then text only on the CPU. The tactical model uses the CPU, text only. With the detailed-speed switch on, every attempt is tried again with it off. |
| Contract | `inference/LiteRtLmBackend` over `ai/LlmSlotPort` (Phase 6). It passes `InferenceContractKit`, and every caller goes through `ai/Roles`. |
| Answers | **In one piece** (`conversation.sendMessage`). Each question is a **fresh conversation**, which the owner decided to keep. Sampler: topK 16, topP 0.85. "Do not think" is appended; thinking tags are stripped. |
| Speed records | `ModelSpeed` per call. Detailed LiteRT-LM figures (time to first token, prefill, decode) come only with the benchmark switch. |
| Failures | A load failure is reported. **An answer that fails on the GPU mid-match is not handled**: the next question fails the same way. |
| Memory | **No check before loading.** The Phase 5 snapshot shows the app at 2.7 GB with both models loaded, leaving 3.2 GB of 11.1 GB free before the game. |
| Device evidence | Gemma 4 E2B on the GPU of the owner's POCO X6 Pro (Phases 2–6 phone tests) |

## 3. Plan (one PR each, version +1 each; built continuously after the owner's OK)

| Step | Work | Owner decision | Changes play? |
|---:|---|---|---|
| L1 | **Compatibility report and supported-model list:** the API calls the app uses and the load order. The list holds **Gemma 4 E2B only** (owner's pick), with its measured load time, speed and memory from the speed test and resource snapshots, plus known limitations. A doc, plus a Model page line naming the supported model | Model list: Gemma 4 E2B | No |
| L2 | **Streaming answers** (`sendMessageAsync`). The slot passes pieces as they are written, and `LiteRtLmBackend` emits Token events. The tactical adviser stops the answer as soon as it has read its choice. The speed recorder gets the real time to the first word on every call, not only with the benchmark switch | Stream | Faster answers; same content |
| L3 | **Memory check before loading:** an estimate from the file size and free memory, keeping room for the game. When the model will probably not fit, the app warns and asks; you can still load. Pure rule, unit-tested | Warn, let me load | Only a question before a risky load |
| L4 | **A GPU failure mid-match:** the model is reloaded on the CPU **once**, and the panel and log say so. The GPU is tried again at the next start. A second failure unloads, and play goes on with the rules | Fall back to CPU once | Only after a GPU failure |
| L5 | **Error words:** LiteRT-LM's exceptions are mapped to the shared error types (not enough memory, unsupported model, GPU unavailable, cancelled), so the Model page and the log say what to do | — | No |
| L6 | **Newer LiteRT-LM release, in its own PR:** CI reports the newest published version, since this container can't reach Google's Maven. Its release notes are checked, the pin is raised and the build is run. It is kept only if the owner's phone test shows Gemma 4 E2B loading and answering at least as well; otherwise the pin goes back to 0.19.1 | Try newer, own PR | Possibly; decided by the phone test |
| L8 | **Experimental NPU, compiled on the phone** (owner, 2026-11-23: the on-device option, "third option"). A Model page switch "Use the NPU (experimental)", **off by default**, tries the order **NPU, then GPU, then CPU** with an ordinary `.litertlm` file. LiteRT-LM compiles the model for this phone's NPU when it loads (just-in-time, with MediaTek's NeuroPilot); Google says this can take over a minute for a Gemma-size model. The Model page shows a "compiling for the NPU…" state, and the compiled result is cached where LiteRT-LM allows. First sub-step: confirm the pinned LiteRT-LM (0.19.1, or the L6 version) exposes the NPU backend with on-device compilation on Android, and which NPU runtime library the app must ship. If it does not, the step reports that and stops. Open reports describe NPU compile crashes on Dimensity 8300 / MT6897 phones with Android 16, so a failure here is expected to be possible: it falls back and is remembered (L9) **The detectors too** (owner, 2026-11-23): the minimap and screen detectors (`.tflite` image networks, today on 2 CPU threads) get the same switch: NPU through LiteRT's compiled-model path, falling back to the CPU, with their time per frame shown before and after. The small fight-choice network stays on the CPU, where it answers in well under a millisecond | Experimental, on-device compile, NPU → GPU → CPU; detectors NPU → CPU | Only with the switch on |
| L9 | **Adaptive processor per model** (owner, 2026-11-23: "best processor per model"). For each model file the app remembers what happened on each processor on this phone: whether it loaded, its load time, and its answer speed from the speed records. Next time it tries the best one first: the fastest that loaded and answered. It falls back in the usual order when that one fails. A processor that crashed or failed is skipped until the app version, the model file or the LiteRT-LM version changes; the S6 load sentinel catches crashes during load. The Model page shows the choice and why (for example "GPU: 13 tok/s; NPU failed to compile on 11-23"), and the GPU switch / NPU switch still limit what may be tried. Pure choosing rule, unit-tested | Best processor per model | Load order per model, from this phone's own results |
| L7 | **Phase 7 phone checklist and closing record** | — | No |

L6, L8 and L9 come after L1–L5, so that L1–L5 are measured on the known-good 0.19.1 first. L9 comes after L8 so it can include the NPU result. L7 closes the phase.

## 4. Acceptance criteria (roadmap deliverables)

- [ ] Compatibility report and supported-model matrix, with dated measurements for Gemma 4 E2B on the owner's phone (L1). **Report in v38.08:** `docs/PHASE_7_LITERTLM_COMPAT.md`; speed rows come after the phone test.
- [ ] Load, answer (streaming), cancel, unload, errors and offline are covered by tests (contract kit) and the phone checklist (L2, L5, L7). **Streaming in code (v38.09):** `LlmSlot.stream` (`sendMessageAsync`), the early stop (`InferenceRequest.stopWhen`, `TacticalAdvisor.enough`), time to the first word on every call.
- [ ] A risky load warns before it starts (L3); a GPU failure falls back to the CPU once (L4). **Memory check in code (v38.10):** `core/MemoryFit` (file size + 200 MB vs free memory, 2.5 GB kept for the game), asked before loads by hand, logged for automatic ones. **GPU fallback in code (v38.11):** `inference/GpuFallback` (once per start, then unload), `LiteRtLmBackend.afterFailure`, panel line `Roles.panelLine`; error words `inference/LiteRtErrorWords` (L5).
- [x] The LiteRT-LM version decision is recorded with its evidence (L6): v0.19.1 is the newest release tag on 2026-11-23, so there is no upgrade (`docs/PHASE_7_LITERTLM_COMPAT.md` §1).
- [ ] Known limitations are documented: NPU experimental only (L8), one model per slot, fresh conversation per question.
- [ ] The experimental NPU switch is off by default, compiles on the phone, falls back NPU → GPU → CPU, and its result on the owner's phone is recorded (L8).
- [ ] Each model's processor is chosen from this phone's own results, a failed processor is not retried by itself, and the Model page shows why (L9).

## 5. Risks

- **Streaming API differences** between LiteRT-LM versions. L2 is built on 0.19.1's own API, and L6 checks it again.
- **Stopping early** must not cut the adviser's choice. The parser decides, and the full answer stays the fallback.
- **The memory estimate** is only an estimate. It warns, never blocks (owner's choice), and it is checked against the snapshots.
