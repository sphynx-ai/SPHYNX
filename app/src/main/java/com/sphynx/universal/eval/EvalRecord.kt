package com.sphynx.universal.eval

import org.json.JSONObject

/**
 * PHASE 10, step E1: one evaluation run and its samples, as the protocol (docs/PHASE_10_BENCHMARK_PROTOCOL.md) defines
 * them. Game-free and pure (org.json only), unit-tested. A run is one model asked one evaluation set, each item [repeats]
 * times, in one job, under recorded conditions. Quality and speed are kept in separate fields and never combined.
 */
object EvalRecord {

    const val SCHEMA = 1

    /** The phone's state when the run started. A run outside the protocol's conditions is kept, but never ranked. */
    data class Conditions(val inMatch: Boolean, val captureOn: Boolean, val batteryPct: Int?, val charging: Boolean?,
                          /** Android's thermal status (0 none, 1 light, 2 moderate, 3 severe ...); -1 = unknown. */
                          val thermalStatus: Int, val batteryC: Float?) {
        /** Why the conditions are not the protocol's, or an empty list when they are. */
        fun problems(): List<String> = listOfNotNull(
            "a match is running".takeIf { inMatch },
            "screen capture is on".takeIf { captureOn },
            "the battery is below ${MIN_BATTERY}% and not charging".takeIf { (batteryPct ?: 100) < MIN_BATTERY && charging != true },
            "the phone is already warm (heat status $thermalStatus)".takeIf { thermalStatus > MAX_THERMAL },
        )
        val ok: Boolean get() = problems().isEmpty()
    }

    const val MIN_BATTERY = 30
    const val MAX_THERMAL = 1        // "light"

    data class RunInfo(
        val runId: String, val setId: String, val setVersion: Int, val role: String,
        val modelSha: String?, val fileName: String, val format: String, val quantization: String?,
        val backend: String, val processor: String?, val phone: String, val android: String,
        val appVersion: String, val runtimeVersion: String, val settings: Map<String, String>,
        val repeats: Int, val conditions: Conditions, val startedAt: Long, val kind: String = KIND_SET,
    )

    const val KIND_SET = "set"            // E4: every item of a set
    const val KIND_SUSTAINED = "sustained" // E8: the tactical set repeated at match pace

    /** One answer. Speed fields in ms (-1 = not measured); the score is filled by E3 (null = not scored). */
    data class Sample(
        val itemId: String, val repeat: Int, val cold: Boolean, val atMs: Long,
        val firstWordMs: Long, val usableMs: Long, val totalMs: Long, val outChars: Int,
        val appMemMb: Long, val thermalStatus: Int, val batteryC: Float?, val batteryPct: Int?,
        val answer: String, val score: Double? = null, val verdict: String? = null, val error: String? = null,
    )

