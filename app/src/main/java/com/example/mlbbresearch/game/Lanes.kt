package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg

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

    // The lane in use and why (v37.13):
    //  - Retribution equipped -> always Jungle (unless manually overridden)
    //  - Not equipped with Retribution -> never auto-assigns Jungle (routes to EXP, Gold, Mid, or Roam)
    fun effective(hero: String?, spell: String?): Pair<Lane, String> {
        val chosen = Lane.of(Cfg.lane)
        val hasRetri = spell == "Retribution"
        if (hasRetri && (chosen == null || Cfg.retriMeansJungle)) return Lane.JUNGLE to "Retribution equipped"
        if (chosen != null) {
            if (chosen == Lane.JUNGLE && spell != null && !hasRetri) {
                return nonRetriLane(hero) to "no Retribution equipped (auto-switched from Jungle)"
            }
            return chosen to "your choice"
        }
        MetaStore.hero(hero)?.lane?.let { l ->
            if (l == Lane.JUNGLE && !hasRetri) return nonRetriLane(hero) to "auto (no Retribution)"
            return l to "auto, from the online meta"
        }
        WikiLanes.lane(hero)?.let { l ->
            if (l == Lane.JUNGLE && !hasRetri) return nonRetriLane(hero) to "auto (no Retribution)"
            return l to "auto, from the wiki's laning list"
        }
        val lane = when (HeroDb.role(hero)) {
            Role.MARKSMAN -> Lane.GOLD
            Role.MAGE -> Lane.MID
            Role.FIGHTER -> Lane.EXP
            Role.TANK, Role.SUPPORT -> Lane.ROAM
            Role.ASSASSIN -> if (hasRetri) Lane.JUNGLE else nonRetriLane(hero)
            Role.UNKNOWN -> Lane.MID
        }
        return lane to "auto, from the hero's role"
    }

    // turtle before 8:00, lord after (needs the match start; null if unknown)
    fun objectiveName(matchSec: Int): String = when {
        matchSec < 0 -> "turtle / lord"
        matchSec < 480 -> "turtle"
        else -> "lord"
    }
}
