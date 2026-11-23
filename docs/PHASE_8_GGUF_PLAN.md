# Phase 8 — GGUF / llama.cpp Backend Integration: audit and execution plan

**Status:** **APPROVED by the owner 2026-11-23** ("approve phase 8 plan, try vulkan and newer llama.cpp"), with one addition: **both jobs use pictures** (G11). **Built in code: G1–G7, G9, G10, G11 (v38.19–v38.28, PRs #110–#119). G8 (Vulkan) stopped at its first sub-step, waiting for the owner's choice** (`docs/PHASE_8_GGUF_NATIVE.md` §8). The owner's phone test of the whole phase is pending (Section 7). Phase 8 has run since 2026-11-14 by owner decision (MASTER_DEVELOPMENT_PLAN section 9.1).
**Based on:** `sphynx-test-branch` at v38.17, read from the code. No phone was used for this plan.

## 1. Role for this phase (roadmap 3.1)

- **Primary role:** Principal Native AI Runtime Engineer (llama.cpp, Android NDK).
- **Supporting roles:** Android Native Systems Engineer; Model Quantization Specialist; Mobile Hardware Performance Engineer; Native QA/Security Engineer.
- **Mission:** a separately maintainable GGUF/llama.cpp backend behind the shared inference contract.
- **Out of scope:**
  - the Model Lab and model registry UI (Phase 9);
  - benchmarks across models (Phase 10);
  - which model fits which phone (Phase 11);
  - an NPU for GGUF: llama.cpp has no MediaTek NPU backend.

## 2. What exists (verified by reading the code)

| Part | State |
|---|---|
| llama.cpp | Vendored at tag **b11900** (commit `74b104c`, 2026-10-26), built from source by CMake with NDK 29 (`app/src/main/cpp`). The newest tag on 2026-11-23 is **b12651** (v1.0.0 was b12359) |
| ABI / processor | arm64-v8a only, **CPU only** (`n_gpu_layers = 0`), `armv8.2-a+dotprod+fp16`. Older CPUs are refused before the library loads (`LlamaCpu`) |
| Engine | `llama_engine.cpp`: one model and context per handle, streaming pieces, cancel from any thread (it also stops a long prompt), chat template from the model, a light repetition penalty |
| Contract | `inference/LlamaCppBackend`: passes `InferenceContractKit`. The Phase 6 router can give either job to a GGUF model (S7). Pictures come through an `mmproj` file (S8). The early stop comes from Phase 7 L2 |
| Settings | 4 threads (the big cores), context from the configuration, batch 512, top-k 40 / top-p 0.95, memory check before loading (file + 64 KB per context token + 128 MB) |
| GGUF checks | Only the header (`GgufFile.checkHeader`: magic number, web page, Git LFS pointer). Architecture, quantization and context length are not read before llama.cpp opens the file |
| Tests | Kotlin unit tests with a fake native engine (`LlamaCppBackendTest`, `LlamaPartsTest`, `LlamaVisionTest`). **No test runs the real native engine**: CI only compiles it |
| Device evidence | Owner's phone 2026-11-14: Llama 3.2 1B Q4_K_M at 13.4 tokens/s writing, Qwen2.5 0.5B at 25.5 tokens/s (section 9.1). Not measured: heat and stability over a match, models above 1B, the GPU |

## 3. Plan (one PR each, version +1 each; built back to back after the owner's OK, one phone test at the end)

| Step | Work | Changes play? |
|---:|---|---|
| G1 | **Native architecture and support matrix:** a document covering the JNI boundary, who owns which handle, the threads, memory, the build, the ABI / phone matrix, and the troubleshooting guide extended | No |
| G2 | **Read a GGUF file before loading it:** a bounds-checked metadata reader in Kotlin. It reads the architecture, quantization type, parameter count, trained context length, whether a chat template is present and whether the file is an `mmproj`. The Model page shows these when a model is added or chosen. A broken or unknown file is refused before native code opens it. Pure, unit-tested with made-up files, including broken ones | No |
| G3 | **Native tests in CI:** the engine is also built for the CI computer and runs with a tiny public GGUF model (a few MB). It tests load, answer, streaming, cancel mid-prompt and mid-answer, unload, unload while answering, a broken file and a double unload. CI fails if any of these fails | No |
| G4 | **JNI safety:** a handle registry, so a call on a freed or wrong handle returns an error instead of crashing. Unload waits for a running answer to stop. Native out-of-memory comes back as `NotEnoughMemory`. Covered by G3's native tests and the contract kit | No (only safer) |
| G5 | **Threads and memory:** the speed test also tries 2, 4 and 6 threads, and the KV cache in 8-bit (`q8_0`, about half the context memory). The best thread count per model is remembered, like Phase 7 L9, and the Model page says why | Possibly faster or lighter; decided by the phone test |
| G6 | **Repeatable GGUF benchmark:** the model speed test runs the same fixed questions on a GGUF model as on LiteRT-LM. It records cold and warm runs, reading and writing tokens/s, time to the first word, memory and heat. The results are exported to the Recordings folder and uploaded (debug-data rule) | No |
| G7 | **Newer llama.cpp, in its own PR:** b11900 → the newest tag (b12651 or newer). The API changes are fixed in the engine, then CI and G3's native tests run. It is kept only if the phone test is at least as good, with the same models and G6's benchmark; otherwise the pin goes back to b11900 | Possibly; decided by the phone test |
| G8 | **Experimental GPU for GGUF (Vulkan):** llama.cpp's Vulkan backend for the Mali-G615, behind a Model page switch **off by default**. It falls back to the CPU and is remembered per model (L9). First sub-step: build it in CI (shaders compiled with the NDK's `glslc`) and measure the APK size. If it can't be built, or the phone test is slower than the CPU, the step reports that and the switch stays out | Only with the switch on |
| G11 | **Pictures for both jobs** (owner, 2026-11-23: "all strategic and tactical models use pictures, whether LiteRT-LM or GGUF"). The tactical model loads with its picture part whenever it has one: a `.litertlm` with vision, or a GGUF with its `mmproj`. Today the tactical job always loads text only. **How the tactical job uses pictures** (owner's choice): fight advice stays a fast text question. Every few seconds the tactical model also gets a small picture (the minimap crop), and its short reading is added to the next answers. A model that can't read pictures (Gemma 3 270M, a GGUF without its mmproj) is marked "text only" in the job picker, which says what to add; the owner can still use it. Pure scheduling rule, unit-tested. Built after G4 | Yes: the tactical model reads the minimap every few seconds when it can |
| G9 | **Stability:** load and unload 20 times in a row, checking that the memory comes back. Then one full 5v5 match with a GGUF model doing a job, with memory and heat snapshots every minute. Both are exported and uploaded (debug-data rule) | No |
| G10 | **Phase 8 phone checklist and closing record**, with the supported GGUF model list and its measured figures | No |

G11 is built right after G4, on b11900. G7 comes after G2–G6 and G11, so its results are compared against the known-good b11900. G8 comes after G7, so the GPU work starts on the newest llama.cpp.

## 4. Acceptance criteria (roadmap deliverables)

- [x] Native integration architecture, JNI boundary and supported ABI/device matrix are documented (G1): `docs/PHASE_8_GGUF_NATIVE.md` (v38.19).
- [x] A GGUF file's metadata is read and checked before native code opens it; a broken file is refused with the reason (G2): `inference/GgufInfo` and `GgufArchitectures`, used by `LlamaCppBackend.check` and `GgufLibrary.add` (v38.20). Phone check pending.
- [ ] Native tests run the real engine in CI: load, answer, stream, cancel, unload and errors (G3, G4). **G3 in CI (v38.21):** `tools/native-test` builds `llama_engine.cpp` and the vendored llama.cpp for the CI computer (only llama.cpp's x86 CPU code is copied from the same tag) and runs 21 checks against a tiny random model made with llama.cpp's gguf-py, so no model is downloaded. 21 of 21 passed in this container on 2026-11-23.
- [x] No crash from a wrong handle, unloading during an answer, or running out of memory (G4): handle registry, free waits for running calls, `bad_alloc` → `NotEnoughMemory`; native tests under ASan/UBSan (v38.22).
- [ ] Reproducible benchmarks with model, quantization, threads, cold/warm state and device recorded, on the owner's phone (G5, G6). **In code (v38.24):** `models/GgufBenchmark` (Model page card), rows in `research/gguf_bench.jsonl`, choices in `inference/GgufTuning` (tested). 8-bit KV cache in the engine (`kv8`, q8_0 + flash attention), with a fall-back to 16 bits for models that can't have it (found by the native test: attention heads must be a multiple of 32 wide). Phone measurements pending.
- [ ] The llama.cpp version decision is recorded with its evidence (G7). **Upgrade in code (v38.25):** b11900 → **b12651** (`f5e6915`, 2026-11-23, the newest tag) through `tools/update-llama.sh`, which gives no change when run with b11900. The engine compiles without changes (two deprecated calls kept on purpose). The native test passes 32 of 32 in the container. 16 new model families, none removed. **Kept only if the phone test is as good** (the G6 benchmark on the same models); otherwise the pin goes back.
- [ ] Each acceleration path is benchmarked rather than assumed: CPU threads and KV cache (G5), GPU through Vulkan (G8). **G8 first sub-step done, stopped by the plan's own rule (2026-11-23):** Vulkan builds on a computer but needs glslc, SPIR-V headers and 139 MB of generated shader code, which the owner's phone build (Code on the Go) cannot produce. Findings and three options in `docs/PHASE_8_GGUF_NATIVE.md` §8, waiting for the owner's choice.
- [ ] Both jobs read pictures when their model can (LiteRT-LM vision or GGUF + mmproj); a text-only model is marked as such in the picker (G11). **In code (v38.23):** `ai/TacticalSight` (pure rules tested), switch `Cfg.tacticalSightOn` (off by default), tactical loads with pictures through `Roles.loadLiteRt` / `ProbesWiring.loadFor` / `StepDown.configFor`, readings logged to `research/tactical_sight.jsonl` next to the detector counts (exported from the Debug data page).
- [ ] Memory, heat and stability over a full match are measured on the phone (G9). **In code (v38.26):** `models/GgufStability`: 20 load/unload cycles with the memory after each unload (`research/gguf_stability.jsonl`, `GgufTuning.leakSummary` tested), and one row a minute during a 5v5 with a GGUF job (`research/match_resources.jsonl`). Phone measurements pending.
- [ ] A supported GGUF model list with measured figures, and troubleshooting documentation (G1, G10). **Checklist in Section 7 (v38.28)**; the list is filled from the phone's benchmark.

## 5. Risks

- **Vulkan on Mali:** llama.cpp's Vulkan backend is known to be slow or wrong on some Mali drivers. That is why G8 is experimental, off by default and remembered per model.
- **APK size:** the Vulkan shaders add several MB, measured in G8's first sub-step.
- **Upgrading llama.cpp:** the API changes between tags. G3's native tests catch most breaks before the phone does.
- **A tiny test model in CI:** CI downloads a small public file. If the download fails, CI fails, which shows as a red build and never passes silently.
- **Two picture-reading models at once (G11):** a tactical model with vision is bigger than Gemma 3 270M, and pictures cost time. The tactical picture comes only every few seconds and never holds up fight advice. Phase 7's memory check (L3) warns before a load that may not fit.
- **No device in the cloud:** CI checks the build and the native tests. Speed, heat and the GPU are decided by the phone test.

## 6. Owner decisions (2026-11-23)

1. **G8, GPU (Vulkan):** try it, experimental switch, off by default.
2. **G7, newer llama.cpp:** try it in its own PR, kept only if the phone test is as good.
3. **G3, native tests:** CI may download a tiny public GGUF test model.
4. **Supported GGUF list:** the suggested list (Llama 3.2 1B Q4_K_M, Qwen2.5 0.5B Q4_K_M, Gemma 3 4B Q4_K_M with its mmproj), plus a small picture-reading tactical model for G11.
5. **G5, 8-bit KV cache:** on by default only if the phone test shows no loss.
6. **Pictures for both jobs (new, G11):** the tactical job gets a picture now and then (the minimap crop every few seconds), not with every fight answer. A model without pictures gets a warning and is still allowed.

## 7. Phone checklist for the whole phase (owner tests it all at once)

After `git pull` on `sphynx-test-branch` and a rebuild (v38.28). The first build takes longer: llama.cpp is new (b12651).

1. **GGUF details (G2):** Model page → "Which model does which job" → a job. Each GGUF model shows a second line, for example "llama · Q4_K_M · 1B · trained for 131,072 tokens · chat format", and a third: "reads pictures (mmproj found)" or "text only: add its mmproj file". Adding a broken or half-downloaded .gguf is refused with the reason.
2. **Newer llama.cpp (G7):** load the same GGUF models as before (Llama 3.2 1B Q4_K_M, Qwen2.5 0.5B Q4_K_M) and ask the test question. The answers should make sense, and the speed should be at least the 2026-11-14 figures (13.4 and 25.5 tokens/s writing).
3. **GGUF benchmark (G5, G6):** Model page → GGUF benchmark → "Run the GGUF benchmark" on each of those models, with the game closed. It takes a few minutes per model; note the result line (writing speed with 2, 4 and 6 threads, and the 8-bit cache with "no loss" or "a loss").
4. **Stability (G9):** "Load and unload 20 times" on one model. The line should end with "comes back".
5. **Pictures for both jobs (G11):** turn on "Small tactical model in fights" and "Tactical model reads the minimap (pictures)", with a tactical model that reads pictures (a GGUF with its mmproj, for example Gemma 3 4B + `mmproj-gemma-3-4b-it-f16.gguf`, or a vision .litertlm). The toast should say "Tactical model: reads pictures". Play a 5v5: fight advice should not be slower than before. With a text-only tactical model, the toast says "text only".
6. **A 5v5 with a GGUF job (G9):** give one job a GGUF model and play a full 5v5. No crash, no app close.
7. **Safety (G4):** while a GGUF answer is running (test question), press Stop, unload, and load again. No crash.
8. **Upload:** Debug data page → export research files and upload. That uploads `gguf_bench.jsonl`, `gguf_stability.jsonl`, `match_resources.jsonl` and `tactical_sight.jsonl`. Then tell me "all test are good", or what failed, and your choice for G8 (Vulkan): 1, 2 or 3.

## 8. Closing record

Filled in after the owner's phone test: the supported GGUF model list with measured figures (from `gguf_bench.jsonl`), the llama.cpp decision (keep b12651 or go back), the 8-bit cache decision, the tactical picture finding (from `tactical_sight.jsonl`), and the G8 choice.
