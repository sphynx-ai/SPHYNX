package com.sphynx.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// PRACTICE BENCHMARK (owner, 2026-11-10: "improve my AI on par with Moonton's AI or better").
// Pure logic, unit tested. One scorecard per VERIFIED practice match vs Computer in which the Player
// really played, so every later change to the AI has a number to beat. It decides nothing and never
// touches the game; it only reads what the play loop already knows.
//
// Per match: result, K/D/A, level reached (and at 5 / 10 minutes, the farm speed), length, how much
// of the match the Player played, idle time (the engine had nothing to do), stuck time (walking but
// the camera did not move), the time share of each intent (farm, fight, push, retreat, ...) and every
// death with its likely cause (what the AI was doing in the 5 s before, and whether it was outnumbered).
// Matches where a real player was detected, or where the Player played under 60 s, are not kept.
class PracticeBenchmark(private val maxRows: Int = 300) {

    companion object {
        const val MIN_PLAYED_MS = 60_000L      // the Player must have played at least a minute
        const val MIN_SECONDS = 120            // like the match log: shorter is a test, not a match
        const val CAUSE_WINDOW_MS = 5_000L     // what the AI was doing this long before a death
        const val MAX_STEP_MS = 1_000L         // a gap between ticks counts for at most this long
        const val COMPARE = 5                  // trend: the last 5 matches vs the 5 before

        // the AI's intent in the seconds before a death -> a short cause
        fun cause(intent: String?, outnumbered: Boolean): String {
            val base = when (intent) {
                "ENGAGE", "AMBUSH" -> "fighting"
                "RETREAT", "RECALL", "REGEN" -> "escaping"
                "JUNGLE", "LANE_CLEAR" -> "farming"
                "PUSH" -> "pushing"
                "OBJECTIVE" -> "at an objective"
                "GROUP" -> "grouping"
                null -> "unknown"
                else -> "other"
            }
            return if (outnumbered) "$base, outnumbered" else base
        }
    }

    data class Death(val atSec: Int, val cause: String)

    data class Row(
        val matchId: Long, val endedAt: Long, val hero: String, val lane: String, val win: Boolean?,
        val k: Int, val d: Int, val a: Int, val level: Int, val levelAt5: Int, val levelAt10: Int, val seconds: Int,
        val playedMs: Long, val inGameMs: Long, val idleMs: Long, val stuckMs: Long,
        val intentMs: Map<String, Long>, val deaths: List<Death>
    ) {
        val kda: Double get() = (k + a).toDouble() / maxOf(1, d)
        val playedShare: Double get() = if (inGameMs <= 0) 0.0 else playedMs.toDouble() / inGameMs
        val idleShare: Double get() = if (playedMs <= 0) 0.0 else idleMs.toDouble() / playedMs
        val stuckShare: Double get() = if (playedMs <= 0) 0.0 else stuckMs.toDouble() / playedMs

        fun toJson(): JSONObject = JSONObject().put("match", matchId).put("t", endedAt).put("hero", hero).put("lane", lane)
            .apply { if (win != null) put("win", win) }
            .put("k", k).put("d", d).put("a", a).put("lvl", level).put("lvl5", levelAt5).put("lvl10", levelAt10).put("sec", seconds)
            .put("played", playedMs).put("ingame", inGameMs).put("idle", idleMs).put("stuck", stuckMs)
            .put("intents", JSONObject().apply { intentMs.forEach { (k, v) -> put(k, v) } })
            .put("deaths", JSONArray(deaths.map { JSONObject().put("s", it.atSec).put("c", it.cause) }))

        companion object {
            fun fromJson(o: JSONObject): Row? = try {
                val im = o.optJSONObject("intents")
                val ds = o.optJSONArray("deaths")
                Row(o.getLong("match"), o.optLong("t"), o.optString("hero", "unknown"), o.optString("lane", "?"),
                    if (o.has("win")) o.getBoolean("win") else null,
                    o.getInt("k"), o.getInt("d"), o.getInt("a"), o.optInt("lvl"), o.optInt("lvl5"), o.optInt("lvl10"), o.getInt("sec"),
                    o.getLong("played"), o.getLong("ingame"), o.optLong("idle"), o.optLong("stuck"),
                    im?.keys()?.asSequence()?.associateWith { im.getLong(it) } ?: emptyMap(),
                    if (ds == null) emptyList() else (0 until ds.length()).map { ds.getJSONObject(it) }.map { Death(it.getInt("s"), it.getString("c")) })
            } catch (e: Exception) { null }
        }
    }

    // ---- the match being measured ----
    private var curId = 0L
    private var lastTickAt = 0L
    private var played = 0L; private var inGame = 0L; private var idle = 0L; private var stuck = 0L
    private val intents = HashMap<String, Long>()
    private val recent = ArrayDeque<Pair<Long, String>>()   // (time, intent) while the Player plays
    private val deaths = ArrayList<Death>()
    private var lastDeathSeen = 0L
    private var revoked = false
    private var verified = false
    private var lvl5 = 0; private var lvl10 = 0
    private var lastOutnumbered = false

