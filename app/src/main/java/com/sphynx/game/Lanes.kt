package com.sphynx.game

import com.sphynx.core.Cfg

object SpellDb {
    val names = listOf(
        "Retribution", "Inspire", "Sprint", "Revitalize", "Aegis", "Petrify",
        "Purify", "Flameshot", "Flicker", "Execute", "Vengeance", "Arrival"
    )

    // spells worth using inside a fighting combo
    val offensive = setOf("Flicker", "Execute", "Flameshot", "Petrify", "Sprint", "Inspire")

    fun match(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val n = raw.lowercase().filter { it.isLetter() }
        if (n.isEmpty()) return null
        // Ice / Flame / Bloody Retribution (the jungle item's kinds) are Retribution to the AI
        if (n.endsWith("retribution")) return "Retribution"
        return names.firstOrNull { it.lowercase() == n }
            ?: names.firstOrNull { n.startsWith(it.lowercase().take(5)) }
    }
}

// MLBB always shows YOUR base bottom-left and the enemy base top-right:
//   EXP lane  = top lane:    walk up from base, then right
//   Gold lane = bottom lane: walk right from base, then up
//   Mid lane  = diagonal:    up-right
//   Roam      = follow your team
//   Jungle    = farm monsters between the lanes, take turtle / lord
enum class Lane(val label: String) {
    EXP("EXP lane"), GOLD("Gold lane"), MID("Mid lane"), ROAM("Roam"), JUNGLE("Jungle");

    companion object {
        /** The watched player's lane for live learning (Settings stores its name; unknown = EXP). */
        fun live(): Lane = of(com.sphynx.core.Cfg.liveLaneName) ?: EXP

        fun of(key: String): Lane? = when (key) {
            "exp" -> EXP; "gold" -> GOLD; "mid" -> MID; "roam" -> ROAM; "jungle" -> JUNGLE; else -> null
        }
    }
}

object LaneLogic {

    // Non-Retribution lane fallback for heroes whose primary role is Assassin/Jungler when played without Retribution (v37.13)
    private fun nonRetriLane(hero: String?): Lane = when (hero) {
        "Selena", "Natalia", "Helcurt", "Saber", "Kadita", "Mathilda", "Kaja" -> Lane.ROAM
        "Gusion", "Harley", "Joy", "Aamon", "Karina", "Harith", "Julian" -> Lane.MID
        "Yi Sun-shin", "Granger", "Kimmy" -> Lane.GOLD
        else -> when (HeroDb.role(hero)) {
            Role.MARKSMAN -> Lane.GOLD
            Role.MAGE -> Lane.MID
            Role.TANK, Role.SUPPORT -> Lane.ROAM
            else -> Lane.EXP
        }
    }

    // The lane in use and why:
    //  - Retribution equipped -> always Jungle (unless a lane is chosen and "Retribution means Jungle" is off)
    //  - a lane chosen in the settings -> that lane (Jungle without Retribution -> a real lane)
    //  - automatic: the hero's RECOMMENDED lanes (HeroLanes - online guide + wiki; owner, 2026-11-11): the
    //    primary; without Retribution never Jungle (the secondary, else the non-jungle fallback); a flex pick
    //    takes its secondary when an ally (line-up read) has the same primary lane.
    fun effective(hero: String?, spell: String?, allies: List<String> = GameStateTracker.myTeam): Pair<Lane, String> {
        val chosen = Lane.of(Cfg.lane)
        val hasRetri = spell == "Retribution"
        if (hasRetri && (chosen == null || Cfg.retriMeansJungle)) return Lane.JUNGLE to "Retribution equipped"
        val rec = HeroLanes.of(hero)
        if (chosen != null) {
            if (chosen == Lane.JUNGLE && spell != null && !hasRetri) {
                val l = rec.secondary?.takeIf { it != Lane.JUNGLE && rec.primary == Lane.JUNGLE } ?: rec.primary.takeIf { it != Lane.JUNGLE } ?: nonRetriLane(hero)
                return l to "no Retribution equipped (auto-switched from Jungle)"
            }
            return chosen to "your choice"
        }
        val others = allies.filter { !it.equals(hero, ignoreCase = true) }.map { HeroLanes.of(it) }
        return HeroLanes.assign(rec, hasRetri, others) ?: (nonRetriLane(hero) to "auto (no Retribution)")
    }

    // turtle before 8:00, lord after (needs the match start; null if unknown)
    fun objectiveName(matchSec: Int): String = when {
        matchSec < 0 -> "turtle / lord"
        matchSec < 480 -> "turtle"
        else -> "lord"
    }
}
