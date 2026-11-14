# Native code: llama.cpp (Phase 3a spike)

| | |
|---|---|
| Upstream | https://github.com/ggml-org/llama.cpp (MIT, see `llama.cpp/LICENSE`) |
| Pinned | tag **b11900**, commit `74b104c9ba5717116b7e65e0cd35397778653304` (26 Oct 2026) |
| Built with | NDK **29.0.14206865**, CMake **3.22.1** (the versions on the owner's Code on the Go) |
| ABI | arm64-v8a only, CPU backend only, `-march=armv8.2-a+dotprod+fp16` |
| Output | `libsphynx_llama.so` (about 6.4 MB) |

**What is vendored (copied unchanged from the pinned commit):**
- `include/`;
- `src/`;
- `ggml/CMakeLists.txt`, `ggml/cmake/`, `ggml/include/`;
- the top-level files of `ggml/src/` and `ggml/src/ggml-cpu/`.

**Left out:**
- every GPU / NPU backend;
- the non-ARM CPU code (`ggml-cpu/arch/{x86,powerpc,riscv,s390,wasm,loongarch}`, `spacemit`);
- tools, tests, examples, `vendor/`, `common/`;
- llama.cpp's own top-level `CMakeLists.txt`. `CMakeLists.txt` here sets the few variables it would have set.

**Our files:**
- `CMakeLists.txt`: build settings;
- `llama_spike.cpp` / `.h`: portable load → ask → free, returning a JSON report;
- `llama_jni.cpp`: the Android JNI bridge.

**To update llama.cpp:**
1. copy the same folders from a newer tag;
2. update the tag and commit here and in `CMakeLists.txt`;
3. re-run the unit tests and the on-phone GGUF test.
