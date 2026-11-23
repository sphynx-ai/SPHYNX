# Native code: llama.cpp (Phase 8 GGUF backend)

| | |
|---|---|
| Upstream | https://github.com/ggml-org/llama.cpp (MIT, see `llama.cpp/LICENSE`) |
| Pinned | tag **b12651**, commit `f5e6915c28f10330885fcb9243a93ae98d3115ab` (23 Nov 2026), from b11900 by Phase 8 G7 (kept only if the phone test is as good; the earlier pin was `74b104c`) |
| Built with | NDK **29.0.14206865**, CMake **3.22.1** (the versions on the owner's Code on the Go; CI installs the same) |
| ABI | arm64-v8a only, CPU backend only, `-march=armv8.2-a+dotprod+fp16` |
| Output | `libsphynx_llama.so` (about 6.4 MB) |

## Files

- `CMakeLists.txt`: build settings (always an optimized build, even for the debug APK).
- `llama_engine.cpp` / `.h`: the portable engine. One loaded model and context; one answer at a time; streaming pieces; cancel from any thread (also stops a long prompt mid-computation, via llama.cpp's abort callback); chat format from the model; a light repetition penalty; llama.cpp's own errors kept for the load error.
- `llama_jni.cpp`: the JNI bridge to `inference/LlamaJni` (Kotlin). Text crosses as UTF-8 bytes, never Java strings.

The Kotlin side: `inference/LlamaCppBackend` (the shared `InferenceBackend` contract), `inference/LlamaCpu` (CPU check), `inference/Utf8Stream`, `inference/GgufFile`, `models/GgufLibrary` (adding models, the test question).

## Supported phones

| Phone | Status |
|---|---|
| arm64-v8a with dot product and fp16 (ARMv8.2+: Cortex-A55/A75 and newer, 2017+) | Supported. VERIFIED on the owner's phone 2026-11-14 (see the roadmap, Section 9.1) |
| arm64-v8a without them (Cortex-A53/A73 only) | Refused before the library loads (`LlamaCpu`), with the reason on the Model page. It would otherwise stop with an illegal instruction. |
| 32-bit ARM, x86 | Not built. The Model page says so. |
| GPU / Vulkan / NPU | Not built (CPU only). |

## Troubleshooting

- **"... did not load: the model could not be loaded" plus lines from llama.cpp:** those lines are llama.cpp's reason (unknown architecture: the model is newer than the pinned llama.cpp; a bad header: a broken file).
- **"not enough memory":** the app refuses a model that would not fit in the free memory (file + about 64 KB per context token + 128 MB). Close other apps or use a smaller quantization (Q4_K_M).
- **A web page or a Git LFS pointer saved as .gguf:** named when the model is added. Download it again with the file's download link.
- **Slow answers:** 4 threads is the default (the big cores). A Q4_K_M 1B model gives about 13 tokens/s on the owner's phone.
- **Picked files:** models are copied into the app (`files/gguf`). On the owner's phone, reading a picked file in place failed while the copy loaded.

## Updating llama.cpp

**Vendored (copied unchanged from the pinned commit):** `include/`, `src/`, `ggml/CMakeLists.txt`, `ggml/cmake/`, `ggml/include/`, and the top-level files of `ggml/src/` and `ggml/src/ggml-cpu/`.

**Also vendored for pictures (Phase 6 S8, 2026-11-22, same commit):**
- `tools/mtmd/`: llama.cpp's multimodal library. It keeps its `CMakeLists.txt`, the sources, `models/` and `debug/mtmd-debug.h`; the tests, sample media, legacy models and command-line tools are left out.
- `vendor/stb` (image decoding), `vendor/hash`, `vendor/miniaudio` (compiled in, no audio used) and `vendor/sheredom` (only for video, which is off).
- A GGUF model reads pictures through its separate `mmproj` GGUF file (`inference/MmprojPairing` pairs them by name).

**Left out:** every GPU / NPU backend; the non-ARM CPU code (`ggml-cpu/arch/{x86,powerpc,riscv,s390,wasm,loongarch}`, `spacemit`); tools other than `tools/mtmd`, tests, examples, the rest of `vendor/`, `common/`; llama.cpp's own top-level `CMakeLists.txt` (`CMakeLists.txt` here sets the few variables it would have set).

1. Run `tools/update-llama.sh <tag>` (Phase 8 G7): it copies exactly the parts listed above from a newer tag and prints the new pin. Running it with the current tag must give no change in git.
2. Update the tag and commit here and in `CMakeLists.txt`.
3. Check the engine still compiles against the new `llama.h` (the API changes between tags).
4. Copy the model family list into `inference/GgufArchitectures.kt` (GgufInfoTest says when it differs), run `tools/native-test/run.sh --tiny`, then the unit tests, then load a model and ask the test question on the phone.

**Deprecated calls (b12651):** `llama_decode` and `llama_batch_get_one` are deprecated in favour of `llama_process` / `llama_batch_ext`. They still work, and the engine keeps them so that the b11900 → b12651 comparison changes only llama.cpp. The move to the new calls is left for a later update.
