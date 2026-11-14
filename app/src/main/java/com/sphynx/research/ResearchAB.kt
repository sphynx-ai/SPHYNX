package com.sphynx.research

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// Research mode, phase R5 (pure logic, unit tested): does the R3 experiment help?
// A controlled comparison INSIDE verified practice only: each verified practice match is put in one
// arm by its match id - ARBITER (the decision arbiter picks the fight option) or NORMAL (the app's
// usual order). Same opponents (Computer), same app, so the arms differ only in who picks.
// Per match it keeps the learning brain's fight scores (the reward it already computes 4 s after
// each AI fight choice), fight deaths, K/D/A, length and result. The report compares the arms with
// the mean fight score and a ~95% interval of the difference, and says "not enough data" until both
// arms have MIN_MATCHES matches. It decides nothing by itself: tuning stays a reviewed change.
class ResearchAB(private val maxRows: Int = 300) {

    enum class Arm { ARBITER, NORMAL }

    companion object {
        const val MIN_MATCHES = 5
        const val MIN_SECONDS = 120
        fun armOf(matchId: Long): Arm = if (Math.floorMod(matchId, 2L) == 0L) Arm.ARBITER else Arm.NORMAL
    }

    data class Row(val matchId: Long, val arm: Arm, val fights: Int, val rewardSum: Double, val rewardSq: Double,
                   val fightDeaths: Int, val k: Int, val d: Int, val a: Int, val seconds: Int, val win: Boolean?) {
        fun toJson(): JSONObject = JSONObject().put("match", matchId).put("arm", arm.name).put("fights", fights)
            .put("rs", rewardSum).put("rq", rewardSq).put("fd", fightDeaths).put("k", k).put("d", d).put("a", a)
            .put("sec", seconds).apply { if (win != null) put("win", win) }
        companion object {
            fun fromJson(o: JSONObject): Row? = try {
                Row(o.getLong("match"), Arm.valueOf(o.getString("arm")), o.getInt("fights"), o.getDouble("rs"), o.getDouble("rq"),
                    o.getInt("fd"), o.getInt("k"), o.getInt("d"), o.getInt("a"), o.getInt("sec"), if (o.has("win")) o.getBoolean("win") else null)
            } catch (e: Exception) { null }
        }
    }

    private val rows = ArrayList<Row>()
    // the match being measured (only verified practice fights reach it)
    private var curId = 0L; private var curFights = 0; private var curRs = 0.0; private var curRq = 0.0; private var curFd = 0

    // an AI fight choice in a verified practice match, scored by the learning brain
    @Synchronized fun fight(matchId: Long, reward: Float, died: Boolean) {
        if (matchId == 0L) return
        if (matchId != curId) { curId = matchId; curFights = 0; curRs = 0.0; curRq = 0.0; curFd = 0 }
        curFights++; curRs += reward; curRq += reward.toDouble() * reward; if (died) curFd++
    }

    // the match ended (the match being measured is the one the fights came from): keeps it if it had
    // verified fights and lasted long enough
    @Synchronized fun matchEnd(k: Int, d: Int, a: Int, seconds: Int, win: Boolean?): Boolean {
        val id = curId
        val ok = id != 0L && curFights > 0 && seconds >= MIN_SECONDS && rows.none { it.matchId == id }
        if (ok) {
            rows.add(Row(id, armOf(id), curFights, curRs, curRq, curFd, k, d, a, seconds, win))
            while (rows.size > maxRows) rows.removeAt(0)
        }
        curId = 0L; curFights = 0; curRs = 0.0; curRq = 0.0; curFd = 0
        return ok
    }

    @Synchronized fun all(): List<Row> = rows.toList()
    @Synchronized fun clear() { rows.clear(); curId = 0L; curFights = 0 }
    @Synchronized fun toJson(): String = JSONArray(rows.map { it.toJson() }).toString()
    @Synchronized fun load(text: String) {
        rows.clear()
        try { val a = JSONArray(text); for (i in 0 until a.length()) Row.fromJson(a.getJSONObject(i))?.let { rows.add(it) } } catch (e: Exception) {}
    }

    data class Group(val matches: Int, val fights: Int, val meanReward: Double, val seReward: Double,
                     val deathsPerMatch: Double, val kda: Double, val wins: Int, val known: Int)

    fun group(rs: List<Row>): Group? {
        if (rs.isEmpty()) return null
        val f = rs.sumOf { it.fights }
        val mean = rs.sumOf { it.rewardSum } / maxOf(1, f)
        val varr = (rs.sumOf { it.rewardSq } / maxOf(1, f) - mean * mean).coerceAtLeast(0.0)
        val d = rs.sumOf { it.d }
        val known = rs.filter { it.win != null }
        return Group(rs.size, f, mean, sqrt(varr / maxOf(1, f)), d.toDouble() / rs.size,
            (rs.sumOf { it.k } + rs.sumOf { it.a }).toDouble() / maxOf(1, d), known.count { it.win == true }, known.size)
    }

    data class Report(val arbiter: Group?, val normal: Group?, val enough: Boolean, val diff: Double?, val low: Double?, val high: Double?, val verdict: String)

    @Synchronized fun report(): Report {
        val a = group(rows.filter { it.arm == Arm.ARBITER }); val n = group(rows.filter { it.arm == Arm.NORMAL })
        val enough = (a?.matches ?: 0) >= MIN_MATCHES && (n?.matches ?: 0) >= MIN_MATCHES
        if (a == null || n == null || !enough)
            return Report(a, n, false, null, null, null,
                "not enough data: ${a?.matches ?: 0} arbiter and ${n?.matches ?: 0} normal practice matches (need $MIN_MATCHES each)")
        val diff = a.meanReward - n.meanReward
        val se = sqrt(a.seReward * a.seReward + n.seReward * n.seReward)
        val lo = diff - 1.96 * se; val hi = diff + 1.96 * se
        val verdict = when {
            lo > 0 -> "the arbiter scores better in fights"
            hi < 0 -> "the arbiter scores WORSE in fights"
            else -> "no clear difference yet"
        }
        return Report(a, n, true, diff, lo, hi, verdict)
    }

    fun text(): String {
        val r = report()
        fun g(name: String, x: Group?) = if (x == null) "$name: no matches" else
            "%s: %d matches, %d fights, fight score %+.3f ± %.3f, deaths %.1f/match, KDA %.2f%s".format(java.util.Locale.US,
                name, x.matches, x.fights, x.meanReward, x.seReward, x.deathsPerMatch, x.kda,
                if (x.known > 0) ", won ${x.wins} of ${x.known}" else "")
        return buildString {
            append("Experiment (verified practice only): ").append(r.verdict)
            if (r.diff != null) append("; difference %+.3f (95%%: %+.3f..%+.3f)".format(java.util.Locale.US, r.diff, r.low, r.high))
            append("\n").append(g("Arbiter", r.arbiter)).append("\n").append(g("Normal", r.normal))
        }
    }
}
