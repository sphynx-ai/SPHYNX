package com.sphynx.game

import com.sphynx.core.Cfg

// HP BACK FROM HITTING (v36.55): Lifesteal (HP back from Basic Attack damage), Spell Vamp (from
// Skill damage), Hybrid Lifesteal (both), and passives / skills that heal on hit. A hero with
// enough of it heals by farming a camp or a wave instead of walking home - recalling is then
// only an option, not a must.
//
//  * MEASURED while you play: every frame you (or the AI) hit something - the AI's basic attack /
//    skill, or an enemy / monster close by losing HP - the rise of your own HP bar is counted.
//    The rate (share of max HP per second of hitting) is learned per hero, so items bought,
//    emblems and passives are all in it.
//  * KNOWN beforehand: the hero's passive / skills that heal on damage, and the lifesteal / spell
//    vamp items in its build (built in, or what the Library learned about each item).
object Sustain {

    // items with lifesteal / spell vamp (the Library's learned item texts are read too)
    private val builtIn = mapOf("Haas' Claws" to "lifesteal", "Concentrated Energy" to "spell vamp",
        "War Axe" to "spell vamp", "Rose Gold Meteor" to "lifesteal", "Blade of the Heptaseas" to "lifesteal")

    fun itemKind(item: String): String? {
        ItemBook.get(item)?.let { i ->
            val t = (i.stats + " " + i.effect).lowercase()
            if ("hybrid lifesteal" in t) return "hybrid lifesteal"
            if ("spell vamp" in t) return "spell vamp"
            if ("lifesteal" in t) return "lifesteal"
        }
        return builtIn[item]
    }

    // what the hero has on paper: passive / skills that heal on damage + items of its build
    fun sources(hero: String?): List<String> {
        val out = ArrayList<String>()
        val kit = SkillBook.get(hero)
        val p = kit.passiveText.lowercase()
        if ("lifesteal" in p || "spell vamp" in p || ("damage" in p && (" heal" in p || "restore" in p || "recover" in p))) out.add("passive heals on damage")
        if (kit.skills.values.any { it.heal && it.damage > 0.1f }) out.add("a skill heals while dealing damage")
        val build = BuildBook.primary(HeroDb.role(hero))
        build.mapNotNull { i -> itemKind(i)?.let { "$i ($it)" } }.forEach { out.add(it) }
        return out
    }

    // ---- measured ----
    @Volatile private var lastHp = -1f
    @Volatile private var lastAt = 0L
    @Volatile private var lastTargetsHp = -1f
    @Volatile private var lastTargetsN = 0
    @Volatile var hittingNow = false; private set

    private fun key(h: String) = "sustain_" + h.lowercase().filter { it.isLetterOrDigit() }
    // (rate as share of max HP per second of hitting, seconds measured)
    fun measured(hero: String?): Pair<Float, Float> {
        val p = hero?.let { Cfg.raw(key(it)) }?.split(",") ?: return 0f to 0f
        return (p.getOrNull(0)?.toFloatOrNull() ?: 0f) to (p.getOrNull(1)?.toFloatOrNull() ?: 0f)
    }

    fun observe(s: GameState) {
        val hero = s.ownHero ?: return
        val now = s.ts
        val hp = s.selfHpRaw
        val near = s.enemiesWithin(0.5f).map { it.hp } + s.monsters.filter { s.distToSelf(it.x, it.y) < 0.5f }.map { it.hp } +
            s.minionsWithin(0.5f).map { it.hp }
        val sum = near.sum()
        val targetsHit = near.isNotEmpty() && near.size == lastTargetsN && lastTargetsHp >= 0f && sum < lastTargetsHp - 0.003f
        lastTargetsHp = if (near.isEmpty()) -1f else sum; lastTargetsN = near.size
        val aiHit = com.sphynx.controller.ActionExecutor.aiCastAgo("attack") < 400 ||
            System.currentTimeMillis() - com.sphynx.controller.ActionExecutor.lastAiCastAt < 400
        hittingNow = targetsHit || (aiHit && near.isNotEmpty())
        val dt = (now - lastAt) / 1000f
        if (hp >= 0f && lastHp >= 0f && dt in 0.02f..0.6f && hittingNow) {
            // a big jump is not lifesteal (respawn, a heal from an ally, Regen spell): left out
            val gain = (hp - lastHp).coerceIn(-1f, 1f)
            if (gain < 0.08f) {
                val (rate, secs) = measured(hero)
                val secs2 = (secs + dt).coerceAtMost(600f)
                // net change while hitting (damage taken counts against it: what matters is whether
                // farming keeps you up), averaged over the time measured
                val r2 = (rate * secs + gain.coerceAtLeast(-0.05f)) / secs2
                if (secs2 - secs > 0f) Cfg.setRaw(key(hero), "%.4f,%.1f".format(java.util.Locale.US, r2, secs2))
            }
        }
        lastHp = hp; lastAt = now
    }

    // heals by hitting: a measured net HP gain while hitting (at least 15 s of hitting measured),
    // or lifesteal / spell vamp on paper while the measurement is still young
    fun healsByHitting(hero: String?): Boolean {
        val (rate, secs) = measured(hero)
        if (secs >= 15f) return rate >= 0.004f
        // not measured yet: only the hero's own passive / skills count (a build's lifesteal item may
        // not be bought yet)
        return sources(hero).any { "passive" in it || "skill" in it }
    }

    fun describe(hero: String?): String {
        val (rate, secs) = measured(hero)
        val src = sources(hero)
        return (if (secs >= 15f) "measured %+.1f%% HP per second while hitting (%.0f s)".format(rate * 100, secs) else "not measured yet") +
            (if (src.isNotEmpty()) " · from: " + src.joinToString() else " · no lifesteal / spell vamp known") +
            (if (healsByHitting(hero)) " · heals by farming: recall only when really low" else "")
    }
}
