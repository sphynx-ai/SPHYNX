// PHASE 8: the portable llama.cpp engine behind the GGUF backend (see llama_engine.h).
#include "llama_engine.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

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

// llama.cpp's own warnings and errors since the last clear: when a model does not load, they say why
// (bad header, unknown architecture, out of memory), not just "failed"
static std::mutex g_logMutex;
static std::string g_log;
static bool g_init = false;

static void logClear() { std::lock_guard<std::mutex> l(g_logMutex); g_log.clear(); }
static std::string logTail(size_t lines) {
    std::lock_guard<std::mutex> l(g_logMutex);
    std::vector<std::string> out;
    size_t end = g_log.size();
    while (end > 0 && out.size() < lines) {
        size_t start = g_log.rfind('\n', end - 1);
        start = (start == std::string::npos) ? 0 : start + 1;
        std::string line = g_log.substr(start, end - start);
        // keep lines with words (progress dots and blank lines are left out)
        if (std::any_of(line.begin(), line.end(), [](char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }))
            out.insert(out.begin(), line.size() > 220 ? line.substr(0, 220) : line);
        if (start == 0) break;
        end = start - 1;
    }
    std::string s;
    for (auto & l : out) { if (!s.empty()) s += "\n"; s += l; }
    return s;
}

// ---- Phase 8 G4: the handles this engine made and has not freed ----
static std::mutex g_regMutex;
static std::unordered_set<const SphynxLlama *> g_live;

bool sphynx_llama_alive(const SphynxLlama * h) {
    std::lock_guard<std::mutex> l(g_regMutex);
    return h && g_live.count(h) > 0;
}

// a call starts on h: false when h is not a live handle. freshAnswer clears the cancel flag under the same lock, so a
// free() that comes right after still stops the answer.
static bool enter(SphynxLlama * h, bool freshAnswer = false) {
    std::lock_guard<std::mutex> l(g_regMutex);
    if (!h || !g_live.count(h)) return false;
    if (freshAnswer) h->cancel.store(false);
    h->busy.fetch_add(1);
    return true;
}
static void leave(SphynxLlama * h) { h->busy.fetch_sub(1); }

void sphynx_llama_init(void (*log)(int level, const char * text)) {
    if (g_init) return;
    static void (*s_log)(int, const char *) = nullptr;
    s_log = log;
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        // a continuation piece (progress dots, the rest of a line) belongs to the message before it
        static ggml_log_level last = GGML_LOG_LEVEL_INFO;
        if (level == GGML_LOG_LEVEL_CONT) level = last; else last = level;
        if (level < GGML_LOG_LEVEL_WARN || !text) return;
        {
            std::lock_guard<std::mutex> l(g_logMutex);
            g_log += text;
            if (g_log.size() > 8000) g_log.erase(0, g_log.size() - 8000);
        }
        if (s_log) s_log((int) level, text);
    }, nullptr);
    llama_backend_init();
    g_init = true;
}

std::string sphynx_llama_info() {
    return std::string("llama.cpp ") + LLAMA_PIN_TAG + " (" + LLAMA_PIN_COMMIT + ") | " + llama_print_system_info();
}

