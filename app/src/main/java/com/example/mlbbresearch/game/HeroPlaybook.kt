package com.example.mlbbresearch.game

import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision

// HOW TO PLAY THE HERO YOU ARE ON: a game plan worked out from what each of its skills really
// does (SkillBook: learned online / pasted / built-in), refined by the online guide (MetaStore:
// combo, dash, playstyle, when to use each skill, power spike), and then APPLIED:
//  - the hero's profile (HeroDb.profile): combo order, wave-clear skill, escape skill, when the
//    ultimate is used, how far it starts fights - instead of the role's defaults
//  - the combo when you have not set one (ComboBook): buff > opener > crowd control > damage > ult
//  - every fight (filterCasts): keep the escape for leaving (fragile heroes, enemy still healthy),
//    use the heal / shield when hurt, cast the self-buff before hitting
// The plan is rebuilt by itself whenever the skills or the guide for the hero change.
class Playbook(
    val hero: String,
    val style: String,               // e.g. "burst assassin", "tank initiator", "poke mage"
    val opener: String?,             // the skill that starts a fight (reach + crowd control)
    val cc: String?,                 // hard crowd control
    val finisher: String?,           // the big damage / execute
    val escape: String?,             // dash / blink kept to get out
    val waveClear: String?,          // area / line damage for minions and camps
    val sustain: String?,            // heal or shield
    val buff: String?,               // self-buff cast before hitting
    val keepEscape: Boolean,         // fragile hero: do not spend the escape to start a fight
    val ult: UltRule,
    val engageRange: Float,
    val combo: String,               // generated combo text (ComboBook format)
    val spike: String,               // when it is strongest
    val tips: List<String>,          // how to play it, in plain words
    val learned: Boolean,            // from real skill knowledge (false = role defaults only)
    val source: String
)

object HeroPlaybook {

    private val cache = HashMap<String, Pair<String, Playbook>>()

    @Synchronized
    fun of(hero: String?): Playbook {
        val h = hero ?: "Unknown"
        val kit = SkillBook.get(hero)
        val meta = MetaStore.hero(hero)
        val sig = "${System.identityHashCode(kit)}|${meta?.updatedAt}|${Cfg_heroDash(h)}"
        cache[h]?.let { if (it.first == sig) return it.second }
        val pb = build(h, kit, meta)
        cache[h] = sig to pb
        return pb
    }

    private fun Cfg_heroDash(h: String) = com.example.mlbbresearch.core.Cfg.heroDash(h)

