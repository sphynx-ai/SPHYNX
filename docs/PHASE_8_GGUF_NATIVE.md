# Phase 8 — GGUF / llama.cpp native integration: architecture and support matrix (G1)

**Date:** 2026-11-23. Read from the code at v38.18. Device facts are from the owner's phone tests, as named in each row. The troubleshooting guide is in `app/src/main/cpp/README.md`.

## 1. Layers

| Layer | File | Job |
|---|---|---|
| Kotlin contract | `inference/LlamaCppBackend.kt` | The shared `InferenceBackend`: checks, load, ask, cancel, unload, status. Every call runs on its own single thread `llama-backend`; time-outs come from `llama-timeout` |
| Kotlin → native seam | `inference/LlamaNative` (interface), `inference/LlamaJni` (the real one) | A fake engine replaces it in the unit tests. `LlamaCpu` refuses phones without dot product / fp16 **before** the library loads |
| JNI bridge | `cpp/llama_jni.cpp` | Turns Java arguments into C++ ones. Text crosses as UTF-8 **bytes**, never Java strings, because a piece can end halfway through a character. Load errors are kept under a mutex |
| Portable engine | `cpp/llama_engine.cpp` / `.h` | No JNI and no Android, so it can also be built on a computer (G3). One model and one context per handle, chat template, sampler, streaming, abort callback |
| llama.cpp | `cpp/llama.cpp/` | Vendored at tag **b12651**, commit `f5e6915` (G7, v38.25; was b11900 / `74b104c`): `ggml` CPU backend, `src/`, `tools/mtmd` for pictures |

## 2. Ownership and threads

- **The handle** is a `SphynxLlama*` passed to Kotlin as a `jlong`. `LlamaCppBackend` owns it: it is made in `load`, freed in `unload` or before the next `load`, always on `llama-backend`.
- **One answer at a time:** `generate` runs on `llama-backend` and blocks it. The pieces reach Kotlin through `onPiece` on the same thread. The JNI frees each piece's byte array, because an answer has hundreds of them.
- **Cancel from any thread:** `sphynx_llama_cancel` only sets an atomic flag. llama.cpp's abort callback reads it, so a cancel also stops a long prompt mid-computation.
- **Handle safety (G4, v38.22):**
  - The engine keeps the set of handles it made and has not freed (`sphynx_llama_alive`). Every call checks its handle first, so a late cancel, a call on a freed handle, a second free, or a pointer the engine never made returns an error or does nothing.
  - `free` takes the handle out of the set, sets the cancel flag and **waits** for running calls (a `busy` count) before freeing.
  - Out of memory (`std::bad_alloc`) while loading, reading pictures or answering comes back as "not enough memory …", which Kotlin maps to `NotEnoughMemory`.
  - Kotlin's `unload` already cancelled a running answer before freeing.
  - Checked by the native test under AddressSanitizer and UndefinedBehaviorSanitizer: 30 of 30 passed in this container on 2026-11-23, and the same runs in CI.

## 3. Memory

- **Before loading:** `GgufFile.estimatedMemoryBytes` = file size + about 64 KB per context token + 128 MB. The backend refuses a load that does not fit in free memory. Phase 7's L3 warning covers loads by hand.
- **In memory:** the model is memory-mapped by llama.cpp (the default), so the weights are file pages Android can drop and read again. The context (KV cache) is ordinary memory, 16-bit today; G5 tries 8-bit.
- **Pictures:** the mmproj is loaded into the same handle (`vision`) and freed with it.

## 4. Build

