# Phase 7 — LiteRT-LM Backend Integration and Stabilization: audit and execution plan

**Status:** PROPOSED (2026-11-23). It is built from the owner's questionnaire answers of 2026-11-23 and needs the owner's OK before building (`CLAUDE.md` section 14). After the OK, all steps are built back to back and phone-tested once at the end (continuous-phases rule).
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
| L8 | **Experimental NPU, behind a switch** (owner addition 2026-11-23). A Model page switch "Use the NPU (experimental)", **off by default**, tries the order **NPU, then GPU, then CPU**. An NPU build of a model is made for one chip (its file name carries the chip, e.g. `.mediatek.mt6993`; the owner's phone is an MT6897). So the NPU is tried only with a model file built for this phone's chip; any other file goes straight to the GPU, and the Model page says why. First sub-step: confirm that the pinned LiteRT-LM (0.19.1, or the L6 version) has an NPU backend on Android. If it has none, the step reports that and stops | Experimental NPU, NPU → GPU → CPU | Only with the switch on |
| L7 | **Phase 7 phone checklist and closing record** | — | No |

L6 and L8 come after L1–L5, so that L1–L5 are measured on the known-good 0.19.1 first. L7 closes the phase.

## 4. Acceptance criteria (roadmap deliverables)

- [ ] Compatibility report and supported-model matrix, with dated measurements for Gemma 4 E2B on the owner's phone (L1).
- [ ] Load, answer (streaming), cancel, unload, errors and offline are covered by tests (contract kit) and the phone checklist (L2, L5, L7).
- [ ] A risky load warns before it starts (L3); a GPU failure falls back to the CPU once (L4).
- [ ] The LiteRT-LM version decision is recorded with its evidence (L6).
- [ ] Known limitations are documented: NPU experimental only (L8), one model per slot, fresh conversation per question.
- [ ] The experimental NPU switch is off by default, falls back NPU → GPU → CPU, and its result on the owner's phone is recorded (L8).

## 5. Risks

- **Streaming API differences** between LiteRT-LM versions. L2 is built on 0.19.1's own API, and L6 checks it again.
- **Stopping early** must not cut the adviser's choice. The parser decides, and the full answer stays the fallback.
- **The memory estimate** is only an estimate. It warns, never blocks (owner's choice), and it is checked against the snapshots.