    private fun build(h: String, kit: HeroSkills, meta: HeroMeta?): Playbook {
        val role = HeroDb.role(h)
        val ranged = HeroDb.isRanged(h)
        val base = HeroDb.baseProfile(h)
        val learned = !kit.source.startsWith("role defaults")
        val sk = listOf("s1", "s2", "ult").mapNotNull { kit[it] }
        val nonUlt = sk.filter { it.slot != "ult" }
        fun name(s: SkillInfo?) = s?.let { if (it.name.isNotBlank() && it.name != it.slot) "${it.slot} (${it.name})" else it.slot }

        // escape: the guide's dash, else a non-ultimate dash / blink, else any
        val metaDash = meta?.dash?.takeIf { it != "none" }?.let { kit[it] }
        val escape = metaDash ?: kit.escape
        val fragile = role in setOf(Role.ASSASSIN, Role.MARKSMAN, Role.MAGE, Role.SUPPORT)
        // opener: reach + crowd control first, then crowd control with range, then a dash (sturdy heroes), then range
        val opener = nonUlt.filter { it.mobility && it.hardCc }.maxByOrNull { it.range }
            ?: sk.filter { it.hardCc && it.slot != "ult" }.maxByOrNull { it.range }
            ?: nonUlt.filter { it.mobility && !(fragile && it === escape) }.maxByOrNull { it.damage }
            ?: nonUlt.maxByOrNull { it.range }
        val cc = nonUlt.filter { it.hardCc }.maxByOrNull { it.range } ?: sk.firstOrNull { it.hardCc } ?: nonUlt.firstOrNull { it.slow }
        val ultS = kit["ult"]
        val finisher = sk.maxByOrNull { it.damage + if (it.slot == "ult") 0.05f else 0f }
        val sustain = sk.filter { it.heal || it.shield }.minByOrNull { if (it.slot == "ult") 1 else 0 }
        val buff = nonUlt.firstOrNull { it.kind == SkillKind.SELF && !it.mobility && !it.heal && !it.shield && it.damage > 0f }
        val waveClear = nonUlt.filter { (it.kind == SkillKind.AREA || it.kind == SkillKind.DIRECTION) && it.damage > 0f && !(fragile && it === escape) }
            .maxByOrNull { it.damage } ?: nonUlt.filter { it.damage > 0f && it !== escape }.maxByOrNull { it.damage }
        val keepEscape = fragile && escape != null && escape.slot != "ult"

        // when the ultimate goes off, from what it does
        val ult = when {
            ultS == null || !learned -> base.ult
            ultS.heal || ultS.shield -> UltRule.DEFENSIVE
            ultS.mobility && ultS.hardCc -> UltRule.ENGAGE
            // an escape ultimate (stealth, blink away) on a fragile hero: to survive
            ultS.mobility && fragile && ultS.damage < 0.25f -> UltRule.DEFENSIVE
            // a single-target lock (kick, suppress, grab): to start the pick
            ultS.hardCc && ultS.kind == SkillKind.TARGET -> UltRule.ENGAGE
            ultS.hardCc && ultS.kind == SkillKind.AREA -> UltRule.TEAMFIGHT
            ultS.kind == SkillKind.SELF && ultS.damage > 0f -> UltRule.BUFF
            ultS.damage >= 0.25f -> UltRule.EXECUTE
            else -> base.ult
        }
        val style = when (role) {
            Role.ASSASSIN -> if (opener?.mobility == true) "burst assassin (dive, kill, leave)" else "assassin (pick off isolated targets)"
            Role.FIGHTER -> if (sustain != null || kit.has(Passive.STACKS)) "bruiser (fight long, out-sustain)" else "diver (jump in with crowd control)"
            Role.TANK -> if (ult == UltRule.TEAMFIGHT || cc != null) "tank initiator (start fights, catch enemies)" else "tank (absorb, protect)"
            Role.MAGE -> if (sk.count { it.range >= 0.4f } >= 2) "poke mage (hit from range, never walk in)" else "burst mage (land crowd control, then burst)"
            Role.MARKSMAN -> if (buff != null || kit.has(Passive.STACKS)) "hyper-carry marksman (keep attacking from behind)" else "marksman (stay back, attack the nearest)"
            Role.SUPPORT -> if (sustain != null) "healer support (keep allies alive)" else "control support (crowd control for the team)"
            Role.UNKNOWN -> "all-rounder"
        }
        // generated combo: buff > opener > crowd control > other damage (strongest first) > ult > attack
        val order = ArrayList<String>()
        fun add(s: SkillInfo?) { if (s != null && s.slot !in order) order.add(s.slot) }
        add(buff); add(opener); add(cc)
        nonUlt.filter { it !== escape || !keepEscape }.sortedByDescending { it.damage }.forEach { add(it) }
        if (ult != UltRule.DEFENSIVE) add(ultS)
        if (!keepEscape) add(escape)
        for (s in listOf("s1", "s2", "ult")) if (s !in order && !(keepEscape && s == escape?.slot) && !(ult == UltRule.DEFENSIVE && s == "ult")) order.add(s)
        val combo = (order + "a").joinToString(" > ")
        val engage = maxOf(base.engageRange, (opener?.range ?: 0f) * 1.05f).coerceAtMost(0.7f)

        val spike = meta?.powerSpike?.takeIf { it.isNotBlank() } ?: when {
            WikiLanes.earlyGame(h) -> "early game: fight from level 2-4, close the game early"
            WikiLanes.lateGame(h) -> "late game: farm safely until 2-3 items, then take fights"
            role == Role.ASSASSIN -> "from level 4 (ultimate) and the first damage item"
            role == Role.MARKSMAN -> "from 2-3 items; until then farm safely"
            else -> "from level 4 (ultimate)"
        }
        val tips = ArrayList<String>()
        meta?.playstyle?.takeIf { it.isNotBlank() }?.let { tips.add(it) }
        if (opener != null) tips.add("Start fights with ${name(opener)}" + when {
            opener.mobility && opener.hardCc -> ": it closes the gap and locks the target."
            opener.hardCc -> ": land the crowd control first."
            opener.mobility -> ": it gets you in reach."
            else -> ": it has the longest reach."
        })
        if (cc != null && cc !== opener) tips.add("Follow with ${name(cc)} to keep them locked.")
        if (finisher != null) tips.add("Finish with ${name(finisher)}" + (if (ult == UltRule.EXECUTE && finisher.slot == "ult") " when the target is low." else "."))
        if (keepEscape) tips.add("Keep ${name(escape)} to get out - spend it only to finish a low target.")
        else if (escape != null) tips.add("${name(escape)} is your dash: use it to engage or chase.")
        if (waveClear != null) tips.add("Clear waves and camps with ${name(waveClear)}.")
        if (sustain != null) tips.add("Use ${name(sustain)} when you are hurt in a fight (${if (sustain.heal) "heal" else "shield"}).")
        if (buff != null) tips.add("Cast ${name(buff)} before you start hitting (self-buff).")
        if (kit.has(Passive.WEAVE_ATTACK)) tips.add("Basic attack after every skill: the passive powers it up.")
        if (kit.has(Passive.STACKS)) tips.add("Keep hitting the same target: the passive stacks.")
        if (kit.has(Passive.BUSH_BONUS)) tips.add("Attack from bushes: the passive rewards it.")
        if (kit.has(Passive.LOW_HP_POWER)) tips.add("Stronger when hurt: do not leave fights too early.")
        tips.add(when (ult) {
            UltRule.ENGAGE -> "Ultimate: start the fight when it looks winnable."
            UltRule.EXECUTE -> "Ultimate: save it to finish a low-HP target."
            UltRule.TEAMFIGHT -> "Ultimate: on 2+ enemies at once."
            UltRule.DEFENSIVE -> "Ultimate: when your HP is low, to survive."
            UltRule.BUFF -> "Ultimate: a self-buff for close fights."
        })
        if (ranged) tips.add("Fight at your range; step back when they close in.") else tips.add("Stay on your target: walk into it as you hit.")
        meta?.skillUse?.forEach { (slot, how) -> if (how.isNotBlank()) tips.add("${slot.uppercase()}: $how") }
        tips.add("Strongest: $spike.")
        val src = (if (learned) "skills: ${kit.source}" else "role defaults (learn the skills online for a real plan)") +
            (if (meta != null) " + online guide (${meta.patch})" else "")
        return Playbook(h, style, opener?.slot, cc?.slot, finisher?.slot, escape?.slot, waveClear?.slot, sustain?.slot, buff?.slot,
            keepEscape, ult, engage, combo, spike, tips, learned, src)
    }

