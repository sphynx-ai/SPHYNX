package com.example.mlbbresearch.game

// RECOMMENDED LANES PER HERO (owner, 2026-11-11): a PRIMARY lane, a SECONDARY lane, and whether the hero is
// a FLEX pick (can be played in almost any lane) - shown on the Library's hero page and used for the automatic
// lane (LaneLogic.effective). Where it comes from, first found wins:
//  1. YOUR setting for the hero (Settings: "exp, jungle" / "exp, jungle, flex");
//  2. lanes YOU confirmed, built in (CONFIRMED - e.g. Alice: EXP or Jungle, never Roam or Mid, though her role
//     is tank / mage and the wiki also lists her for Mid);
//  3. the online guide ("lane", "lane2", "flex");
//  4. the wiki's laning lists (WikiLanes.lanes - every list the hero is in; flex if 3 or more lanes);
//  5. the hero's role.
// Pure logic (settings and guide are passed in), unit tested.
object HeroLanes {

    class Rec(val primary: Lane, val secondary: Lane?, val flex: Boolean, val source: String) {
        fun text(): String = primary.label + (secondary?.let { " · 2nd: ${it.label}" } ?: "") + (if (flex) " · flex pick" else "")
    }

    private fun byRole(role: Role): Lane = when (role) {
        Role.MARKSMAN -> Lane.GOLD
        Role.MAGE -> Lane.MID
        Role.FIGHTER -> Lane.EXP
        Role.TANK, Role.SUPPORT -> Lane.ROAM
        Role.ASSASSIN -> Lane.JUNGLE
        Role.UNKNOWN -> Lane.MID
    }

    // lanes you confirmed (owner): kept here so they hold even before any guide is learned
    val CONFIRMED: Map<String, Rec> = mapOf(
        "Alice" to Rec(Lane.EXP, Lane.JUNGLE, false, "confirmed by you")
    )

    // "exp, jungle" / "gold" / "exp, jungle, flex" -> a recommendation; null = nothing usable
    fun parse(text: String?): Rec? {
        if (text.isNullOrBlank()) return null
        val words = text.lowercase().split(Regex("[,/;\\s>|]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val lanes = words.mapNotNull { w -> Lane.of(when (w) { "gold lane", "marksman" -> "gold"; "roamer", "roaming" -> "roam"; "jungler" -> "jungle"; else -> w }) }.distinct()
        val primary = lanes.firstOrNull() ?: return null
        return Rec(primary, lanes.getOrNull(1), "flex" in words || "flexible" in words, "your setting")
    }

    // meta = the online guide (null = none learned / online meta off); manual = your setting for the hero
    fun of(hero: String?, meta: HeroMeta?, role: Role = HeroDb.role(hero), manual: String? = null): Rec {
        parse(manual)?.let { return it }
        hero?.let { h -> CONFIRMED.entries.firstOrNull { it.key.equals(h, ignoreCase = true) }?.let { return it.value } }
        val wiki = WikiLanes.lanes(hero)
        val primary = meta?.lane ?: wiki.firstOrNull() ?: byRole(role)
        val src = when {
            meta?.lane != null -> "online guide"
            wiki.isNotEmpty() -> "wiki laning lists"
            else -> "hero's role"
        }
        val secondary = meta?.lane2?.takeIf { it != primary } ?: wiki.firstOrNull { it != primary }
        val flex = meta?.flex ?: (wiki.size >= 3)
        return Rec(primary, secondary, flex, src + (if (meta?.lane2 != null || meta?.flex != null) "" else if (wiki.size > 1) " + wiki" else ""))
    }

    // the hero's recommendation with the saved online guide (if any)
    fun of(hero: String?): Rec = of(hero, MetaStore.hero(hero), HeroDb.role(hero), com.example.mlbbresearch.core.Cfg.manualLanes(hero))

    // THE AUTOMATIC LANE from the recommendation (no lane chosen in the settings):
    //  - Retribution: Jungle (decided before this, in LaneLogic);
    //  - primary Jungle without Retribution: the secondary lane (if it is not Jungle too), else null (the
    //    caller's non-jungle fallback);
    //  - an ally already has this lane as THEIR primary and you are a flex pick whose secondary no ally takes:
    //    the secondary (the flex hero moves, not the specialist).
    // Returns the lane and why; null = no lane without Retribution here.
    fun assign(me: Rec, hasRetri: Boolean, allies: List<Rec>): Pair<Lane, String>? {
        var lane = me.primary
        var why = "recommended lane (${me.source})"
        if (lane == Lane.JUNGLE && !hasRetri) {
            lane = me.secondary?.takeIf { it != Lane.JUNGLE } ?: return null
            why = "2nd recommended lane (no Retribution)"
        }
        val taken = allies.map { it.primary }
        if (me.flex && lane in taken) {
            val alt = me.secondary?.takeIf { it != lane && it !in taken && (it != Lane.JUNGLE || hasRetri) }
            if (alt != null) return alt to "flex pick: an ally takes the ${lane.label}, so the 2nd lane"
        }
        return lane to why
    }
}
