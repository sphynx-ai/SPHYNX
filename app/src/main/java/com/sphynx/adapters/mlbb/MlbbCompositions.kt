package com.sphynx.adapters.mlbb

/**
 * PHASE 13, step D4 (roadmap 13.3): recommended five-hero compositions, coherent as a team rather than five strong heroes.
 * It fills the five lanes (EXP, jungle, mid, gold, roam) around the picks already made, with role-lane fit, a mix of
 * magic and physical damage, a front line, engage, and counters read from the online meta where present (app-generated
 * data, labelled as such). Each composition explains its evidence, strengths, weaknesses, win condition and likely
 * counters, and its confidence: never high, because there are no official statistics behind it (D3). Pure, unit-tested.
 */
object MlbbCompositions {

    enum class Lane(val label: String) { EXP("EXP lane"), JUNGLE("Jungle"), MID("Mid lane"), GOLD("Gold lane"), ROAM("Roam") }

    /** What the app knows about a hero: [role] (tank, fighter, assassin, mage, marksman, support), its lanes (most usual
     *  first; empty = from the role), damage, and the heroes the online meta says counter it (empty = not known). */
    data class Hero(val name: String, val role: String, val lanes: List<Lane>, val magic: Boolean, val counteredBy: List<String> = emptyList(),
                    val metaKnown: Boolean = false)

    data class Composition(val picks: Map<Lane, String>, val score: Double, val strengths: List<String>, val weaknesses: List<String>,
                           val winCondition: String, val likelyCounters: List<String>, val evidence: List<String>, val confidence: String)

    fun defaultLanes(role: String): List<Lane> = when (role.lowercase()) {
        "marksman" -> listOf(Lane.GOLD)
        "mage" -> listOf(Lane.MID, Lane.GOLD)
        "assassin" -> listOf(Lane.JUNGLE, Lane.EXP)
        "fighter" -> listOf(Lane.EXP, Lane.JUNGLE)
        "tank" -> listOf(Lane.ROAM, Lane.EXP)
        "support" -> listOf(Lane.ROAM)
        else -> emptyList()
    }

    private fun lanesOf(h: Hero) = h.lanes.ifEmpty { defaultLanes(h.role) }
    private fun front(h: Hero) = h.role == "tank" || h.role == "fighter"
    private fun engage(h: Hero) = h.role == "tank" || h.role == "assassin"

    /** How well [h] fits [lane]: its first lane 1.0, a later lane 0.6, else 0. */
    fun fit(h: Hero, lane: Lane): Double = lanesOf(h).indexOf(lane).let { if (it < 0) 0.0 else if (it == 0) 1.0 else 0.6 }

    private fun teamScore(team: List<Hero>, enemies: List<String>): Double {
        var s = 0.0
        val magic = team.count { it.magic }; val phys = team.size - magic
        if (magic >= 1 && phys >= 1) s += 1.0 else s -= 1.0
        if (team.count { front(it) } >= 1) s += 1.0 else s -= 1.5
        if (team.any { engage(it) }) s += 0.5
        // counters from the online meta: a hero countered by an enemy pick is weaker here
        s -= 0.7 * team.sumOf { h -> h.counteredBy.count { it in enemies } }
        return s
    }

    /**
     * Up to [top] distinct compositions completing [mine] (hero → lane already fixed), from [pool], avoiding [taken]
     * (bans and both teams' picks). Deterministic: candidates are tried in a fixed order.
     */
    fun recommend(pool: List<Hero>, mine: Map<Lane, String>, enemies: List<String>, taken: Set<String>, top: Int = 3): List<Composition> {
        val byName = pool.associateBy { it.name }
        val open = Lane.values().filter { it !in mine }
        val free = pool.filter { it.name !in taken && it.name !in mine.values }.sortedBy { it.name }
        // beam search over the open lanes
        var beam = listOf(mine to 0.0)
        for (lane in open) {
            val next = ArrayList<Pair<Map<Lane, String>, Double>>()
            for ((picks, sc) in beam) for (h in free) {
                if (h.name in picks.values) continue
                val f = fit(h, lane); if (f == 0.0) continue
                next.add(picks + (lane to h.name) to sc + f)
            }
            beam = next.sortedByDescending { it.second + teamScore(it.first.values.mapNotNull { n -> byName[n] }, enemies) }.take(40)
            if (beam.isEmpty()) return emptyList()
        }
        val ranked = beam.map { (p, sc) -> p to sc + teamScore(p.values.mapNotNull { byName[it] }, enemies) }.sortedByDescending { it.second }
        // distinct: each next composition differs from the ones kept in at least two heroes
        val kept = ArrayList<Pair<Map<Lane, String>, Double>>()
        for (r in ranked) if (kept.all { k -> k.first.values.toSet().intersect(r.first.values.toSet()).size <= 3 }) { kept.add(r); if (kept.size == top) break }
        return kept.map { (p, sc) -> describe(p, sc, byName, enemies) }
    }

    private fun describe(p: Map<Lane, String>, score: Double, byName: Map<String, Hero>, enemies: List<String>): Composition {
        val team = Lane.values().mapNotNull { l -> p[l]?.let { byName[it] } }
        val magic = team.count { it.magic }
        val strengths = ArrayList<String>(); val weak = ArrayList<String>()
        if (team.count { front(it) } >= 2) strengths.add("solid front line") else if (team.none { front(it) }) weak.add("no front line")
        if (magic >= 1 && team.size - magic >= 1) strengths.add("mixed damage ($magic magic, ${team.size - magic} physical)")
        else weak.add("one damage type only: easy to itemise against")
        if (team.any { engage(it) }) strengths.add("has engage (" + team.filter { engage(it) }.joinToString { it.name } + ")") else weak.add("little engage")
        val countered = team.flatMap { h -> h.counteredBy.filter { it in enemies }.map { "${h.name} by $it" } }
        if (countered.isNotEmpty()) weak.add("countered (online meta): " + countered.joinToString())
        val win = when {
            team.any { it.role == "marksman" } && team.count { front(it) } >= 2 -> "protect the marksman into late game teamfights"
            team.count { engage(it) } >= 2 -> "pick fights early with engage and snowball objectives"
            else -> "control objectives (turtle, lord) through grouped fights"
        }
        val likely = team.flatMap { it.counteredBy }.distinct().filter { it !in enemies }.take(4)
        val metaHeroes = team.count { it.metaKnown }
        val evidence = listOf("lanes and roles: the app's hero data", if (metaHeroes > 0) "counters: app-generated online meta for $metaHeroes of 5 heroes (not official statistics)"
            else "counters: not known (no online meta for these heroes)", "no official pick/win/ban statistics (not available to the app)")
        val conf = if (metaHeroes >= 3) "medium" else "low"
        return Composition(p, score, strengths, weak, win, likely, evidence, conf)
    }
}
