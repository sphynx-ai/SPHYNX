// PHASE 8, step G3: runs the app's real native engine (llama_engine.cpp) on the CI computer.
// Usage: engine_test [tiny.gguf]. Without a model only the model-free checks run; REQUIRE_MODEL=1 makes a missing model
// a failure (CI). Exit code 0 = every check passed.
#include "llama_engine.h"
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <thread>

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, what) do { if (cond) { g_pass++; std::printf("PASS  %s\n", what); } \
                               else { g_fail++; std::printf("FAIL  %s  (%s:%d)\n", what, __FILE__, __LINE__); } } while (0)

static bool has(const std::string & json, const std::string & part) { return json.find(part) != std::string::npos; }

struct Pieces { std::string text; int count = 0; SphynxLlama * cancelAfterFirst = nullptr; };
static void collect(const char * b, int n, void * u) {
    auto * p = static_cast<Pieces *>(u);
    p->text.append(b, n); p->count++;
    if (p->cancelAfterFirst && p->count == 1) sphynx_llama_cancel(p->cancelAfterFirst);
}

static std::string tempFile(const char * name, const std::string & bytes) {
    std::string path = std::string("/tmp/") + name;
    std::ofstream(path, std::ios::binary) << bytes;
    return path;
}

int main(int argc, char ** argv) {
    sphynx_llama_init(nullptr);
    const std::string model = argc > 1 ? argv[1] : "";
    const bool requireModel = std::getenv("REQUIRE_MODEL") && std::string(std::getenv("REQUIRE_MODEL")) == "1";

    std::string info = sphynx_llama_info();
    CHECK(has(info, "llama.cpp b"), "info names the pinned llama.cpp");

    // ---- no model needed ----
    std::string err;
    CHECK(sphynx_llama_load("/tmp/does-not-exist.gguf", 256, 2, err) == nullptr && !err.empty(), "a missing file fails with a reason");
    err.clear();
    std::string broken = std::string("GGUF") + std::string("\x03\0\0\0", 4) + std::string(64, '\xff');
    CHECK(sphynx_llama_load(tempFile("sphynx-broken.gguf", broken), 256, 2, err) == nullptr && !err.empty(), "a broken GGUF fails with a reason");
    err.clear();
    CHECK(sphynx_llama_load(tempFile("sphynx-page.gguf", "<!DOCTYPE html><html></html>"), 256, 2, err) == nullptr, "a web page saved as .gguf fails");
    CHECK(has(sphynx_llama_generate(nullptr, "hi", 4, 0, 1, nullptr, nullptr), "\"ok\":false"), "an answer without a model fails cleanly");
    CHECK(sphynx_llama_describe(nullptr) == "{}", "describe without a model");
    sphynx_llama_cancel(nullptr);
    sphynx_llama_free(nullptr);
    CHECK(!sphynx_llama_alive(nullptr), "no handle is not alive");
    CHECK(true, "cancel and free on no model do nothing");

    if (model.empty()) {
        std::printf("no model given%s\n", requireModel ? ": FAIL (REQUIRE_MODEL=1)" : ": model checks skipped");
        if (requireModel) g_fail++;
    } else {
        // ---- the tiny model ----
        err.clear();
        SphynxLlama * h = sphynx_llama_load(model, 128, 2, err);
        CHECK(h != nullptr, "the tiny model loads");
        if (!h) { std::printf("load error: %s\n", err.c_str()); return 1; }
        const std::string desc = sphynx_llama_describe(h);
        std::printf("describe: %s\n", desc.c_str());
        CHECK(has(desc, "\"nCtx\":") && has(desc, "\"threads\":2") && has(desc, "\"vision\":false"), "describe gives the context, threads and no pictures");

        Pieces a, b;
        std::string r1 = sphynx_llama_generate(h, "Once upon a time", 16, 0.0f, 1, collect, &a);
        CHECK(has(r1, "\"ok\":true"), "an answer completes");
        CHECK(a.count > 0 && !a.text.empty(), "the answer streams pieces");
        CHECK(has(r1, "\"stop\":\"length\"") || has(r1, "\"stop\":\"eos\""), "it stops at the length or the end");
        std::string r2 = sphynx_llama_generate(h, "Once upon a time", 16, 0.0f, 1, collect, &b);
        CHECK(a.text == b.text, "greedy answers repeat exactly (each question starts from an empty context)");

        Pieces c; c.cancelAfterFirst = h;
        std::string r3 = sphynx_llama_generate(h, "Once upon a time", 64, 0.0f, 1, collect, &c);
        CHECK(has(r3, "\"stop\":\"cancelled\"") && c.count <= 2, "a cancel stops the answer at once");
        Pieces d;
        std::string r4 = sphynx_llama_generate(h, "Once upon a time", 8, 0.0f, 1, collect, &d);
        CHECK(has(r4, "\"ok\":true") && d.count > 0, "the next answer runs normally after a cancel");

        // cancel from another thread while the answer runs
        Pieces e;
        std::atomic<bool> done{false};
        std::thread t([&] { while (!done.load() && e.count == 0) std::this_thread::yield(); sphynx_llama_cancel(h); });
        std::string r5 = sphynx_llama_generate(h, "Once upon a time", 100, 0.0f, 1, collect, &e);
        done = true; t.join();
        CHECK(has(r5, "\"stop\":\"cancelled\"") || has(r5, "\"stop\":\"eos\""), "a cancel from another thread is seen");

        std::string longPrompt;
        for (int i = 0; i < 200; i++) longPrompt += "word ";
        CHECK(has(sphynx_llama_generate(h, longPrompt, 8, 0.0f, 1, nullptr, nullptr), "the context holds"), "a prompt longer than the context is refused");
        unsigned char jpeg[4] = {0xFF, 0xD8, 0xFF, 0xD9};
        CHECK(has(sphynx_llama_generate(h, "what is this", 8, 0.0f, 1, nullptr, nullptr, jpeg, 4), "no picture part"),
              "a picture without an mmproj is refused, not crashed");
        err.clear();
        CHECK(!sphynx_llama_load_projector(h, "/tmp/does-not-exist-mmproj.gguf", 2, err) && !err.empty(), "a missing mmproj fails with a reason");
        CHECK(has(sphynx_llama_generate(h, "Once", 4, 0.0f, 1, nullptr, nullptr), "\"ok\":true"), "the model still answers after a failed mmproj");

        // load and free 20 times: nothing may be left behind (G9 checks the memory on the phone)
        bool all = true;
        for (int i = 0; i < 20 && all; i++) { std::string e2; SphynxLlama * x = sphynx_llama_load(model, 128, 2, e2); all = x != nullptr; sphynx_llama_free(x); }
        CHECK(all, "20 loads and frees in a row");

        // ---- G4: handles ----
        CHECK(sphynx_llama_alive(h), "a loaded handle is alive");
        // free while an answer runs on another thread: the answer stops, free waits, nothing crashes
        Pieces f;
        std::string r6;
        std::thread runner([&] { r6 = sphynx_llama_generate(h, "Once upon a time", 120, 0.0f, 1, collect, &f); });
        while (f.count == 0 && r6.empty()) std::this_thread::yield();
        sphynx_llama_free(h);
        runner.join();
        CHECK(has(r6, "\"stop\":\"cancelled\"") || has(r6, "\"stop\":\"eos\"") || has(r6, "\"stop\":\"length\""),
              "free during an answer stops it and waits for it");
        CHECK(!sphynx_llama_alive(h), "a freed handle is no longer alive");
        // every call on the freed handle is refused, not a crash
        sphynx_llama_cancel(h);
        CHECK(sphynx_llama_describe(h) == "{}", "describe on a freed handle");
        CHECK(has(sphynx_llama_generate(h, "hi", 4, 0, 1, nullptr, nullptr), "no model loaded"), "an answer on a freed handle");
        err.clear();
        CHECK(!sphynx_llama_load_projector(h, "/tmp/x.gguf", 2, err) && err == "no model loaded", "an mmproj on a freed handle");
        sphynx_llama_free(h);
        CHECK(true, "a second free does nothing");
        // a pointer this engine never made
        SphynxLlama fake;
        CHECK(!sphynx_llama_alive(&fake) && sphynx_llama_describe(&fake) == "{}", "a handle the engine never made is refused");
        sphynx_llama_free(&fake);
    }
    std::printf("\n%d passed, %d failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