SphynxLlama * sphynx_llama_load(const std::string & path, int nCtx, int threads, std::string & err) {
    logClear();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                       // CPU backend only in this build
    llama_model * model = nullptr;
    try { model = llama_model_load_from_file(path.c_str(), mp); }
    catch (const std::bad_alloc &) { err = "not enough memory to load this model"; return nullptr; }
    if (!model) {
        err = "the model could not be loaded";
        std::string t = logTail(4);
        if (!t.empty()) err += "\n" + t;
        return nullptr;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = (uint32_t) std::min(nCtx, 512);
    cp.n_threads = threads; cp.n_threads_batch = threads;
    llama_context * ctx = nullptr;
    try { ctx = llama_init_from_model(model, cp); } catch (const std::bad_alloc &) { ctx = nullptr; }
    if (!ctx) {
        llama_model_free(model);
        err = "the context could not be created (not enough memory for " + std::to_string(nCtx) + " tokens?)";
        std::string t = logTail(4);
        if (!t.empty()) err += "\n" + t;
        return nullptr;
    }
    auto * h = new SphynxLlama();
    h->model = model; h->ctx = ctx; h->nCtx = (int) llama_n_ctx(ctx); h->threads = threads;
    // a cancel also stops a long prompt in the middle of its computation
    llama_set_abort_callback(ctx, [](void * d) { return static_cast<SphynxLlama *>(d)->cancel.load(); }, h);
    { std::lock_guard<std::mutex> l(g_regMutex); g_live.insert(h); }
    return h;
}

bool sphynx_llama_load_projector(SphynxLlama * h, const std::string & path, int threads, std::string & err) {
    if (!enter(h)) { err = "no model loaded"; return false; }
    struct Leave { SphynxLlama * h; ~Leave() { leave(h); } } leaveOnReturn{h};
    if (h->vision) { mtmd_free(h->vision); h->vision = nullptr; }
    logClear();
    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu = false;                         // CPU backend only in this build
    p.n_threads = threads;
    p.print_timings = false;
    p.warmup = false;
    try { h->vision = mtmd_init_from_file(path.c_str(), h->model, p); }
    catch (const std::bad_alloc &) { h->vision = nullptr; err = "not enough memory for the picture part (mmproj)"; return false; }
    if (!h->vision) {
        err = "the picture part (mmproj) could not be loaded";
        std::string t = logTail(4);
        if (!t.empty()) err += "\n" + t;
        return false;
    }
    if (!mtmd_support_vision(h->vision)) {
        mtmd_free(h->vision); h->vision = nullptr;
        err = "this mmproj file has no picture input (audio only?)";
        return false;
    }
    return true;
}

std::string sphynx_llama_describe(const SphynxLlama * ch) {
    auto * h = const_cast<SphynxLlama *>(ch);
    if (!enter(h)) return "{}";
    struct Leave { SphynxLlama * h; ~Leave() { leave(h); } } leaveOnReturn{h};
    char buf[256];
    llama_model_desc(h->model, buf, sizeof buf);
    char nums[256];
    snprintf(nums, sizeof nums, "\"sizeBytes\":%llu,\"params\":%llu,\"nCtx\":%d,\"threads\":%d,\"templated\":%s",
             (unsigned long long) llama_model_size(h->model), (unsigned long long) llama_model_n_params(h->model),
             h->nCtx, h->threads, llama_model_chat_template(h->model, nullptr) ? "true" : "false");
    std::string vision = std::string(",\"vision\":") + (h->vision ? "true" : "false");
    return std::string("{\"model\":\"") + jsonEscape(buf) + "\"," + nums + vision + "}";
}

void sphynx_llama_cancel(SphynxLlama * h) {
    std::lock_guard<std::mutex> l(g_regMutex);
    if (h && g_live.count(h)) h->cancel.store(true);       // a late cancel on a freed handle does nothing
}

std::string sphynx_llama_generate(SphynxLlama * h, const std::string & prompt, int maxTokens, float temperature,
                                  uint32_t seed, void (*onPiece)(const char *, int, void *), void * user,
                                  const unsigned char * image, size_t imageLen) {
    std::string err, stop = "error";
    long long promptMs = 0, genMs = 0, firstTokenMs = -1;
    int nPrompt = 0, nGen = 0;
    llama_sampler * smpl = nullptr;
    long long t0 = nowMs();
    bool entered = enter(h, true);
    if (!entered) { err = "no model loaded"; goto done; }
    try {
        const llama_vocab * vocab = llama_model_get_vocab(h->model);
        // each question starts from an empty context (no memory of earlier answers)
        llama_memory_clear(llama_get_memory(h->ctx), true);

        // Phase 6 S8: a picture goes in front of the question, as the model's media marker
        const bool withImage = image && imageLen > 0;
        if (withImage && !h->vision) { err = "this model has no picture part (mmproj) loaded"; goto done; }
        const std::string question = withImage ? std::string(mtmd_default_marker()) + "\n" + prompt : prompt;

        // the model's own chat format when it has one, else the plain prompt
        std::string text = question;
        const char * tmpl = llama_model_chat_template(h->model, nullptr);
        if (tmpl) {
            llama_chat_message msg{"user", question.c_str()};
            std::vector<char> f(question.size() * 2 + 512);
            int n = llama_chat_apply_template(tmpl, &msg, 1, true, f.data(), (int32_t) f.size());
            if (n > (int) f.size()) { f.resize(n); n = llama_chat_apply_template(tmpl, &msg, 1, true, f.data(), (int32_t) f.size()); }
            if (n > 0) text.assign(f.data(), n);
        }
        std::vector<llama_token> toks;
        mtmd_input_chunks * chunks = nullptr;
        if (withImage) {
            // the picture is decoded (stb_image) and cut into the model's image tokens with the text around it
            mtmd_helper_bitmap_wrapper bmp = mtmd_helper_bitmap_init_from_buf(h->vision, image, imageLen, false, mtmd_helper_init_opt_default());
            if (!bmp.bitmap) { err = "the picture could not be read"; goto done; }
            chunks = mtmd_input_chunks_init();
            mtmd_input_text in{text.c_str(), text.size(), true, true};
            const mtmd_bitmap * bitmaps[1] = {bmp.bitmap};
            int32_t r = mtmd_tokenize(h->vision, chunks, &in, bitmaps, 1);
            mtmd_bitmap_free(bmp.bitmap);
            if (r != 0) { mtmd_input_chunks_free(chunks); err = "the picture and question could not be prepared (" + std::to_string(r) + ")"; goto done; }
            nPrompt = (int) mtmd_helper_get_n_tokens(chunks);
        } else {
            int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
            if (n <= 0) { err = "the prompt could not be tokenized"; goto done; }
            toks.resize(n);
            if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, true, true) < 0) { err = "the prompt could not be tokenized"; goto done; }
            nPrompt = n;
        }
        if (nPrompt >= h->nCtx) {
            if (chunks) mtmd_input_chunks_free(chunks);
            err = "the prompt is " + std::to_string(nPrompt) + " tokens, the context holds " + std::to_string(h->nCtx); goto done;
        }
        // the answer may not run past the end of the context
        maxTokens = std::min(maxTokens, h->nCtx - nPrompt);

        smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        // a light repetition penalty: small models otherwise loop ("early game champions or early game champions")
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
        if (temperature <= 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
        }

        // the prompt, in batches of at most n_batch tokens
        long long tp = nowMs();
        const int nBatch = std::min(h->nCtx, 512);
        if (chunks) {
            // text and image chunks in order: the image is encoded by the projector, then decoded with the text
            llama_pos nPast = 0;
            int32_t r = mtmd_helper_eval_chunks(h->vision, h->ctx, chunks, 0, 0, nBatch, true, &nPast);
            mtmd_input_chunks_free(chunks);
            if (r != 0) {
                if (h->cancel.load()) { stop = "cancelled"; promptMs = nowMs() - tp; goto done; }
                err = "the picture and question could not be computed"; goto done;
            }
            // the answer may not run past the end of the context (positions used by the picture included)
            maxTokens = std::min(maxTokens, h->nCtx - (int) nPast);
        } else {
            for (int i = 0; i < nPrompt; i += nBatch) {
                llama_batch batch = llama_batch_get_one(toks.data() + i, std::min(nBatch, nPrompt - i));
                if (llama_decode(h->ctx, batch) != 0) {
                    if (h->cancel.load()) { stop = "cancelled"; promptMs = nowMs() - tp; goto done; }
                    err = "the prompt could not be computed"; goto done;
                }
            }
        }
        promptMs = nowMs() - tp;

        long long tg = nowMs();
        stop = "length";
        std::vector<char> piece(256);
        for (int i = 0; i < maxTokens; i++) {
            if (h->cancel.load()) { stop = "cancelled"; break; }
            llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) { stop = "eos"; break; }
            int k = llama_token_to_piece(vocab, tok, piece.data(), (int32_t) piece.size(), 0, false);
            if (k < 0) { piece.resize(-k); k = llama_token_to_piece(vocab, tok, piece.data(), (int32_t) piece.size(), 0, false); }
            if (k > 0 && onPiece) onPiece(piece.data(), k, user);
            nGen++;
            if (firstTokenMs < 0) firstTokenMs = nowMs() - t0;
            llama_batch batch = llama_batch_get_one(&tok, 1);
            if (llama_decode(h->ctx, batch) != 0) {
                if (h->cancel.load()) { stop = "cancelled"; break; }
                err = "the answer could not be computed"; stop = "error"; break;
            }
        }
        genMs = nowMs() - tg;
    } catch (const std::bad_alloc &) {
        err = "not enough memory for this answer"; stop = "error";
    }
done:
    if (smpl) llama_sampler_free(smpl);
    if (entered) leave(h);
    char nums[256];
    snprintf(nums, sizeof nums, "\"promptTokens\":%d,\"promptMs\":%lld,\"genTokens\":%d,\"genMs\":%lld,\"firstTokenMs\":%lld",
             nPrompt, promptMs, nGen, genMs, firstTokenMs);
    return std::string("{\"ok\":") + (err.empty() ? "true" : "false") + ",\"error\":\"" + jsonEscape(err) +
           "\",\"stop\":\"" + (err.empty() ? stop : std::string("error")) + "\"," + nums + "}";
}

void sphynx_llama_free(SphynxLlama * h) {
    {
        std::lock_guard<std::mutex> l(g_regMutex);
        if (!h || !g_live.count(h)) return;     // never made here, or freed already: nothing to do
        g_live.erase(h);
        h->cancel.store(true);                  // a running answer stops ...
    }
    while (h->busy.load() > 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));   // ... and is waited for
    if (h->vision) mtmd_free(h->vision);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}
