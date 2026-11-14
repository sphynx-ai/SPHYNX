package com.sphynx.game

// Companion advice: which skill to level up next. Owner decision 2026-11-10: outside a verified
// practice match vs Computer the app only ADVISES what to prioritize leveling; it never taps a
// skill "+" (ActionExecutor.maybeUpgrade runs only when the Player is allowed to play).
// The pick mirrors maybeUpgrade: the ultimate first whenever its "+" shows, else the first skill
// in the hero's priority order whose "+" shows.
object SkillAdvice {
    private fun name(slot: String) = when (slot) { "ult" -> "Ultimate"; "s1" -> "Skill 1"; "s2" -> "Skill 2"; else -> slot }

    /** The slot to level now, or null when no "+" is showing. */
    fun pick(order: List<String>, ready: Set<String>): String? = when {
        ready.isEmpty() -> null
        "ult" in ready -> "ult"
        else -> order.firstOrNull { it in ready } ?: ready.first()
    }

    /** One short line for the advice panel. */
    fun line(order: List<String>, ready: Set<String>): String {
        val prio = order.joinToString(" > ") { name(it) }
        return pick(order, ready)?.let { "level up now: ${name(it)} (priority $prio)" } ?: "skill priority: $prio"
    }
}