    private fun reset(id: Long) {
        curId = id; lastTickAt = 0L; played = 0L; inGame = 0L; idle = 0L; stuck = 0L
        intents.clear(); recent.clear(); deaths.clear(); lastDeathSeen = 0L; revoked = false; verified = false; lvl5 = 0; lvl10 = 0; lastOutnumbered = false
    }

    /**
     * One play-loop tick during a match (in game, or dead / in the shop).
     * @param verdict the Player gate for this match (null while Player mode is off)
     * @param playing the Player actually played this tick (verdict PLAY and in game)
     * @param intent the engine's intent before any "keep walking" fill-in (null when not playing)
     * @param idleNow the engine had nothing to do (raw IDLE)
     * @param stuckNow walking, but the camera has not moved
     * @param deathAt the tracker's last death time (a new value = a new death)
     * @param outnumbered more enemy heroes than allied heroes (you included) near you; the last value seen while
     *        the Player played is used for a death (the death screen itself shows nobody)
     */
    @Synchronized fun tick(matchId: Long, nowMs: Long, verdict: PlayerGate.Verdict?, inGameNow: Boolean, playing: Boolean,
                           intent: String?, idleNow: Boolean, stuckNow: Boolean, matchSec: Int, level: Int,
                           deathAt: Long, outnumbered: Boolean) {
        if (matchId == 0L) return
        if (matchId != curId) { reset(matchId); lastDeathSeen = deathAt }
        if (verdict == PlayerGate.Verdict.LOCKED) revoked = true
        if (verdict == PlayerGate.Verdict.PLAY) verified = true
        val step = if (lastTickAt == 0L) 0L else (nowMs - lastTickAt).coerceIn(0L, MAX_STEP_MS)
        lastTickAt = nowMs
        // a new death first, judged on what came before it
        if (deathAt > lastDeathSeen) {
            lastDeathSeen = deathAt
            if (verified) deaths.add(Death(matchSec, cause(intentBefore(deathAt), lastOutnumbered)))
        }
        if (inGameNow) inGame += step
        if (playing) {
            played += step
            if (idleNow) idle += step
            if (stuckNow) stuck += step
            lastOutnumbered = outnumbered
            if (intent != null) {
                intents[intent] = (intents[intent] ?: 0L) + step
                recent.addLast(nowMs to intent)
            }
        }
        while (recent.isNotEmpty() && nowMs - recent.first().first > CAUSE_WINDOW_MS * 3) recent.removeFirst()
        if (matchSec in 0 until 300 && level > 0) lvl5 = level     // the last level seen before 5:00
        if (matchSec in 0 until 600 && level > 0) lvl10 = level    // ... and before 10:00
    }

    // the intent the AI held longest in the 5 s before t
    private fun intentBefore(t: Long): String? {
        val w = recent.filter { it.first in (t - CAUSE_WINDOW_MS)..t }
        return w.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key
    }

    /** The match ended. Returns the scorecard when it is kept (verified practice, the Player played), else null. */
    @Synchronized fun matchEnd(hero: String?, lane: String, k: Int, d: Int, a: Int, level: Int, seconds: Int, win: Boolean?, nowMs: Long): Row? {
        val id = curId
        val ok = id != 0L && verified && !revoked && played >= MIN_PLAYED_MS && seconds >= MIN_SECONDS && rows.none { it.matchId == id }
        val row = if (!ok) null else Row(id, nowMs, hero ?: "unknown", lane, win, k, d, a, level,
            if (seconds >= 300) lvl5 else 0, if (seconds >= 600) lvl10 else 0, seconds, played, inGame, idle, stuck,
            HashMap(intents), ArrayList(deaths))
        if (row != null) { rows.add(row); while (rows.size > maxRows) rows.removeAt(0) }
        reset(0L)
        return row
    }

    /** What is being measured now, for the panel. */
    @Synchronized fun live(): String = when {
        curId == 0L -> "no match"
        revoked -> "not counted: a real player was detected"
        !verified -> "not counted yet: practice not verified"
        else -> "measuring: Player played %d s, %d deaths".format(Locale.US, played / 1000, deaths.size)
    }

    // ---- stored scorecards ----
    private val rows = ArrayList<Row>()
    @Synchronized fun all(): List<Row> = rows.toList()
    @Synchronized fun clear() { rows.clear() }
    @Synchronized fun toJson(): String = JSONArray(rows.map { it.toJson() }).toString()
    @Synchronized fun load(text: String) {
        rows.clear()
        try { val arr = JSONArray(text); for (i in 0 until arr.length()) Row.fromJson(arr.getJSONObject(i))?.let { rows.add(it) } } catch (e: Exception) {}
    }

