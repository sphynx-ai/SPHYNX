#!/bin/bash
# PHASE 8, step G7: copies a llama.cpp release into app/src/main/cpp/llama.cpp with exactly the parts the app vendors
# (app/src/main/cpp/README.md, "Updating llama.cpp"). Usage: tools/update-llama.sh <tag>, e.g. b12651.
# Then: set LLAMA_PIN_TAG / LLAMA_PIN_COMMIT in app/src/main/cpp/CMakeLists.txt (the script prints them), copy the model
# family list (inference/GgufArchitectures.kt, GgufInfoTest says what changed), fix the engine for API changes, and run
# tools/native-test/run.sh --tiny.
set -euo pipefail
TAG=${1:?usage: tools/update-llama.sh <tag>}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DST="$ROOT/app/src/main/cpp/llama.cpp"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$TMP/src"
SRC="$TMP/src"
COMMIT=$(git -C "$SRC" rev-parse HEAD)
rm -rf "$DST"; mkdir -p "$DST/ggml/src" "$DST/tools" "$DST/vendor"
cp "$SRC/LICENSE" "$DST/"
cp -r "$SRC/include" "$SRC/src" "$DST/"
# ggml: the build files, headers, the top-level sources and the CPU backend for ARM only; no other backend
cp "$SRC/ggml/CMakeLists.txt" "$DST/ggml/"
cp -r "$SRC/ggml/cmake" "$SRC/ggml/include" "$DST/ggml/"
find "$SRC/ggml/src" -maxdepth 1 -type f -exec cp {} "$DST/ggml/src/" \;
cp -r "$SRC/ggml/src/ggml-cpu" "$DST/ggml/src/"
rm -rf "$DST"/ggml/src/ggml-cpu/arch/{x86,powerpc,riscv,s390,wasm,loongarch} "$DST/ggml/src/ggml-cpu/spacemit"
# pictures (Phase 6 S8): the multimodal library without its tests, sample media, legacy converters and command-line tools
cp -r "$SRC/tools/mtmd" "$DST/tools/"
M="$DST/tools/mtmd"
rm -rf "$M"/README*.md "$M/debug/mtmd-debug.cpp" "$M"/debug/*.md "$M/deprecation-warning.cpp" "$M/legacy-models" \
  "$M/mtmd-cli.cpp" "$M/requirements.txt" "$M"/test-* "$M/tests.sh" "$M/tests"
for v in stb hash miniaudio sheredom; do cp -r "$SRC/vendor/$v" "$DST/vendor/"; done
echo "copied llama.cpp $TAG"
echo "set(LLAMA_PIN_TAG    \"$TAG\")"
echo "set(LLAMA_PIN_COMMIT \"${COMMIT:0:7}\")   # $COMMIT"
