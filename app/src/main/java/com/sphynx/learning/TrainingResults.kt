package com.sphynx.learning

import android.content.Context
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.models.AutoFolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

// TRAINING RESULTS (owner, 2026-11-12): how good each trained part is, and how much better than the last
// training. The Learn page shows it as a circle with the percentage; the Debug data page's upload sends it
// (training-results-<time>.json in the recordings folder).
//  - Minimap / screen detector: boxes found (mAP50) from <kind>_detector_report.json (the notebooks write it).
//  - Tactical networks: moves right (balanced accuracy) from policy_info_all.json inside mlbb_policies.zip.
//  - Small tactical model: answers right (balanced accuracy) from tactical_llm_info.json.
//  - Tactical engine (smart fights, learns on the phone): share of the AI's fights that went well (reward > 0)
//    in a match, recorded at each match end (5 fights or more). Your own fights are a result of their own.
// Improvement = (score now - score last time) / score last time, in percent.
object TrainingResults {

    enum class Kind(val key: String, val label: String, val measure: String) {
        MINIMAP("minimap", "Minimap detector", "boxes found (mAP50)"),
        SCREEN("screen", "Screen detector", "boxes found (mAP50)"),
        POLICY("policy", "Tactical networks", "moves right (balanced)"),
        LLM("llm", "Small tactical model", "answers right (balanced)"),
        ENGINE("engine", "Tactical engine (the AI's fights)", "fights that went well"),
        // (owner, 2026-11-13: the first engine result, 84%, measured the owner's own fights - they are counted apart)
        YOU("you", "Your own fights (learned from)", "fights that went well")
    }

    class Result(val at: Long, val score: Double, val n: Int, val sig: String)

    const val KEEP = 30          // results kept per kind
    const val MIN_FIGHTS = 5     // fights in a match before the tactical engine's result counts

    // ---------------- pure (unit tested) ----------------

    fun improvementPct(prev: Double?, now: Double?): Double? =
        if (prev == null || now == null || prev <= 0.0) null else (now - prev) / prev * 100.0

    // a result file -> its score (0..1), null when it has none
    fun scoreOf(kind: Kind, json: String?): Double? = try {
        val o = JSONObject(json ?: return null)
        val v = when (kind) {
            Kind.MINIMAP, Kind.SCREEN -> o.optDouble("map50", Double.NaN)
            Kind.POLICY -> o.optDouble("move_balanced", Double.NaN)
            Kind.LLM -> o.optDouble("balanced", -1.0).takeIf { it >= 0 } ?: o.optDouble("acc", Double.NaN)
            Kind.ENGINE, Kind.YOU -> Double.NaN
        }
        if (v.isNaN() || v < 0.0 || v > 1.0) null else v
    } catch (e: Exception) { null }

    // how many examples a result was measured on (shown next to the score)
    fun countOf(json: String?): Int = try {
        val o = JSONObject(json ?: return 0)
        listOf("test", "move_checked", "examples").firstOrNull { o.has(it) }?.let { o.optInt(it) } ?: 0
    } catch (e: Exception) { 0 }

    // the same result (same file) is kept once; the newest KEEP are kept
    fun added(list: List<Result>, r: Result): List<Result> =
        if (list.lastOrNull()?.sig == r.sig) list else (list + r).takeLast(KEEP)

