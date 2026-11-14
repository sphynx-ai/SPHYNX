package com.sphynx.learning

import com.sphynx.core.Cfg
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// SMALL MODEL ON vs OFF: does the small tactical model really help? Every match the AI plays is
// saved with whether the small model was on (and loaded) the WHOLE match, and how it went: the fight
// results the learning brain scores (damage dealt, kills, HP kept, death), deaths, K/D/A, win / loss,
// and how much of the small model's advice was used. The Model page compares the two groups, in
// percent, from your own matches - instead of a guess.
object ABCompare {

    // which fight adviser played this match: "off" (none), "small" (small model), "net" (fight-choice
    // network); null = it changed during the match (not counted)
    private var on: String? = null
    private var fights = 0
    private var rewardSum = 0.0
    private var rewardSq = 0.0
    private var fightDeaths = 0
    private var used0 = 0
    private var rejected0 = 0

    private fun smallOn(): String = when {
        Cfg.fightNetOn && FightNet.usable -> "net"
        com.sphynx.ai.TacticalAdvisor.active -> "small"
        else -> "off"
    }
    private fun modeOf(o: JSONObject): String = o.optString("mode").ifEmpty { if (o.optBoolean("on")) "small" else "off" }

    @Synchronized
    fun matchStart() {
        on = smallOn(); fights = 0; rewardSum = 0.0; rewardSq = 0.0; fightDeaths = 0
        used0 = com.sphynx.ai.TacticalAdvisor.used; rejected0 = com.sphynx.ai.TacticalAdvisor.rejected
    }

    // a fight choice of the AI, scored 4 s later by the learning brain
    @Synchronized
    fun fight(reward: Float, died: Boolean, byYou: Boolean) {
        if (byYou) return
        if (on == null && fights == 0 && rewardSum == 0.0) on = smallOn()
        if (on != null && on != smallOn()) on = null              // switched during the match: not counted
        fights++; rewardSum += reward; rewardSq += reward * reward
        if (died) fightDeaths++
    }

    @Synchronized
    fun matchEnd(aiPlayed: Boolean, k: Int, d: Int, a: Int, seconds: Int, win: Boolean?) {
        val o = on
        if (aiPlayed && o != null && o == smallOn() && seconds >= 120 && fights > 0) {
            val arr = try { JSONArray(Cfg.raw("ab_log") ?: "[]") } catch (e: Exception) { JSONArray() }
            arr.put(JSONObject().put("t", System.currentTimeMillis()).put("mode", o).put("k", k).put("d", d).put("a", a)
                .put("sec", seconds).put("fights", fights).put("rs", rewardSum).put("rq", rewardSq).put("fd", fightDeaths)
                .put("used", com.sphynx.ai.TacticalAdvisor.used - used0)
                .put("rej", com.sphynx.ai.TacticalAdvisor.rejected - rejected0)
                .apply { if (win != null) put("win", win) })
            val keep = JSONArray()
            for (i in maxOf(0, arr.length() - 200) until arr.length()) keep.put(arr.get(i))
            Cfg.setRaw("ab_log", keep.toString())
        }
        matchStart()
    }

    private class G(val n: Int, val fights: Int, val reward: Double, val rewardSe: Double, val deaths: Double, val kda: Double,
                    val wins: Int, val known: Int, val used: Int, val rej: Int)

    private fun group(rows: List<JSONObject>): G? {
        if (rows.isEmpty()) return null
        val f = rows.sumOf { it.optInt("fights") }
        val rs = rows.sumOf { it.optDouble("rs") }; val rq = rows.sumOf { it.optDouble("rq") }
        val mean = rs / maxOf(1, f)
        val varr = (rq / maxOf(1, f) - mean * mean).coerceAtLeast(0.0)
        val k = rows.sumOf { it.optInt("k") }; val d = rows.sumOf { it.optInt("d") }; val a = rows.sumOf { it.optInt("a") }
        val known = rows.filter { it.has("win") }
        return G(rows.size, f, mean, sqrt(varr / maxOf(1, f)), d.toDouble() / rows.size, (k + a).toDouble() / maxOf(1, d),
            known.count { it.optBoolean("win") }, known.size, rows.sumOf { it.optInt("used") }, rows.sumOf { it.optInt("rej") })
    }

    fun summary(): String {
        val arr = try { JSONArray(Cfg.raw("ab_log") ?: "[]") } catch (e: Exception) { JSONArray() }
        val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
        val off = group(rows.filter { modeOf(it) == "off" })
        val names = linkedMapOf("small" to "Small model", "net" to "Fight-choice network")
        fun line(name: String, g: G?) = if (g == null) "$name: no matches yet" else
            "$name: ${g.n} matches, ${g.fights} fights · fight score %+.2f · deaths %.1f/match · KDA %.1f".format(g.reward, g.deaths, g.kda) +
                (if (g.known > 0) " · won ${g.wins}/${g.known}" else "") + (if (g.used + g.rej > 0) " · advice used ${g.used}, rejected ${g.rej}" else "")
        val sb = StringBuilder(line("No adviser", off))
        for ((k, label) in names) {
            val on = group(rows.filter { modeOf(it) == k }) ?: continue
            sb.append("\n\n").append(line(label, on))
            if (off == null) { sb.append("\nPlay some matches with no adviser on too, to compare."); continue }
            val diff = on.reward - off.reward
            val se = sqrt(on.rewardSe * on.rewardSe + off.rewardSe * off.rewardSe)
            val base = maxOf(0.25, kotlin.math.abs(off.reward))
            fun pct(a: Double, b: Double) = if (b == 0.0) 0.0 else (a - b) / kotlin.math.abs(b) * 100
            sb.append("\nvs no adviser: fight score %+.0f%%, deaths %+.0f%%, KDA %+.0f%%".format(diff / base * 100, pct(on.deaths, off.deaths), pct(on.kda, off.kda)))
            if (on.known > 0 && off.known > 0) sb.append(", win rate %d%% vs %d%%".format(on.wins * 100 / on.known, off.wins * 100 / off.known))
            sb.append('\n').append(when {
                on.n < 5 || off.n < 5 -> "Too early to tell: at least 5 matches each (${on.n} with it, ${off.n} without)."
                kotlin.math.abs(diff) < 2 * se -> "No clear difference yet (within the normal ups and downs of fights)."
                diff > 0 -> "$label HELPS in fights (a real difference, not luck)."
                else -> "$label makes fights WORSE: leave it off and retrain it with more fights."
            })
        }
        if (rows.isEmpty()) sb.append("\nPlay matches with the AI with and without an adviser (the setting must stay the same for the whole match).")
        return sb.toString()
    }

    fun clear() = Cfg.setRaw("ab_log", null)
}
