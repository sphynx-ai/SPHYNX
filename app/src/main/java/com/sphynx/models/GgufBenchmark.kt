package com.sphynx.models

import android.content.Context
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.inference.EventCollector
import com.sphynx.inference.GgufInfo
import com.sphynx.inference.GgufTuning
import com.sphynx.inference.InferenceRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// THE GGUF BENCHMARK (Phase 8, steps G5 and G6). The same fixed questions on one GGUF model, greedy so answers can be
// compared, with each setting loaded fresh: 4, 2 and 6 CPU threads, then 4 threads with the 8-bit KV cache. The first
// answer after each load is the cold one. Every answer becomes one row in research/gguf_bench.jsonl: the model,
// quantization, size, threads, cache, cold or warm, reading and writing tokens/s, time to the first word, the app's and
// the phone's memory, battery temperature and heat status. The Debug data page exports and uploads it with the other
// research files. The best thread count is remembered for the model (GgufTuning); the 8-bit cache is only reported (owner
// decision: on by default only after the phone test shows no loss).
object GgufBenchmark {

    val QUESTIONS = listOf(
        GgufLibrary.TEST_PROMPT,
        "Name three items a marksman should buy first in a MOBA, in one line.",
        "An enemy jungler is missing from the map for 20 seconds. What should a mid laner do? One sentence.",
    )
    const val MAX_TOKENS = 64

    @Volatile var running = false; private set
    @Volatile var line = ""; private set

    /** Starts the benchmark of [file] in the background; null when it started, else why not. */
    fun start(ctx: Context, file: File, rolesUseGguf: Boolean, done: (String) -> Unit): String? {
        if (running) return "The benchmark is already running"
        if (rolesUseGguf) return "A job uses a GGUF model now: give both jobs a LiteRT-LM model for the benchmark, then switch back"
        if (!file.isFile) return "Add a GGUF model first"
        running = true
        line = "Benchmark: starting…"
        Thread {
            val msg = try { run(ctx.applicationContext, file) } catch (t: Throwable) { Diag.error("GGUF benchmark", t); "Benchmark failed: ${t.message}" }
            line = msg
            running = false
            Diag.info("GGUF benchmark (${file.name}): $msg")
            done(msg)
        }.apply { name = "gguf-bench" }.start()
        return null
    }

    private fun run(c: Context, file: File): String {
        val info = GgufInfo.read(file).getOrNull()
        val be = GgufLibrary.backend(c)
        val cores = Runtime.getRuntime().availableProcessors()
        val settings = listOf(4 to false, 2 to false, 6 to false, 4 to true).filter { it.first <= cores }
        val runs = ArrayList<GgufTuning.Run>()
        for ((threads, kv8) in settings) {
            line = "Benchmark: $threads threads${if (kv8) ", 8-bit cache" else ""}, loading…"
            val t0 = System.currentTimeMillis()
            val st = be.load(GgufLibrary.config(file).copy(threads = threads, kvCache8Bit = kv8))
            val loadMs = System.currentTimeMillis() - t0
            if (!st.ready) { row(c, file, info, threads, kv8, null, loadMs, st.error?.message ?: "did not load"); continue }
            if (kv8 && be.kv8Refused != null) {      // this model can't have the 8-bit cache: nothing to compare
                row(c, file, info, threads, kv8, null, loadMs, "8-bit cache refused: " + be.kv8Refused); continue
            }
            QUESTIONS.forEachIndexed { i, q ->
                line = "Benchmark: $threads threads${if (kv8) ", 8-bit cache" else ""}, question ${i + 1} of ${QUESTIONS.size}…"
                val r = ask(be, q, threads, kv8, cold = i == 0)
                if (r != null) runs.add(r)
                row(c, file, info, threads, kv8, r, if (i == 0) loadMs else -1, if (r == null) "no answer" else "")
            }
        }
        be.unload()
        val best = GgufTuning.bestThreads(runs, cores)
        Cfg.setRaw(GgufTuning.threadsKey(file.name, file.length()), best.toString())
        return GgufTuning.summary(runs, cores)
    }

    private fun ask(be: com.sphynx.inference.LlamaCppBackend, q: String, threads: Int, kv8: Boolean, cold: Boolean): GgufTuning.Run? {
        val id = "bench-" + System.nanoTime()
        val col = EventCollector(id)
        val latch = CountDownLatch(1)
        be.generate(InferenceRequest(id, q, temperature = 0f, maxOutputTokens = MAX_TOKENS, timeoutMs = 180_000L)) { e ->
            col.accept(e); if (e.terminal) latch.countDown()
        }
        if (!latch.await(200, TimeUnit.SECONDS)) { be.cancel(id); return null }
        val res = be.lastRun ?: return null
        val text = col.text ?: return null          // null: it failed or was stopped
        return GgufTuning.Run(threads, kv8, cold, res.optInt("promptTokens"), res.optLong("promptMs"), res.optInt("genTokens"),
            res.optLong("genMs"), res.optLong("firstTokenMs", -1L), text)
    }

    private fun row(c: Context, file: File, info: GgufInfo?, threads: Int, kv8: Boolean, r: GgufTuning.Run?, loadMs: Long, error: String) {
        try {
            val snap = com.sphynx.core.DeviceStats.sample(c)
            val ph = com.sphynx.core.PhoneState.read()
            val o = JSONObject().put("t", System.currentTimeMillis()).put("app", com.sphynx.core.AppVersion.VERSION)
                .put("model", file.name).put("sizeMb", file.length() / 1048576).put("arch", info?.architecture ?: "")
                .put("quant", info?.quantization ?: "").put("threads", threads).put("kv8", kv8).put("loadMs", loadMs)
                .put("error", error)
            if (r != null) o.put("cold", r.cold).put("promptTokens", r.promptTokens).put("promptTps", r.promptTps)
                .put("genTokens", r.genTokens).put("genTps", r.genTps).put("firstTokenMs", r.firstTokenMs)
                .put("answer", r.answer.take(200))
            snap?.let { o.put("appRamMb", it.appRamMb).put("phoneAvailMb", it.ramAvailMb) }
            ph.batteryC?.let { o.put("batteryC", it.toDouble()) }
            ph.thermal?.let { o.put("thermal", it) }
            File(File(c.filesDir, "research").apply { mkdirs() }, "gguf_bench.jsonl").appendText(o.toString() + "\n")
        } catch (e: Exception) { }
    }
}
