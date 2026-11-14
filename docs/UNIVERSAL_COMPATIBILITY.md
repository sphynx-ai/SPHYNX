# Sphynx — Universal Device Compatibility & Adaptive Intelligence

**Phase A report (read-only audit) and roadmap integration.** Written by Claude Code on 2026-11-09, at `origin/main` = `716af66`.

The audit looked only at what the repository actually contains: source, build files and the built APK. Nothing was tested on a device. Labels follow `DEVELOPMENT_STATUS.md`:

| Label | Meaning |
|---|---|
| VERIFIED | Checked against code, a build, a test run or CI. |
| REPORTED | Stated by someone; not re-checked here. |
| PROPOSED | Suggested; not approved. |
| BLOCKED | Waiting on something named. |
| REQUIRES TESTING | Needs a real device. |

> **Guiding rule (owner, 2026-11-09):** the device decides *how much* intelligence runs locally. It never decides *whether* Sphynx works.

---

## A.1 Current architecture (VERIFIED by code reading)

```mermaid
flowchart LR
  subgraph Capture
    MP[MediaProjection<br/>ScreenCaptureService<br/>800 px wide, ~15 fps] --> FH[FrameHub]
  end
  FH --> FP[FrameProcessor<br/>colour vision: HP bars, minimap,<br/>HUD, scoreboard - pure Kotlin]
  FH --> OD[ObjectDetector / ScreenDetector<br/>TFLite YOLO, CPU, 2 threads<br/>OPTIONAL file]
  FP --> GST[GameStateTracker -> GameState]
  OD --> GST
  GST --> TE[TacticalEngine + Utility + Deliberation<br/>deterministic Kotlin - ALWAYS ON]
  GST --> BR[Brain LinUCB bandit<br/>pure Kotlin]
  GST --> FN[FightNet / PolicyNet<br/>TFLite, CPU, 1 thread<br/>OPTIONAL files]
  GST --> SA[StrategicAdvisor]
  SA --> G4[GemmaEngine = LlmSlot<br/>LiteRT-LM, .litertlm<br/>GPU then CPU fallback<br/>OPTIONAL]
  GST --> TA[TacticalAdvisor]
  TA --> G3[TacticalLlmEngine = LlmSlot<br/>LiteRT-LM, CPU text-only<br/>OPTIONAL, advisory only]
  SA -. online, optional .-> GQ[GroqClient: Groq / Gemini / Cerebras keys]
  TE --> AE[ActionExecutor -> AccessibilityController<br/>touches]
  subgraph Universal layer
    U[universal/: UniversalMatchState, Capabilities,<br/>Decision, Validator, Arbiter, TargetFusion,<br/>CombatRange, SpatialMap]
  end
  GST --> U
```

What runs **with no model at all**:
- capture, colour vision and game state;
- the Kotlin tactical engine and the Brain;
- Player mode in "rules only" (`AiRuntime.kt:108`);
- Drafter in non-ranked;
- research mode;
- the session recorder.

Every AI model is an **optional file** found by `models/AutoFolder.kt`.

## A.2 AI dependency map (VERIFIED)

| Component | Runtime / library | Where | Backend | Required? |
|---|---|---|---|---|
| Strategic LLM (Gemma 4 E2B / 3n E2B) | LiteRT-LM `litertlm-android:0.19.1` (pinned) | `ai/GemmaEngine.kt` (`LlmSlot`), `models/ModelManager.kt` | GPU first (`Cfg.useGpu` default **true**), then CPU, then CPU text-only | Optional for play. **Required to start Builder, and Drafter in Ranked** (`AiRuntime.needsGemma`, `AiRuntime.kt:115`) |
| Tactical LLM (Gemma 3 270M) | LiteRT-LM | same `LlmSlot`, `TacticalAdvisor` | CPU, text only | Optional; advisory only (validator: Experimental) |
| Minimap / screen detector (YOLO) | TensorFlow Lite `tensorflow-lite:2.17.0` | `vision/ObjectDetector.kt:74` | CPU, `setNumThreads(2)` | Optional |
| Fight-choice network | TFLite | `learning/FightNet.kt:53` | CPU, 1 thread | Optional |
| Tactical policy network | TFLite | `learning/PolicyNet.kt:174` | CPU, 1 thread | Optional |
| Online LLMs | HTTPS (Groq, Gemini, Cerebras) | `ai/GroqClient.kt` | remote | Optional (needs a key file) |
| `com.google.ai.edge.litert:litert:1.4.2` | — | **no import anywhere** | — | **Unused dependency** |

