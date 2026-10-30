package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.Lane
import com.example.mlbbresearch.game.LaneLogic
import com.example.mlbbresearch.game.HeroProfile
import com.example.mlbbresearch.game.JungleNav
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.game.UltRule
import com.example.mlbbresearch.learning.FightOption
import com.example.mlbbresearch.vision.Seen
import com.example.mlbbresearch.vision.Track
import kotlin.math.hypot

enum class Intent { IDLE, LANE_CLEAR, ENGAGE, RETREAT, RECALL, REGEN, PUSH, GROUP, IMITATE, OBJECTIVE, JUNGLE }

// What the fast engine wants to do right now. Movement is a direction on screen.
class TacticalDecision(
    val intent: Intent,
    val moveX: Float,               // -1..1, 0 = stand still
    val moveY: Float,
    val casts: List<String>,        // "attack", "s1", "s2", "ult", "spell", "recall"
    val targetX: Float,             // where the target is (for aimed skills), -1 = none
    val targetY: Float,
    val targetLabel: String,
    val targetHp: Float,            // -1 = none
    val targetId: Int,
    val reason: String
) {
    fun describe(): String {
        val c = if (casts.isEmpty()) "" else "  [${casts.joinToString(", ")}]"
        return "${intent.name.replace('_', ' ')}: $reason$c"
    }

    companion object {
        val IDLE = TacticalDecision(Intent.IDLE, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1, "waiting")
    }
}

// Deterministic Kotlin rules, 5 times a second: lane clearing, engaging, retreating,
// using skills and managing the ultimate for your hero. Gemma only adjusts the plan.
object TacticalEngine {

    private var held: Intent = Intent.IDLE
    private var heldSince = 0L
    private var recallUntil = 0L
    private var regenUntil = 0L

    fun reset() {
        held = Intent.IDLE
        heldSince = 0L
        recallUntil = 0L
        regenUntil = 0L
        JungleNav.reset()
    }