    // ---- summaries ----
    data class Summary(val matches: Int, val wins: Int, val known: Int, val kda: Double, val deathsPerMatch: Double,
                       val idleShare: Double, val stuckShare: Double, val levelAt10: Double?) {
        val winRate: Double? get() = if (known == 0) null else wins.toDouble() / known
    }

    fun summarize(rs: List<Row>): Summary? {
        if (rs.isEmpty()) return null
        val known = rs.filter { it.win != null }
        val d = rs.sumOf { it.d }
        val played = rs.sumOf { it.playedMs }.coerceAtLeast(1L)
        val l10 = rs.filter { it.levelAt10 > 0 }
        return Summary(rs.size, known.count { it.win == true }, known.size,
            (rs.sumOf { it.k } + rs.sumOf { it.a }).toDouble() / maxOf(1, d), d.toDouble() / rs.size,
            rs.sumOf { it.idleMs }.toDouble() / played, rs.sumOf { it.stuckMs }.toDouble() / played,
            if (l10.isEmpty()) null else l10.map { it.levelAt10 }.average())
    }

    /** Death causes over all kept matches, most frequent first. */
    @Synchronized fun deathCauses(): List<Pair<String, Int>> =
        rows.flatMap { it.deaths }.groupingBy { it.cause }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    private fun fmt(s: Summary?): String = if (s == null) "no matches" else buildString {
        append("${s.matches} matches")
        s.winRate?.let { append(", won ${s.wins} of ${s.known} (${(it * 100).toInt()}%)") }
        append(", KDA %.2f, %.1f deaths/match, idle %d%%, stuck %d%%".format(Locale.US, s.kda, s.deathsPerMatch,
            (s.idleShare * 100).toInt(), (s.stuckShare * 100).toInt()))
        s.levelAt10?.let { append(", level %.1f at 10 min".format(Locale.US, it)) }
    }

    /** The benchmark page text: overall, trend (last 5 vs the 5 before), per hero, death causes. */
    @Synchronized fun text(): String {
        if (rows.isEmpty()) return "No practice scorecards yet. A match counts when it is verified as practice vs Computer " +
            "(open the scoreboard once) and the Player plays at least a minute of it."
        return buildString {
            append("ALL: ").append(fmt(summarize(rows))).append('\n')
            if (rows.size >= COMPARE * 2) {
                val last = rows.takeLast(COMPARE); val before = rows.dropLast(COMPARE).takeLast(COMPARE)
                append("TREND (last $COMPARE vs the $COMPARE before)\n")
                append("  now:    ").append(fmt(summarize(last))).append('\n')
                append("  before: ").append(fmt(summarize(before))).append('\n')
            } else append("TREND: needs ${COMPARE * 2} matches (${rows.size} so far)\n")
            append("BY HERO\n")
            rows.groupBy { it.hero }.entries.sortedByDescending { it.value.size }.take(10).forEach { (h, g) ->
                append("  ").append(h).append(": ").append(fmt(summarize(g))).append('\n')
            }
            val causes = deathCauses()
            if (causes.isNotEmpty()) {
                append("DEATHS BY CAUSE\n")
                causes.take(8).forEach { (c, n) -> append("  ").append(c).append(": ").append(n).append('\n') }
            }
        }.trimEnd()
    }

    /** One line per match, for the export (spreadsheet friendly). */
    @Synchronized fun csv(): String = buildString {
        val keys = listOf("JUNGLE", "LANE_CLEAR", "ENGAGE", "AMBUSH", "RETREAT", "RECALL", "REGEN", "PUSH", "GROUP", "OBJECTIVE", "IDLE")
        append("ended_at,match,hero,lane,win,k,d,a,kda,level,level_5min,level_10min,seconds,played_s,played_share,idle_share,stuck_share,")
        append(keys.joinToString(",") { "t_" + it.lowercase() }).append(",deaths\n")
        rows.forEach { r ->
            append(listOf(r.endedAt, r.matchId, r.hero, r.lane, r.win?.toString() ?: "", r.k, r.d, r.a,
                "%.2f".format(Locale.US, r.kda), r.level, r.levelAt5, r.levelAt10, r.seconds, r.playedMs / 1000,
                "%.2f".format(Locale.US, r.playedShare), "%.3f".format(Locale.US, r.idleShare), "%.3f".format(Locale.US, r.stuckShare)
            ).joinToString(","))
            append(',').append(keys.joinToString(",") { ((r.intentMs[it] ?: 0L) / 1000).toString() })
            append(",\"").append(r.deaths.joinToString("; ") { "${it.atSec / 60}:%02d %s".format(Locale.US, it.atSec % 60, it.cause) }).append("\"\n")
        }
    }
}
