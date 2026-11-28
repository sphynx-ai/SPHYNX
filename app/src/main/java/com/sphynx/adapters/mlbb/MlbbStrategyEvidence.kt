package com.sphynx.adapters.mlbb

/**
 * PHASE 13, step D5 (roadmap 13.4, 13.6, 13.9, 13.10): experimental compositions and rotations as hypotheses, kept apart
 * from recommendations; their evidence (each supporting match with its source, patch and outcome); and the validation
 * and promotion rules. A hypothesis is promoted to "validated" only past the documented thresholds (enough matches,
 * from more than one team and tournament, a win rate above the baseline by more than its own uncertainty, on the
 * current patch); otherwise it stays experimental or emerging, and old patches make it "outdated". Tags are metadata,
 * never evidence. Pure, unit-tested.
 */
object MlbbStrategyEvidence {

    enum class Kind { COMPOSITION, ROTATION }
    enum class Level(val label: String) { EXPERIMENTAL("experimental"), EMERGING("emerging"), VALIDATED("validated"), OUTDATED("outdated") }
    enum class Context { SOLO, DUO_TRIO, FIVE_STACK, PROFESSIONAL }

    /** One match that supports or contradicts a hypothesis: where it came from and how it ended. */
    data class Match(val id: String, val source: String, val link: String?, val patch: String, val dateMs: Long,
                     val team: String?, val tournament: String?, val won: Boolean, val complete: Boolean)

    data class Hypothesis(val id: String, val kind: Kind, val title: String, val description: String, val context: Context,
                          val tags: Set<String>, val matches: List<Match> = emptyList(), val baselineWinRate: Double = 0.5,
                          val limitations: List<String> = emptyList())

    // the promotion thresholds (docs/PHASE_13_MLBB_PLAN.md section 3, D5)
    const val MIN_MATCHES_EMERGING = 5
    const val MIN_MATCHES_VALIDATED = 30
    const val MIN_TEAMS = 3
    const val MIN_TOURNAMENTS = 2

    data class Assessment(val level: Level, val matches: Int, val winRate: Double?, val low: Double?, val high: Double?, val why: String)

    private fun wilson(k: Int, n: Int): Pair<Double, Double> {
        if (n == 0) return 0.0 to 1.0
        val z = 1.96; val p = k.toDouble() / n; val d = 1 + z * z / n
        val c = (p + z * z / (2 * n)) / d; val h = z * kotlin.math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
        return (c - h).coerceAtLeast(0.0) to (c + h).coerceAtMost(1.0)
    }

    /** The hypothesis' level on [currentPatch]: only complete matches of that patch count. */
    fun assess(h: Hypothesis, currentPatch: String): Assessment {
        val all = h.matches.filter { it.complete }
        val now = all.filter { it.patch == currentPatch }
        if (all.isNotEmpty() && now.isEmpty()) return Assessment(Level.OUTDATED, 0, null, null, null, "no evidence on patch $currentPatch")
        val n = now.size
        if (n == 0) return Assessment(Level.EXPERIMENTAL, 0, null, null, null, "no supporting matches yet")
        val k = now.count { it.won }
        val (lo, hi) = wilson(k, n)
        val wr = k.toDouble() / n
        val teams = now.mapNotNull { it.team }.toSet().size
        val tours = now.mapNotNull { it.tournament }.toSet().size
        return when {
            n < MIN_MATCHES_EMERGING -> Assessment(Level.EXPERIMENTAL, n, wr, lo, hi, "only $n matches (needs $MIN_MATCHES_EMERGING to be emerging)")
            n < MIN_MATCHES_VALIDATED -> Assessment(Level.EMERGING, n, wr, lo, hi, "$n matches (needs $MIN_MATCHES_VALIDATED to be validated)")
            teams < MIN_TEAMS || tours < MIN_TOURNAMENTS -> Assessment(Level.EMERGING, n, wr, lo, hi,
                "$teams teams and $tours tournaments (needs $MIN_TEAMS and $MIN_TOURNAMENTS: not one team's habit)")
            lo <= h.baselineWinRate -> Assessment(Level.EMERGING, n, wr, lo, hi,
                String.format(java.util.Locale.US, "win rate %.0f%% is not clearly above the baseline %.0f%% (interval starts at %.0f%%)", wr * 100, h.baselineWinRate * 100, lo * 100))
            else -> Assessment(Level.VALIDATED, n, wr, lo, hi, "$n matches, $teams teams, $tours tournaments, above the baseline")
        }
    }

    /** Hypotheses whose tags hold every one of [query]'s words (case-insensitive), or whose title does. */
    fun search(hs: List<Hypothesis>, query: String): List<Hypothesis> {
        val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (words.isEmpty()) return hs
        return hs.filter { h -> words.all { w -> h.tags.any { it.lowercase().contains(w) } || h.title.lowercase().contains(w) } }
    }

    /** The consistent tag set (roadmap 13.10): normalised to lower-case words with dashes. */
    fun tag(raw: String): String = raw.trim().lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-')
}