    // option: what the learning brain chose for this fight (null / RULES = the normal rules)
    fun decide(s: GameState, p: HeroProfile, plan: StrategyPlan, learnedBias: Float, option: FightOption? = null): TacticalDecision {
        val self = s.self
        if (s.phase != Phase.IN_GAME || self == null) return TacticalDecision.IDLE
        val now = s.ts
        val hp = s.selfHp
        val aggression = (plan.aggression + learnedBias).coerceIn(0f, 1f)
        val retreatHp = (p.retreatHp + (0.5f - aggression) * 0.15f).coerceIn(0.12f, 0.6f)

        val threat = s.enemiesWithin(0.55f)
        val alliesNear = s.alliesWithin(0.5f).size
        val minions = s.minionsWithin(0.6f)

        // ---- 1. survive ----
        // Low HP always retreats. "Outnumbered" / "losing the duel" are rules of thumb: when the
        // brain has chosen an option it decides those itself (and learns if it was wrong).
        val rules = option == null || option == FightOption.RULES
        val outnumbered = rules && threat.size >= alliesNear + 2
        val losingDuel = rules && threat.size == 1 && alliesNear == 0 && hp < 0.5f && threat[0].hp > hp + 0.25f
        val brainRetreat = option == FightOption.RETREAT && s.enemiesWithin(0.9f).isNotEmpty()
        if (hp in 0f..retreatHp && threat.isNotEmpty() || outnumbered || losingDuel || brainRetreat ||
            plan.plan == "retreat" && threat.isNotEmpty() && rules
        ) {
            val (mx, my) = away(s, threat)
            val casts = ArrayList<String>()
            p.escapeSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            if (p.ult == UltRule.DEFENSIVE && hp < 0.35f && CooldownTracker.ready("ult", p)) casts.add("ult")
            if (Cfg.spellIsDefensive && s.spell != "Retribution" && hp < 0.2f && CooldownTracker.ready("spell", p)) casts.add("spell")
            recallUntil = 0L
            return hold(now, TacticalDecision(Intent.RETREAT, mx, my, casts, -1f, -1f, "-", -1f, -1,
                when {
                    outnumbered -> "outnumbered ${threat.size} vs ${alliesNear + 1}"
                    losingDuel -> "losing the duel"
                    brainRetreat && hp > retreatHp -> "brain: back off"
                    else -> "HP ${(hp * 100).toInt()}%"
                }), force = true)
        }

        // ---- 2. recall when low and safe ----
        if (recallUntil in 1..now) {
            // recall finished: back in base, the lane route starts again
            recallUntil = 0L
            GameStateTracker.markSpawn()
        }
        if (now < recallUntil) {
            if (threat.isEmpty()) {
                return TacticalDecision(Intent.RECALL, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1, "recalling")
            }
            recallUntil = 0L
        }
        val safe = s.enemiesWithin(0.9f).isEmpty()
        if (Cfg.useRecall && hp in 0f..Cfg.recallHpPct / 100f && safe) {
            // Recall channels ~6 s: stand still; an enemy showing up cancels it (step 1 / above)
            regenUntil = 0L
            recallUntil = now + 8000
            return TacticalDecision(Intent.RECALL, 0f, 0f, listOf("recall"), -1f, -1f, "-", -1f, -1, "low HP, safe to recall")
        }

        // ---- 2b. Regen: heal over a few seconds when hurt but not low enough to go home ----
        if (now < regenUntil) {
            if (safe && hp < 0.98f) {
                return TacticalDecision(Intent.REGEN, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1, "regenerating")
            }
            regenUntil = 0L
        }
        if (Cfg.useRegen && hp in 0f..Cfg.regenHpPct / 100f && safe && s.minionsWithin(0.5f).isEmpty() &&
            CooldownTracker.ready("regen", p)
        ) {
            regenUntil = now + 5000
            return TacticalDecision(Intent.REGEN, 0f, 0f, listOf("regen"), -1f, -1f, "-", -1f, -1,
                "HP ${(hp * 100).toInt()}%, using Regen")
        }

        // ---- 3. engage an enemy hero (the brain's choice first) ----
        val target = pickTarget(s, if (option == null) threat else s.enemiesWithin(0.9f), plan)
        if (target != null && option != null && !rules) {
            brainFight(s, p, option, target, hp, alliesNear)?.let { return hold(now, it, force = true) }
        }
        if (target != null && (rules || option == FightOption.ALL_IN) && s.distToSelf(target.x, target.y) <= 0.55f) {
            val d = s.distToSelf(target.x, target.y)
            val winnable = target.hp <= hp + 0.1f + (aggression - 0.5f) * 0.4f ||
                target.hp < 0.35f ||
                (alliesNear + 1 >= threat.size && aggression >= 0.5f)
            if (d <= p.engageRange && winnable) {
                val casts = ArrayList<String>()
                if (d <= p.attackRange * 1.3f) casts.add("attack")
                for (sk in p.combo) {
                    if (sk == "ult") continue
                    if (d <= p.engageRange && CooldownTracker.ready(sk, p)) casts.add(sk)
                }
                if (useUlt(p, s, target, d, hp, threat.size, alliesNear)) casts.add("ult")
                val (mx, my) = if (d > p.attackRange) toward(s, target.x, target.y) else (0f to 0f)
                return hold(now, TacticalDecision(Intent.ENGAGE, mx, my, casts, target.x, target.y,
                    "enemy #${target.id}", target.hp, target.id,
                    "target HP ${(target.hp * 100).toInt()}%, ${(d * 100).toInt()} away"))
            }
        }

        // ---- 4. turtle / lord: help the team take it (or take it as the jungler) ----
        val boss = s.boss
        if (Cfg.takeObjectives && boss != null && threat.isEmpty()) {
            val alliesThere = s.allies.count { s.dist(it.x, it.y, boss.x, boss.y) < 0.5f }
            if (alliesThere >= 1 || s.lane == Lane.JUNGLE || s.lane == Lane.ROAM || boss.hp < 0.5f) {
                val name = LaneLogic.objectiveName(s.matchSec)
                return hold(now, hitMonster(s, p, boss, Cfg.retriBossPct, Intent.OBJECTIVE,
                    "$name HP ${(boss.hp * 100).toInt()}%" + if (alliesThere > 0) ", with $alliesThere allies" else ""))
            }
        }

        // ---- 5. jungler: farm the jungle monsters ----
        if (s.lane == Lane.JUNGLE && threat.isEmpty()) {
            val creep = s.creeps.minByOrNull { s.distToSelf(it.x, it.y) }
            if (creep != null) {
                return hold(now, hitMonster(s, p, creep, Cfg.retriCreepPct, Intent.JUNGLE,
                    "jungle monster HP ${(creep.hp * 100).toInt()}%"))
            }
        }

        // ---- 6. roamer: stay with the team ----
        if (s.lane == Lane.ROAM && s.allies.isNotEmpty()) {
            val ax = s.allies.map { it.x }.average().toFloat()
            val ay = s.allies.map { it.y }.average().toFloat()
            if (s.distToSelf(ax, ay) > 0.2f) {
                val (mx, my) = toward(s, ax, ay)
                return hold(now, TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "roaming with allies"))
            }
        }