    /**
     * Which runs may be compared (roadmap: never rank incomparable runs): the same set and version, job, model file
     * (fingerprint), backend, processor, phone, Android, runtime and the model's own settings. The app version is left
     * out on purpose: comparing app versions is what the regression check (E7) is for.
     */
    fun comparabilityKey(r: RunInfo): String = listOf(r.kind, r.setId, r.setVersion, r.role, r.modelSha ?: (r.fileName + "?"),
        r.backend, r.processor ?: "?", r.phone, r.android, r.runtimeVersion,
        r.settings.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }).joinToString("|")

    /** Why two runs can't be compared, or null when they can. */
    fun whyNotComparable(a: RunInfo, b: RunInfo): String? {
        val diffs = listOfNotNull(
            "kind".takeIf { a.kind != b.kind }, "evaluation set".takeIf { a.setId != b.setId || a.setVersion != b.setVersion },
            "job".takeIf { a.role != b.role }, "model file".takeIf { (a.modelSha ?: a.fileName) != (b.modelSha ?: b.fileName) },
            "backend".takeIf { a.backend != b.backend }, "processor".takeIf { a.processor != b.processor },
            "phone".takeIf { a.phone != b.phone }, "Android version".takeIf { a.android != b.android },
            "runtime version".takeIf { a.runtimeVersion != b.runtimeVersion }, "model settings".takeIf { a.settings != b.settings },
        )
        return if (diffs.isEmpty()) null else "different " + diffs.joinToString(", ")
    }

    // ---- JSON (one line per record in research/eval_runs.jsonl: {"run":{...}} or {"sample":{...},"runId":...}) ----

    fun toJson(c: Conditions): JSONObject = JSONObject().put("inMatch", c.inMatch).put("captureOn", c.captureOn)
        .putOpt("batteryPct", c.batteryPct).putOpt("charging", c.charging).put("thermalStatus", c.thermalStatus)
        .putOpt("batteryC", c.batteryC?.toDouble())

    fun toJson(r: RunInfo): JSONObject = JSONObject().put("schema", SCHEMA).put("runId", r.runId).put("kind", r.kind)
        .put("setId", r.setId).put("setVersion", r.setVersion).put("role", r.role).putOpt("modelSha", r.modelSha)
        .put("fileName", r.fileName).put("format", r.format).putOpt("quantization", r.quantization).put("backend", r.backend)
        .putOpt("processor", r.processor).put("phone", r.phone).put("android", r.android).put("appVersion", r.appVersion)
        .put("runtimeVersion", r.runtimeVersion).put("settings", JSONObject(r.settings as Map<*, *>)).put("repeats", r.repeats)
        .put("conditions", toJson(r.conditions)).put("startedAt", r.startedAt)

    fun toJson(s: Sample): JSONObject = JSONObject().put("itemId", s.itemId).put("repeat", s.repeat).put("cold", s.cold)
        .put("atMs", s.atMs).put("firstWordMs", s.firstWordMs).put("usableMs", s.usableMs).put("totalMs", s.totalMs)
        .put("outChars", s.outChars).put("appMemMb", s.appMemMb).put("thermalStatus", s.thermalStatus)
        .putOpt("batteryC", s.batteryC?.toDouble()).putOpt("batteryPct", s.batteryPct).put("answer", s.answer)
        .putOpt("score", s.score).putOpt("verdict", s.verdict).putOpt("error", s.error)

    private fun JSONObject.optIntOrNull(k: String) = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.optFloatOrNull(k: String) = if (has(k) && !isNull(k)) getDouble(k).toFloat() else null
    private fun JSONObject.optStr(k: String) = if (has(k) && !isNull(k)) getString(k) else null

    fun conditions(o: JSONObject) = Conditions(o.optBoolean("inMatch"), o.optBoolean("captureOn"), o.optIntOrNull("batteryPct"),
        if (o.has("charging") && !o.isNull("charging")) o.getBoolean("charging") else null, o.optInt("thermalStatus", -1),
        o.optFloatOrNull("batteryC"))

    fun run(o: JSONObject): RunInfo? = try {
        if (o.optInt("schema", 0) > SCHEMA) null else {
            val st = o.optJSONObject("settings")
            RunInfo(o.getString("runId"), o.getString("setId"), o.getInt("setVersion"), o.getString("role"), o.optStr("modelSha"),
                o.getString("fileName"), o.getString("format"), o.optStr("quantization"), o.getString("backend"), o.optStr("processor"),
                o.getString("phone"), o.getString("android"), o.getString("appVersion"), o.getString("runtimeVersion"),
                st?.keys()?.asSequence()?.associateWith { st.getString(it) } ?: emptyMap(), o.getInt("repeats"),
                conditions(o.getJSONObject("conditions")), o.getLong("startedAt"), o.optString("kind", KIND_SET))
        }
    } catch (e: Exception) { null }

    fun sample(o: JSONObject): Sample? = try {
        Sample(o.getString("itemId"), o.getInt("repeat"), o.optBoolean("cold"), o.optLong("atMs"), o.optLong("firstWordMs", -1),
            o.optLong("usableMs", -1), o.optLong("totalMs", -1), o.optInt("outChars"), o.optLong("appMemMb", -1),
            o.optInt("thermalStatus", -1), o.optFloatOrNull("batteryC"), o.optIntOrNull("batteryPct"), o.optString("answer"),
            if (o.has("score") && !o.isNull("score")) o.getDouble("score") else null, o.optStr("verdict"), o.optStr("error"))
    } catch (e: Exception) { null }

    /** The lines of a results file for a run with its samples. */
    fun lines(r: RunInfo, samples: List<Sample>): List<String> =
        listOf(JSONObject().put("run", toJson(r)).toString()) +
            samples.map { JSONObject().put("runId", r.runId).put("sample", toJson(it)).toString() }

    /** Every run in a results file with its samples; broken lines are skipped. */
    fun read(lines: List<String>): List<Pair<RunInfo, List<Sample>>> {
        val runs = LinkedHashMap<String, RunInfo>()
        val samples = HashMap<String, MutableList<Sample>>()
        for (l in lines) {
            val o = try { JSONObject(l) } catch (e: Exception) { continue }
            o.optJSONObject("run")?.let { j -> run(j)?.let { runs[it.runId] = it } }
            o.optJSONObject("sample")?.let { j -> sample(j)?.let { s -> samples.getOrPut(o.optString("runId")) { ArrayList() }.add(s) } }
        }
        return runs.values.map { it to (samples[it.runId] ?: emptyList<Sample>()) }
    }

}
