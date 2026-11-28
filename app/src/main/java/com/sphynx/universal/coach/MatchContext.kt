package com.sphynx.universal.coach

/**
 * PHASE 12, step C2 (WP-02 portion): the shared match context, a timestamped history of match events that the coach,
 * the post-match review and later prediction read. Each event keeps its source (provenance) and confidence. Events
 * arriving out of order are put in order; the same event twice within a second is kept once; a jump in the match clock
 * is recorded as a GAP (missing evidence, never filled in); a context with no fresh observation is STALE. Game-free and
 * pure: the game's adapter turns its own screens into these events. Unit-tested.
 */
class MatchContext(private val maxEvents: Int = 400) {

    enum class Type(val label: String) {
        MATCH_START("match started"), MATCH_END("match ended"), PHASE("phase"), SELF_DOWN("you went down"),
        SELF_BACK("you are back"), ENEMY_DOWN("enemy down"), ALLY_DOWN("ally down"), OBJECTIVE_SEEN("objective seen"),
        OBJECTIVE_TAKEN("objective taken"), STRUCTURE_LOST("structure lost"), GAP("no evidence")
    }

    data class Event(val type: Type, val atMs: Long, val matchSec: Int?, val detail: String = "", val source: String,
                     val confidence: Float? = null)

    private val events = ArrayList<Event>()
    @Volatile var lastObservedMs = 0L; private set
    @Volatile var lastMatchSec: Int? = null; private set

    @Synchronized fun all(): List<Event> = events.toList()

    @Synchronized fun reset() { events.clear(); lastObservedMs = 0; lastMatchSec = null }

    /** The game was observed at [nowMs] with the match clock at [matchSec] (null = not read): freshness and gaps. */
    @Synchronized fun observe(nowMs: Long, matchSec: Int?) {
        val prev = lastMatchSec
        if (matchSec != null && prev != null && matchSec - prev > GAP_SEC)
            insert(Event(Type.GAP, nowMs, matchSec, "${matchSec - prev} s of the match not observed", "context"))
        lastObservedMs = maxOf(lastObservedMs, nowMs)
        if (matchSec != null) lastMatchSec = matchSec
    }

    /** Adds [e] in time order; the same type and detail again within [DUP_MS] is not kept twice. Returns whether kept. */
    @Synchronized fun add(e: Event): Boolean {
        if (events.any { it.type == e.type && it.detail == e.detail && kotlin.math.abs(it.atMs - e.atMs) < DUP_MS }) return false
        insert(e)
        return true
    }

    private fun insert(e: Event) {
        val i = events.indexOfFirst { it.atMs > e.atMs }.let { if (it < 0) events.size else it }
        events.add(i, e)
        while (events.size > maxEvents) events.removeAt(0)
    }

    /** No fresh observation for [maxAgeMs]: what the context says may be out of date. */
    fun stale(nowMs: Long, maxAgeMs: Long = STALE_MS): Boolean = lastObservedMs == 0L || nowMs - lastObservedMs > maxAgeMs

    @Synchronized fun recent(nowMs: Long, windowMs: Long): List<Event> = events.filter { nowMs - it.atMs <= windowMs }

    @Synchronized fun count(type: Type, sinceMs: Long = 0): Int = events.count { it.type == type && it.atMs >= sinceMs }

    /** A short summary for the coach and the review: "last 3 min: 2 enemies down, you went down once; 1 gap". */
    @Synchronized fun summary(nowMs: Long, windowMs: Long = 180_000): String {
        val r = recent(nowMs, windowMs)
        if (r.isEmpty()) return if (stale(nowMs)) "no recent evidence" else "nothing happened recently"
        val parts = Type.values().mapNotNull { t -> r.count { it.type == t }.takeIf { it > 0 }?.let { n -> "$n × ${t.label}" } }
        return "last ${windowMs / 60_000} min: " + parts.joinToString(", ") + if (stale(nowMs)) " (out of date)" else ""
    }

    companion object {
        const val DUP_MS = 1_000L
        const val GAP_SEC = 30
        const val STALE_MS = 5_000L
    }
}
