package com.sphynx.game

// HERO COMPANIONS AND SUMMONS (owner's list from the session recordings, v37.19): things that walk or
// fly next to a hero and can look like a unit on screen, but are NOT heroes and NOT monsters. The
// screen check (universal.TargetFusion.keepScreenEnemy) already drops what the minimap does not back
// up; this list says what they are (for the logs, the recorder and future detectors).
object Companions {

    enum class Kind { ALWAYS, FROM_SKILL }

    data class Companion(val hero: String, val name: String, val kind: Kind)

    val ALL = listOf(
        // always with the hero (no skill needed)
        Companion("Angela", "Doll", Kind.ALWAYS),
        Companion("Floryn", "Dew", Kind.ALWAYS),
        Companion("Pharsa", "Verri", Kind.ALWAYS),
        Companion("Alpha", "Beta", Kind.ALWAYS),
        // created by a skill
        Companion("Claude", "Dexter", Kind.FROM_SKILL),
        Companion("Melissa", "Muddles", Kind.FROM_SKILL),
        Companion("Popol and Kupa", "Kupa", Kind.FROM_SKILL),
        Companion("Zhask", "Alien spawn", Kind.FROM_SKILL),
        Companion("Vexana", "Eternal Guard", Kind.FROM_SKILL)
    )

    private fun norm(h: String?) = h?.lowercase()?.filter { it.isLetter() } ?: ""

    fun of(hero: String?): List<Companion> = ALL.filter { norm(it.hero) == norm(hero) }

    // heroes in this match (yours, allies, enemies) whose companions can be on screen
    fun inMatch(heroes: List<String?>): List<Companion> = heroes.flatMap { of(it) }.distinct()
}
