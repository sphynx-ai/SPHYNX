package com.sphynx.inference

import java.util.Locale

/**
 * PHASE 8, steps G5 and G6: the benchmark's figures and the choices made from them. Pure, unit-tested.
 *
 *  - G5 threads: the benchmark tries 2, 4 and 6 CPU threads. The best one is remembered per model file (name + size),
 *    when it writes clearly faster than the default 4. Small differences are noise on a phone, so the default stays.
 *  - G5 8-bit KV cache: owner decision 2026-11-23, "on by default only if the phone test shows no loss". [kv8NoLoss]
 *    says whether one benchmark run shows no loss: the same greedy answers, and writing at least 95% as fast.
 *    Whether it becomes the default is decided after the phone test, not by the app.
 */
object GgufTuning {

    const val DEFAULT_THREADS = 4
    val THREADS_TRIED = listOf(2, 4, 6)
    /** A thread count must write at least this much faster than the default to replace it. */
    const val CLEARLY_FASTER = 1.10
    const val KV8_MIN_SPEED = 0.95

    /** One timed answer (cold = the first answer after the load). */
    data class Run(val threads: Int, val kv8: Boolean, val cold: Boolean, val promptTokens: Int, val promptMs: Long,
                   val genTokens: Int, val genMs: Long, val firstTokenMs: Long, val answer: String) {
        val genTps: Double get() = if (genMs <= 0 || genTokens <= 0) 0.0 else genTokens * 1000.0 / genMs
        val promptTps: Double get() = if (promptMs <= 0 || promptTokens <= 0) 0.0 else promptTokens * 1000.0 / promptMs
    }

    /** Mean writing speed of the warm runs for one setting, or null without any. */
    fun meanGenTps(runs: List<Run>, threads: Int, kv8: Boolean): Double? =
        runs.filter { it.threads == threads && it.kv8 == kv8 && !it.cold && it.genTps > 0 }.map { it.genTps }
            .takeIf { it.isNotEmpty() }?.average()

    /** The thread count to remember for this model: the fastest, if clearly faster than the default; else the default. */
    fun bestThreads(runs: List<Run>, cores: Int): Int {
        val base = meanGenTps(runs, DEFAULT_THREADS, false) ?: return DEFAULT_THREADS
        val best = THREADS_TRIED.filter { it <= cores }.mapNotNull { t -> meanGenTps(runs, t, false)?.let { t to it } }
            .maxByOrNull { it.second } ?: return DEFAULT_THREADS
        return if (best.first != DEFAULT_THREADS && best.second >= base * CLEARLY_FASTER) best.first else DEFAULT_THREADS
    }

    /** One benchmark's verdict on the 8-bit KV cache: true when answers match and writing is at least 95% as fast. */
    fun kv8NoLoss(runs: List<Run>): Boolean? {
        val f16 = runs.filter { it.threads == DEFAULT_THREADS && !it.kv8 && !it.cold }
        val q8 = runs.filter { it.threads == DEFAULT_THREADS && it.kv8 && !it.cold }
        if (f16.isEmpty() || q8.isEmpty()) return null
        val same = f16.zip(q8).all { (a, b) -> a.answer.trim() == b.answer.trim() }
        val speed = (meanGenTps(runs, DEFAULT_THREADS, true) ?: 0.0) / (meanGenTps(runs, DEFAULT_THREADS, false) ?: return null)
        return same && speed >= KV8_MIN_SPEED
    }

    /** The Model page's result line. */
    fun summary(runs: List<Run>, cores: Int): String {
        fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
        val parts = THREADS_TRIED.filter { it <= cores }.map { t -> "$t threads ${f(meanGenTps(runs, t, false))}" }
        val kv = meanGenTps(runs, DEFAULT_THREADS, true)
        val verdict = when (kv8NoLoss(runs)) { true -> "no loss"; false -> "a loss"; null -> "not measured" }
        return "Writing (tokens/s): " + parts.joinToString(", ") + "; 8-bit cache ${f(kv)} ($verdict). " +
            "Best: ${bestThreads(runs, cores)} threads"
    }

    /** The settings key for a model file's remembered thread count. */
    fun threadsKey(fileName: String, sizeBytes: Long) = "gguf_threads_${fileName}_$sizeBytes"
}
