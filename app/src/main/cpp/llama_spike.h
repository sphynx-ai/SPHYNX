// PHASE 3a SPIKE: portable llama.cpp calls used by the JNI bridge (and by a computer-side check).
#pragma once
#include <string>

// once per process; log receives llama.cpp warnings and errors (may be null)
void sphynx_llama_init(void (*log)(int level, const char * text));
// "llama.cpp <tag> (<commit>) | <CPU features>"
std::string sphynx_llama_info();
// load the GGUF, ask prompt (in the model's chat format when it has one), greedy answer of up to
// maxTokens, free everything; returns a JSON report (ok, error, model, timings, text)
std::string sphynx_llama_run(const std::string & path, const std::string & prompt, int maxTokens, int threads, int nCtx);