All LLM calls go through one class, `LlmSlot`, which has:
- `load` with a backend fallback list;
- `ask`, single-threaded;
- `cancel` and `unload`.

That makes it the natural seam for a runtime abstraction. Only 6 files call `GemmaEngine.*` directly:
- `StrategicAdvisor`;
- `AiRuntime`;
- `ComboFetcher`;
- `MainActivity`;
- `AutoFolder`;
- `ModelManager`.

## A.3 Device-specific assumptions found (VERIFIED)

| # | Finding | Where | Impact on compatibility |
|---|---|---|---|
| 1 | **LiteRT-LM ships native code for arm64-v8a and x86_64 only.** TFLite also ships armeabi-v7a and x86. | built APK `lib/` | On 32-bit ARM phones the LLMs cannot run; the detectors and networks can. `LlmSlot.load` catches `Throwable`, so it *should* fail softly — REQUIRES TESTING. |
| 2 | `minSdk = 30` (Android 11), target 36 | `app/build.gradle.kts:52` | Android 8–10 devices cannot install. Lowering it touches MediaProjection, storage and accessibility code — owner decision. |
| 3 | GPU is tried first for the strategic model by default | `Settings.kt:32`, `ModelManager.load` | Good when it works. A *native* crash in a GPU driver is not catchable (see 5). |
| 4 | Fixed capture size (800 px), fixed frame gap (66 ms; 33 ms "fast mode") | `FrameHub.CAPTURE_WIDTH`, `ScreenCaptureService.kt:40` | No adaptation to slow CPUs or to heat. |
| 5 | **No crash-loop protection.** A native crash during model load kills the app; the next start re-runs the folder scan, which loads the model again. | `AutoFolder.kt:243-263` | A device that crashes on GPU load could crash on every start. Highest-priority gap. |
| 6 | **No memory budget.** The folder scan loads the big model whatever the free RAM is; both LLMs can be loaded at once. `largeHeap="true"`. | `AutoFolder.kt`, `AndroidManifest.xml:28` | Out-of-memory kills likely on 4–6 GB devices — REQUIRES TESTING. |
| 7 | **No thermal or memory-pressure handling.** No `PowerManager.getCurrentThermalStatus`, no `onTrimMemory`. `DeviceStats` only *displays* CPU, GPU, RAM and storage. | `core/DeviceStats.kt` | Sustained play can throttle; the app does not react. |
| 8 | Fixed inference cadence: strategy every 8 s (`strategyEverySec`), builder screen read every 25–30 s, small model ~3 Hz in fights | `Settings.kt:45,223`, `TacticalAdvisor` | No scaling by device class. |
| 9 | Hardware facts are read for display only (CPU clocks from `/sys`, GPU vendor files that many phones block) | `DeviceStats.kt` | Fine for display. **Not** evidence of usable acceleration. |
| 10 | Measured on the owner's POCO X6 Pro (Dimensity 8300-Ultra, Mali-G615), from the 2026-11-08 recordings: frame-to-touch about **310 ms average**; the touch queue alone 160–320 ms; vision 26–80 ms | session recordings, Speed log | The bottleneck on this phone is touch dispatch, not inference. |

## A.4 Compatibility gap analysis (against the owner's 15 principles)

| Principle | Status today |
|---|---|
| 1–4. No mandatory Snapdragon / MediaTek / Adreno / Mali | **Met.** Nothing is vendor-specific. |
| 5–6. No mandatory NPU / GPU | **Met for play.** The GPU is preferred, with a CPU fallback. |
| 7. CPU-only operation | **Met in code.** REQUIRES TESTING on a CPU-only device. |
| 8. Local LLM optional | **Partly met.** Builder and Ranked Drafter refuse to start without Gemma. |
| 9. Core works without an LLM | **Met for play, research and recorder.** Not met for Builder and Ranked Drafter (see 8). |
| 10. Runtimes replaceable | **Not met.** LiteRT-LM types are used directly in `LlmSlot`. |
| 11. Models replaceable without logic changes | **Partly met.** Any `.litertlm` loads, but the slot is chosen by file name and size (`ModelManager.isTactical`). There is no metadata. |
| 12. Graceful degradation | **Partly met.** Kotlin errors fall back; native crashes and crash loops do not. |
| 13. Evidence-based hardware detection | **Not met.** No capability detection exists. |
| 14. Compatibility-based model selection | **Not met.** Selection is by file name and size. |
| 15. Optimisation must not break features | Process rule; to be kept by tests. |