        // ---- 6b. jungler: walk the jungle path to the next camp (from the minimap), not the lanes ----
        if (s.lane == Lane.JUNGLE && Cfg.jungleRoute && threat.isEmpty()) {
            val nav = JungleNav.direction(s)
            if (nav != null) {
                return hold(now, TacticalDecision(Intent.JUNGLE, nav.first, nav.second, emptyList(), -1f, -1f, "-", -1f, -1,
                    "jungle path: ${nav.third}"))
            }
        }

        // ---- 7. clear the minion wave ----
        if (minions.isNotEmpty() && plan.plan != "group") {
            val mx0 = minions.map { it.x }.average().toFloat()
            val my0 = minions.map { it.y }.average().toFloat()
            val d = s.distToSelf(mx0, my0)
            val casts = ArrayList<String>()
            if (d <= p.attackRange * 1.4f) casts.add("attack")
            val clustered = minions.count { s.dist(it.x, it.y, mx0, my0) < 0.25f }
            val wc = p.waveClearSkill
            if (wc != null && clustered >= 3 && d <= p.attackRange * 1.8f && CooldownTracker.ready(wc, p)) casts.add(wc)
            val (mx, my) = if (d > p.attackRange) toward(s, mx0, my0) else (0f to 0f)
            return hold(now, TacticalDecision(Intent.LANE_CLEAR, mx, my, casts, mx0, my0,
                "${minions.size} minions", minions.map { it.hp }.average().toFloat(), -1,
                "${minions.size} minions"))
        }

