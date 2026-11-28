package com.sphynx.universal.coach

/**
 * PHASE 12, step C6 (roadmap 6.1.2 A): the post-match review, built only from the match's own recorded evidence (the
 * MatchContext events). [prompt] gives the strategic model the facts (counts and a short timeline, gaps named as
 * missing evidence) in the same form as the Phase 10 review set; [confidence] says how much evidence there was. The
 * model's answer is shown with its evidence lines, never as fact. Game-free (the adapter gives the game's name), pure,
 * unit-tested. Advice only.
 */
object MatchReview {

    enum class Confidence(val label: String) { HIGH("good evidence"), MEDIUM("some evidence"), LOW("little evidence") }

    /** The facts of the match, one line each, from its events. */
    fun facts(events: List<MatchContext.Event>, hero: String?, lane: String?): List<String> {
        val start = events.firstOrNull { it.type == MatchContext.Type.MATCH_START }?.atMs ?: events.firstOrNull()?.atMs ?: 0L
        fun n(t: MatchContext.Type) = events.count { it.type == t }
        val out = ArrayList<String>()
        out.add(listOfNotNull(hero?.let { "Hero $it" }, lane).joinToString(", ").ifEmpty { "Hero unknown" })
        out.add("You went down ${n(MatchContext.Type.SELF_DOWN)} times; ${n(MatchContext.Type.ENEMY_DOWN)} enemies and " +
            "${n(MatchContext.Type.ALLY_DOWN)} allies went down while you were watching")
        val objs = events.filter { it.type == MatchContext.Type.OBJECTIVE_TAKEN }.map { it.detail }
        if (objs.isNotEmpty()) out.add("Objectives taken (by either team): " + objs.joinToString())
        val downs = events.filter { it.type == MatchContext.Type.SELF_DOWN }.mapNotNull { it.matchSec }
        if (downs.isNotEmpty()) out.add("You went down at " + downs.joinToString { "%d:%02d".format(it / 60, it % 60) })
        val gaps = n(MatchContext.Type.GAP)
        if (gaps > 0) out.add("$gaps parts of the match were not observed (no evidence for them)")
        val end = events.lastOrNull { it.type == MatchContext.Type.MATCH_END }?.atMs
        if (end != null && end > start) out.add("Watched for ${(end - start) / 60_000} min")
        return out
    }

    fun confidence(events: List<MatchContext.Event>): Confidence {
        val real = events.count { it.type != MatchContext.Type.GAP && it.type != MatchContext.Type.PHASE }
        val gaps = events.count { it.type == MatchContext.Type.GAP }
        return when { real >= 8 && gaps <= 1 -> Confidence.HIGH; real >= 4 -> Confidence.MEDIUM; else -> Confidence.LOW }
    }

    /** The question for the strategic model (the form of the Phase 10 review set). */
    fun prompt(game: String, facts: List<String>): String =
        "You coach a $game player after a match. Match: " + facts.joinToString(". ") + ". Write a short review: what went " +
            "wrong or right, and one thing to do next time. Use only these facts. At most four sentences."
}