## A.5 Risk assessment

| Risk | Likelihood | Severity | Mitigation (phase) |
|---|---|---|---|
| Crash loop after a native model-load crash | Medium on unknown GPUs | High | Load sentinel + per-backend blocklist (Phase E, **pull forward**) |
| OOM when loading Gemma 4 E2B on 4–6 GB phones | Medium | High | Memory budget before load (C + E) |
| Abstraction that adds layers without value | Medium | Medium | One interface around `LlmSlot`, no framework (B) |
| llama.cpp needs an NDK build, JNI and its own release process | High if attempted | Medium | Feasibility spike only, behind the adapter; no dependency until justified (D) |
| Changing defaults breaks the owner's tuned MLBB behaviour | Low–medium | High | Defaults on the POCO profile must stay identical; regression tests, recordings (all phases) |
| Claims of acceleration without evidence | — | Medium | "Verified" only after a real inference on that backend (C, G) |

## A.6 Proposed architecture (PROPOSED — design only, not implemented)

The aim is small and additive. The existing classes stay; new code wraps them.

```mermaid
flowchart TB
  DCM[DeviceCapabilityManager<br/>evidence: API, ABIs, RAM, avail RAM,<br/>thermal status, storage, power save,<br/>GL renderer string; NPU = unknown unless a runtime proves it] --> AIM
  REG[ModelRegistry<br/>metadata per model file:<br/>id, version, format, arch, quant, runtimes,<br/>backends, RAM need, context, caps,<br/>installed, verification status] --> AIM
  AIM[AdaptiveInferenceManager<br/>picks level 1/2/3, model, backend, cadence;<br/>load sentinel, crash/OOM/slow fallback;<br/>thermal + memory policy] --> RT
  RT[InferenceRuntime interface<br/>load / ask / cancel / unload / capabilities]
  RT --> L1[LiteRtLmRuntime = today's LlmSlot]
  RT -. feasibility only .-> L2[LlamaCppRuntime GGUF]
  AIM --> SA[StrategicAdvisor / TacticalAdvisor / ComboFetcher<br/>unchanged callers]
  AIM -. no model / failure .-> K[Kotlin rules: always available]
```

**Intelligence levels** are computed from measured evidence, never from brand names.

| Level | Typical evidence | Intelligence |
|---|---|---|
| 1 Basic | No 64-bit LLM runtime, or less than about 6 GB total RAM, or thermal SEVERE+, or no model installed | Kotlin rules; optional TFLite nets on CPU; no local LLM, or the 270M only if its memory budget fits; strategy cadence relaxed |
| 2 Enhanced | arm64, enough *available* RAM for the strategic model plus headroom; CPU inference verified | Gemma on CPU (or GPU if verified); current cadence |
| 3 Advanced | Level 2 plus GPU (or NPU) inference **verified by a successful run** on this device, and thermal status NONE or LIGHT | Today's POCO behaviour: GPU Gemma, vision prompts, the 270M alongside |

All RAM thresholds are placeholders until Phase G measures them; they are not a claim. The level is re-evaluated when the thermal status or available memory changes.

**Graceful degradation, in order:**
1. Detect: exception, timeout, latency above budget, OOM signal, or a sentinel left by a crash.
2. Cancel the operation.
3. Keep the session.
4. Try the next backend or model.
5. Fall back to Kotlin.
6. Show the reduced level on the Speed / Model page.
7. Persist a per-model, per-backend blocklist so the same crash is not retried on start.

**LiteRT-LM compared with llama.cpp** (to verify in Phase D; not claims):

| | LiteRT-LM (in use) | llama.cpp (candidate) |
|---|---|---|
| Model format | `.litertlm` (Gemma family published by Google) | GGUF (many families) |
| Android packaging | Maven AAR (used today) | No official AAR; NDK build + JNI, or a community wrapper |
| ABIs today | arm64-v8a, x86_64 (VERIFIED in our APK) | Builds for arm64 and others; 32-bit ARM support and speed to verify |
| GPU | GPU backend used today; works on the owner's Mali (REPORTED: "ready on GPU") | Vulkan / OpenCL backends; per-vendor maturity to verify |
| Vision input | Yes (Gemma 4 E2B / 3n images) — used for draft and line-up reading | Multimodal support varies by model and build |
| Cost to add | — | APK size, build time, a second prompt format, maintenance |

