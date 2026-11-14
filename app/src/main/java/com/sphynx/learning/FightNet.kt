package com.sphynx.learning

import android.content.Context
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File

// THE FIGHT-CHOICE NETWORK: a small numeric network (trained in the tactical-network notebook from
// every saved fight moment - yours, the AI's and the live matches you watched - and how each went)
// that picks ALL_IN / POKE / FARM / RETREAT / GROUP for the moment, in well under a millisecond.
// It sits in the small model's slot: ADVICE that the Kotlin checks must allow (TacticalAdvisor.check),
// and the safety rules still come after it.
// Its input is the SAME fight summary the small model reads, turned into 26 numbers by features()
// below - the notebook does exactly the same with the saved summaries (keep the two in step).
object FightNet {

    val OPTIONS = listOf(FightOption.ALL_IN, FightOption.POKE, FightOption.FARM, FightOption.RETREAT, FightOption.GROUP)
    const val N = 26

    @Volatile private var interpreter: Interpreter? = null
    @Volatile var ready = false
        private set
    @Volatile var info = "-"
        private set
    @Volatile var lastAdvice = "-"
        private set
    @Volatile var asked = 0; private set

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "fight_choice.tflite")
    fun saveInfo(json: String) { Cfg.setRaw("fight_choice_info", json); if (ready) info = "loaded · " + quality() }
    // trained, but its report says it learned nothing useful: it gives no advice (ModelQuality)
    val weakReason: String? get() = ModelQuality.weakReason(Cfg.raw("fight_choice_info"))
    val usable: Boolean get() = ready && weakReason == null

    // how good the trained network was on fights it never saw (from the notebook)
    fun quality(): String = Cfg.raw("fight_choice_info")?.let {
        try {
            val o = JSONObject(it)
            "right %d%% (always the most common answer: %d%%), balanced %d%% on %d test moments, trained on %d".format(
                (o.optDouble("acc") * 100).toInt(), (o.optDouble("base") * 100).toInt(), (o.optDouble("balanced") * 100).toInt(),
                o.optInt("test"), o.optInt("train"))
        } catch (e: Exception) { null }
    }?.let { q -> weakReason?.let { "TOO WEAK, NOT USED ($it) · $q" } ?: q } ?: "no quality report"

    @Synchronized
    fun load(ctx: Context): String? {
        close()
        val f = file(ctx)
        if (!f.exists()) { info = "not trained yet (Learn page: Train the tactical networks)"; return info }
        return try {
            val it = Interpreter(f, Interpreter.Options().setNumThreads(1))
            val inShape = it.getInputTensor(0).shape()
            if (inShape.last() != N) { it.close(); info = "the file has ${inShape.last()} inputs, this app needs $N: retrain"; return info }
            interpreter = it; ready = true
            info = "loaded · " + quality()
            Diag.info("Fight-choice network loaded")
            null
        } catch (t: Throwable) {
            Diag.error("Fight-choice network", t); info = "could not load: ${t.message}"; info
        }
    }

    @Synchronized
    fun close() { try { interpreter?.close() } catch (e: Exception) { }; interpreter = null; ready = false }

    // the network's pick for this fight summary (null = not loaded / not sure enough)
    @Synchronized
    fun advise(summary: String): Pair<FightOption, Float>? {
        val it = interpreter ?: return null
        if (weakReason != null) { lastAdvice = "none (too weak, not used)"; return null }
        val x = arrayOf(features(summary))
        val out = arrayOf(FloatArray(OPTIONS.size))
        it.run(x, out)
        asked++
        val p = out[0]
        val i = p.indices.maxByOrNull { p[it] } ?: return null
        lastAdvice = "${OPTIONS[i].label} (${(p[i] * 100).toInt()}%)"
        return OPTIONS[i] to p[i]
    }

    // ---- the summary -> 26 numbers (the notebook's fight_features() does the same) ----
    //  0-5 role (tank fighter assassin mage marksman support), 6 ranged, 7 minutes/20, 8 time known,
    //  9 my HP, 10 level/15, 11 skills ready/3, 12 ultimate ready, 13 spell ready, 14 enemies close/5,
    //  15 lowest enemy HP, 16 mean enemy HP, 17 nearest in reach, 18 allies close/4, 19 minions/6,
    //  20 under enemy tower, 21 enemies missing/5, 22 attack score, 23 retreat score, 24 risk, 25 scores known
    fun features(s: String): FloatArray {
        val f = FloatArray(N)
        val t = s.lowercase()
        val roles = listOf("tank", "fighter", "assassin", "mage", "marksman", "support")
        Regex("\\(([a-z]+), (ranged|melee)\\)").find(t)?.let { m ->
            roles.indexOf(m.groupValues[1]).takeIf { it >= 0 }?.let { f[it] = 1f }
            f[6] = if (m.groupValues[2] == "ranged") 1f else 0f
        }
        Regex("time (\\d+):(\\d+)").find(t)?.let { m -> f[7] = ((m.groupValues[1].toFloat() + m.groupValues[2].toFloat() / 60f) / 20f).coerceAtMost(1f); f[8] = 1f }
        Regex("hp (\\d+)%, level (\\d+)").find(t)?.let { m -> f[9] = m.groupValues[1].toFloat() / 100f; f[10] = m.groupValues[2].toFloat() / 15f }
        Regex("ready: ([a-z0-9 ]+?)(,|\\n|$)").find(t)?.let { m ->
            val r = m.groupValues[1].trim()
            if (r != "none") { val parts = r.split(" ").filter { it.isNotBlank() }; f[11] = parts.size / 3f; f[12] = if ("ult" in parts) 1f else 0f }
        }
        if (Regex("spell [a-z ]+ ready").containsMatchIn(t)) f[13] = 1f
        Regex("enemies close: (\\d+)").find(t)?.let { f[14] = (it.groupValues[1].toFloat() / 5f).coerceAtMost(1f) }
        val hps = Regex("enemies close: \\d+ \\(hp ([0-9% ]+)\\)").find(t)?.groupValues?.get(1)
            ?.split(" ")?.mapNotNull { it.trimEnd('%').toFloatOrNull() }.orEmpty()
        f[15] = (hps.minOrNull() ?: 100f) / 100f
        f[16] = (if (hps.isEmpty()) 100f else hps.average().toFloat()) / 100f
        f[17] = if ("nearest in reach" in t) 1f else 0f
        Regex("allies close: (\\d+)").find(t)?.let { f[18] = (it.groupValues[1].toFloat() / 4f).coerceAtMost(1f) }
        Regex("minions: (\\d+)").find(t)?.let { f[19] = (it.groupValues[1].toFloat() / 6f).coerceAtMost(1f) }
        f[20] = if ("under enemy tower: yes" in t) 1f else 0f
        Regex("missing from map: (\\d+)").find(t)?.let { f[21] = (it.groupValues[1].toFloat() / 5f).coerceAtMost(1f) }
        Regex("scores: attack ([0-9.]+), retreat ([0-9.]+), risk ([0-9.]+)").find(t)?.let { m ->
            f[22] = m.groupValues[1].toFloat(); f[23] = m.groupValues[2].toFloat(); f[24] = m.groupValues[3].toFloat(); f[25] = 1f
        }
        return f
    }
}
