// PHASE 3a SPIKE: the portable part (no JNI, no Android), so it can also be checked on a computer.
#include "llama_spike.h"
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>
#include "llama.h"

static long long nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

static std::string jsonEscape(const std::string & s) {
    std::string o;
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default: if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; } else o += (char) c;
        }
    }
    return o;
}

static bool g_init = false;

void sphynx_llama_init(void (*log)(int level, const char * text)) {
    if (g_init) return;
    static void (*s_log)(int, const char *) = nullptr;
    s_log = log;
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        if (s_log && level >= GGML_LOG_LEVEL_WARN) s_log((int) level, text);
    }, nullptr);
    llama_backend_init();
    g_init = true;
}

std::string sphynx_llama_info() {
    return std::string("llama.cpp ") + LLAMA_PIN_TAG + " (" + LLAMA_PIN_COMMIT + ") | " + llama_print_system_info();
}

std::string sphynx_llama_run(const std::string & path, const std::string & prompt, int maxTokens, int threads, int nCtx) {
    std::string out, err, desc;
    long long t0 = nowMs(), loadMs = 0, promptMs = 0, genMs = 0;
    int nPrompt = 0, nGen = 0;
    uint64_t sizeBytes = 0;
    bool templated = false;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    if (!model) { err = "the model could not be loaded (not a GGUF, unsupported architecture, or not enough memory)"; goto done; }
    {
        char buf[256];
        llama_model_desc(model, buf, sizeof buf); desc = buf;
        sizeBytes = llama_model_size(model);

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t) nCtx; cp.n_batch = (uint32_t) nCtx;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        ctx = llama_init_from_model(model, cp);
        if (!ctx) { err = "the context could not be created (memory?)"; goto done; }
        loadMs = nowMs() - t0;

        const llama_vocab * vocab = llama_model_get_vocab(model);
        // the model's own chat format when it has one (llama.cpp's built-in templates), else the raw prompt
        std::string text = prompt;
        const char * tmpl = llama_model_chat_template(model, nullptr);
        if (tmpl) {
            llama_chat_message msg{"user", prompt.c_str()};
            std::vector<char> f(prompt.size() * 2 + 512);
            int n = llama_chat_apply_template(tmpl, &msg, 1, true, f.data(), (int32_t) f.size());
            if (n > (int) f.size()) { f.resize(n); n = llama_chat_apply_template(tmpl, &msg, 1, true, f.data(), (int32_t) f.size()); }
            if (n > 0) { text.assign(f.data(), n); templated = true; }
        }
        int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
        std::vector<llama_token> toks(n);
        if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, true, true) < 0) { err = "tokenize failed"; goto done; }
        nPrompt = n;
        if (nPrompt + maxTokens > nCtx) { err = "prompt too long for the context"; goto done; }

        smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

        long long tp = nowMs();
        llama_batch batch = llama_batch_get_one(toks.data(), (int32_t) toks.size());
        if (llama_decode(ctx, batch) != 0) { err = "prompt decode failed"; goto done; }
        promptMs = nowMs() - tp;

        long long tg = nowMs();
        llama_token tok;
        for (int i = 0; i < maxTokens; i++) {
            tok = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;
            char piece[256];
            int k = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, false);
            if (k > 0) out.append(piece, k);
            nGen++;
            batch = llama_batch_get_one(&tok, 1);
            if (llama_decode(ctx, batch) != 0) { err = "decode failed"; break; }
        }
        genMs = nowMs() - tg;
    }
done:
    if (smpl) llama_sampler_free(smpl);
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);

    char nums[512];
    snprintf(nums, sizeof nums,
             "\"loadMs\":%lld,\"promptTokens\":%d,\"promptMs\":%lld,\"genTokens\":%d,\"genMs\":%lld,\"sizeBytes\":%llu,\"threads\":%d,\"templated\":%s",
             loadMs, nPrompt, promptMs, nGen, genMs, (unsigned long long) sizeBytes, (int) threads, templated ? "true" : "false");
    std::string json = std::string("{\"ok\":") + (err.empty() ? "true" : "false") + ",\"error\":\"" + jsonEscape(err) +
                       "\",\"model\":\"" + jsonEscape(desc) + "\"," + nums + ",\"text\":\"" + jsonEscape(out) + "\"}";
    return json;
}