    // the hero's profile, played the way its skills work (only when its skills are really known)
    fun adapt(p: HeroProfile, ultFixed: Boolean): HeroProfile {
        val pb = of(p.name)
        if (!pb.learned) return p
        val order = pb.combo.split(">").map { it.trim() }.filter { it in setOf("s1", "s2", "ult") }.ifEmpty { p.combo }
        return HeroProfile(p.name, p.role, p.damage, p.attackRange, maxOf(p.attackRange * 1.1f, pb.engageRange), p.retreatHp,
            order, pb.waveClear ?: p.waveClearSkill, pb.escape ?: p.escapeSkill, if (ultFixed) p.ult else pb.ult, p.cooldownSec, p.ranged)
    }

    // In a fight: apply the plan to the skills about to be cast
    fun applyCasts(d: TacticalDecision, casts0: List<String>, s: GameState, p: HeroProfile, ready: (String) -> Boolean): List<String> {
        val pb = of(s.ownHero)
        if (!pb.learned) return casts0
        var casts = casts0
        val fight = d.intent == Intent.ENGAGE || d.intent == Intent.AMBUSH
        if (fight) {
            // fragile hero, enemy still healthy, nobody to help: keep the dash for leaving
            val esc = pb.escape
            if (pb.keepEscape && esc != null && esc in casts && esc != pb.opener && d.targetHp > 0.35f && s.alliesWithin(0.5f).isEmpty())
                casts = casts.filter { it != esc }
            // the self-buff first, as soon as the target is about to be in reach
            pb.buff?.let { b -> if (ready(b) && d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= p.attackRange * 1.4f) casts = listOf(b) + casts.filter { it != b } }
        }
        // hurt in a fight (or running): the heal / shield
        pb.sustain?.let { su ->
            val hurt = s.selfHp in 0f..0.55f && s.enemiesWithin(0.7f).isNotEmpty()
            if (hurt && su !in casts && ready(su) && (fight || d.intent == Intent.RETREAT)) casts = casts + su
        }
        return casts
    }

    fun text(hero: String?): String {
        val pb = of(hero)
        return buildString {
            append(pb.style)
            append("\nCombo: ").append(pb.combo)
            append("\nOpener ").append(pb.opener ?: "-").append(" · CC ").append(pb.cc ?: "-").append(" · finisher ").append(pb.finisher ?: "-")
            append("\nEscape ").append(pb.escape ?: "-").append(if (pb.keepEscape) " (kept)" else "").append(" · waves ").append(pb.waveClear ?: "-")
            if (pb.sustain != null) append(" · heal/shield ").append(pb.sustain)
            if (pb.buff != null) append(" · buff ").append(pb.buff)
        }
    }
}