        // ---- 8. follow Gemma's plan, else walk your lane ----
        if (plan.plan == "group" && s.allies.isNotEmpty()) {
            val ax = s.allies.map { it.x }.average().toFloat()
            val ay = s.allies.map { it.y }.average().toFloat()
            val (mx, my) = toward(s, ax, ay)
            return hold(now, TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "grouping with allies"))
        }
        val (px, py, where) = laneVector(s)
        return hold(now, TacticalDecision(Intent.PUSH, px, py, emptyList(), -1f, -1f, "-", -1f, -1,
            "${s.lane.label}: $where (${plan.plan})"))
    }

    // Attack a jungle monster / turtle / lord; Retribution (if you have it) to finish it
    private fun hitMonster(s: GameState, p: HeroProfile, m: Seen, retriPct: Int, intent: Intent, why: String): TacticalDecision {
        val d = s.distToSelf(m.x, m.y)
        val casts = ArrayList<String>()
        if (d <= p.attackRange * 1.4f) casts.add("attack")
        if (d <= p.attackRange * 1.8f) {
            p.waveClearSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            if (m.hero) for (sk in p.combo) if (sk != "ult" && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
        }
        if (s.spell == "Retribution" && m.hp * 100 <= retriPct && d <= 0.45f && CooldownTracker.ready("spell", p)) casts.add("spell")
        val (mx, my) = if (d > p.attackRange) toward(s, m.x, m.y) else (0f to 0f)
        return TacticalDecision(intent, mx, my, casts, m.x, m.y, if (m.hero) "objective" else "monster", m.hp, -1, why)
    }

    // Lane route from base: EXP = up then right, Gold = right then up, Mid = your push direction.
    // Jungle and roam head to the middle of the map, or to the team when it is in sight.
    private fun laneVector(s: GameState): Triple<Float, Float, String> {
        val travelling = s.sinceSpawnMs < Cfg.laneTravelSec * 1000L
        fun t(v: Pair<Float, Float>, what: String) = Triple(v.first, v.second, what)
        return when (s.lane) {
            Lane.EXP -> if (travelling) t(0f to -1f, "going up to the lane") else t(1f to 0f, "pushing right")
            Lane.GOLD -> if (travelling) t(1f to 0f, "going right to the lane") else t(0f to -1f, "pushing up")
            Lane.MID -> t(pushVector(), "pushing ${Cfg.pushDir}")
            Lane.JUNGLE, Lane.ROAM -> {
                if (s.allies.isNotEmpty() && !travelling) {
                    val ax = s.allies.map { it.x }.average().toFloat()
                    val ay = s.allies.map { it.y }.average().toFloat()
                    val (mx, my) = toward(s, ax, ay)
                    t(mx to my, "joining the team")
                } else t(norm(1f, -1f), if (s.lane == Lane.JUNGLE) "heading into the jungle" else "heading to mid")
            }
        }
    }

    // How each brain option is played. null = nothing special, carry on with the normal steps.
    private fun brainFight(s: GameState, p: HeroProfile, o: FightOption, t: Track, hp: Float, alliesNear: Int): TacticalDecision? {
        val d = s.distToSelf(t.x, t.y)
        val tag = "brain: ${o.label}"
        return when (o) {
            FightOption.ALL_IN -> {
                if (d > p.engageRange * 1.4f) return null
                val casts = ArrayList<String>()
                if (d <= p.attackRange * 1.3f) casts.add("attack")
                for (sk in p.combo) if (sk != "ult" && CooldownTracker.ready(sk, p)) casts.add(sk)
                if (CooldownTracker.ready("ult", p) && d <= p.engageRange) casts.add("ult")
                val (mx, my) = if (d > p.attackRange) toward(s, t.x, t.y) else (0f to 0f)
                TacticalDecision(Intent.ENGAGE, mx, my, casts, t.x, t.y, "enemy #${t.id}", t.hp, t.id,
                    "$tag, target HP ${(t.hp * 100).toInt()}%")
            }
            FightOption.POKE -> {
                // stay near the edge of skill range: skills yes, walking into them no
                val casts = ArrayList<String>()
                if (d <= p.engageRange) for (sk in p.combo) if (sk != "ult" && CooldownTracker.ready(sk, p)) casts.add(sk)
                if (d <= p.attackRange) casts.add("attack")
                val (mx, my) = when {
                    d < p.engageRange * 0.7f && p.attackRange < p.engageRange * 0.7f -> away(s, listOf(t))
                    d > p.engageRange -> toward(s, t.x, t.y)
                    else -> 0f to 0f
                }
                TacticalDecision(Intent.ENGAGE, mx, my, casts, t.x, t.y, "enemy #${t.id}", t.hp, t.id,
                    "$tag, ${(d * 100).toInt()} away")
            }
            FightOption.GROUP -> {
                if (s.allies.isEmpty()) return null
                val ax = s.allies.map { it.x }.average().toFloat()
                val ay = s.allies.map { it.y }.average().toFloat()
                val casts = if (d <= p.attackRange * 1.2f) listOf("attack") else emptyList()
                val (mx, my) = if (s.distToSelf(ax, ay) > 0.15f) toward(s, ax, ay) else (0f to 0f)
                TacticalDecision(Intent.GROUP, mx, my, casts, t.x, t.y, "enemy #${t.id}", t.hp, t.id,
                    "$tag ($alliesNear near)")
            }
            FightOption.FARM -> null          // skip the fight, the farming steps follow
            FightOption.RETREAT, FightOption.RULES -> null
        }
    }

    private fun useUlt(p: HeroProfile, s: GameState, t: Track, d: Float, hp: Float, enemies: Int, allies: Int): Boolean {
        if (!CooldownTracker.ready("ult", p)) return false
        return when (p.ult) {
            UltRule.EXECUTE -> t.hp < 0.4f && d <= p.engageRange
            UltRule.ENGAGE -> d <= p.engageRange && hp > 0.5f && enemies <= allies + 1
            UltRule.TEAMFIGHT -> enemies >= 2 && d <= p.engageRange
            UltRule.DEFENSIVE -> hp < 0.35f
            UltRule.BUFF -> d <= p.attackRange * 1.5f && hp > 0.3f
        }
    }

    // Weakest nearby enemy hero (or Gemma's choice of focus)
    private fun pickTarget(s: GameState, near: List<Track>, plan: StrategyPlan): Track? {
        if (near.isEmpty()) return null
        return when (plan.focus) {
            "nearest" -> near.minByOrNull { s.distToSelf(it.x, it.y) }
            else -> near.minByOrNull { it.hp + s.distToSelf(it.x, it.y) * 0.6f }
        }
    }

    private fun toward(s: GameState, x: Float, y: Float): Pair<Float, Float> {
        val self = s.self ?: return 0f to 0f
        return norm((x - self.x) * s.aspect, y - self.y)
    }

    private fun away(s: GameState, from: List<Track>): Pair<Float, Float> {
        val self = s.self ?: return pushVector().let { -it.first to -it.second }
        if (from.isEmpty()) return pushVector().let { -it.first to -it.second }
        val cx = from.map { it.x }.average().toFloat()
        val cy = from.map { it.y }.average().toFloat()
        return norm((self.x - cx) * s.aspect, self.y - cy)
    }

    // Direction towards the enemy base, set on the Player page
    fun pushVector(): Pair<Float, Float> = when (Cfg.pushDir) {
        "up-right" -> norm(1f, -1f)
        "down-left" -> norm(-1f, 1f)
        "right" -> 1f to 0f
        "left" -> -1f to 0f
        "up" -> 0f to -1f
        "down" -> 0f to 1f
        "up-left" -> norm(-1f, -1f)
        "down-right" -> norm(1f, 1f)
        else -> 0f to 0f
    }

    private fun norm(x: Float, y: Float): Pair<Float, Float> {
        val l = hypot(x, y)
        return if (l < 1e-4f) 0f to 0f else (x / l) to (y / l)
    }

    // Keeps an intent for at least 0.6 s so the hero does not jitter between choices
    private fun hold(now: Long, d: TacticalDecision, force: Boolean = false): TacticalDecision {
        if (d.intent != held) {
            if (!force && now - heldSince < 600 && held != Intent.IDLE && held != Intent.PUSH) {
                // keep moving the old way, but still allow attacks/skills
                return TacticalDecision(held, d.moveX, d.moveY, d.casts, d.targetX, d.targetY, d.targetLabel,
                    d.targetHp, d.targetId, d.reason)
            }
            held = d.intent
            heldSince = now
        }
        return d
    }
}
