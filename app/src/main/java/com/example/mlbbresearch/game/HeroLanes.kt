package com.example.mlbbresearch.game

// RECOMMENDED LANES PER HERO (owner, 2026-11-11): a PRIMARY lane, a SECONDARY lane, and whether the hero is
// a FLEX pick (can be played in almost any lane) - from the online guide and the wiki, shown on the Library's
// hero page and used for the automatic lane (LaneLogic.effective).
//  - primary: the online guide's "lane", else the wiki's best list (WikiLanes.lane), else the hero's role;
//  - secondary: the online guide's "lane2", else the next lane the wiki lists the hero for (the "also played
//    there" lists included), else none;
//  - flex: the online guide's "flex", else listed by the wiki for 3 or more lanes.
// Pure logic (the online guide is passed in), unit tested.
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

    // meta = the online guide (null = none learned / online meta off)
    fun of(hero: String?, meta: HeroMeta?, role: Role = HeroDb.role(hero)): Rec {
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
    fun of(hero: String?): Rec = of(hero, MetaStore.hero(hero))

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
