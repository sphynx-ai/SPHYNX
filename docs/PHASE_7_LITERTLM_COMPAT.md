# Phase 7 — LiteRT-LM compatibility report and supported models (L1, L6)

**Date:** 2026-11-23. Claude Code read the app's code at v38.07 and the LiteRT-LM Kotlin API source at tag `v0.19.1` (github.com/google-ai-edge/LiteRT-LM, `kotlin/java/com/google/ai/edge/litertlm/`). Device evidence comes from the owner's phone tests and uploaded snapshots, as named in each row.

## 1. Version (L6)

| | |
|---|---|
| Pinned in the app | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` (since 2026-11-07, PR #8) |
| Newest release tag on 2026-11-23 | **`v0.19.1`**, from `git ls-remote --tags` on the LiteRT-LM repository; the newest tags are v0.18.0, v0.18.1, v0.19.0, v0.19.1 |
| **L6 result** | **No upgrade: the app already runs the newest release.** Not reached from this container: Google's Maven (`dl.google.com`). If a newer version is published later, L6 is done again in its own PR, decided by the phone test |

## 2. API calls the app makes (all present in v0.19.1)

| Call | Where | Notes |
|---|---|---|
| `Engine(EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumImages = 1, cacheDir))`, `initialize()` | `ai/LlmSlot.load` | Vision engine: image + text. Text engine: no vision / audio backend |
| `Backend.GPU()`, `Backend.CPU()` | load order | `Backend.CPU(threadCount)` exists; the app uses the default |
| `Backend.NPU(nativeLibraryDir)` | **not used yet** (L8) | The directory with the NPU libraries; empty = the app's own native library directory |
| `engine.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 16, topP = 0.85, temperature)))` | `LlmSlot.askJpeg` | A fresh conversation per question, by owner decision |
| `conversation.sendMessage(Contents)` | `LlmSlot.askJpeg` | One piece. **L2 moves to `sendMessageAsync(Contents, MessageCallback)`**: `onMessage(Message)` for each piece, `onDone()`, `onError(Throwable)` |
| `Content.Text`, `Content.ImageBytes` | `LlmSlot.askJpeg` | The text comes first, then the image (LiteRT-LM's guide) |
| `conversation.cancelProcess()` | `LlmSlot.cancel` | Emergency stop |
| `conversation.getBenchmarkInfo()` | `LlmSlot.record` | With `ExperimentalFlags.enableBenchmark` (the owner's detailed-speed switch) |
| Exceptions | everywhere | `LiteRtLmJniException` (a native error, with a message) and `IllegalStateException` (closed conversation or engine). L5 maps them to the shared error types |

**Load order today:**
1. Strategic model: image + text on the GPU, then image + text on the CPU, then text only on the CPU.
2. Tactical model: text only, on the CPU.
3. With the detailed-speed switch on, every attempt is tried once with it on and once with it off.

## 3. Supported-model list (owner's choice: Gemma 4 E2B)

| Model | File | Job | Processor on the owner's phone | Evidence | Status |
|---|---|---|---|---|---|
| **Gemma 4 E2B** (`litert-community/gemma-4-E2B-it-litert-lm`) | `.litertlm`, about 2.6 GB | Strategic (images) | GPU (Mali-G615) | Played in Phases 2–6, owner phone tests up to 2026-11-23. Memory: the app uses 2,700 MB with it and the small model loaded, and 3,236 of 11,323 MB stay free (snapshot `diagnostics-baseline-20261123-032846.json`, v37.92) | **Supported** (owner's phone) |

**Measured speed:** load time and answer speed come from the model speed test (Model page) after L2. L2 records the time to the first word on every call, and the rows are added after the phone test. Published figures for Gemma 4 E2B on a Galaxy S26 Ultra: GPU about 3,808 tokens/s reading and 52 writing; CPU about 557 and 47 (LiteRT-LM overview). They are not from this phone.

Other files may work but are **not on the supported list** until they are tested on the phone: Gemma 4 E4B, Gemma 3n E2B, and the small Gemma 3 270M / 1B used for the tactical job.

## 4. Known limitations

- **NPU:** not used by default. L8 adds an experimental switch; open reports describe NPU compile problems on MT6897 / Android 16 phones.
- **One model per slot:** the strategic and tactical slots each hold one model.
- **A fresh conversation per question:** no memory between questions, by owner decision.
- **Pictures:** one image per question (`maxNumImages = 1`), at most 512 px wide, JPEG quality 70.
- **Memory check before loading (L3, v38.10):** only an estimate (file size + 200 MB, 2.5 GB kept for the game); it warns and asks, never blocks.
- **A GPU failure mid-match is not recovered:** L4 adds one CPU fallback.
