// PHASE 8: the portable llama.cpp engine behind the GGUF backend (no JNI, no Android), so it can be
// compiled and checked on a computer too. One loaded model with one context; one answer at a time
// (the Kotlin backend runs every call on its own single thread); cancel() may come from any thread.
#pragma once
#include <atomic>
#include <cstdint>
#include <string>

struct llama_model;
struct llama_context;

struct SphynxLlama {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
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

// JSON: model (description), sizeBytes, params, nCtx, threads, templated (has a chat format)
std::string sphynx_llama_describe(const SphynxLlama * h);

// one answer: prompt in the model's chat format when it has one, up to maxTokens. temperature <= 0 is
// greedy. onPiece gets the answer's bytes as they are made (a UTF-8 character can be split between
// two pieces). Returns JSON: ok, error, stop ("eos" | "length" | "cancelled" | "error"),
// promptTokens, promptMs, genTokens, genMs, firstTokenMs.
std::string sphynx_llama_generate(SphynxLlama * h, const std::string & prompt, int maxTokens, float temperature,
                                  uint32_t seed, void (*onPiece)(const char * bytes, int n, void * user), void * user);

// stops the answer that is running (a new answer starts with the flag cleared); safe from any thread
void sphynx_llama_cancel(SphynxLlama * h);

void sphynx_llama_free(SphynxLlama * h);