Recommendation: keep LiteRT-LM as the only runtime. Add llama.cpp only if Phase G shows a real device class that LiteRT-LM cannot serve. 32-bit ARM is the prime candidate to check.

## A.7 Roadmap integration

How the new phases relate to the existing plan. Nothing is removed. The full table is in `DEVELOPMENT_STATUS.md` §8.

- **Phase A** (this document): Completed, read-only.
- **Phase B** (runtime-independent contracts): a specification only. It extends the existing `universal/` package (Decision, Capabilities), so no parallel framework is created.
- **Phase C** (Device Capability Manager): first code phase. Pure detection plus display; no behaviour change.
- **Pulled forward from Phase E: the load sentinel / crash-loop guard.** It is the only item that protects users *today*, and it is tiny. It is proposed as the first item of Phase C or a separate small PR.
- **Phase D** (runtime abstraction): wraps `LlmSlot` behind an interface. llama.cpp is a feasibility spike only.
- **Phase E** (model registry and adaptive manager): replaces file-name slot guessing with metadata. It also needs the owner's decision on Builder and Ranked Drafter without Gemma (principle 9).
- **Phase F** (resource and thermal): cadence and capture scaling.
- **Phase G** (device validation): BLOCKED on access to devices other than the POCO X6 Pro.

**Existing work keeps its priority.** Issue #2 re-test, R5 tuning (needs 5 or more practice matches per group), R4 readiness (needs 200 or more moments) and map validation (needs screenshots) are all waiting on device data. Phases B and C do not compete with them for the owner's time.

## A.8 Testing plan per phase (summary)

Each phase adds JVM unit tests for the following, using fakes; no device needed:
- classification;
- registry validation;
- the fallback order;
- the sentinel logic;
- the thermal and memory policies.

The existing suite (168 tests, VERIFIED on `716af66`) must stay green. Real-device checks are listed separately and are never reported as passed without a device run.

## A.9 Hardware-aware deployment profiles (owner-approved direction, 2026-11-10)

**Origin.** A tier proposal from the project manager (entry / midrange / flagship phones, with a model per tier). Claude Code checked it against published figures and the app's real constraints. The owner approved adding the corrected version here as the plan for the resource manager:
- hybrid inference **Phase Six**;
- **Phase F** in this document.

It refines the three intelligence levels of A.6 and does not replace them:
- Lite ≈ Level 1;
- Balanced / Advanced ≈ Level 2 / 3;
- Maximum is new.

The A.6 recommendation "LiteRT-LM as the only runtime" is superseded by the owner's hybrid LiteRT-LM + GGUF directive (`HYBRID_INFERENCE_AUDIT.md`).

