package com.sphynx.inference.roles

import com.sphynx.inference.Accelerator
import java.util.Locale

// THE BEST PROCESSOR PER MODEL (Phase 7, step L9; owner decision 2026-11-23: "best processor per model").
// For each model file the app remembers what happened on each processor on this phone: whether it loaded, how long the
// load took, and how fast it answered. The next load tries the best one first: the fastest that loaded and answered.
// A processor that failed (or crashed the app while loading) is skipped until the app version, the model file or the
// LiteRT-LM version changes (the [stamp]). The owner's switches still decide which processors may be tried ([allowed]).
// The CPU is never skipped: it is the last resort. Pure; the app supplies the store. Unit-tested.
class ProcessorMemory(private val store: LoadSentinel.Store, stamp: String, private val today: () -> String) {

    private val stamp = clean(stamp)     // as it is stored

    class Rec(val ok: Boolean, val loadMs: Long, val tokPerS: Double, val answers: Int, val stamp: String, val date: String)

    private fun key(model: String) = "proc_" + model

    @Synchronized
    fun records(model: String): Map<Accelerator, Rec> = decode(store.get(key(model)))

    @Synchronized
    private fun put(model: String, a: Accelerator, r: Rec) {
        val m = decode(store.get(key(model))).toMutableMap(); m[a] = r
        store.setNow(key(model), encode(m))
    }

    /** It loaded on [a] in [ms]. */
    @Synchronized
    fun loaded(model: String, a: Accelerator, ms: Long) {
        val old = records(model)[a]?.takeIf { it.stamp == stamp && it.ok }
        put(model, a, Rec(true, ms, old?.tokPerS ?: 0.0, old?.answers ?: 0, stamp, today()))
    }

    /** It failed (or crashed) on [a]: skipped until the stamp changes. The CPU is only noted. */
    @Synchronized
    fun failed(model: String, a: Accelerator) = put(model, a, Rec(false, 0, 0.0, 0, stamp, today()))

    /** One answer on [a]: [chars] written in [ms] (about 4 characters a token). A running average of the last answers. */
    @Synchronized
    fun answered(model: String, a: Accelerator, chars: Int, ms: Long) {
        if (chars <= 0 || ms <= 0) return
        val r = records(model)[a]?.takeIf { it.stamp == stamp && it.ok } ?: return
        val now = chars / 4.0 / (ms / 1000.0)
        val n = minOf(r.answers, 9)
        put(model, a, Rec(true, r.loadMs, (r.tokPerS * n + now) / (n + 1), r.answers + 1, stamp, r.date))
    }

    /** Does a record still count (same app, file and LiteRT-LM)? */
    private fun Rec.current() = stamp == this@ProcessorMemory.stamp

    /** The order to try for [model]: failed ones out (never the CPU), then the fastest known first, the rest as allowed. */
    @Synchronized
    fun order(model: String, allowed: List<Accelerator>): List<Accelerator> {
        val recs = records(model).filterValues { it.current() }
        val usable = allowed.filter { a -> a == Accelerator.CPU || recs[a]?.ok != false }
        val best = usable.filter { recs[it]?.let { r -> r.ok && r.answers > 0 } == true }.maxByOrNull { recs[it]!!.tokPerS }
        val out = if (best == null) usable else listOf(best) + usable.filter { it != best }
        return out.ifEmpty { listOf(Accelerator.CPU) }
    }

    /** Why that order, for the Model page ("GPU: about 13 tokens/s, loads in 8.2 s; NPU: failed on 2026-11-23"). */
    @Synchronized
    fun explain(model: String, allowed: List<Accelerator>): String {
        val recs = records(model).filterValues { it.current() }
        val first = order(model, allowed).first()
        val parts = allowed.map { a ->
            val r = recs[a]
            a.name + ": " + when {
                r == null -> "not tried yet"
                !r.ok -> "failed on ${r.date}" + if (a == Accelerator.CPU) "" else " (skipped until the app, the model or LiteRT-LM changes)"
                r.answers == 0 -> "loads in ${sec(r.loadMs)}, no answers timed yet"
                else -> "about ${String.format(Locale.US, "%.1f", r.tokPerS)} tokens/s, loads in ${sec(r.loadMs)}"
            }
        }
        return "Tried first: $first. " + parts.joinToString("; ")
    }

    private fun sec(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)

    companion object {
        /** The key of a model file: its name and size, so a new file of the same name starts over. */
        fun modelKey(fileName: String, sizeBytes: Long) = "$fileName:$sizeBytes"

        fun encode(m: Map<Accelerator, Rec>): String = m.entries.joinToString(";") { (a, r) ->
            listOf(a.name, if (r.ok) "1" else "0", r.loadMs, String.format(Locale.US, "%.3f", r.tokPerS), r.answers,
                clean(r.stamp), clean(r.date)).joinToString("|")
        }

        private fun clean(s: String) = s.replace(";", ",").replace("|", "/")

        fun decode(s: String?): Map<Accelerator, Rec> {
            if (s.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<Accelerator, Rec>()
            for (e in s.split(";")) {
                val f = e.split("|"); if (f.size < 7) continue
                val a = try { Accelerator.valueOf(f[0]) } catch (x: Exception) { continue }
                out[a] = Rec(f[1] == "1", f[2].toLongOrNull() ?: 0, f[3].toDoubleOrNull() ?: 0.0, f[4].toIntOrNull() ?: 0, f[5], f[6])
            }
            return out
        }
    }
}
