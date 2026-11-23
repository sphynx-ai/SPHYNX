// PHASE 8: the portable llama.cpp engine behind the GGUF backend (no JNI, no Android), so it can be
// compiled and checked on a computer too. One loaded model with one context; one answer at a time
// (the Kotlin backend runs every call on its own single thread); cancel() may come from any thread.
#pragma once
#include <atomic>
#include <cstdint>
#include <string>

struct llama_model;
struct llama_context;
struct mtmd_context;

struct SphynxLlama {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * vision = nullptr;     // Phase 6 S8: the multimodal projector (mmproj), when one is loaded
    std::atomic<bool> cancel{false};
    std::atomic<int> busy{0};            // Phase 8 G4: calls running on this handle; free waits for them
    int nCtx = 0;
    int threads = 0;
};

// once per process; log receives llama.cpp warnings and errors (may be null)
void sphynx_llama_init(void (*log)(int level, const char * text));

// "llama.cpp <tag> (<commit>) | <CPU features>"
std::string sphynx_llama_info();

// loads the GGUF at path with a context of nCtx tokens on threads CPU threads; null on failure, and
// err says why (llama.cpp's own last warnings and errors included)
SphynxLlama * sphynx_llama_load(const std::string & path, int nCtx, int threads, std::string & err);

// Phase 6 S8: loads the model's multimodal projector (a separate mmproj GGUF) so answers can take a picture; false on
// failure, and err says why. The text model keeps working without it.
bool sphynx_llama_load_projector(SphynxLlama * h, const std::string & path, int threads, std::string & err);

// JSON: model (description), sizeBytes, params, nCtx, threads, templated (has a chat format)
std::string sphynx_llama_describe(const SphynxLlama * h);

// one answer: prompt in the model's chat format when it has one, up to maxTokens. temperature <= 0 is
// greedy. onPiece gets the answer's bytes as they are made (a UTF-8 character can be split between
// two pieces). Returns JSON: ok, error, stop ("eos" | "length" | "cancelled" | "error"),
// promptTokens, promptMs, genTokens, genMs, firstTokenMs.
// image / imageLen: an encoded picture (JPEG, PNG) for a model with a projector loaded (Phase 6 S8); null = text only.
std::string sphynx_llama_generate(SphynxLlama * h, const std::string & prompt, int maxTokens, float temperature,
                                  uint32_t seed, void (*onPiece)(const char * bytes, int n, void * user), void * user,
                                  const unsigned char * image = nullptr, size_t imageLen = 0);

// stops the answer that is running (a new answer starts with the flag cleared); safe from any thread
void sphynx_llama_cancel(SphynxLlama * h);

// Phase 8 G4: every call checks its handle against the handles this engine made and has not freed yet, so a late cancel,
// a call on a freed handle or a second free returns an error (or does nothing) instead of crashing. free() stops a running
// answer and waits for it before freeing. Out of memory comes back as "not enough memory ...", never as a crash.
bool sphynx_llama_alive(const SphynxLlama * h);

void sphynx_llama_free(SphynxLlama * h);
