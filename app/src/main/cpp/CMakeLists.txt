# PHASE 8: the GGUF backend. Builds llama.cpp (pinned, CPU only, arm64-v8a) and the app's engine
# (llama_engine.cpp + llama_jni.cpp) into one JNI library, libsphynx_llama.so. Same NDK (29.0.14206865) and CMake (3.22.1)
# as the owner's Code on the Go, so the phone can build it from source.
cmake_minimum_required(VERSION 3.18)
project(sphynx_llama C CXX)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# what llama.cpp's own top-level CMakeLists normally sets (it is not vendored: it pulls in tools,
# tests and third-party code the app does not need)
set(LLAMA_PIN_TAG    "b11900")
set(LLAMA_PIN_COMMIT "74b104c")
set(LLAMA_VERSION_MAJOR 0)
set(LLAMA_VERSION_MINOR 7)
set(LLAMA_VERSION_PATCH 0)
set(LLAMA_VERSION_BASE "${LLAMA_VERSION_MAJOR}.${LLAMA_VERSION_MINOR}.${LLAMA_VERSION_PATCH}")
set(LLAMA_VERSION "${LLAMA_VERSION_BASE}-${LLAMA_PIN_TAG}")
set(LLAMA_BUILD_NUMBER 11900)
set(LLAMA_BUILD_COMMIT ${LLAMA_PIN_COMMIT})
set(GGML_BUILD_NUMBER  ${LLAMA_BUILD_NUMBER})
set(GGML_BUILD_COMMIT  ${LLAMA_PIN_COMMIT})
function(llama_add_compile_flags)
endfunction()

# always optimized: Gradle's debug APK asks for a Debug native build, which would make llama.cpp
# many times slower (unoptimized matrix code)
set(CMAKE_BUILD_TYPE Release)

# one static build inside our .so; CPU backend only; no OpenMP (ggml's own thread pool)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        ON  CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
# ARMv8.2 with dot product and fp16: every arm64 phone core since Cortex-A55 / A75 (2017).
# Older cores (Cortex-A53/A73) would stop with an illegal instruction, so the app checks the CPU
# (LlamaCpu, /proc/cpuinfo) before it loads this library at all.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_DIR}/ggml ggml)
add_subdirectory(${LLAMA_DIR}/src  llama)

add_library(sphynx_llama SHARED llama_jni.cpp llama_engine.cpp)
target_compile_definitions(sphynx_llama PRIVATE LLAMA_PIN_TAG="${LLAMA_PIN_TAG}" LLAMA_PIN_COMMIT="${LLAMA_PIN_COMMIT}")
target_link_libraries(sphynx_llama PRIVATE llama ggml log android)
# smaller library: drop unused code; 16 KB pages (Android 15+ devices)
target_link_options(sphynx_llama PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