| | |
|---|---|
| Toolchain | NDK 29.0.14206865, CMake 3.22.1 (the owner's Code on the Go and CI use the same) |
| Flags | Always an optimized build (`Release`), static ggml, `-march=armv8.2-a+dotprod+fp16`, `--gc-sections`, 16 KB page alignment (`max-page-size=16384`, needed on Android 15+ phones with 16 KB pages) |
| Output | `libsphynx_llama.so`, arm64-v8a only |
| Left out | Every GPU / NPU backend (G8 adds Vulkan as an experiment), non-ARM CPU code, llama.cpp's tools except `mtmd`, tests, examples |

## 5. Support matrix

| Phone / ABI | Status | Evidence |
|---|---|---|
| arm64-v8a with dot product and fp16 (ARMv8.2+, Cortex-A55/A75 and newer, about 2017+) | **Supported, CPU** | VERIFIED on the owner's POCO X6 Pro (MT6897, Android 16), 2026-11-14 and the Phase 6 phone test |
| arm64-v8a without them (Cortex-A53/A73 only) | Refused before the library loads, with the reason | `LlamaCpu` unit tests |
| 32-bit ARM, x86, x86_64 | Not built; the Model page says so | `abiFilters("arm64-v8a")` |
| GPU (Vulkan, Mali-G615) | Not built yet; experiment in G8 | — |
| NPU | Not possible: llama.cpp has no MediaTek NPU backend | — |

## 6. Models (measured on the owner's phone)

| Model | Quantization | Size | Writing | Pictures | Evidence |
|---|---|---|---|---|---|
| Llama 3.2 1B Instruct | Q4_K_M | 762 MB | 13.4 tokens/s | no | 2026-11-14, roadmap section 9.1 |
| Qwen2.5 0.5B Instruct | Q4_K_M | 462 MB | 25.5 tokens/s | no | 2026-11-14 (answer repeated itself) |
| Gemma 3 4B + mmproj | Q4_K_M | — | — | yes (S8) | Not yet measured; G6 and G10 |

G6's benchmark fills this table on the phone; G10 publishes the supported list.

## 7. Native tests (G3)

`tools/native-test/run.sh --tiny` builds the app's `llama_engine.cpp` with the vendored llama.cpp for a Linux x86-64 computer and runs `engine_test`. The CI job `native-tests` (workflow "Android build") runs it on every pull request. Only llama.cpp's x86 CPU code (`ggml-cpu/arch/x86`) is copied in, from the same tag, and never committed.

The model is a tiny random llama-family GGUF (about 0.5 MB) made by `make_tiny_model.py` with llama.cpp's own `gguf-py`, so nothing is downloaded. Its answers are nonsense: the test checks the engine, not the words.

The checks: the info line; a missing, broken or web-page file; an answer, the free and the cancel without a model; load; describe; an answer; streaming; the stop reason; greedy answers repeating exactly; a cancel from inside and from another thread; the next answer after a cancel; a prompt longer than the context; a picture without an mmproj; a missing mmproj; answering after a failed mmproj; and 20 loads and frees in a row.

## 8. GPU through Vulkan (G8, first sub-step): finding, 2026-11-23

Built in this container from llama.cpp b12651 (`GGML_VULKAN=ON`, Ubuntu's glslc 2023.8, Vulkan and SPIR-V headers):

| | |
|---|---|
| Build needs | `glslc` (the shader compiler) and the SPIR-V headers (`find_package(Vulkan COMPONENTS glslc REQUIRED)`, `find_package(SPIRV-Headers CONFIG REQUIRED)`), Vulkan's C++ header `vulkan.hpp`, and a host program, `vulkan-shaders-gen`, built and run during the build |
| Generated | **1,342 SPIR-V shaders (35 MB)**, turned into **162 C++ files, 139 MB** |
| Library | `libggml-vulkan.a` **36 MB** (x86-64, not stripped); the phone library would grow by roughly the 35 MB of shaders |
| Result here | Builds (348 build steps) |

**Why it is not in the app yet:** the owner builds the app on the phone (Code on the Go).
- The NDK ships `glslc` only for desktop computers (Linux x86-64, macOS, Windows), so the phone cannot generate the shaders.
- Committing the 139 MB of generated C++ instead would make the phone compile it on every clean build, and would add that much to the repository.
- llama.cpp's Vulkan backend is also known to be slow or wrong on some Mali drivers, so even a working build has to prove itself.

**Options for the owner:**
1. Keep GGUF on the CPU; G8 stops here.
2. Build a Vulkan test APK in CI only (GitHub's computers have glslc), download and install it for one benchmark, and keep the phone build CPU-only unless the GPU wins.
3. Generate the shaders in CI and commit only the 35 MB of SPIR-V files, with a small change so llama.cpp reads them instead of the generated C++. That is a local change to llama.cpp that every update has to carry.
