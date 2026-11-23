#!/bin/bash
# PHASE 8, step G3: builds and runs the native engine test. Usage: tools/native-test/run.sh [model.gguf | --tiny]
# --tiny makes a tiny random model with llama.cpp's gguf-py (needs numpy). Without a model only the checks that need
# none run; CI uses --tiny and sets REQUIRE_MODEL=1.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
LLAMA="$ROOT/app/src/main/cpp/llama.cpp"
TAG=$(sed -n 's/^set(LLAMA_PIN_TAG *"\([^"]*\)").*/\1/p' "$ROOT/app/src/main/cpp/CMakeLists.txt")
BUILD=${BUILD_DIR:-"$HERE/build"}
mkdir -p "$BUILD"
# the x86 CPU code from the same llama.cpp tag (the app vendors only the ARM part); never committed (.gitignore)
X86="$LLAMA/ggml/src/ggml-cpu/arch/x86"
if [ ! -f "$X86/quants.c" ]; then
  rm -rf "$BUILD/upstream"
  git clone -q --depth 1 --filter=blob:none --sparse --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$BUILD/upstream"
  git -C "$BUILD/upstream" sparse-checkout set ggml/src/ggml-cpu/arch/x86 gguf-py
  cp -r "$BUILD/upstream/ggml/src/ggml-cpu/arch/x86" "$X86"
fi
MODEL=${1:-}
if [ "$MODEL" = "--tiny" ]; then
  [ -d "$BUILD/upstream/gguf-py" ] || {
    rm -rf "$BUILD/upstream"
    git clone -q --depth 1 --filter=blob:none --sparse --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$BUILD/upstream"
    git -C "$BUILD/upstream" sparse-checkout set gguf-py; }
  MODEL="$BUILD/tiny.gguf"
  python3 "$HERE/make_tiny_model.py" "$BUILD/upstream/gguf-py" "$MODEL"
fi
# SANITIZE=1: AddressSanitizer + UndefinedBehaviorSanitizer (G4: a use after free or a double free stops the test)
FLAGS=""
[ "${SANITIZE:-0}" = "1" ] && FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer"
cmake -S "$HERE" -B "$BUILD/out" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_CXX_FLAGS="$FLAGS" \
  -DCMAKE_EXE_LINKER_FLAGS="$FLAGS" > /dev/null
cmake --build "$BUILD/out" --target engine_test -j "$(nproc)"
"$BUILD/out/engine_test" "$MODEL"
