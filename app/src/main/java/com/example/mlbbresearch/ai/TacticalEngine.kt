package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.Lane
import com.example.mlbbresearch.game.LaneLogic
import com.example.mlbbresearch.game.HeroProfile
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.JungleNav
import com.example.mlbbresearch.game.TowerLogic
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.game.UltRule
import com.example.mlbbresearch.learning.FightOption
import com.example.mlbbresearch.vision.Seen
import com.example.mlbbresearch.vision.Track
import kotlin.math.hypot

enum class Intent { IDLE, LANE_CLEAR, ENGAGE, RETREAT, RECALL, REGEN, PUSH, GROUP, IMITATE, OBJECTIVE, JUNGLE, AMBUSH }

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
    private var ambushSince = 0L

    fun reset() {
        held = Intent.IDLE
        heldSince = 0L
        recallUntil = 0L
        regenUntil = 0L
        JungleNav.reset()
    }

    // option: what the learning brain chose for this fight (null / RULES = the normal rules)
    fun decide(s: GameState, p: HeroProfile, plan: StrategyPlan, learnedBias: Float, option: FightOption? = null): TacticalDecision {
        pendingHop = false
        var d = decideRaw(s, p, plan, learnedBias, option)
        if (d.intent != Intent.RETREAT && d.intent != Intent.RECALL && d.intent != Intent.IDLE) {
            // never walk into enemy tower range without your minions there
            val (gx, gy, changed) = TowerLogic.guard(s, d.moveX, d.moveY)
            if (changed) d = TacticalDecision(d.intent, gx, gy, d.casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
                d.reason + " (keeping out of enemy tower range)")
        }
        return wallHop(s, p, steerAroundWalls(s, d))
    }

    // Walls seen on screen right ahead (rock, cliff, trees): curve 35 or 70 degrees to the clear
    // side before bumping into them. If every way is blocked, the wall-jump check takes over.
    private fun steerAroundWalls(s: GameState, d: TacticalDecision): TacticalDecision {
        val self = s.self ?: return d
        if (!Cfg.wallAvoid || (d.moveX == 0f && d.moveY == 0f) || s.screenWalls.isEmpty()) return d
        val bd = com.example.mlbbresearch.vision.BushDetector
        val fy = self.y + 0.02f                        // feet
        if (bd.wallAhead(s.screenWalls, self.x, fy, d.moveX, d.moveY, s.aspect) < 0.34f) return d
        for (deg in intArrayOf(35, -35, 70, -70)) {
            val a = Math.toRadians(deg.toDouble())
            val rx = (d.moveX * Math.cos(a) - d.moveY * Math.sin(a)).toFloat()
            val ry = (d.moveX * Math.sin(a) + d.moveY * Math.cos(a)).toFloat()
            if (bd.wallAhead(s.screenWalls, self.x, fy, rx, ry, s.aspect) < 0.2f) {
                return TacticalDecision(d.intent, rx, ry, d.casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
                    d.reason + " (curving around a wall)")
            }
        }
        pendingHop = true                              // boxed in: jump it if a dash is ready
        return d
    }

    // ---- thinking ahead: what comes after the current task ----
    // Worked out on every decision while the current task runs, so the next one starts at once.
    @Volatile var nextPlan = "-"
        private set

    // ---- hero level (1..15): higher level = more damage and HP ----
    //  - before level 4 there is no ultimate: farm first, fight only easy targets
    //  - level 4 (ultimate), 8 and 12 (ultimate upgrades) are power spikes: fight more willingly
    //  - ahead of the enemy in level: press the fight; 2+ levels behind: avoid even fights
    fun levelBias(s: GameState): Float {
        var b = when {
            s.level < 4 -> -0.1f
            s.level >= 12 -> 0.12f
            s.level >= 8 -> 0.08f
            else -> 0.05f
        }
        if (s.enemyLevel > 0) b += ((s.level - s.enemyLevel).coerceIn(-3, 3)) * 0.07f
        return b
    }

    // what to do to raise the level (shown with the plan)
    fun levelGoal(s: GameState): String {
        val xp = if (s.xp >= 0f) " (EXP ${(s.xp * 100).toInt()}%)" else ""
        val vs = if (s.enemyLevel > 0) ", enemy level ${s.enemyLevel}" else ""
        val next = when {
            s.level >= 15 -> "max level: push towers and take turtle / lord to end the game"
            s.level < 4 -> "farm to level 4 for the ultimate: " + if (s.lane == Lane.JUNGLE) "clear camps back to back, buffs first" else "last-hit every minion, take a near camp between waves"
            s.enemyLevel > 0 && s.enemyLevel > s.level + 1 -> "behind in level: farm safe camps / waves, fight only with allies"
            s.enemyLevel > 0 && s.level > s.enemyLevel + 1 -> "ahead in level: look for kills and push while stronger"
            s.lane == Lane.JUNGLE -> "keep the camp route going, kill heroes when they are low (kills give the most EXP)"
            else -> "clear waves (EXP), kill heroes when they are low, push with minions"
        }
        return "level ${s.level}$xp$vs: $next"
    }

    fun planAhead(s: GameState, d: TacticalDecision, p: HeroProfile): String {
        val hp = s.selfHp
        nextPlan = when {
            d.intent == Intent.RECALL -> "back in base: buy, then walk the lane"
            hp in 0f..0.35f && d.intent != Intent.RETREAT -> "recall or Regen once safe"
            d.intent == Intent.ENGAGE && s.minionsWithin(0.9f).isNotEmpty() -> "after the fight: clear the minion wave"
            d.intent == Intent.ENGAGE && s.lane == Lane.JUNGLE -> "after the fight: back to the jungle camps"
            d.intent == Intent.ENGAGE -> "after the fight: walk the lane / push"
            d.intent == Intent.JUNGLE -> JungleNav.next?.let { "next: the ${if (it.buff) "buff" else "camp"} at %.0f%%,%.0f%% of the map".format(it.x * 100, it.y * 100) }
                ?: "next: look for the next camp"
            d.intent == Intent.LANE_CLEAR && TowerLogic.selfInRange(s, 0.06f) -> "after the wave: push the tower with our minions"
            d.intent == Intent.LANE_CLEAR -> "after the wave: a nearby camp or the next tower"
            d.intent == Intent.OBJECTIVE -> "after the objective: push a lane with the buff"
            else -> "keep going: ${d.reason}"
        } + " | " + levelGoal(s)
        return nextPlan
    }

    // ---- skill rules: the last step before the touches go out ----
    // (also covers combos and "Play like me")
    //  - the ultimate is kept for enemy heroes (fighting or escaping them), never for creeps
    //  - Skill 1 and 2 go out the moment they are ready while fighting creeps or heroes in reach
    fun filterCasts(d: TacticalDecision, s: GameState, p: HeroProfile): TacticalDecision {
        val vsHero = (d.intent == Intent.ENGAGE || d.intent == Intent.AMBUSH || d.intent == Intent.IMITATE) &&
            d.targetId >= 0 || d.intent == Intent.RETREAT
        var casts = d.casts
        // a wall jump may use the ultimate when that is the hero's dash: keep it
        val hop = if (d.reason.contains("jumping the wall")) HeroDb.dashSlot(s.ownHero) else null
        if (Cfg.noUltOnCreeps && !vsHero) casts = casts.filter { it != "ult" || it == hop }
        if (Cfg.skillsWhenReady && inReach(d, s, p)) {
            val extra = listOf("s1", "s2").filter { it !in casts && CooldownTracker.ready(it, p) }
            if (extra.isNotEmpty()) casts = casts + extra
        }
        // Nothing to hit = no attack, no skills (escape skills, wall jumps, Regen, Recall stay).
        // Something in reach and no skill ready = keep pressing attack.
        // A jungle monster shows NO HP bar until it is hit: standing at a camp the minimap still
        // shows, the attack button is pressed anyway (it locks on to the nearest monster), and the
        // bars appear after the first hit.
        val atCamp = JungleNav.atCamp && (d.intent == Intent.JUNGLE || d.intent == Intent.LANE_CLEAR)
        val skillRange = s.enemiesWithin(p.engageRange).isNotEmpty() || atCamp ||
            s.minionsWithin(p.attackRange * 1.8f).isNotEmpty() || s.monsters.any { s.distToSelf(it.x, it.y) <= p.attackRange * 1.8f }
        val hitRange = s.enemiesWithin(p.attackRange * 1.4f).isNotEmpty() || atCamp ||
            s.minionsWithin(p.attackRange * 1.4f).isNotEmpty() || s.monsters.any { s.distToSelf(it.x, it.y) <= p.attackRange * 1.5f } ||
            TowerLogic.canPush(s)
        // Chasing an enemy hero: keep pressing attack while walking after it (it lands the moment
        // they are in reach). Standing hidden in a bush: still hit whatever is in reach.
        val chasing = (d.intent == Intent.ENGAGE || d.intent == Intent.IMITATE) && d.targetId >= 0 &&
            d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= p.engageRange * 1.3f
        val hiding = d.intent == Intent.RETREAT && d.moveX == 0f && d.moveY == 0f
        if (d.intent != Intent.RETREAT) {
            if (!skillRange && !TowerLogic.canPush(s) && !chasing) {
                casts = casts.filter { it == hop || it == "regen" || it == "recall" }
            } else if (!hitRange && !chasing) {
                casts = casts.filter { it != "attack" }
            }
        }
        if ((hitRange && (d.intent != Intent.RETREAT || hiding) || chasing) &&
            casts.none { it == "s1" || it == "s2" || it == "ult" } && "attack" !in casts
        ) {
            casts = casts + "attack"
        }
        if (casts == d.casts) return d
        return TacticalDecision(d.intent, d.moveX, d.moveY, casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId, d.reason)
    }

    // fighting something right now: an enemy hero, minions, a jungle camp or turtle / lord, in reach
    private fun inReach(d: TacticalDecision, s: GameState, p: HeroProfile): Boolean {
        return when (d.intent) {
            Intent.ENGAGE -> d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= p.engageRange
            Intent.LANE_CLEAR, Intent.OBJECTIVE -> d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= p.attackRange * 1.8f
            Intent.JUNGLE -> JungleNav.atCamp && s.creeps.isNotEmpty() || d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= p.attackRange * 1.8f
            else -> false
        }
    }

    // ---- passing walls with a dash, a mobility skill or Flicker ----
    private var pendingHop = false       // the path planner found a thin wall worth jumping
    private var movingSince = 0L

    private fun wallHop(s: GameState, p: HeroProfile, d: TacticalDecision): TacticalDecision {
        val now = s.ts
        val moving = d.moveX != 0f || d.moveY != 0f
        if (!moving) { movingSince = 0L; return d }
        if (movingSince == 0L) movingSince = now
        if (!Cfg.wallHop) return d
        // stuck against a wall: walking for 1 s but the camera has not moved for 0.6 s
        val stuck = now - movingSince > 1000 && now - GameStateTracker.lastMotionAt > 600
        if (!pendingHop && !stuck) return d
        val dash = if (Cfg.useDash) HeroDb.dashSlot(s.ownHero)?.takeIf { CooldownTracker.ready(it, p) } else null
        // Flicker has a long cooldown: only to escape or to catch someone
        val flicker = if (s.spell == "Flicker" && CooldownTracker.ready("spell", p) &&
            (d.intent == Intent.RETREAT || d.intent == Intent.ENGAGE)) "spell" else null
        val slot = dash ?: flicker ?: return d
        if (slot in d.casts) return d
        val self = s.self ?: return d
        // aim the jump the way we walk (a chase keeps its target)
        val tx = if (d.targetX >= 0f && d.intent == Intent.ENGAGE) d.targetX else self.x + d.moveX / s.aspect * 0.3f
        val ty = if (d.targetX >= 0f && d.intent == Intent.ENGAGE) d.targetY else self.y + d.moveY * 0.3f
        movingSince = now
        return TacticalDecision(d.intent, d.moveX, d.moveY, d.casts + slot, tx, ty, d.targetLabel, d.targetHp, d.targetId,
            d.reason + " | jumping the wall (${if (slot == "spell") "Flicker" else slot})")
    }

    private fun decideRaw(s: GameState, p: HeroProfile, plan: StrategyPlan, learnedBias: Float, option: FightOption?): TacticalDecision {
        val self = s.self
        if (s.phase != Phase.IN_GAME || self == null) return TacticalDecision.IDLE
        val now = s.ts
        val hp = s.selfHp
        val aggression = (plan.aggression + learnedBias + levelBias(s)).coerceIn(0f, 1f)
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
            var (mx, my) = retreatDir(s, threat)
            // hurt and chased: a bush on the way out hides you (enemies lose sight of you)
            if (Cfg.useBushes && hp < 0.4f) {
                if (s.inBush && s.enemiesWithin(0.3f).isEmpty()) {
                    val casts = if (s.enemiesWithin(0.6f).isEmpty() && Cfg.useRegen && CooldownTracker.ready("regen", p)) listOf("regen") else emptyList()
                    return hold(now, TacticalDecision(Intent.RETREAT, 0f, 0f, casts, -1f, -1f, "-", -1f, -1, "hiding in a bush"), force = true)
                }
                val b = s.bushes.filter { s.distToSelf(it.x, it.y) < 0.35f }.firstOrNull { bu ->
                    val (bx, by) = toward(s, bu.x, bu.y)
                    bx * mx + by * my > 0.3f
                }
                if (b != null) { val t = toward(s, b.x, b.y); mx = t.first; my = t.second }
            }
            val casts = ArrayList<String>()
            p.escapeSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            if (p.ult == UltRule.DEFENSIVE && hp < 0.35f && CooldownTracker.ready("ult", p)) casts.add("ult")
            if (Cfg.spellIsDefensive && s.spell != "Retribution" && hp < 0.2f && CooldownTracker.ready("spell", p)) casts.add("spell")
            // dash away when an enemy is right on you (aimed away from them)
            val dash = dashSlot(s, p)
            if (dash != null && dash !in casts && s.enemiesWithin(0.35f).isNotEmpty()) casts.add(dash)
            recallUntil = 0L
            val awayX = self.x + mx / s.aspect * 0.3f
            val awayY = self.y + my * 0.3f
            return hold(now, TacticalDecision(Intent.RETREAT, mx, my, casts, awayX, awayY, "away", -1f, -1,
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
        // early game: walking home costs too much farm, so recall only when really low (Regen covers the rest)
        val recallAt = if (s.matchSec in 0..180) minOf(Cfg.recallHpPct, 20) else Cfg.recallHpPct
        // a level-up is moments away (EXP ring almost full) and not in danger: finish the camp or
        // wave first, the new level heals a little and makes the walk back worth it
        val levelSoon = s.xp >= 0.8f && s.level < 15 && hp > 0.15f && (s.creeps.isNotEmpty() || minions.isNotEmpty())
        if (Cfg.useRecall && hp in 0f..recallAt / 100f && safe && !levelSoon) {
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

        // ---- 2c. finish off low-HP enemy heroes (never under their tower) ----
        val safeTargets = s.enemiesWithin(0.9f).filter { !TowerLogic.enemyUnderTower(s, it) }
        val low = safeTargets.filter { it.hp * 100 <= Cfg.executeHpPct }.minByOrNull { it.hp + s.distToSelf(it.x, it.y) * 0.3f }
        if (low != null && hp > retreatHp) {
            val d = s.distToSelf(low.x, low.y)
            val casts = ArrayList<String>()
            if (d <= p.attackRange * 1.3f) casts.add("attack")
            chaseDash(s, p, d)?.let { casts.add(it) }
            for (sk in p.combo) if (sk != "ult" && sk !in casts && d <= p.engageRange && CooldownTracker.ready(sk, p)) casts.add(sk)
            if (d <= p.engageRange && CooldownTracker.ready("ult", p) && (p.ult == UltRule.EXECUTE || p.ult == UltRule.ENGAGE)) casts.add("ult")
            if (s.spell in setOf("Execute", "Flameshot") && d <= p.engageRange && CooldownTracker.ready("spell", p)) casts.add("spell")
            val (mx, my) = heroSpacing(s, p, low, d)
            return hold(now, TacticalDecision(Intent.ENGAGE, mx, my, casts, low.x, low.y, "enemy #${low.id}", low.hp, low.id,
                "finishing enemy at ${(low.hp * 100).toInt()}% HP"), force = true)
        }

        // ---- 2d. ambush from a bush: hide while an enemy walks towards us (3 s at most) ----
        if (Cfg.useBushes && hp > 0.55f && (s.lane == Lane.ROAM || s.lane == Lane.JUNGLE || alliesNear > 0)) {
            val e = safeTargets.minByOrNull { s.distToSelf(it.x, it.y) }
            val de = e?.let { s.distToSelf(it.x, it.y) } ?: 9f
            // only while the enemy is actually coming towards us, and 3 s at most (no long waits)
            val coming = e != null && self.let { me -> (me.x - e.x) * e.vx * s.aspect * s.aspect + (me.y - e.y) * e.vy > 0f }
            if (e != null && coming && de > p.engageRange * 1.1f && de < 0.9f && (ambushSince == 0L || now - ambushSince < 3000)) {
                if (ambushSince == 0L) ambushSince = now
                if (s.inBush) {
                    return hold(now, TacticalDecision(Intent.AMBUSH, 0f, 0f, emptyList(), e.x, e.y, "enemy #${e.id}", e.hp, e.id,
                        "hidden in a bush, waiting for enemy #${e.id}"))
                }
                val b = s.bushes.filter { s.distToSelf(it.x, it.y) < 0.25f && s.dist(it.x, it.y, e.x, e.y) < de }
                    .minByOrNull { s.distToSelf(it.x, it.y) }
                if (b != null) {
                    val (mx, my) = toward(s, b.x, b.y)
                    return hold(now, TacticalDecision(Intent.AMBUSH, mx, my, emptyList(), e.x, e.y, "enemy #${e.id}", e.hp, e.id,
                        "moving into a bush to ambush enemy #${e.id}"))
                }
            }
        }
        if (safeTargets.isEmpty()) ambushSince = 0L

        // ---- 3. engage an enemy hero (the brain's choice first; never one under their tower) ----
        val target = pickTarget(s, if (option == null) threat.filter { it in safeTargets } else safeTargets, plan)
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
                chaseDash(s, p, d)?.let { if (it !in casts) casts.add(it) }
                val (mx, my) = heroSpacing(s, p, target, d)
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
        // (at level 15 more EXP is useless: the jungler pushes lanes and towers instead,
        // farming only when a camp is right on the way)
        if (s.lane == Lane.JUNGLE && Cfg.jungleRoute && threat.isEmpty()) {
            JungleNav.direction(s, if (s.level >= 15) 0.15f else 9f)?.let { return hold(now, jungleStep(s, p, it)) }
        }

        // ---- 7. kill the minion wave: lowest HP first, so they actually die (gold + EXP) ----
        if (minions.isNotEmpty() && plan.plan != "group") {
            val mx0 = minions.map { it.x }.average().toFloat()
            val my0 = minions.map { it.y }.average().toFloat()
            val tgt = minions.minByOrNull { it.hp * 0.6f + s.distToSelf(it.x, it.y) }!!
            val d = s.distToSelf(tgt.x, tgt.y)
            val casts = ArrayList<String>()
            if (d <= p.attackRange * 1.4f) casts.add("attack")
            val clustered = minions.count { s.dist(it.x, it.y, mx0, my0) < 0.25f }
            val wc = p.waveClearSkill
            // early game: clear waves fast with skills (no enemy hero close), later only on big waves
            val early = s.matchSec in 0..360 && threat.isEmpty()
            if (wc != null && clustered >= (if (early) 2 else 3) && s.distToSelf(mx0, my0) <= p.attackRange * 1.8f && CooldownTracker.ready(wc, p)) casts.add(wc)
            if (early && clustered >= 3 && s.distToSelf(mx0, my0) <= p.attackRange * 1.8f) {
                for (sk in p.combo) if (sk != "ult" && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
            }
            val (mx, my) = if (d > p.attackRange) toward(s, tgt.x, tgt.y) else (0f to 0f)
            return hold(now, TacticalDecision(Intent.LANE_CLEAR, mx, my, casts, tgt.x, tgt.y,
                "${minions.size} minions", tgt.hp, -1,
                "${minions.size} minions, hitting one at ${(tgt.hp * 100).toInt()}%"))
        }

        // ---- 7b. push the enemy tower: only with your minions in its range and no enemy hero ----
        if (TowerLogic.canPush(s)) {
            val n = TowerLogic.nearest(s)!!
            val me = s.minimapSelf!!
            val (mx, my) = if (n.second > TowerLogic.range() * 0.6f) norm(n.first.first - me.first, n.first.second - me.second) else (0f to 0f)
            return hold(now, TacticalDecision(Intent.PUSH, mx, my, listOf("attack"), -1f, -1f, "tower", -1f, -1,
                "pushing the tower with ${s.allyMinions.size} minions"))
        }

        // ---- 7c. defend your tower when enemies are at it ----
        if (s.lane != Lane.JUNGLE) {
            TowerLogic.towerToDefend(s)?.let { t ->
                val (mx, my) = mapDir(s.minimapSelf!!, t)
                return hold(now, TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "defending our tower"))
            }
        }

        // ---- 7d. laners: grab a nearby jungle camp between waves (gold + EXP) ----
        if (Cfg.lanersTakeCamps && s.lane != Lane.JUNGLE && s.lane != Lane.ROAM && threat.isEmpty() && s.level < 15) {
            JungleNav.direction(s, if (s.matchSec in 0..360) 0.22f else 0.15f)?.let { return hold(now, jungleStep(s, p, it)) }
        }

        // ---- 7e. MOBA rule: do not wander alone deep in the enemy half (ganks) ----
        val me = s.minimapSelf
        if (me != null && s.lane != Lane.JUNGLE && threat.isEmpty() && s.allies.isEmpty() && s.allyMinions.isEmpty() &&
            me.second < me.first - 0.25f && me.second > 0.15f && me.first < 0.85f
        ) {
            val home = s.allyTowers.minByOrNull { kotlin.math.hypot(it.first - me.first, it.second - me.second) } ?: (0.1f to 0.9f)
            val (mx, my) = mapDir(me, home)
            return hold(now, TacticalDecision(Intent.PUSH, mx, my, emptyList(), -1f, -1f, "-", -1f, -1,
                "alone deep in the enemy half: falling back"))
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

    // Walking the jungle path, or standing at a camp: attack (MLBB's attack button locks on to
    // the nearest monster) and use skills until the camp's icon is gone from the minimap.
    private fun jungleStep(s: GameState, p: HeroProfile, nav: Triple<Float, Float, String>): TacticalDecision {
        if (!JungleNav.atCamp) {
            return TacticalDecision(Intent.JUNGLE, nav.first, nav.second, emptyList(), -1f, -1f, "-", -1f, -1, "jungle path: ${nav.third}")
        }
        val casts = arrayListOf("attack")
        // farm fast: every ready skill except the ultimate goes into the camp
        p.waveClearSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
        for (sk in p.combo) if (sk != "ult" && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
        val m = s.creeps.minByOrNull { s.distToSelf(it.x, it.y) }
        // no HP bar to read: Retribution once the camp has taken a few seconds of hits
        val retriAfter = if (JungleNav.atBuff) 5000L else 2500L
        if (s.spell == "Retribution" && JungleNav.atCampMs > retriAfter && CooldownTracker.ready("spell", p)) casts.add("spell")
        if (m != null) {
            // a monster is seen: stand in reach of it and aim at it
            val d = s.distToSelf(m.x, m.y)
            val (mx, my) = if (d > p.attackRange) toward(s, m.x, m.y) else (0f to 0f)
            return TacticalDecision(Intent.JUNGLE, mx, my, casts, m.x, m.y, "monster", m.hp, -1, JungleNav.status)
        }
        // no bar yet (a monster's HP bar only shows once it is hit): basic attack to start the
        // fight, the skills follow as soon as the bars appear (no skills thrown at nothing)
        return TacticalDecision(Intent.JUNGLE, 0f, 0f, listOf("attack"), -1f, -1f, "camp", -1f, -1,
            JungleNav.status + ", first hit to show the monsters")
    }

    private fun dashSlot(s: GameState, p: HeroProfile): String? {
        if (!Cfg.useDash) return null
        val slot = HeroDb.dashSlot(s.ownHero) ?: return null
        return if (CooldownTracker.ready(slot, p)) slot else null
    }

    // dash to close the gap when the target is a bit out of reach
    private fun chaseDash(s: GameState, p: HeroProfile, d: Float): String? =
        if (d > p.attackRange * 1.5f && d <= p.engageRange * 1.4f) dashSlot(s, p) else null

    // Attack a jungle monster / turtle / lord; Retribution (if you have it) to finish it
    private fun hitMonster(s: GameState, p: HeroProfile, m: Seen, retriPct: Int, intent: Intent, why: String): TacticalDecision {
        val d = s.distToSelf(m.x, m.y)
        val casts = ArrayList<String>()
        if (d <= p.attackRange * 1.4f) casts.add("attack")
        if (d <= p.attackRange * 1.8f) {
            p.waveClearSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            for (sk in p.combo) if (sk != "ult" && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
        }
        // turtle / lord: the HP bar under the scoreboard is exact
        val mhp = if (m.hero && s.bossHp >= 0f) s.bossHp else m.hp
        if (s.spell == "Retribution" && mhp * 100 <= retriPct && d <= 0.45f && CooldownTracker.ready("spell", p)) casts.add("spell")
        // no poking monsters: walk into reach, and never leave its aggro circle (it would reset)
        val (mx, my) = if (d > p.attackRange || !inLeash(s, m)) toward(s, m.x, m.y) else (0f to 0f)
        return TacticalDecision(intent, mx, my, casts, m.x, m.y, if (m.hero) "objective" else "monster", mhp, -1, why)
    }

    // A monster fights back only inside the round indicator on the ground around it (seen as a
    // wide ellipse because of the camera tilt); step out and it stops and heals. Measured on
    // your screenshots: about 0.6 screen heights across and 0.27 up / down. Stay well inside.
    private fun inLeash(s: GameState, m: Seen): Boolean {
        val self = s.self ?: return true
        val dx = (self.x - m.x) * s.aspect / 0.5f
        val dy = (self.y - m.y) / 0.22f
        return dx * dx + dy * dy <= 1f
    }

    // Fighting an enemy HERO: melee walks up close and stays there; ranged keeps a safe distance
    // at the edge of its attack range, steps back if the enemy gets too close (kiting), and
    // steps in if it is out of range.
    private fun heroSpacing(s: GameState, p: HeroProfile, t: Track, d: Float): Pair<Float, Float> {
        if (!p.ranged) return if (d > p.attackRange * 0.8f) toward(s, t.x, t.y) else (0f to 0f)
        return when {
            d < p.attackRange * 0.55f -> away(s, listOf(t))
            d > p.attackRange -> toward(s, t.x, t.y)
            else -> 0f to 0f
        }
    }

    // Lane route from base: EXP = up then right, Gold = right then up, Mid = your push direction.
    // Jungle and roam head to the middle of the map, or to the team when it is in sight.
    private fun laneVector(s: GameState): Triple<Float, Float, String> {
        val travelling = s.sinceSpawnMs < Cfg.laneTravelSec * 1000L
        fun t(v: Pair<Float, Float>, what: String) = Triple(v.first, v.second, what)
        return when (s.lane) {
            Lane.EXP, Lane.GOLD, Lane.MID -> laneByMap(s) ?: when (s.lane) {
                Lane.EXP -> if (travelling) t(0f to -1f, "going up to the lane") else t(1f to 0f, "pushing right")
                Lane.GOLD -> if (travelling) t(1f to 0f, "going right to the lane") else t(0f to -1f, "pushing up")
                else -> t(pushVector(), "pushing ${Cfg.pushDir}")
            }
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

    // With your minimap position: walk the lane (EXP: up the left edge then along the top,
    // gold: along the bottom then up the right edge, mid: straight) to the next enemy tower,
    // and wait at the edge of its range for your minions.
    private fun laneByMap(s: GameState): Triple<Float, Float, String>? {
        val me = s.minimapSelf ?: return null
        val target = TowerLogic.laneTarget(s, s.lane) ?: return null
        val corner = when (s.lane) {
            Lane.EXP -> if (me.second > 0.15f && me.first < 0.5f) (0.08f to 0.08f) else null
            Lane.GOLD -> if (me.first < 0.85f && me.second > 0.5f) (0.92f to 0.92f) else null
            else -> null
        }
        if (corner != null) {
            val (mx, my) = mapDir(me, corner)
            return Triple(mx, my, "walking to the ${s.lane.label}")
        }
        val dist = kotlin.math.hypot(target.x - me.first, target.y - me.second)
        val what = if (target.bonus) "bonus tower" else if (s.towers.isEmpty()) "enemy base" else "next enemy tower"
        if (dist < TowerLogic.range() + 0.03f) {
            // no standing around at the tower: walk back along the lane to meet our next wave
            val home = s.allyTowers.minByOrNull { kotlin.math.hypot(it.first - me.first, it.second - me.second) } ?: (0.1f to 0.9f)
            val (bx, by) = mapDir(me, home)
            return Triple(bx, by, "meeting our next minion wave (the $what needs minions)")
        }
        val (mx, my) = mapDir(me, target.x to target.y)
        return Triple(mx, my, "pushing to the $what")
    }

    // walk on the minimap from `me` to `to`, around walls when the wall map knows the way
    // A thin wall straight ahead with a long way round: go straight and jump it (see wallHop).
    private fun mapDir(me: Pair<Float, Float>, to: Pair<Float, Float>): Pair<Float, Float> {
        val straight = norm(to.first - me.first, to.second - me.second)
        val sd = kotlin.math.hypot(to.first - me.first, to.second - me.second)
        val wm = com.example.mlbbresearch.vision.WallMap
        if (Cfg.wallHop && wm.thinWallAhead(me, straight.first, straight.second, minOf(0.15f, sd)) &&
            wm.distance(me, to) > sd * 1.35f + 0.04f
        ) {
            pendingHop = true
            return straight
        }
        return wm.direction(me, to) ?: straight
    }

    // MOBA rule: run home towards your own tower (it shoots the chasers), not just "away",
    // unless that way leads through the enemies
    private fun retreatDir(s: GameState, from: List<Track>): Pair<Float, Float> {
        val (ax, ay) = away(s, from)
        val me = s.minimapSelf ?: return ax to ay
        val home = s.allyTowers.minByOrNull { kotlin.math.hypot(it.first - me.first, it.second - me.second) } ?: (0.1f to 0.9f)
        val (tx, ty) = mapDir(me, home)
        if (tx * ax + ty * ay < -0.2f) return ax to ay
        return norm(ax * 0.5f + tx * 0.5f, ay * 0.5f + ty * 0.5f)
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
                chaseDash(s, p, d)?.let { if (it !in casts) casts.add(it) }
                val (mx, my) = heroSpacing(s, p, t, d)
                TacticalDecision(Intent.ENGAGE, mx, my, casts, t.x, t.y, "enemy #${t.id}", t.hp, t.id,
                    "$tag, target HP ${(t.hp * 100).toInt()}%")
            }
            FightOption.POKE -> {
                // only ranged heroes poke (a melee hero cannot hit from there): fall back to the rules
                if (!p.ranged) return null
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
            if (!force && now - heldSince < (if (Cfg.fastMode) 300 else 600) && held != Intent.IDLE && held != Intent.PUSH) {
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
