package com.example.mlbbresearch.ai

import org.json.JSONObject
import java.util.Locale

// MODEL SPEED RECORDER (owner, 2026-11-10: "a model speed data recorder for both strategic and
// tactical models so I can send you the results"). Pure logic, unit tested; the Android side
// (ModelSpeed) feeds it from LlmSlot and saves / exports it.
//
// One row per model call and one per model load. Every call has its wall-clock time; with
// "detailed" on, LiteRT-LM's own figures are added (time to the first token, prompt and answer
// tokens and tokens per second). The phone's state at that moment (thermal status, battery
// temperature, free memory) is kept too, because a hot or busy phone answers slower.
class ModelSpeedLog(private val maxRows: Int = 3000) {

    data class Row(
        val kind: String,                // "call" or "load"
        val t: Long,                     // when (epoch ms)
        val slot: String,                // "strategic" or "tactical"
        val model: String,               // model file name
        val backend: String,             // "GPU", "CPU, text only", ...
        val ms: Long,                    // call: question to full answer; load: loading time
        val ok: Boolean,
        val error: String = "",
        // calls only
        val purpose: String = "",        // what asked: draft, plan, combo, fight advice, readiness, speed test ...
        val image: Boolean = false,
        val promptChars: Int = 0,
        val replyChars: Int = 0,
        val cold: Boolean = false,       // the first call after the model loaded
        // detailed figures (null when off or not reported)
        val ttftMs: Long? = null,
        val prefillTokens: Int? = null,
        val prefillTps: Double? = null,
        val decodeTokens: Int? = null,
        val decodeTps: Double? = null,
        // loads only
        val detailed: Boolean = false,   // the model was loaded with the detailed figures on
        val attempts: Int = 0,           // load attempts tried (GPU, CPU, text only ...)
        // the phone
        val thermal: Int? = null,        // Android thermal status 0 (none) .. 6 (shutdown)
        val batteryC: Float? = null,
        val ramAvailMb: Long? = null
    ) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind).put("t", t).put("slot", slot).put("model", model)
            .put("backend", backend).put("ms", ms).put("ok", ok).put("error", error).put("purpose", purpose)
            .put("image", image).put("pc", promptChars).put("rc", replyChars).put("cold", cold)
            .put("detailed", detailed).put("attempts", attempts).apply {
                ttftMs?.let { put("ttft", it) }; prefillTokens?.let { put("pt", it) }; prefillTps?.let { put("ptps", it) }
                decodeTokens?.let { put("dt", it) }; decodeTps?.let { put("dtps", it) }
                thermal?.let { put("thermal", it) }; batteryC?.let { put("batt", it.toDouble()) }; ramAvailMb?.let { put("ram", it) }
            }

        companion object {
            fun fromJson(o: JSONObject): Row? = try {
                Row(o.getString("kind"), o.getLong("t"), o.getString("slot"), o.optString("model"), o.optString("backend"),
                    o.getLong("ms"), o.getBoolean("ok"), o.optString("error"), o.optString("purpose"), o.optBoolean("image"),
                    o.optInt("pc"), o.optInt("rc"), o.optBoolean("cold"),
                    if (o.has("ttft")) o.getLong("ttft") else null, if (o.has("pt")) o.getInt("pt") else null,
                    if (o.has("ptps")) o.getDouble("ptps") else null, if (o.has("dt")) o.getInt("dt") else null,
                    if (o.has("dtps")) o.getDouble("dtps") else null, o.optBoolean("detailed"), o.optInt("attempts"),
                    if (o.has("thermal")) o.getInt("thermal") else null, if (o.has("batt")) o.getDouble("batt").toFloat() else null,
                    if (o.has("ram")) o.getLong("ram") else null)
            } catch (e: Exception) { null }
        }
    }

    private val rows = ArrayList<Row>()

    @Synchronized fun add(r: Row) { rows.add(r); while (rows.size > maxRows) rows.removeAt(0) }
    @Synchronized fun all(): List<Row> = rows.toList()
    @Synchronized fun clear() = rows.clear()
    @Synchronized fun size(): Int = rows.size

    /** One JSON object per line (the saved file). */
    @Synchronized fun toJsonLines(): String = rows.joinToString("\n") { it.toJson().toString() }
    @Synchronized fun loadJsonLines(text: String) {
        rows.clear()
        text.lineSequence().filter { it.isNotBlank() }.forEach { l ->
            try { Row.fromJson(JSONObject(l))?.let { rows.add(it) } } catch (e: Exception) {}
        }
        while (rows.size > maxRows) rows.removeAt(0)
    }

    // ---- summary ----
    companion object {
        fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        /** Nearest-rank percentile (p in 0..100). */
        fun percentile(xs: List<Double>, p: Int): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
            s[((p / 100.0 * s.size).let { kotlin.math.ceil(it).toInt() } - 1).coerceIn(0, s.size - 1)] }
    }

    data class Group(val slot: String, val model: String, val backend: String, val image: Boolean, val calls: Int,
                     val failed: Int, val medianMs: Double?, val p90Ms: Double?, val medianTtftMs: Double?,
                     val medianPrefillTps: Double?, val medianDecodeTps: Double?)

    /** Calls grouped by engine, model, backend and image / text (cold calls left out of the times). */
    @Synchronized fun groups(): List<Group> = rows.filter { it.kind == "call" }
        .groupBy { listOf(it.slot, it.model, it.backend, it.image.toString()) }
        .map { (k, g) ->
            val warm = g.filter { it.ok && !it.cold }
            Group(k[0], k[1], k[2], k[3] == "true", g.size, g.count { !it.ok },
                median(warm.map { it.ms.toDouble() }), percentile(warm.map { it.ms.toDouble() }, 90),
                median(warm.mapNotNull { it.ttftMs?.toDouble() }), median(warm.mapNotNull { it.prefillTps }),
                median(warm.mapNotNull { it.decodeTps }))
        }.sortedWith(compareBy({ it.slot }, { it.model }, { it.backend }, { it.image }))

    @Synchronized fun text(): String {
        if (rows.isEmpty()) return "No model calls recorded yet. Load the models and play, or run the speed test."
        fun s(ms: Double?) = if (ms == null) "-" else "%.2f s".format(Locale.US, ms / 1000)
        fun r(x: Double?) = if (x == null) "-" else "%.1f".format(Locale.US, x)
        return buildString {
            for (slot in listOf("strategic", "tactical")) {
                val gs = groups().filter { it.slot == slot }
                val loads = rows.filter { it.kind == "load" && it.slot == slot }
                append(if (slot == "strategic") "STRATEGIST" else "TACTICAL").append('\n')
                if (gs.isEmpty()) append("  no calls yet\n")
                gs.forEach { g ->
                    append("  ${g.model} · ${g.backend}${if (g.image) " · with image" else ""}: ${g.calls} calls")
                    if (g.failed > 0) append(" (${g.failed} failed)")
                    append(", median ${s(g.medianMs)}, slowest 10% ${s(g.p90Ms)}")
                    if (g.medianDecodeTps != null || g.medianTtftMs != null)
                        append(", first token ${s(g.medianTtftMs)}, prompt ${r(g.medianPrefillTps)} tok/s, answer ${r(g.medianDecodeTps)} tok/s")
                    append('\n')
                }
                loads.lastOrNull()?.let { l ->
                    append("  last load: ${if (l.ok) "${l.backend} in ${s(l.ms.toDouble())}" else "FAILED (${l.error.take(80)})"}" +
                        (if (l.detailed) ", detailed on" else "") + "\n")
                }
            }
            append("${rows.count { it.kind == "call" }} calls and ${rows.count { it.kind == "load" }} loads recorded.")
        }
    }

    /** Spreadsheet-friendly export: one line per call or load. */
    @Synchronized fun csv(): String = buildString {
        append("time,kind,slot,purpose,model,backend,image,cold,ok,ms,ttft_ms,prompt_tokens,prompt_tok_s,answer_tokens,answer_tok_s," +
            "prompt_chars,reply_chars,detailed,attempts,thermal,battery_c,ram_free_mb,error\n")
        fun d(x: Double?) = if (x == null) "" else "%.2f".format(Locale.US, x)
        rows.forEach { r ->
            append(listOf(r.t, r.kind, r.slot, q(r.purpose), q(r.model), q(r.backend), r.image, r.cold, r.ok, r.ms,
                r.ttftMs ?: "", r.prefillTokens ?: "", d(r.prefillTps), r.decodeTokens ?: "", d(r.decodeTps),
                r.promptChars, r.replyChars, r.detailed, r.attempts, r.thermal ?: "",
                r.batteryC?.let { "%.1f".format(Locale.US, it) } ?: "", r.ramAvailMb ?: "", q(r.error)).joinToString(","))
            append('\n')
        }
    }

    private fun q(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\"" else s
}