### A.9.1 Facts checked (2026-11-10)
| Fact | Source / status |
|---|---|
| llama.cpp b11900 (the pinned spike version, PR #34) has the `gemma4`, `lfm2` and `qwen35` architectures | VERIFIED in `src/llama-arch.cpp` |
| Gemma 4 E2B on a Galaxy S26 Ultra (LiteRT-LM):<ul><li>GPU: ~3,800 prefill / ~52 decode tok/s, ~680 MB peak *CPU* memory;</li><li>CPU: ~560 / ~47 tok/s, ~1.7 GB.</li></ul>E4B:<ul><li>GPU: ~1,300 / ~22 tok/s, ~710 MB;</li><li>CPU: ~3.3 GB.</li></ul>On phones the GPU uses the same RAM, so the GPU memory figures are **not** the total. | Google's published figures (LiteRT-LM Gemma 4 page and model cards); flagship only, midrange unknown |
| Gemma 4 E2B as a Q4_K_M GGUF: **3.1–3.4 GB file** | Hugging Face quant listings |
| LFM2.5-1.2B (Liquid AI): Q4 GGUF about 0.86 GB | Model card (desktop figures only) |
| LFM2.5 license: **LFM Open License v1.0**, free commercial use only under US$10M yearly revenue | Liquid AI license page |
| Qwen3.5 0.8B: released March 2026, natively multimodal; community GGUFs exist | Release coverage; license to confirm on the official model card |
| Representative entry chips (Helio G85 / G81, Unisoc T7250, Dimensity 6080) have Cortex-A75/A76 + A55 cores, which can run the spike's `armv8.2-a+dotprod` build | Public chip specs |
| MLBB's own RAM need: about 2 GB minimum, 3–4 GB comfortable | Third-party guides only; no official Moonton figure found |

### A.9.2 Corrections to the proposal
1. **No 3+ GB GGUF strategist on 4 GB phones.** The Gemma 4 E2B GGUF does not fit next to Android and MLBB. It would also be CPU-only and text-only, which loses draft and line-up reading. For Gemma, the LiteRT-LM GPU path is the lighter one.
2. **A bigger tactical model must be fast enough *and* better:**
   - the tactical adviser's budget is about 350 ms (Speed page);
   - prompt prefill dominates, and a 1.2B model is roughly 4× the work of 270M;
   - it is used only if the measured time to the first token fits the budget **and** the practice benchmark / readiness test shows better advice.
3. **"Both models loaded" is a runtime decision, not a tier setting:**
   - during a match MLBB is the foreground app and Sphynx is a background process, the first one Android closes under memory pressure;
   - a GPU strategist competes with the game's rendering;
   - a CPU tactical model competes with the game and with screen reading.
4. **The play loop never waits for a language model, on every tier.** Screen → decision → touch stays on the rules engine (with the optional TFLite networks); LLMs only steer. The screen-to-touch recorder (PR #35) checks this per device.
5. **Licenses.** Users bring their own model files, so the app distributes none. LFM's revenue limit and Gemma's terms matter only if Sphynx is ever sold.

### A.9.3 Profiles (to be validated per device; not guarantees)
| Profile | Chosen when (measured on this phone) | Strategist | Tactical |
|---|---|---|---|
| **Lite** | Free memory with MLBB running too low for a strategist, or no LiteRT-LM backend loads | Only during the draft and between matches, then unloaded; otherwise rules and the online meta | Rules only, or Gemma 3 270M if its time to first token fits the budget |
| **Balanced** | Gemma 4 E2B loads on the GPU with headroom left | Gemma 4 E2B (LiteRT-LM, GPU) | Gemma 3 270M (CPU) |
| **Advanced** | Balanced, plus a 0.8–1.2B model meets the tactical budget **and** improves the practice / readiness scores | Gemma 4 E2B (LiteRT-LM, GPU) | Qwen3.5 0.8B or LFM2.5 1.2B (GGUF, CPU) |
| **Maximum** | A flagship-class phone with enough measured free memory for E4B on the GPU plus the tactical model | Gemma 4 E4B (LiteRT-LM, **GPU only**) | LFM2.5 1.2B or Gemma 4 E2B |

**What the selector reads** (all measured; never the brand name or the advertised RAM):
1. available memory **while MLBB is running**;
2. which backend actually loaded each model (load sentinel and blocklist, Phase Two);
3. speed-test results on this phone: time to first token and tokens/s per model and backend (`ModelSpeed`, PR #35);
4. screen-to-touch with the models on vs off (`TouchSpeed`, PR #35);
5. thermal status over a whole match.

**Stepping down during a match:**
- one profile down when the thermal status reaches MODERATE (2) or higher, or when Android signals low memory (`onTrimMemory`);
- back up only after a cool-down and at a quiet moment (not in a fight).

**Profile per device:**
- the chosen profile and the evidence behind it are shown on the Model / Speed page;
- the owner can force a lower profile, never a higher one than measured.

### A.9.4 Requirements to publish
| | Minimum (experimental) | Recommended |
|---|---|---|
| CPU / ABI | 64-bit ARM (arm64-v8a); LiteRT-LM ships only arm64 and x86_64 | Upper-midrange or flagship |
| Android | 11 (the app's minSdk 30) | Latest supported |
| RAM | **6 GB**: Lite profile only, not full autonomy | **8–12 GB**: Balanced / Advanced |
| Storage | Enough for the chosen model files plus a copy, with the free-space check before import | 256 GB |

**Validation status:**
- Only the owner's POCO X6 Pro (midrange; Balanced / Advanced candidate) can be tested today.
- The other phones in the proposal are examples. A phone becomes "validated for profile X" only with speed-test and practice-benchmark results from it (Phase G).

### A.9.5 Where it is built
| Step | Phase |
|---|---|
| Measurement | **Done in PR #35** (speed recorder) and **PR #34** (GGUF test card) |
| Load sentinel and blocklist | Phase Two |
| Model registry | Phase Four |
| Assignment per engine | Phase Five |
| Profile selector, runtime step-down, profile display | **Phase Six** (resource manager) = Phase F here |
| Per-device validation | Phase G |

Unit tests use fakes for the selector rules: memory and thermal thresholds, step-down / step-up, and never above the measured profile.
