package com.sphynx.adapters.mlbb

import com.sphynx.universal.coach.MatchContext

/**
 * PHASE 12, step C2: MLBB's screens turned into match events for the shared context. It compares two readings and
 * reports what changed: the match starting or ending, you going down or coming back, the death timers' counts of fallen
 * enemies and allies rising, an objective (turtle / lord) appearing or its health reaching zero. Each event names its
 * source (the screen reading it came from). Only what was read counts; nothing is guessed. Pure, unit-tested.
 */
object MlbbEvents {

    /** One reading of the screen, reduced to what the events need (null = not read). */
    data class Reading(val atMs: Long, val inMatch: Boolean, val dead: Boolean, val matchSec: Int?,
                       val enemiesDead: Int?, val alliesDead: Int?, val objective: String?, val objectiveHp: Float?)

    fun diff(prev: Reading?, cur: Reading): List<MatchContext.Event> {
        val out = ArrayList<MatchContext.Event>()
        fun ev(t: MatchContext.Type, detail: String, src: String) = out.add(MatchContext.Event(t, cur.atMs, cur.matchSec, detail, src))
        if (prev == null || !prev.inMatch) { if (cur.inMatch) ev(MatchContext.Type.MATCH_START, "", "screen phase"); if (prev == null) return out }
        if (prev.inMatch && !cur.inMatch) { ev(MatchContext.Type.MATCH_END, "", "screen phase"); return out }
        if (!cur.inMatch) return out
        if (!prev.dead && cur.dead) ev(MatchContext.Type.SELF_DOWN, "", "screen phase")
        if (prev.dead && !cur.dead) ev(MatchContext.Type.SELF_BACK, "", "screen phase")
        val e0 = prev.enemiesDead; val e1 = cur.enemiesDead
        if (e0 != null && e1 != null && e1 > e0) repeat(e1 - e0) { k -> ev(MatchContext.Type.ENEMY_DOWN, "${e0 + k + 1} down", "death timers") }
        val a0 = prev.alliesDead; val a1 = cur.alliesDead
        if (a0 != null && a1 != null && a1 > a0) repeat(a1 - a0) { k -> ev(MatchContext.Type.ALLY_DOWN, "${a0 + k + 1} down", "death timers") }
        if (cur.objective != null && prev.objective == null) ev(MatchContext.Type.OBJECTIVE_SEEN, cur.objective, "screen objective bar")
        val h0 = prev.objectiveHp; val h1 = cur.objectiveHp
        if (prev.objective != null && h0 != null && h0 > 0.05f && (h1 == null || h1 <= 0.02f) && cur.objective == prev.objective && h1 != null)
            ev(MatchContext.Type.OBJECTIVE_TAKEN, prev.objective, "screen objective bar")
        return out
    }
}