    fun toJson(list: List<Result>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("at", it.at).put("score", it.score).put("n", it.n).put("sig", it.sig)) }
    }.toString()

    fun fromJson(s: String?): List<Result> = try {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Result(o.getLong("at"), o.getDouble("score"), o.optInt("n"), o.optString("sig"))
        }
    } catch (e: Exception) { emptyList() }

    // circle text: "+12.5%", "-3.0%", "first result", "no result yet"
    fun circleText(prev: Result?, now: Result?): String = when {
        now == null -> "no result yet"
        prev == null -> "first result"
        else -> improvementPct(prev.score, now.score)?.let { "%+.1f%%".format(it) } ?: "-"
    }

    // ---------------- history ----------------

    fun history(k: Kind): List<Result> = fromJson(Cfg.raw("tr_hist_" + k.key))
    fun latest(k: Kind): Result? = history(k).lastOrNull()
    fun previous(k: Kind): Result? = history(k).let { if (it.size >= 2) it[it.size - 2] else null }
    fun improvement(k: Kind): Double? = improvementPct(previous(k)?.score, latest(k)?.score)

    @Synchronized
    fun record(k: Kind, score: Double, n: Int, sig: String) {
        val before = history(k)
        val after = added(before, Result(System.currentTimeMillis(), score, n, sig))
        if (after.size == before.size && after.lastOrNull()?.sig == before.lastOrNull()?.sig) return
        Cfg.setRaw("tr_hist_" + k.key, toJson(after))
        Diag.info("Training result: ${k.label} ${"%.1f".format(score * 100)}% ${k.measure}" +
            (improvement(k)?.let { " (%+.1f%% vs last training)".format(it) } ?: ""))
    }

    // ---------------- reading the result files ----------------

    private fun fileFor(k: Kind): String? = when (k) {
        Kind.MINIMAP -> "minimap_detector_report.json"
        Kind.SCREEN -> "screen_detector_report.json"
        Kind.LLM -> "tactical_llm_info.json"
        Kind.POLICY -> "mlbb_policies.zip"
        Kind.ENGINE, Kind.YOU -> null
    }

    // the result file's text (for the networks: policy_info_all.json inside the zip)
    private fun text(files: List<AutoFolder.Entry>, k: Kind): String? {
        val name = fileFor(k) ?: return null
        val e = files.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return e.open()?.use { input ->
            if (k != Kind.POLICY) input.readBytes().toString(Charsets.UTF_8)
            else ZipInputStream(input).use { z ->
                var found: String? = null
                while (true) {
                    val en = z.nextEntry ?: break
                    if (en.name.substringAfterLast('/') == "policy_info_all.json") { found = z.readBytes().toString(Charsets.UTF_8); break }
                }
                found
            }
        }
    }

    // read every result file now in the app's folder; a new one is added to its history
    fun refresh(ctx: Context) {
        val files = AutoFolder.files(ctx)
        for (k in Kind.values()) {
            if (k == Kind.ENGINE || k == Kind.YOU) continue
            try {
                val t = text(files, k) ?: continue
                val score = scoreOf(k, t) ?: continue
                record(k, score, countOf(t), t.hashCode().toString())
            } catch (e: Exception) { Diag.error("Reading the ${k.label} result", e) }
        }
    }

    // ---------------- the tactical engine (learns on the phone) ----------------

    private var fights = 0
    private var good = 0
    private var yourFights = 0
    private var yourGood = 0

    @Synchronized
    fun fightScored(reward: Float, byYou: Boolean = false) {
        if (byYou) { yourFights++; if (reward > 0f) yourGood++ } else { fights++; if (reward > 0f) good++ }
    }

    @Synchronized
    fun matchEnded() {
        val now = System.currentTimeMillis()
        if (fights >= MIN_FIGHTS) record(Kind.ENGINE, good.toDouble() / fights, fights, "match-$now")
        if (yourFights >= MIN_FIGHTS) record(Kind.YOU, yourGood.toDouble() / yourFights, yourFights, "you-$now")
        fights = 0; good = 0; yourFights = 0; yourGood = 0
    }

    // ---------------- export (for the GitHub upload) ----------------

    // every result with its history, and the raw result files; null when nothing changed since the last export
    fun exportJson(ctx: Context): String? {
        val files = try { AutoFolder.files(ctx) } catch (e: Exception) { emptyList() }
        val kinds = JSONObject()
        for (k in Kind.values()) {
            val h = history(k)
            val raw = try { text(files, k) } catch (e: Exception) { null }
            kinds.put(k.key, JSONObject().put("label", k.label).put("measure", k.measure)
                .put("improvement_pct", improvement(k) ?: JSONObject.NULL)
                .put("history", JSONArray(toJson(h)))
                .put("result_file", raw?.let { r -> try { JSONObject(r) } catch (e: Exception) { r.take(4000) } } ?: JSONObject.NULL))
        }
        // the fight-choice network's own report, when the networks zip has one
        val fight = try {
            files.firstOrNull { it.name.equals("mlbb_policies.zip", true) }?.open()?.use { i ->
                ZipInputStream(i).use { z ->
                    var f: String? = null
                    while (true) { val en = z.nextEntry ?: break; if (en.name.endsWith("fight_choice_info.json")) { f = z.readBytes().toString(Charsets.UTF_8); break } }
                    f
                }
            }
        } catch (e: Exception) { null }
        val body = JSONObject().put("kinds", kinds).put("fight_choice_info", fight?.let { try { JSONObject(it) } catch (e: Exception) { it } } ?: JSONObject.NULL)
        val hash = body.toString().hashCode().toString()
        if (Cfg.raw("tr_export_hash") == hash) return null
        Cfg.setRaw("tr_export_hash", hash)
        return body.put("app_version", com.sphynx.core.AppVersion.VERSION)
            .put("exported_at", System.currentTimeMillis()).toString(1)
    }

    // writes training-results-<time>.json into the recordings folder (uploaded with the debug data)
    fun export(ctx: Context): String {
        val json = exportJson(ctx) ?: return "training results: nothing new"
        val name = "training-results-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
        val f = File(ctx.cacheDir, name)
        f.writeText(json)
        return try { com.sphynx.core.SessionRecorder.exportFile(ctx, f, "application/json") } finally { f.delete() }
    }
}
