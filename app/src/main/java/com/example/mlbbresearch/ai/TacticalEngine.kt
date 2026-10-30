package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroProfile
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.game.UltRule
import com.example.mlbbresearch.vision.Track
import kotlin.math.hypot

enum class Intent { IDLE, LANE_CLEAR, ENGAGE, RETREAT, RECALL, PUSH, GROUP, IMITATE }

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

    fun reset() {
        held = Intent.IDLE
        heldSince = 0L
        recallUntil = 0L
    }

    fun decide(s: GameState, p: HeroProfile, plan: StrategyPlan, learnedBias: Float): TacticalDecision {
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
        val outnumbered = threat.size >= alliesNear + 2
        val losingDuel = threat.size == 1 && alliesNear == 0 && hp < 0.5f && threat[0].hp > hp + 0.25f
        if (hp in 0f..retreatHp && threat.isNotEmpty() || outnumbered || losingDuel || plan.plan == "retreat" && threat.isNotEmpty()) {
            val (mx, my) = away(s, threat)
            val casts = ArrayList<String>()
            p.escapeSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            if (p.ult == UltRule.DEFENSIVE && hp < 0.35f && CooldownTracker.ready("ult", p)) casts.add("ult")
            if (Cfg.spellIsDefensive && hp < 0.2f && CooldownTracker.ready("spell", p)) casts.add("spell")
            recallUntil = 0L
            return hold(now, TacticalDecision(Intent.RETREAT, mx, my, casts, -1f, -1f, "-", -1f, -1,
                when {
                    outnumbered -> "outnumbered ${threat.size} vs ${alliesNear + 1}"
                    losingDuel -> "losing the duel"
                    else -> "HP ${(hp * 100).toInt()}%"
                }), force = true)
        }

        // ---- 2. recall when low and safe ----
        if (now < recallUntil) {
            if (threat.isEmpty()) {
                return TacticalDecision(Intent.RECALL, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1, "recalling")
            }
            recallUntil = 0L
        }
        if (hp in 0f..0.35f && s.enemiesWithin(0.9f).isEmpty()) {
            recallUntil = now + 8000
            return TacticalDecision(Intent.RECALL, 0f, 0f, listOf("recall"), -1f, -1f, "-", -1f, -1, "low HP, safe to recall")
        }

        // ---- 3. engage an enemy hero ----
        val target = pickTarget(s, threat, plan)
        if (target != null) {
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

        // ---- 4. clear the minion wave ----
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

        // ---- 5. follow Gemma's plan ----
        if (plan.plan == "group" && s.allies.isNotEmpty()) {
            val ax = s.allies.map { it.x }.average().toFloat()
            val ay = s.allies.map { it.y }.average().toFloat()
            val (mx, my) = toward(s, ax, ay)
            return hold(now, TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "grouping with allies"))
        }
        val (px, py) = pushVector()
        return hold(now, TacticalDecision(Intent.PUSH, px, py, emptyList(), -1f, -1f, "-", -1f, -1,
            "moving ${Cfg.pushDir} (${plan.plan})"))
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
