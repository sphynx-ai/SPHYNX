package com.sphynx.ai

import com.sphynx.game.SkillBook
import com.sphynx.game.HeroPlaybook
import com.sphynx.game.Passive

import com.sphynx.core.Cfg
import com.sphynx.game.CooldownTracker
import com.sphynx.game.GameState
import com.sphynx.game.GameStateTracker
import com.sphynx.game.Lane
import com.sphynx.game.HeroProfile
import com.sphynx.game.Role
import com.sphynx.game.Phase
import com.sphynx.game.UltRule
import com.sphynx.learning.FightOption
import com.sphynx.vision.Seen
import com.sphynx.vision.Track
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

    /** The game's own rules (heroes, spells, jungle, objectives, towers): the app sets Mobile Legends' at start
     *  (app/ProbesWiring), the tests set it too. Phase 2 adapter split, step 4. */
    @Volatile var rules: TacticalRules = TacticalRules.NONE

    private var held: Intent = Intent.IDLE
    private var heldSince = 0L
    private var recallUntil = 0L
    private var regenUntil = 0L
    private var ambushSince = 0L

    // Everything the engine remembers about the current match goes back to its start value (fixed 2026-11-14:
    // before, only the held intent and the recall / regen timers were reset, so a restart of the AI soon after a
    // stop could act on a monster, objective commitment or decision from before - the Phase 2 regression net
    // showed it). A new remembered field must be added here; TacticalEngineResetTest checks it.
    fun reset() {
        held = Intent.IDLE
        heldSince = 0L
        recallUntil = 0L
        regenUntil = 0L
        ambushSince = 0L
        nextPlan = "-"
        lastTargetId = -1
        lastBushCheck = 0L
        committed = null
        commitUntil = 0L
        lastTravelDash = 0L
        travelSince = 0L
        pendingHop = false
        movingSince = 0L
        invadeAt = null
        invadeArrived = 0L
        invadeSkip.clear()
        invadeStatus = "-"
        lastMonX = -1f; lastMonY = -1f; lastMonHp = 1f; lastMonAt = 0L
        lastMonSpecies = ""; lastMonBoss = false
        objCommit.reset()
        hitWatch.reset()
        monComboStartedAt = 0L
        monsterContact.reset()
        rules.jungle.reset()
        com.sphynx.game.MapIntel.reset()
    }

    // SCENERY CALIBRATION ONLY (owner, 2026-11-11): the tour's next step, steered around walls, with no
    // attack and no skill - the Player does not fight while it calibrates. Without a step (minimap not
    // read yet, between waypoints) it stands still.
    fun calibrationStep(s: GameState, p: HeroProfile): TacticalDecision {
        if (s.self != null && s.phase == Phase.IN_GAME) rules.scenery.observe(s)
        val d = rules.scenery.roamDecision(s, p)?.let { steerAroundWalls(s, it) }
            ?: return TacticalDecision(Intent.PUSH, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1, "scenery calibration: " +
                rules.scenery.status)
        return if (d.casts.isEmpty()) d else TacticalDecision(d.intent, d.moveX, d.moveY, emptyList(), -1f, -1f, d.targetLabel, -1f, -1, d.reason)
    }

    // option: what the learning brain chose for this fight (null / RULES = the normal rules)
    fun decide(s: GameState, p: HeroProfile, plan: StrategyPlan, learnedBias: Float, option: FightOption? = null): TacticalDecision {
        pendingHop = false
        if (s.self != null && s.phase == Phase.IN_GAME) {
            rules.scenery.observe(s)
            rules.scenery.roamDecision(s, p)?.let { return steerAroundWalls(s, it) }
        }
        // paths keep out of the enemy towers' range (WallMap danger zones - owner, 2026-11-12) and the HP is
        // watched for hits (no regenerating while being hit)
        com.sphynx.vision.WallMap.setDanger(rules.towers.effectiveTowers(s).map { it.x to it.y }, rules.towers.range() * 1.15f)
        hitWatch.add(s.ts, s.selfHp)
        var d = decideRaw(s, p, plan, learnedBias, option)
        d = leashGuard(s, d)
        // game sense: remember enemies that left the map; weigh the options (the "why")
        com.sphynx.game.GameMemory.update(s, d.reason.substringBefore(" |").take(60), plan.plan)
        if (s.self != null && s.phase == Phase.IN_GAME) {
            val sc = Deliberation.score(s, p)
            val brainFight = option != null && option != FightOption.RULES
            if (!brainFight) Deliberation.vetoFight(s, d, sc)?.let { why ->
                val (mx, my) = retreatDir(s, s.enemiesWithin(0.9f))
                d = TacticalDecision(Intent.RETREAT, mx, my, d.casts.filter { it != "ult" && it != "attack" }, -1f, -1f, "-", -1f, -1, why)
            }
            if (!brainFight) Deliberation.takeKill(s, p, d, sc)?.let { t ->
                val casts = arrayListOf("attack")
                for (sk in p.combo) if (CooldownTracker.ready(sk, p)) casts.add(sk)
                if (CooldownTracker.ready("ult", p) && "ult" !in casts) casts.add("ult")
                val (mx, my) = toward(s, t.x, t.y)
                d = TacticalDecision(Intent.ENGAGE, mx, my, casts, t.x, t.y, "enemy #${t.id}", t.hp, t.id,
                    "the scores say take the kill: attack %.2f > retreat %.2f, enemy at %d%%".format(sc.attack, sc.retreat, (t.hp * 100).toInt()))
            }
            Deliberation.observe(s, d, sc)
        }
        // (a chase for a kill on an enemy under 10% may follow it into tower range)
        val killChase = d.intent == Intent.ENGAGE && d.targetHp in 0f..0.10f
        if (d.intent != Intent.RETREAT && d.intent != Intent.RECALL && d.intent != Intent.IDLE && !killChase) {
            // never walk into enemy tower range without your minions there
            val (gx, gy, changed) = rules.towers.guard(s, d.moveX, d.moveY)
            if (changed) d = TacticalDecision(d.intent, gx, gy, d.casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
                d.reason + " (keeping out of enemy tower range)")
        }
        d = towerRingRule(s, d)
        d = dodge(s, p, d)
        d = commit(s, d)
        return travelDash(s, p, wallHop(s, p, steerAroundWalls(s, d)))
    }

    // The enemy tower's circle on screen:
    //  RED (inside, no minions): the tower shoots you - walk straight out, away from the tower
    //    (only a kill on an enemy under 10% with a healthy survival score may stay a moment)
    //  YELLOW (just outside, no minions): never step in; wait at the edge for your minions
    //  GREEN (your minions are in): safe to go in and hit the tower (see step 7b)
    private fun towerRingRule(s: GameState, d0: TacticalDecision): TacticalDecision {
        val ring = s.towerRing
        val self = s.self ?: return d0
        if (ring.zone == com.sphynx.vision.TowerRing.Zone.NONE) return d0
        // attacking an enemy HERO inside its tower range: the tower shoots YOU first, even with our
        // minions there - so for a hero fight even a green circle counts as red / yellow
        val targetIn = d0.intent == Intent.ENGAGE && d0.targetX >= 0f &&
            ((d0.targetX - ring.cx) / ring.rx).let { it * it } + ((d0.targetY - ring.cy) / ring.ry).let { it * it } < 1f
        if (ring.zone == com.sphynx.vision.TowerRing.Zone.GREEN && !targetIn) return d0
        val (tx, ty) = ring.dirFrom(self.x, self.y, s.aspect)
        val killChase = d0.intent == Intent.ENGAGE && (d0.targetHp in 0f..0.10f && Utility.lastSurvival >= 50f || rules.deathImmune(s))
        if ((ring.zone == com.sphynx.vision.TowerRing.Zone.RED || targetIn) && ring.inside) {
            if (killChase) return d0
            val casts = d0.casts.filter { it != "attack" }      // no hitting the tower from in here
            return TacticalDecision(Intent.RETREAT, -tx, -ty, casts, -1f, -1f, "-", -1f, -1,
                if (targetIn && ring.zone == com.sphynx.vision.TowerRing.Zone.GREEN)
                    "fighting a hero under its tower: the tower shoots me first, getting out"
                else "inside the enemy tower range with no minions (red circle): getting out")
        }
        // yellow: any step towards the tower is taken out; with nothing left, wait for minions
        val dot = d0.moveX * tx + d0.moveY * ty
        if (dot <= 0.15f || killChase || d0.intent == Intent.RETREAT) return d0
        val sx = d0.moveX - dot * tx; val sy = d0.moveY - dot * ty
        val l = kotlin.math.hypot(sx, sy)
        return if (l > 0.35f) TacticalDecision(d0.intent, sx / l, sy / l, d0.casts.filter { it != "attack" }, d0.targetX, d0.targetY,
            d0.targetLabel, d0.targetHp, d0.targetId, d0.reason + " (along the edge of the tower range, yellow circle)")
        else TacticalDecision(Intent.PUSH, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1,
            "waiting for our minions at the tower range (yellow circle)")
    }

    // DODGE: an enemy just dashed AT you and you are not fighting it - step sideways (most dashes
    // and skill shots fly straight: a step to the side makes them miss), away from its line.
    // Fighting it anyway (it is the target of an engage): keep fighting.
    private fun dodge(s: GameState, p: HeroProfile, d: TacticalDecision): TacticalDecision {
        val e = com.sphynx.game.EnemyIntel.dashAtMe(s) ?: return d
        if (d.intent == Intent.ENGAGE && d.targetId == e.id) return d
        if (d.intent == Intent.RECALL && s.enemiesWithin(0.9f).isEmpty()) return d
        if (rules.channelling(s)?.allowMove == false) return d
        val self = s.self ?: return d
        // perpendicular to the enemy's dash, the side that also leads away from it
        val lx = e.vx * s.aspect; val ly = e.vy
        val l = hypot(lx, ly).coerceAtLeast(1e-3f)
        var px = -ly / l; var py = lx / l
        if (px * (self.x - e.x) * s.aspect + py * (self.y - e.y) < 0f) { px = -px; py = -py }
        val casts = d.casts.toMutableList()
        // (the escape skill only under 20% HP, as in the retreat rule)
        if (s.selfHp in 0f..0.20f) p.escapeSkill?.let { if (CooldownTracker.ready(it, p) && it !in casts) casts.add(it) }
        return TacticalDecision(if (d.intent == Intent.ENGAGE) Intent.ENGAGE else Intent.RETREAT, px, py, casts, d.targetX, d.targetY,
            d.targetLabel, d.targetHp, d.targetId, "dodging: enemy #${e.id} dashed at me, stepping aside")
    }

    // Walls seen on screen right ahead (rock, cliff, trees): curve 35 or 70 degrees to the clear
    // side before bumping into them. If every way is blocked, the wall-jump check takes over.
    private fun steerAroundWalls(s: GameState, d: TacticalDecision): TacticalDecision {
        val self = s.self ?: return d
        // Yu Zhong in Black Dragon flight flies straight over all walls and terrain! (v37.15)
        if (rules.steeringTransform(s)) return d
        if (!Cfg.wallAvoid || (d.moveX == 0f && d.moveY == 0f) || s.screenWalls.isEmpty()) return d
        val bd = com.sphynx.vision.BushDetector
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

    @Volatile var lastTargetId = -1
    private var lastBushCheck = 0L

    fun planAhead(s: GameState, d: TacticalDecision, p: HeroProfile): String {
        val hp = s.selfHp
        nextPlan = when {
            d.intent == Intent.RECALL -> "back in base: buy, then walk the lane"
            hp in 0f..0.35f && d.intent != Intent.RETREAT -> "recall or Regen once safe"
            d.intent == Intent.ENGAGE && s.minionsWithin(0.9f).isNotEmpty() -> "after the fight: clear the minion wave"
            d.intent == Intent.ENGAGE && s.lane == Lane.JUNGLE -> "after the fight: back to the jungle camps"
            d.intent == Intent.ENGAGE -> "after the fight: walk the lane / push"
            d.intent == Intent.JUNGLE -> rules.jungle.next?.let { "next: the ${if (it.buff) "buff" else "camp"} at %.0f%%,%.0f%% of the map".format(it.x * 100, it.y * 100) }
                ?: "next: look for the next camp"
            d.intent == Intent.LANE_CLEAR && rules.towers.selfInRange(s, 0.06f) -> "after the wave: push the tower with our minions"
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
    //  - Active continuous-damage skills on the badge / skill icon (e.g., Balmond s2, Cici s1) are NEVER interrupted (v37.15)
    fun filterCasts(d: TacticalDecision, s: GameState, p: HeroProfile): TacticalDecision {
        // 1. Yu Zhong Black Dragon Flight (v37.15): s1/s2/s3 and basic attacks are locked; only allow "ult" (early landing on target) or "spell"
        if (rules.steeringTransform(s)) {
            val dragonCasts = d.casts.filter { it == "ult" || it == "spell" }
            return if (dragonCasts == d.casts) d
            else TacticalDecision(d.intent, d.moveX, d.moveY, dragonCasts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId, d.reason)
        }

        val vsHero = (d.intent == Intent.ENGAGE || d.intent == Intent.AMBUSH || d.intent == Intent.IMITATE) &&
            d.targetId >= 0 || d.intent == Intent.RETREAT
        var casts = d.casts
        val activeCont = rules.channelling(s)
        // Wall-hop and travel dashes may ONLY use non-ultimate mobility skills (s1, s2, s3), NEVER "ult"! (v37.13)
        val hop = if (d.reason.contains("passing the wall") || d.reason.contains("to get there faster")) rules.dashSlot(s.ownHero)?.takeIf { it != "ult" } else null
        val transformOrEnhanced = CooldownTracker.lowCdUlt(s.ownHero)
        val enemyHeroInUltReach = s.enemiesWithin(maxOf(p.engageRange * 1.15f, 0.52f)).isNotEmpty()
        val emergencyDefUlt = d.intent == Intent.RETREAT && p.ult == UltRule.DEFENSIVE && s.selfHp in 0f..0.22f && s.enemiesWithin(0.45f).isNotEmpty()
        // Strictly forbid Ultimate (especially Ultimate with dash) when roaming, pathing, farming creeps/waves, or when no enemy hero is in reach!
        if (!transformOrEnhanced && !((vsHero && enemyHeroInUltReach) || emergencyDefUlt)) {
            casts = casts.filter { it != "ult" }
        }
        // Coach: you cast this skill at enemies about this close; wait until the target is that
        // close (a far-away cast mostly misses) - only for skills seen at least 3 times
        // (while the combo plays against a hero, or while a continuous-damage skill is active on the badge/icon, no extra skills are thrown in)
        val comboOn = d.intent == Intent.ENGAGE && ComboRunner.running
        if (Cfg.skillsWhenReady && !comboOn && activeCont == null && inReach(d, s, p)) {
            val nonUltSlots = rules.skillSlots(s.ownHero).filter { it != "ult" }
            val extra = nonUltSlots.filter { it !in casts && CooldownTracker.ready(it, p) }
            if (extra.isNotEmpty()) casts = casts + extra
        }
        // only basic attacks damage turrets: no skills thrown at a tower
        if (d.targetLabel == "tower") casts = casts.filter { it == "attack" || it == "regen" || it == "recall" }
        // Nothing to hit = no attack, no skills (escape skills, wall jumps, Regen, Recall stay).
        // Something in reach and no skill ready = keep pressing attack.
        // A jungle monster shows NO HP bar until it is hit: standing at a camp the minimap still
        // shows, the attack button is pressed anyway (it locks on to the nearest monster), and the
        // bars appear after the first hit.
        val atCamp = rules.jungle.atCamp && (d.intent == Intent.JUNGLE || d.intent == Intent.LANE_CLEAR) ||
            d.intent == Intent.PUSH && s.towerRing.zone == com.sphynx.vision.TowerRing.Zone.GREEN ||
            d.reason.startsWith("finishing the monster") ||
            d.intent == Intent.OBJECTIVE && d.targetLabel == "turtle" ||   // at the Turtle pit: its bar shows after the first hit
            d.reason.startsWith("invading: first hit")                     // at an enemy camp (bars show after the first hit)
        val skillRange = s.enemiesWithin(maxOf(p.engageRange * 1.35f, 0.55f)).isNotEmpty() || atCamp ||
            s.minionsWithin(maxOf(p.attackRange * 2.2f, p.engageRange * 1.15f, 0.45f)).isNotEmpty() ||
            s.monsters.any { s.distToSelf(it.x, it.y) <= maxOf(p.attackRange * 2.4f, p.engageRange * 1.2f, 0.48f) }
        val hitRange = s.enemiesWithin(maxOf(p.attackRange * 1.75f, 0.38f)).isNotEmpty() || atCamp ||
            s.minionsWithin(maxOf(p.attackRange * 1.85f, 0.42f)).isNotEmpty() ||
            s.monsters.any { s.distToSelf(it.x, it.y) <= maxOf(p.attackRange * 2.2f, 0.48f) } ||
            rules.towers.canPush(s)
        // Chasing an enemy hero: keep pressing attack while walking after it (it lands the moment
        // they are in reach). Standing hidden in a bush: still hit whatever is in reach.
        val chasing = (d.intent == Intent.ENGAGE || d.intent == Intent.IMITATE) && d.targetId >= 0 &&
            d.targetX >= 0f && (s.distToSelf(d.targetX, d.targetY) <= p.engageRange * 1.3f || d.targetHp in 0f..0.10f)
        val hiding = d.intent == Intent.RETREAT && d.moveX == 0f && d.moveY == 0f
        // in a bush: no attack (it gives you away) until the fight has started - you were hit, or
        // you are already fighting
        val fightStarted = GameStateTracker.hurtRecently(s.ts) || Utility.state == Utility.State.ENGAGING
        val bushQuiet = s.inBush && !fightStarted && (hiding || d.intent == Intent.AMBUSH)
        if (d.intent != Intent.RETREAT) {
            if (!skillRange && !rules.towers.canPush(s) && !chasing) {
                casts = casts.filter { it == hop || it == "regen" || it == "recall" }
            } else if (!hitRange && !chasing) {
                casts = casts.filter { it != "attack" }
            }
        }
        // (never while recalling or regenerating: attacking cancels the channel)
        // (nor while backing off to wait for a better moment: stopping to hit would undo it)
        if (d.intent == Intent.RECALL || d.intent == Intent.REGEN || d.reason.startsWith("positioning")) casts = casts.filter { it != "attack" }
        else if (bushQuiet) casts = casts.filter { it != "attack" }
        else if ((hitRange && (d.intent != Intent.RETREAT || hiding) || chasing) && "attack" !in casts) {
            // basic attacks keep going between and alongside skills (and while they cool down)
            casts = casts + "attack"
        }
        // how this hero is played: keep the escape, heal / shield when hurt, self-buff first
        casts = HeroPlaybook.applyCasts(d, casts, s, p) { CooldownTracker.ready(it, p) }
        casts = spellCasts(s, p, d, casts)

        // 2. Enforce Active Continuous-Damage Skill Guard across all intents (PVP, Jungle, Lane Clear) (v37.15):
        // Never allow another skill (or basic attack when allowBasicAttack = false, e.g. Balmond s2) to interrupt an active continuous-damage skill!
        var moveX = d.moveX
        var moveY = d.moveY
        if (activeCont != null && d.intent != Intent.RETREAT) {
            val canExecuteWithUlt = activeCont.executeUltHp > 0f && d.intent == Intent.ENGAGE && d.targetHp in 0f..activeCont.executeUltHp
            casts = casts.filter { c ->
                when (c) {
                    "spell" -> true
                    "ult" -> canExecuteWithUlt
                    "attack" -> activeCont.allowBasicAttack
                    in rules.allSkillSlots -> false
                    else -> true
                }
            }
            if (!activeCont.allowMove) {
                moveX = 0f
                moveY = 0f
            }
        }

        if (casts == d.casts && moveX == d.moveX && moveY == d.moveY) return d
        return TacticalDecision(d.intent, moveX, moveY, casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId, d.reason)
    }

    // THE BATTLE SPELL, by what it does (wiki descriptions) - not only at very low HP.
    // Retribution: only buffs (Molten Fiend / Thunder Fenrir), Turtle, Lord (see hitMonster) and in a
    // fight against a hero for the edge (slow / burn), never on minions or common creeps.
    private fun spellCasts(s: GameState, p: HeroProfile, d: TacticalDecision, casts0: List<String>): List<String> {
        val sp = s.spell ?: return casts0
        if ("spell" in casts0 || !CooldownTracker.ready("spell", p)) return casts0
        val fight = d.intent == Intent.ENGAGE || d.intent == Intent.AMBUSH
        val running = d.intent == Intent.RETREAT && d.moveX != 0f || d.intent == Intent.RETREAT && d.moveY != 0f
        val tDist = if (d.targetX >= 0f) s.distToSelf(d.targetX, d.targetY) else 9f
        val close = s.enemiesWithin(0.15f).size
        val near = s.enemiesWithin(0.5f).size
        val hurt = GameStateTracker.hurtRecently(s.ts)
        // which spell does what is the game's rule (MLBB: MlbbTacticalRules.useSpellInFight)
        val use = rules.useSpellInFight(sp, s, p, fight, running, tDist, close, near, hurt, d.targetHp)
        return if (use) casts0 + "spell" else casts0
    }

    // fighting something right now: an enemy hero, minions, a jungle camp or turtle / lord, in reach
    private fun inReach(d: TacticalDecision, s: GameState, p: HeroProfile): Boolean {
        return when (d.intent) {
            Intent.ENGAGE -> d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= maxOf(p.engageRange * 1.3f, 0.52f)
            Intent.LANE_CLEAR, Intent.OBJECTIVE -> d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= maxOf(p.attackRange * 2.1f, p.engageRange)
            Intent.JUNGLE -> rules.jungle.atCamp || s.creeps.isNotEmpty() || d.targetX >= 0f && s.distToSelf(d.targetX, d.targetY) <= maxOf(p.attackRange * 2.1f, p.engageRange)
            else -> false
        }
    }

    // ---- FINISH THE CURRENT PLAN: a walk to a camp / an objective / a lane / a fight on the map
    // is kept for up to 2.5 s instead of switching to another walk every frame (no back-and-forth,
    // no standing around). Anything urgent (danger, a fight, something to hit) takes over at once.
    private var committed: TacticalDecision? = null
    private var commitUntil = 0L
    private fun travelling(d: TacticalDecision) = (d.moveX != 0f || d.moveY != 0f) && d.casts.isEmpty() && d.targetX < 0f &&
        (d.intent == Intent.PUSH || d.intent == Intent.GROUP || d.intent == Intent.JUNGLE || d.intent == Intent.OBJECTIVE)
    private fun commit(s: GameState, d: TacticalDecision): TacticalDecision {
        val now = s.ts
        val c = committed
        val urgent = !travelling(d) || s.enemiesWithin(0.9f).isNotEmpty() || GameStateTracker.hurtRecently(now)
        if (urgent) { committed = if (travelling(d)) d else null; commitUntil = now + 1200; return d }
        if (c == null || now > commitUntil || c.intent == d.intent && c.reason.substringBefore(':') == d.reason.substringBefore(':') ||
            d.intent == Intent.IDLE) {
            if (c == null || now > commitUntil || c.intent != d.intent || c.reason.substringBefore(':') != d.reason.substringBefore(':')) commitUntil = now + 1200
            committed = d
            return d
        }
        // a different walk while the current one is not finished: carry on with the current one
        return TacticalDecision(c.intent, c.moveX, c.moveY, d.casts, c.targetX, c.targetY, c.targetLabel, c.targetHp, c.targetId,
            c.reason + " (finishing this first)")
    }

    // ---- DASH TO TRAVEL: on a long walk (to a camp, a lane, an objective, roaming) with nobody
    // around, the dash skill speeds the trip up (aimed the way the hero walks). Not the ultimate,
    // not while low, at most every 6 s.
    private var lastTravelDash = 0L
    private var travelSince = 0L
    private fun travelDash(s: GameState, p: HeroProfile, d: TacticalDecision): TacticalDecision {
        val now = s.ts
        if (!travelling(d)) { travelSince = 0L; return d }
        if (travelSince == 0L) travelSince = now
        if (rules.channelling(s) != null || rules.steeringTransform(s)) return d
        val slot = rules.dashSlot(s.ownHero) ?: return d
        if (slot == "ult" || slot in d.casts || now - lastTravelDash < 6000 || now - travelSince < 800) return d
        if (s.selfHp in 0f..0.5f || s.enemiesWithin(1.0f).isNotEmpty() || s.creeps.isNotEmpty() || s.boss != null || rules.jungle.atCamp) return d
        if (!CooldownTracker.ready(slot, p)) return d
        lastTravelDash = now
        return TacticalDecision(d.intent, d.moveX, d.moveY, d.casts + slot, -1f, -1f, d.targetLabel, d.targetHp, d.targetId,
            d.reason + " | dash ($slot) to get there faster")
    }

    // ---- passing walls with a dash, a mobility skill or Flicker ----
    private var pendingHop = false       // the path planner found a thin wall worth jumping
    private var movingSince = 0L

    private fun wallHop(s: GameState, p: HeroProfile, d: TacticalDecision): TacticalDecision {
        val now = s.ts
        val moving = d.moveX != 0f || d.moveY != 0f
        if (!moving) { movingSince = 0L; return d }
        if (movingSince == 0L) movingSince = now
        if (!Cfg.wallHop || rules.channelling(s) != null || rules.steeringTransform(s)) return d
        // stuck against a wall: walking for 1 s but the camera has not moved for 0.6 s
        val stuck = now - movingSince > 1000 && now - GameStateTracker.lastMotionAt > 600
        if (!pendingHop && !stuck) return d
        val dash = if (Cfg.useDash) rules.dashSlot(s.ownHero)?.takeIf { it != "ult" && CooldownTracker.ready(it, p) } else null
        // Flicker has a long cooldown: only to escape or to catch someone
        val flicker = if (rules.isBlink(s.spell) && CooldownTracker.ready("spell", p) &&
            (d.intent == Intent.RETREAT || d.intent == Intent.ENGAGE)) "spell" else null
        val slot = dash ?: flicker ?: return d
        if (slot in d.casts) return d
        val self = s.self ?: return d
        // aim the jump the way we walk (a chase keeps its target)
        val tx = if (d.targetX >= 0f && d.intent == Intent.ENGAGE) d.targetX else self.x + d.moveX / s.aspect * 0.3f
        val ty = if (d.targetX >= 0f && d.intent == Intent.ENGAGE) d.targetY else self.y + d.moveY * 0.3f
        movingSince = now
        return TacticalDecision(d.intent, d.moveX, d.moveY, d.casts + slot, tx, ty, d.targetLabel, d.targetHp, d.targetId,
            d.reason + " | passing the wall with ${if (slot == "spell") (s.spell ?: "spell") else slot}")
    }

    private fun decideRaw(s: GameState, p: HeroProfile, plan: StrategyPlan, learnedBias: Float, option: FightOption?): TacticalDecision {
        val self = s.self
        if (s.phase != Phase.IN_GAME || self == null) return TacticalDecision.IDLE
        val now = s.ts
        val hp = s.selfHp
        // the big picture (game state engine) and the role's style (role profile)
        val roleP = rules.roleProfile(s.lane, s.ownHero, s.matchSec)
        val advantage = com.sphynx.game.MatchSense.advantage(s)
        val gamePhase = com.sphynx.game.MatchSense.phase(s)
        val aggression = (plan.aggression + learnedBias + levelBias(s) + advantage * 0.15f).coerceIn(0f, 1f)
        // (the hero's HP bar model: X.Borg without armour leaves earlier, a shielded Esmeralda or
        // Masha's segmented HP stays longer, and Thamuz during Cauterant Inferno stays longer - HeroKit)
        val buffUltAdj = rules.buffUltRetreatShift(s)
        val retreatHp = (p.retreatHp + roleP.retreatBonus + (0.5f - aggression) * 0.15f +
            rules.retreatAdjust(s) + buffUltAdj).coerceIn(0.01f, 0.6f)

        val threat = s.enemiesWithin(0.55f)
        val alliesNear = s.alliesWithin(0.5f).size
        val minions = s.minionsWithin(0.6f)

        // ---- 0. assess: survival, the best target and how good a fight with it is, the state ----
        val kit = SkillBook.get(s.ownHero)
        val surv = Utility.survival(s, p, kit)
        val cand = Utility.bestTarget(s, p, threat, lastTargetId)
        val eng = cand?.let { Utility.engagement(s, p, it, kit) + (aggression - 0.5f) * 30f } ?: 0f
        val bossNow = s.boss
        val objectiveOn = Cfg.takeObjectives && bossNow != null &&
            (s.allies.count { s.dist(it.x, it.y, bossNow.x, bossNow.y) < 0.5f } >= 1 || s.lane == Lane.JUNGLE)
        val fstate = Utility.update(s, surv, eng, objectiveOn, threat.isNotEmpty())

        // ---- 0a. Yu Zhong Black Dragon Flight (1000033086..1000033088, v37.15):
        // While flying as the Black Dragon (center-bottom red bar active), steer directly onto the nearest enemy hero
        // (or camp/lane if no enemy hero is on screen) so the Black Dragon knocks them back and deals collision damage!
        if (rules.steeringTransform(s)) {
            if (cand != null) {
                val d = s.distToSelf(cand.x, cand.y)
                val (mx, my) = toward(s, cand.x, cand.y)
                val landNow = d <= 0.14f && s.transformBarPct in 0.01f..0.22f
                val casts = if (landNow) listOf("ult") else emptyList()
                lastTargetId = cand.id
                return hold(now, TacticalDecision(Intent.ENGAGE, mx, my, casts, cand.x, cand.y, "enemy #${cand.id}", cand.hp, cand.id,
                    "${rules.steeringTransformName} (${(s.transformBarPct.coerceAtLeast(0f) * 100).toInt()}%): steering onto enemy #${cand.id}"), force = true)
            }
        }

        // ---- 0b. Blue Recall Execution Bar (1000033098..1000033099, v37.15):
        // When the blue Recall execution bar is active at the bottom-center of the screen and no enemy is hitting us,
        // stand completely still (0f, 0f) with empty casts so the Recall channel finishes cleanly without interruption!
        if (s.recallBarPct > 0f && threat.isEmpty() && !GameStateTracker.hurtRecently(now)) {
            recallUntil = maxOf(recallUntil, now + 1500L)
            return TacticalDecision(Intent.RECALL, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1,
                "recalling (${(s.recallBarPct * 100).toInt()}% blue bar)")
        }

        // ---- 1. survive ----
        // Low HP always retreats (EXCEPT Argus during Eternal Evil 4s death immunity on badge/icon!).
        val argusImmortal = rules.deathImmune(s)
        val rulesMode = option == null || option == FightOption.RULES
        val outnumbered = rulesMode && !argusImmortal && threat.size >= alliesNear + 2 && surv < 55f
        val losingDuel = rulesMode && !argusImmortal && threat.size == 1 && alliesNear == 0 && hp < 0.5f && threat[0].hp > hp + 0.25f && surv < 45f
        val brainRetreat = option == FightOption.RETREAT && !argusImmortal && s.enemiesWithin(0.9f).isNotEmpty()
        // LAST STAND (your rule): low HP but a 1v1 you can still win - the enemy is lower than you,
        // or the skills you have ready can finish it (e.g. an execute ultimate when you are both
        // low) - fight instead of running (it would chase and kill you anyway)
        val duelFoe = threat.singleOrNull()?.takeIf { s.enemiesWithin(0.9f).size == 1 && s.distToSelf(it.x, it.y) <= p.engageRange * 1.2f }
        val canFinish = duelFoe != null && (argusImmortal || duelFoe.hp + 0.03f < hp ||
            duelFoe.hp <= kit.burst { CooldownTracker.ready(it, p) } + 0.05f ||
            duelFoe.hp <= 0.4f && p.ult == UltRule.EXECUTE && CooldownTracker.ready("ult", p))
        if (canFinish && duelFoe != null && (hp in 0f..0.15f || fstate == Utility.State.RETREATING) && !outnumbered) {
            val d = s.distToSelf(duelFoe.x, duelFoe.y)
            val casts = arrayListOf("attack")
            for (sk in p.combo) if (sk != "ult" && CooldownTracker.ready(sk, p) && d <= (kit[sk]?.range ?: p.engageRange) * 1.2f) casts.add(sk)
            if (CooldownTracker.ready("ult", p) && d <= p.engageRange) casts.add("ult")
            chaseDash(s, p, d)?.let { if (it !in casts) casts.add(it) }
            val (mx, my) = if (d > p.attackRange * 0.8f) toward(s, duelFoe.x, duelFoe.y) else heroSpacing(s, p, duelFoe, d)
            lastTargetId = duelFoe.id
            return hold(now, TacticalDecision(Intent.ENGAGE, mx, my, casts, duelFoe.x, duelFoe.y, "enemy #${duelFoe.id}", duelFoe.hp, duelFoe.id,
                "last stand: 1v1 I can win (me ${(hp * 100).toInt()}%, enemy ${(duelFoe.hp * 100).toInt()}%)"), force = true)
        }
        if (!argusImmortal && ((hp in 0f..0.15f || fstate == Utility.State.RETREATING) && threat.isNotEmpty() || outnumbered || losingDuel || brainRetreat ||
                plan.plan == "retreat" && threat.isNotEmpty() && rulesMode)
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
                else s.minimapSelf?.let { me ->
                    // none on screen: a bush on the minimap, close and on the way out
                    val wm = com.sphynx.vision.WallMap
                    wm.nearestBush(me, 0.08f, mx, my)?.let { mb ->
                        val t = wm.direction(me, mb) ?: mapDir(me, mb); mx = t.first; my = t.second
                    }
                }
            }
            val casts = ArrayList<String>()
            // the escape skill, the dash and a defensive ultimate are spent getting out only under 20% HP
            // (above that, walking back is enough and they stay ready for the next fight)
            val escapeNow = hp in 0f..0.20f
            if (escapeNow) p.escapeSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            if (escapeNow && p.ult == UltRule.DEFENSIVE && CooldownTracker.ready("ult", p)) casts.add("ult")
            // (the battle spell is chosen by what it does: spellCasts)
            // dash away when an enemy is right on you (aimed away from them)
            val dash = dashSlot(s, p)
            if (escapeNow && dash != null && dash !in casts && s.enemiesWithin(0.35f).isNotEmpty()) casts.add(dash)
            recallUntil = 0L
            val awayX = self.x + mx / s.aspect * 0.3f
            val awayY = self.y + my * 0.3f
            return hold(now, TacticalDecision(Intent.RETREAT, mx, my, casts, awayX, awayY, "away", -1f, -1,
                when {
                    outnumbered -> "outnumbered ${threat.size} vs ${alliesNear + 1}"
                    losingDuel -> "losing the duel"
                    brainRetreat && hp > retreatHp -> "brain: back off"
                    else -> "HP ${(hp * 100).toInt()}%, survival ${surv.toInt()}"
                }), force = true)
        }

        // ---- 1. THE LORD NEEDS 3+ ALLIES (owner, 2026-11-12 - BossRule): with fewer near, walk away from it ----
        val lordNear = s.boss?.takeIf { it.species == rules.teamBossId }
        if (lordNear != null && !lordOk(s, lordNear) && s.distToSelf(lordNear.x, lordNear.y) < 0.6f) {
            val (tx, ty) = toward(s, lordNear.x, lordNear.y)
            return hold(now, TacticalDecision(Intent.RETREAT, -tx, -ty, emptyList(), -1f, -1f, "away", -1f, -1,
                "the Lord with only ${lordAllies(s, lordNear)} allies near (needs ${rules.teamBossAllies}): walking away"), force = true)
        }
        val lordLeft = lastMonSpecies == rules.teamBossId && !lordOk(s, null)

        // ---- 1a. Monster Leash Lock (v37.09): if a monster we were just fighting flickers out of sight
        // (e.g. damage numbers / skill effects cover its HP bar), stand completely still (0f, 0f) and keep
        // attacking for up to 1.8 s so we NEVER walk out of its aggro zone and reset the monster! ----
        val monStillAlive = !(lastMonHp <= 0.18f && now - lastMonAt > 1100)
        if (s.creeps.isEmpty() && s.boss == null && threat.isEmpty() && lastMonAt > 0 && now - lastMonAt in 0..1800 && !lordLeft &&
            monStillAlive && s.distToSelf(lastMonX, lastMonY) <= maxOf(p.attackRange * 2.4f, 0.46f)
        ) {
            val casts = arrayListOf("attack")
            val ultToo = CooldownTracker.lowCdUlt(s.ownHero)
            p.waveClearSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            for (sk in p.combo) if ((sk != "ult" || ultToo) && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
            if (rules.isSmite(s.spell) && lastMonHp <= 0.25f && retriWorthy(lastMonSpecies, lastMonBoss) && CooldownTracker.ready("spell", p)) casts.add("spell")
            // issue #2: hold if it was inside your attack range; otherwise close in on where it was (never
            // out of its aggro zone) instead of standing out of range
            val (hx, hy) = monsterStep(s, p, lastMonX, lastMonY)
            return TacticalDecision(Intent.JUNGLE, hx, hy, casts, lastMonX, lastMonY, "monster", lastMonHp, -1,
                "holding position at monster (last seen at ${(lastMonHp * 100).toInt()}% HP)")
        }

        // ---- 1b. never walk away from a monster that is almost dead or that you are fighting:
        // finish it first (its gold / EXP / buff), whatever the lane - then recall / move on.
        val fighting = now - lastMonAt in 0..2500
        val lowMon = (s.creeps + listOfNotNull(s.boss?.takeIf { lordOk(s, it) })).filter {
            s.distToSelf(it.x, it.y) <= maxOf(p.attackRange * 2.2f, 0.44f) && (it.hp <= (if (it.hero) 0.18f else 0.45f) ||
                fighting && !it.hero && s.dist(it.x, it.y, lastMonX, lastMonY) < 0.20f && (hp >= 0.25f || it.hp <= 0.55f))
        }.minByOrNull { it.hp }
        if (lowMon != null && threat.isEmpty() && hp >= 0.04f && now >= recallUntil) {
            return hold(now, hitMonster(s, p, lowMon, if (lowMon.hero) rules.smiteBossPct() else rules.smiteCreepPct(),
                if (lowMon.hero) Intent.OBJECTIVE else Intent.JUNGLE,
                "finishing the monster first (${(lowMon.hp * 100).toInt()}% HP)"), force = true)
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
        var recallAt = if (s.matchSec in 0..180) minOf(Cfg.recallHpPct, 20) else Cfg.recallHpPct
        // passive heals out of combat: stepping away beats walking home
        if (kit.has(Passive.OUT_OF_COMBAT_REGEN)) recallAt = recallAt * 6 / 10
        // LIFESTEAL / SPELL VAMP: a hero that gets HP back by hitting (measured, or from its passive /
        // items) heals on the camp or wave in front of it - home only when really low
        val sustainHere = com.sphynx.game.Sustain.healsByHitting(s.ownHero) &&
            (s.creeps.isNotEmpty() || s.minionsWithin(0.6f).isNotEmpty() || rules.jungle.atCamp)
        if (sustainHere && hp > 0.15f) recallAt = minOf(recallAt, 15)
        // a level-up is moments away (EXP ring almost full) and not in danger: finish the camp or
        // wave first, the new level heals a little and makes the walk back worth it
        val levelSoon = s.xp >= 0.8f && s.level < 15 && hp > 0.15f && (s.creeps.isNotEmpty() || minions.isNotEmpty())
        if (Cfg.useRecall && hp in 0f..recallAt / 100f && safe && !levelSoon && !s.inBush && Cfg.useBushes) {
            // a bush close by: recall from inside it (enemies cannot see you channel)
            s.bushes.filter { s.distToSelf(it.x, it.y) < 0.3f }.minByOrNull { s.distToSelf(it.x, it.y) }?.let { b ->
                val (bx, by) = toward(s, b.x, b.y)
                return TacticalDecision(Intent.RECALL, bx, by, emptyList(), -1f, -1f, "-", -1f, -1, "into the bush to recall hidden")
            }
            // none on screen: the nearest bush on the minimap (a few steps away)
            s.minimapSelf?.let { me ->
                val wm = com.sphynx.vision.WallMap
                wm.nearestBush(me, 0.06f)?.let { mb ->
                    val (bx, by) = wm.direction(me, mb) ?: mapDir(me, mb)
                    return TacticalDecision(Intent.RECALL, bx, by, emptyList(), -1f, -1f, "-", -1f, -1, "into the bush (minimap) to recall hidden")
                }
            }
        }
        if (Cfg.useRecall && hp in 0f..recallAt / 100f && safe && !levelSoon) {
            // Recall channels ~6 s: stand still; an enemy showing up cancels it (step 1 / above)
            regenUntil = 0L
            recallUntil = now + 8000
            return TacticalDecision(Intent.RECALL, 0f, 0f, listOf("recall"), -1f, -1f, "-", -1f, -1, "low HP, safe to recall")
        }

        // ---- 2b. Regen: heal over a few seconds when hurt but not low enough to go home ----
        if (now < regenUntil) {
            if (safe && hp < 0.98f && !hitWatch.beingHit()) {
                return TacticalDecision(Intent.REGEN, 0f, 0f, emptyList(), -1f, -1f, "-", -1f, -1, "regenerating")
            }
            regenUntil = 0L
        }
        if (Cfg.useRegen && hp in 0f..Cfg.regenHpPct / 100f && safe && s.minionsWithin(0.5f).isEmpty() && !hitWatch.beingHit() &&
            CooldownTracker.ready("regen", p)
        ) {
            regenUntil = now + 5000
            return TacticalDecision(Intent.REGEN, 0f, 0f, listOf("regen"), -1f, -1f, "-", -1f, -1,
                "HP ${(hp * 100).toInt()}%, using Regen")
        }

        // ---- 2c. finish off low-HP enemy heroes ----
        //  - under 10% HP: ALWAYS chase if possible: further out, and even under their tower when
        //    you are healthy (40%+ HP) and it is a quick kill (close, or a dash is ready)
        //  - under the execute % (20 by default): chase, but never under their tower
        //  - before level 4 only the under-10% kills (no duels yet)
        val safeTargets = s.enemiesWithin(0.9f).filter { !rules.towers.enemyUnderTower(s, it) || argusImmortal }
        // (never on HP alone: you must be fairly safe, the enemy alone, and no objective to lose)
        val dying = s.enemiesWithin(1.3f).filter { e ->
            e.hp <= 0.10f && (hp > 0.15f || argusImmortal) && surv >= 35f && !(objectiveOn && bossNow!!.hp < 0.4f) &&
                s.enemies.none { o -> o.id != e.id && s.dist(o.x, o.y, e.x, e.y) < 0.35f && o.hp > 0.3f } && (!rules.towers.enemyUnderTower(s, e) || argusImmortal ||
                hp >= 0.4f && (s.distToSelf(e.x, e.y) <= p.attackRange * 1.6f || dashSlot(s, p) != null || rules.isBlink(s.spell) && CooldownTracker.ready("spell", p)))
        }.minByOrNull { it.hp + s.distToSelf(it.x, it.y) * 0.3f }
        val heroExecPct = if (p.ult == UltRule.EXECUTE && CooldownTracker.ready("ult", p)) (rules.executeUltBelow(s.ownHero, s.level) * 100f).toInt() else 0
        val effExecPct = maxOf(Cfg.executeHpPct + (if (kit.has(Passive.EXECUTE_BONUS)) 10 else 0), heroExecPct)
        val low = dying ?: if (s.level < 4) null else
            safeTargets.filter { it.hp * 100 <= effExecPct }.minByOrNull { it.hp + s.distToSelf(it.x, it.y) * 0.3f }
        if (low != null && (hp > retreatHp || low === dying || argusImmortal)) {
            val d = s.distToSelf(low.x, low.y)
            val casts = ArrayList<String>()
            if (d <= p.attackRange * 1.3f) casts.add("attack")
            chaseDash(s, p, d)?.let { casts.add(it) }
            for (sk in p.combo) if (sk != "ult" && sk !in casts && d <= p.engageRange && CooldownTracker.ready(sk, p)) casts.add(sk)
            if (d <= p.engageRange && CooldownTracker.ready("ult", p) && (p.ult == UltRule.EXECUTE || p.ult == UltRule.ENGAGE)) casts.add("ult")
            // Execute: true damage that grows with the target's LOST HP - a finisher (wiki), not an opener
            if (rules.isExecute(s.spell) && low.hp <= rules.executeSpellBelow && d <= p.engageRange && CooldownTracker.ready("spell", p)) casts.add("spell")
            if (rules.isNuke(s.spell) && d <= p.engageRange && CooldownTracker.ready("spell", p)) casts.add("spell")
            // Flicker onto a fleeing enemy under 10% that is just out of reach
            if (low === dying && rules.isBlink(s.spell) && d > p.attackRange * 1.5f && d <= p.engageRange && CooldownTracker.ready("spell", p)) casts.add("spell")
            // chasing: straight after it (no keeping distance)
            val (mx, my) = if (low === dying && d > p.attackRange * 0.8f) toward(s, low.x, low.y) else heroSpacing(s, p, low, d)
            return hold(now, TacticalDecision(Intent.ENGAGE, mx, my, casts, low.x, low.y, "enemy #${low.id}", low.hp, low.id,
                (if (low === dying) "chasing a kill: enemy at " else "finishing enemy at ") + "${(low.hp * 100).toInt()}% HP"), force = true)
        }

        // ---- 2d. ambush from a bush: hide while an enemy walks towards us (3 s at most) ----
        if (Cfg.useBushes && hp > 0.55f && (s.lane == Lane.ROAM || s.lane == Lane.JUNGLE || alliesNear > 0 || kit.has(Passive.BUSH_BONUS))) {
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

        // ---- 2e. before level 4: only back off if low HP (< 35%) and outnumbered; otherwise trade with skills & basic attacks ----
        val noDuel = s.level < 4 && alliesNear == 0 && hp < 0.35f && threat.size >= 2
        if (noDuel) {
            val close = threat.filter { s.distToSelf(it.x, it.y) < 0.35f && it.hp > 0.15f }
            if (close.isNotEmpty()) {
                val (mx, my) = retreatDir(s, close)
                return hold(now, TacticalDecision(Intent.RETREAT, mx, my, emptyList(), -1f, -1f, "-", -1f, -1,
                    "level ${s.level}, low HP & outnumbered: backing off"))
            }
        }

        // ---- 2f. LAST HITS first, in every lane: the turtle / lord, a monster or a minion that the
        // next hit kills (gold, EXP, the buff, and no steal). Not in a fight that is going well. ----
        if (fstate != Utility.State.ENGAGING && !ComboRunner.running && surv >= 45f) {
            s.boss?.takeIf { it.hp <= 0.15f && s.distToSelf(it.x, it.y) <= p.engageRange && lordOk(s, it) }?.let { b ->
                return hold(now, hitMonster(s, p, b, rules.smiteBossPct(), Intent.OBJECTIVE,
                    "last hit: ${rules.objectiveName(s.matchSec)} at ${(b.hp * 100).toInt()}%"), force = true)
            }
            s.creeps.filter { it.hp <= 0.2f && s.distToSelf(it.x, it.y) <= p.attackRange * 1.6f }.minByOrNull { it.hp }?.let { c ->
                return hold(now, hitMonster(s, p, c, rules.smiteCreepPct(), Intent.JUNGLE, "last hit: monster at ${(c.hp * 100).toInt()}%"))
            }
            val minStopDist = maxOf(p.attackRange * 1.55f, if (p.ranged) 0.38f else 0.28f)
            minions.filter { it.hp <= 0.18f && s.distToSelf(it.x, it.y) <= minStopDist * 1.15f }.minByOrNull { it.hp }?.let { m ->
                val d = s.distToSelf(m.x, m.y)
                val (mx, my) = if (d > minStopDist) toward(s, m.x, m.y) else (0f to 0f)
                return hold(now, TacticalDecision(Intent.LANE_CLEAR, mx, my, listOf("attack"), m.x, m.y, "minion", m.hp, -1,
                    "last hit: minion at ${(m.hp * 100).toInt()}%"))
            }
        }

        // ================= the rest: each role does these in its own order =================
        val role = rules.role(s.ownHero)
        val me = s.minimapSelf
        val early = s.matchSec in 0..599
        val target = if (noDuel) null else cand?.takeIf { option != null || it in safeTargets }
        if (target != null && option != null && !rulesMode) {
            brainFight(s, p, option, target, hp, alliesNear)?.let { lastTargetId = target.id; return hold(now, it, force = true) }
        }

        // ---- fight an enemy hero (engagement 50+, 35+ to keep going) ----
        fun fight(minEng: Float = 0f): TacticalDecision? {
            val effMin = if (minEng >= 70f) 52f else minEng
            val fightOk = (fstate == Utility.State.ENGAGING || ComboRunner.running || rules.buffUltActive(s)) && eng >= effMin && !(objectiveOn && eng < 60f) || option == FightOption.ALL_IN
            if (target == null || !(rulesMode || option == FightOption.ALL_IN) || s.distToSelf(target.x, target.y) > 0.62f || !fightOk) return null
            val d = s.distToSelf(target.x, target.y)
            lastTargetId = target.id
            val casts = ArrayList<String>()
            if (d <= p.attackRange * 1.45f) casts.add("attack")
            chaseDash(s, p, d)?.let { if (it !in casts) casts.add(it) }
            if (d > p.engageRange * 1.25f && casts.isEmpty()) {
                // out of reach: close in on the target while pressing attack if close enough
                val (mx, my) = toward(s, target.x, target.y)
                return TacticalDecision(Intent.ENGAGE, mx, my, listOf("attack"), target.x, target.y, "enemy #${target.id}",
                    target.hp, target.id, "closing in on enemy #${target.id} (engage ${eng.toInt()})")
            }
            for (sk in p.combo) {
                if (sk == "ult") continue
                val skR = maxOf(kit[sk]?.range ?: p.engageRange, p.engageRange)
                if (d <= skR * 1.25f && CooldownTracker.ready(sk, p)) casts.add(sk)
            }
            if (useUlt(p, s, target, d, hp, threat.size, alliesNear)) casts.add("ult")
            var (mx, my) = heroSpacing(s, p, target, d)
            // a support stays BESIDE the ally it protects (skills reach the enemy from there)
            if (role == Role.SUPPORT) s.allies.minByOrNull { s.distToSelf(it.x, it.y) }?.let { a ->
                if (s.distToSelf(a.x, a.y) > 0.08f) { val (gx, gy) = toward(s, a.x, a.y); mx = gx; my = gy }
                else if (d > p.engageRange) { mx = 0f; my = 0f }
            }
            return TacticalDecision(Intent.ENGAGE, mx, my, casts, target.x, target.y,
                "enemy #${target.id}", target.hp, target.id,
                "target HP ${(target.hp * 100).toInt()}%, ${(d * 100).toInt()} away, engage ${eng.toInt()}, survival ${surv.toInt()}")
        }

        // ---- turtle / lord on screen: take it with the team ----
        fun bossOnScreen(): TacticalDecision? {
            val boss = s.boss ?: return null
            if (!Cfg.takeObjectives || threat.isNotEmpty() || !lordOk(s, boss)) return null
            val alliesThere = s.allies.count { s.dist(it.x, it.y, boss.x, boss.y) < 0.5f }
            if (!(alliesThere >= 1 || s.lane == Lane.JUNGLE || s.lane == Lane.ROAM || boss.hp < 0.5f)) return null
            val name = rules.objectiveName(s.matchSec)
            return hitMonster(s, p, boss, rules.smiteBossPct(), Intent.OBJECTIVE,
                "$name HP ${(boss.hp * 100).toInt()}%" + if (alliesThere > 0) ", with $alliesThere allies" else "")
        }

        // ---- turtle / lord up on the minimap: rotate there (who goes, and how far, depends on the role) ----
        fun objectiveOnMap(maxDist: Float, needAlly: Boolean): TacticalDecision? {
            val obj = s.map.objective ?: run { objCommit.done(); return null }
            if (objCommit.gaveUp(now)) return null
            if (!Cfg.takeObjectives || me == null || threat.isNotEmpty() || hp <= 0.55f || s.level < 4 || s.boss != null) return null
            val dObj = kotlin.math.hypot(obj.first - me.first, obj.second - me.second)
            val alliesThere = s.map.allies.count { kotlin.math.hypot(it.first - obj.first, it.second - obj.second) < 0.2f }
            val enemiesThere = s.map.enemies.count { kotlin.math.hypot(it.first - obj.first, it.second - obj.second) < 0.2f }
            if (dObj > maxDist || needAlly && alliesThere == 0 || enemiesThere > alliesThere) return null
            // the Lord: only with 3+ allies already there (BossRule - owner, 2026-11-12)
            if (rules.objectiveName(s.matchSec) == "lord" && !rules.mayTakeTeamBoss(alliesThere)) return null
            if (dObj < 0.05f) {
                objCommit.done()                          // arrived: the commitment is over (ObjectiveCommit)
                // at the pit and no boss bar on screen. Only the Lord shows its HP bar before it is hit:
                // no Lord bar = it is not in view yet, keep walking into the pit. The Turtle's bar shows
                // only once someone hits it: start it with a basic attack (the skills follow on its bar).
                if (rules.objectiveName(s.matchSec) == "lord") {
                    val (mx, my) = mapDir(me, obj)
                    return TacticalDecision(Intent.OBJECTIVE, mx * 0.5f, my * 0.5f, emptyList(), -1f, -1f, "-", -1f, -1,
                        "at the Lord pit: its bar is not on screen yet, stepping in")
                }
                return TacticalDecision(Intent.OBJECTIVE, 0f, 0f, listOf("attack"), -1f, -1f, "turtle", -1f, -1,
                    "at the Turtle pit: first hit to show its HP bar (it shows only after it is hit)")
            }
            val (mx, my) = com.sphynx.vision.WallMap.direction(me, obj) ?: mapDir(me, obj)
            objCommit.rotating(now)
            return TacticalDecision(Intent.OBJECTIVE, mx, my, emptyList(), -1f, -1f, "-", -1f, -1,
                "${rules.objectiveName(s.matchSec)} is up (minimap): rotating there" + if (alliesThere > 0) ", $alliesThere allies there" else "")
        }

        // ---- jungle monsters on screen (your buffs first) ----
        fun monstersOnScreen(): TacticalDecision? {
            if (threat.isNotEmpty() && (eng >= 70f || surv < 40f)) return null
            val creep = s.creeps.minByOrNull { s.distToSelf(it.x, it.y) } ?: return null
            return hitMonster(s, p, creep, rules.smiteCreepPct(), Intent.JUNGLE,
                (rules.monsterLabel(creep.species) ?: if (rules.jungle.atBuff) "our buff" else "jungle monster") + " HP ${(creep.hp * 100).toInt()}%")
        }

        fun jungleRoute(): TacticalDecision? {
            if (!Cfg.jungleRoute || threat.isNotEmpty()) return null
            // (at level 15 more EXP is useless: only camps on the way)
            return rules.jungle.direction(s, if (s.level >= 15) 0.15f else 9f)?.let { jungleStep(s, p, it) }
        }

        // ---- the minion wave: last hits first (the minion that dies to the next hit), lowest HP ----
        fun minionWave(): TacticalDecision? {
            if (minions.isEmpty() || plan.plan == "group") return null
            // jungler with Retribution: lane minions give 70% less until 5:00 and feed the laners less (wiki)
            if (s.lane == Lane.JUNGLE && rules.isSmite(s.spell) && s.matchSec in 0 until 300 &&
                rules.towers.towerToDefend(s) == null) return null
            val mx0 = minions.map { it.x }.average().toFloat()
            val my0 = minions.map { it.y }.average().toFloat()
            // WAVE MANAGEMENT: PUSH (clear fast with skills: mid before rotating, anyone ahead or with
            // an objective / teamfight to go to), FREEZE (only last hits, no skills, wave stays near our
            // tower: gold lane early or when behind, or enemies hidden near), else CLEAR
            val hiddenNear = (com.sphynx.game.MapIntel.enemiesNear(s, 0.18f) - s.enemies.size) > 0
            val wave = when {
                s.lane == Lane.MID || advantage > 0.2f || s.map.objective != null -> "push"
                (s.lane == Lane.GOLD && gamePhase == com.sphynx.game.MatchSense.GamePhase.EARLY && advantage <= 0f) ||
                    advantage < -0.25f || hiddenNear -> "freeze"
                else -> "clear"
            }
            val pushing = wave == "push"
            if (false && wave == "freeze") {
                val lh = minions.filter { it.hp <= 0.35f }.minByOrNull { it.hp }
                if (lh == null) {
                    // nothing to last-hit yet: stay a little back and let the minions fight
                    val (bx, by) = norm(-pushVector().first, -pushVector().second)
                    val near = minions.minOf { s.distToSelf(it.x, it.y) }
                    val (mx, my) = if (near < p.attackRange * 0.9f) (bx to by) else (0f to 0f)
                    return TacticalDecision(Intent.LANE_CLEAR, mx, my, emptyList(), -1f, -1f, "${minions.size} minions", -1f, -1,
                        "freezing the wave: waiting for last hits")
                }
                val d = s.distToSelf(lh.x, lh.y)
                val (mx, my) = if (d > p.attackRange) toward(s, lh.x, lh.y) else (0f to 0f)
                return TacticalDecision(Intent.LANE_CLEAR, mx, my, if (d <= p.attackRange * 1.4f) listOf("attack") else emptyList(),
                    lh.x, lh.y, "minion", lh.hp, -1, "freezing the wave: last hit at ${(lh.hp * 100).toInt()}%")
            }
            val tgt = minions.minByOrNull { it.hp * 0.6f + s.distToSelf(it.x, it.y) }!!
            val d = s.distToSelf(tgt.x, tgt.y)
            val casts = ArrayList<String>()
            if (d <= maxOf(p.attackRange * 1.5f, 0.35f)) casts.add("attack")
            val wc = p.waveClearSkill
            val reach = maxOf(p.attackRange * 2.0f, p.engageRange)
            if (wc != null && s.distToSelf(mx0, my0) <= reach && CooldownTracker.ready(wc, p)) casts.add(wc)
            if (s.distToSelf(mx0, my0) <= reach) {
                for (sk in p.combo) if (sk != "ult" && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
            }
            val waveStopDist = maxOf(p.attackRange * 1.55f, if (p.ranged) 0.38f else 0.28f)
            val (mx, my) = if (d > waveStopDist) toward(s, tgt.x, tgt.y) else (0f to 0f)
            return TacticalDecision(Intent.LANE_CLEAR, mx, my, casts, tgt.x, tgt.y,
                "${minions.size} minions", tgt.hp, -1,
                "${if (pushing) "pushing" else "clearing"} the wave (${minions.size}), hitting one at ${(tgt.hp * 100).toInt()}%")
        }

        // ---- push the enemy tower: only with your minions in its range and no enemy hero ----
        val ring = s.towerRing
        fun tower(): TacticalDecision? {
            if (ring.zone == com.sphynx.vision.TowerRing.Zone.GREEN && threat.isEmpty()) {
                val dx = (ring.cx - self.x) * s.aspect; val dy = ring.cy - self.y
                val dd = kotlin.math.hypot(dx, dy)
                // Once inside the green tower circle (ring.inside or dd <= ring.ry * 0.85f), stand completely still (0f, 0f) and attack!
                val (mx, my) = if (!ring.inside && dd > ring.ry * 0.85f) (dx / dd) to (dy / dd) else (0f to 0f)
                return TacticalDecision(Intent.PUSH, mx, my, listOf("attack"), -1f, -1f, "tower", -1f, -1,
                    "pushing the tower: our minions are in (green circle)")
            }
            if (rules.towers.canPush(s) && ring.zone != com.sphynx.vision.TowerRing.Zone.YELLOW &&
                ring.zone != com.sphynx.vision.TowerRing.Zone.RED) {
                val n = rules.towers.nearest(s)!!
                val m = s.minimapSelf!!
                val (mx, my) = if (n.second > rules.towers.range() * 0.6f) norm(n.first.first - m.first, n.first.second - m.second) else (0f to 0f)
                return TacticalDecision(Intent.PUSH, mx, my, listOf("attack"), -1f, -1f, "tower", -1f, -1,
                    "pushing the tower with ${s.allyMinions.size} minions")
            }
            return null
        }

        // ---- rotations: defend a tower, join a teamfight, then back to the lane ----
        fun defendTower(): TacticalDecision? = if (s.lane == Lane.JUNGLE) null else rules.towers.towerToDefend(s)?.let { t ->
            val (mx, my) = mapDir(s.minimapSelf!!, t)
            TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "rotating: defending our tower")
        }
        fun teamfight(maxDist: Float): TacticalDecision? {
            if (me == null || threat.isNotEmpty() || hp < 0.6f || s.map.allies.size < 2) return null
            // two allies close together with an enemy next to them = a fight going on
            val spot = s.map.allies.firstOrNull { a ->
                s.map.allies.count { kotlin.math.hypot(it.first - a.first, it.second - a.second) < 0.15f } >= 2 &&
                    s.map.enemies.any { kotlin.math.hypot(it.first - a.first, it.second - a.second) < 0.15f }
            } ?: return null
            val d = kotlin.math.hypot(spot.first - me.first, spot.second - me.second)
            if (d !in 0.1f..maxDist) return null
            val (mx, my) = com.sphynx.vision.WallMap.direction(me, spot) ?: mapDir(me, spot)
            return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "rotating to help a teamfight (minimap)")
        }

        // ---- laners (not equipped with Retribution): only take a small adjacent non-buff camp after Lv4 between waves ----
        fun laneCamps(radius: Float): TacticalDecision? {
            if (!Cfg.lanersTakeCamps || threat.isNotEmpty() || s.level >= 15) return null
            if (s.lane == Lane.ROAM) return null
            val hasRetri = rules.isSmite(s.spell)
            // Non-Retribution laners must NEVER detour into jungle camps before Lv4 (2:40) or while a minion wave is present!
            if (!hasRetri && (s.level < 4 || s.matchSec in 0..160 || minions.isNotEmpty())) return null
            val effRadius = if (hasRetri) radius else minOf(radius, 0.11f)
            return rules.jungle.direction(s, effRadius, allowBuffs = hasRetri)?.let { jungleStep(s, p, it) }
        }

        // ---- Optimized Roamer Pathing (not equipped with Retribution) (v37.13):
        //  1. Opening (0:00..1:10): Escort Mid wave & Lithowanderer river bush (0.44, 0.56 -> 0.39, 0.61)
        //  2. Minimap Ally Rescue: Immediately rotate around walls to any ally fighting an enemy on the minimap
        //  3. Screen Ally Escort & Bush Vision: Step into bushes ahead of nearby allies to provide vision
        //  4. Minimap Carry Follow: Follow Jungler -> Mid -> Gold Laner (Marksman protection)
        //  5. Active River Patrol: Patrol river bushes without ever walking alone into an enemy tower
        fun roam(): TacticalDecision? {
            if (threat.isNotEmpty()) return null
            // Mid Laner 1-3-1 Semi-Roam between waves (Built-In + Online Meta, v37.16):
            if (s.lane == Lane.MID) {
                if (me == null) return null
                val (goal, why) = rules.midRoamTarget(s) ?: return null
                val (mx, my) = com.sphynx.vision.WallMap.direction(me, goal) ?: mapDir(me, goal)
                return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, why)
            }
            if (Cfg.useBushes && hp > 0.55f && !s.inBush && s.allies.isNotEmpty() && now - lastBushCheck > 5500) {
                val (px0, py0) = pushVector()
                val bush = s.bushes.filter { s.distToSelf(it.x, it.y) in 0.08f..0.34f }.firstOrNull { b ->
                    val (bx, by) = toward(s, b.x, b.y); bx * px0 + by * py0 > 0.25f
                }
                if (bush != null) {
                    if (s.distToSelf(bush.x, bush.y) < 0.1f) lastBushCheck = now
                    val (mx, my) = toward(s, bush.x, bush.y)
                    return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "roamer: checking bush ahead for team vision")
                }
            }
            if (s.allies.isNotEmpty()) {
                val ax = s.allies.map { it.x }.average().toFloat(); val ay = s.allies.map { it.y }.average().toFloat()
                if (s.distToSelf(ax, ay) > 0.18f) {
                    val (mx, my) = toward(s, ax, ay)
                    return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "roaming with nearby allies")
                }
                return null
            }
            if (me == null) return null
            // 1. Pro + Online Meta time-windowed Roam waypoint (0:00 Mid/Litho -> 0:36 Jungler guard -> 1:45 Turtle zoning) (v37.16)
            rules.roamWaypoint(s)?.let { (goal, why) ->
                val (mx, my) = com.sphynx.vision.WallMap.direction(me, goal) ?: mapDir(me, goal)
                return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, why)
            }
            // 2. Rescue any ally who has an enemy hero near them on the minimap
            val allyInFight = s.map.allies.filter { a -> !(a.first < 0.18f && a.second > 0.82f) }
                .firstOrNull { a -> s.map.enemies.any { e -> kotlin.math.hypot(e.first - a.first, e.second - a.second) < 0.18f } }
            if (allyInFight != null && kotlin.math.hypot(allyInFight.first - me.first, allyInFight.second - me.second) > 0.055f) {
                val (mx, my) = com.sphynx.vision.WallMap.direction(me, allyInFight) ?: mapDir(me, allyInFight)
                return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "roamer: rotating to assist ally in skirmish")
            }
            // 3. Follow active Jungler / Mid laner, or rotate to protect the Gold laner
            val activeAllies = s.map.allies.filter { a -> !(a.first < 0.18f && a.second > 0.82f) }
            val buddy = activeAllies.filter { a -> a.first >= 0.13f && a.second <= 0.87f }
                .minByOrNull { a -> kotlin.math.abs(a.first + a.second - 1f) * 0.6f + kotlin.math.hypot(a.first - me.first, a.second - me.second) * 0.5f }
                ?: activeAllies.filter { a -> a.second > 0.65f && a.first > 0.35f }
                    .minByOrNull { a -> kotlin.math.hypot(a.first - me.first, a.second - me.second) }
                ?: activeAllies.minByOrNull { a -> kotlin.math.hypot(a.first - me.first, a.second - me.second) }
            if (buddy != null) {
                if (kotlin.math.hypot(buddy.first - me.first, buddy.second - me.second) < 0.055f) return null
                val (mx, my) = com.sphynx.vision.WallMap.direction(me, buddy) ?: mapDir(me, buddy)
                return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "roamer: escorting teammate on map")
            }
            // 4. River & bush vision patrol when no ally icon is visible on the minimap
            val patrol = rules.roamPatrol[(s.ts / 7000L).toInt().mod(rules.roamPatrol.size)]
            if (kotlin.math.hypot(patrol.first - me.first, patrol.second - me.second) < 0.05f) return null
            val (mx, my) = com.sphynx.vision.WallMap.direction(me, patrol) ?: mapDir(me, patrol)
            return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "roamer: river & bush vision patrol")
        }

        // ---- MOBA rule: do not wander alone deep in the enemy half (ganks) ----
        fun safety(): TacticalDecision? {
            if (me == null || s.lane == Lane.JUNGLE || threat.isNotEmpty() || s.allies.isNotEmpty() || s.allyMinions.isNotEmpty() ||
                !(me.second < me.first - 0.25f && me.second > 0.15f && me.first < 0.85f)) return null
            val home = s.allyTowers.minByOrNull { kotlin.math.hypot(it.first - me.first, it.second - me.second) } ?: (0.1f to 0.9f)
            val (mx, my) = mapDir(me, home)
            return TacticalDecision(Intent.PUSH, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "alone deep in the enemy half: falling back")
        }

        // 25-49: not good enough yet - keep a safe distance and wait for a better moment
        fun positioning(): TacticalDecision? {
            if (fstate != Utility.State.POSITIONING || target == null || minions.isNotEmpty() || s.creeps.isNotEmpty()) return null
            // ranged heroes keep outside the enemy team's crowd-control reach (EnemyIntel)
            val keep = if (p.ranged) maxOf(0.42f, com.sphynx.game.EnemyIntel.dangerRange() + 0.03f) else 0.42f
            if (s.distToSelf(target.x, target.y) >= keep) return null
            val (mx, my) = retreatDir(s, listOf(target))
            return TacticalDecision(Intent.GROUP, mx, my, emptyList(), target.x, target.y, "enemy #${target.id}", target.hp, -1,
                "positioning: engage ${eng.toInt()} is not enough, keeping distance")
        }

        // ---- jungle GANK: an enemy in a lane on the minimap, close to us, alone (and better near our
        // laner or our tower), while we are healthy, level 4+ with the ultimate or 2 skills ready ----
        fun gank(): TacticalDecision? {
            if (me == null || threat.isNotEmpty() || hp < 0.6f || s.level < 4) return null
            val ready = listOf("s1", "s2", "ult").count { CooldownTracker.ready(it, p) }
            if (!CooldownTracker.ready("ult", p) && ready < 2) return null
            fun d(a: Pair<Float, Float>, b: Pair<Float, Float>) = kotlin.math.hypot(a.first - b.first, a.second - b.second)
            val prey = s.map.enemies.filter { e ->
                val onLane = e.first < 0.14f || e.second > 0.86f || e.first > 0.86f || e.second < 0.14f || kotlin.math.abs(e.first + e.second - 1f) < 0.12f
                onLane && d(e, me) in 0.08f..0.35f && s.map.enemies.none { o -> o !== e && d(o, e) < 0.15f }
            }.maxByOrNull { e ->
                (if (s.map.allies.any { d(it, e) < 0.15f }) 1f else 0f) + (if (s.allyTowers.any { d(it, e) < 0.15f }) 1f else 0f) - d(e, me)
            } ?: return null
            val (mx, my) = com.sphynx.vision.WallMap.direction(me, prey) ?: mapDir(me, prey)
            return TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "ganking: an enemy alone in a lane (minimap)")
        }

        // ---- EXP SPLIT PUSH: mid / late game, nobody near on the map, healthy: push the side lane ----
        fun splitPush(): TacticalDecision? {
            if (me == null || gamePhase == com.sphynx.game.MatchSense.GamePhase.EARLY || threat.isNotEmpty() || hp < 0.6f) return null
            // only when the enemies are really SEEN elsewhere (2+ on the minimap), not just unknown
            if (s.map.enemies.size < 2 || com.sphynx.game.MapIntel.enemiesNear(s, 0.3f) > 0) return null
            val (px, py, where) = laneVector(s)
            return TacticalDecision(Intent.PUSH, px, py, emptyList(), -1f, -1f, "-", -1f, -1, "split pushing: $where (enemies are elsewhere)")
        }

        // ---- the role's order (RoleProfile): the same jobs for everyone, in the role's order ----
        //  EXP: lane pressure, engage, split push    JUNGLE: farm, gank, objectives
        //  MID: wave clear, rotate, control           GOLD: farm, positioning, survival
        //  ROAM: vision, initiation, protection
        val rotate = if (early) roleP.rotateEarly else roleP.rotateLate
        val needAlly = roleP.objectiveNeedsAlly || (s.lane == Lane.JUNGLE && s.level < 6) ||
            (!early && (s.lane == Lane.EXP || s.lane == Lane.MID))
        val campR = if (s.matchSec in 0..360) roleP.campRadiusEarly else roleP.campRadiusLate
        val order: List<() -> TacticalDecision?> = roleP.order.map { job ->
            when (job) {
                com.sphynx.game.Job.STRONG_FIGHT -> { -> fight(70f) }
                com.sphynx.game.Job.BOSS -> ::bossOnScreen
                com.sphynx.game.Job.OBJECTIVE_MAP -> { -> objectiveOnMap(rotate, needAlly) }
                com.sphynx.game.Job.MONSTERS -> ::monstersOnScreen
                com.sphynx.game.Job.FIGHT -> { -> fight() }
                com.sphynx.game.Job.POSITIONING -> ::positioning
                com.sphynx.game.Job.GANK -> ::gank
                com.sphynx.game.Job.JUNGLE_ROUTE -> ::jungleRoute
                com.sphynx.game.Job.MINIONS -> ::minionWave
                com.sphynx.game.Job.TOWER -> ::tower
                com.sphynx.game.Job.DEFEND -> ::defendTower
                com.sphynx.game.Job.TEAMFIGHT -> { -> teamfight(rotate) }
                com.sphynx.game.Job.LANE_CAMPS -> { -> laneCamps(campR) }
                com.sphynx.game.Job.SPLIT_PUSH -> ::splitPush
                com.sphynx.game.Job.ROAM -> ::roam
                com.sphynx.game.Job.SAFETY -> ::safety
                com.sphynx.game.Job.INVADE -> { -> invade(s, p, kit, surv, threat.isEmpty()) }
                com.sphynx.game.Job.BASE -> { -> attackBase(s, threat.isEmpty(), hp) }
            }
        }
        // COMMITTED TO THE OBJECTIVE (ObjectiveCommit - owner, 2026-11-12): the rotation goes first until arrival,
        // unless in danger; after 45 s without arriving it gives up for 60 s
        if (objCommit.first(now, threat.isNotEmpty(), hp)) {
            (bossOnScreen() ?: objectiveOnMap(9f, false))?.let { d ->
                return hold(now, TacticalDecision(d.intent, d.moveX, d.moveY, d.casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
                    d.reason + " (committed: ${(now - objCommit.since) / 1000} s)"))
            }
        }
        // MARKSMAN POKE (owner decision 2026-11-16, regression scenario marksman_poke_range: "poke instead" of waiting for
        // the minion wave): a marksman hits a lone, hurt enemy hero from range - one enemy around, at 60% HP or less,
        // within poke reach and not under its tower, while the marksman is healthy and safe. It keeps its distance.
        fun marksmanPoke(): TacticalDecision? {
            if (!rulesMode || p.role != Role.MARKSMAN || plan.plan == "retreat" || objectiveOn) return null
            if (hp < maxOf(retreatHp + 0.2f, 0.5f) || surv < 35f) return null   // 35: the same safety bar as finishing a low enemy (2c)
            val around = s.enemiesWithin(0.9f)
            val t = around.singleOrNull()?.takeIf { it in safeTargets && it.hp in 0f..0.6f } ?: return null
            val d = s.distToSelf(t.x, t.y)
            if (d > maxOf(p.engageRange * 1.3f, p.attackRange * 1.45f)) return null
            lastTargetId = t.id
            val casts = ArrayList<String>()
            if (d <= p.attackRange * 1.45f) casts.add("attack")
            for (sk in p.combo) {
                if (sk == "ult") continue
                val skR = maxOf(kit[sk]?.range ?: p.engageRange, p.engageRange)
                if (d <= skR * 1.25f && CooldownTracker.ready(sk, p)) casts.add(sk)
            }
            val (mx, my) = heroSpacing(s, p, t, d)
            return TacticalDecision(Intent.ENGAGE, mx, my, casts, t.x, t.y, "enemy #${t.id}", t.hp, t.id,
                "poking enemy #${t.id} at ${(t.hp * 100).toInt()}% from range (marksman), ${(d * 100).toInt()} away")
        }
        marksmanPoke()?.let { return hold(now, it) }
        for (step in order) step()?.let { return hold(now, it) }

        // ---- 8. follow Gemma's plan, else walk your lane ----
        if (plan.plan == "group" && s.allies.isNotEmpty()) {
            val ax = s.allies.map { it.x }.average().toFloat()
            val ay = s.allies.map { it.y }.average().toFloat()
            val (mx, my) = toward(s, ax, ay)
            return hold(now, TacticalDecision(Intent.GROUP, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "grouping with allies"))
        }
        val (px, py, where) = laneVector(s)
        return hold(now, TacticalDecision(Intent.PUSH, px, py, emptyList(), -1f, -1f, "-", -1f, -1,
            "${s.lane.label} (${roleP.focus}): $where (${plan.plan})"))
    }

    // ---- the ENEMY BASE exposed (your rule): every enemy tower in front of it is down -> go and hit it.
    // The tower rules still apply at the base (its circle: wait for your minions on yellow, never
    // stand in red), so this walks there and the TOWER job hits it once our minions are in.
    private fun attackBase(s: GameState, safe: Boolean, hp: Float): TacticalDecision? {
        val me = s.minimapSelf ?: return null
        val base = s.enemyBase ?: return null
        if (!safe || hp < 0.4f) return null
        // exposed = no enemy tower left between us and it (none closer to the base than 0.35 of the map)
        if (s.towers.any { hypot(it.x - base.first, it.y - base.second) < 0.35f }) return null
        val d = hypot(base.first - me.first, base.second - me.second)
        if (d < rules.towers.range() + 0.02f) {
            // at its range: hit it with minions in (green circle), else wait at the edge for them
            return if (rules.towers.canPush(s) || s.towerRing.zone == com.sphynx.vision.TowerRing.Zone.GREEN)
                TacticalDecision(Intent.PUSH, 0f, 0f, listOf("attack"), -1f, -1f, "tower", -1f, -1, "hitting the exposed enemy base")
            else null
        }
        val (mx, my) = com.sphynx.vision.WallMap.direction(me, base) ?: mapDir(me, base)
        return TacticalDecision(Intent.PUSH, mx, my, emptyList(), -1f, -1f, "-", -1f, -1, "the enemy base is exposed: going to hit it")
    }

    // ---- JUNGLE INVADE (your rule): take an enemy buff or camp when the risk analysis allows it ----
    //  risk: healthy (65%+), level 4+, survival 60+, 2+ skills ready; no enemy seen on the minimap
    //  within 0.25 of the camp, no remembered enemy that could have walked there (fog), at most 2
    //  enemies unaccounted for; the camp within 0.35 of the map. Buffs are worth more; a common
    //  enemy camp only when none of yours is up. At the camp: first hit to show the monsters,
    //  leave after 5 s if nothing is there. Any enemy showing up -> the survival rules take over.
    private var invadeAt: Pair<Float, Float>? = null
    private var invadeArrived = 0L
    private val invadeSkip = HashMap<Pair<Int, Int>, Long>()
    @Volatile var invadeStatus = "-"
        private set

    private fun invade(s: GameState, p: HeroProfile, kit: com.sphynx.game.HeroSkills, surv: Float, safe: Boolean): TacticalDecision? {
        val me = s.minimapSelf ?: return null
        val now = s.ts
        fun cell(x: Float, y: Float) = (x * 12).toInt() to (y * 12).toInt()
        invadeSkip.entries.removeAll { it.value < now }
        if (!safe || s.lane != Lane.JUNGLE || s.selfHp < 0.65f || s.level < 4 || surv < 60f) { invadeAt = null; return null }
        if (listOf("s1", "s2", "ult").count { CooldownTracker.ready(it, p) } < 2) { invadeStatus = "invade: waiting for skills"; return null }
        val seen = s.map.enemies
        val unknown = (5 - seen.size).coerceAtLeast(0)
        if (unknown > 2) { invadeStatus = "invade: $unknown enemies unaccounted for"; invadeAt = null; return null }
        val ownUp = s.camps.any { !it.enemy }
        val pick = s.camps.filter { c ->
            c.enemy && (invadeSkip[cell(c.x, c.y)] ?: 0L) < now && (c.buff || !ownUp) &&
                hypot(c.x - me.first, c.y - me.second) <= 0.35f &&
                seen.none { hypot(it.first - c.x, it.second - c.y) < 0.25f } &&
                com.sphynx.game.GameMemory.fogDanger(c.x to c.y, now) < 0.3f
        }.maxByOrNull { c -> (if (c.buff) 1f else 0.4f) - com.sphynx.vision.WallMap.distance(me, c.x to c.y) * 1.5f }
        val t = pick?.let { it.x to it.y } ?: invadeAt?.takeIf { a ->
            // the camp's icon is hidden under ours when we stand on it
            hypot(a.first - me.first, a.second - me.second) < 0.09f && seen.none { hypot(it.first - a.first, it.second - a.second) < 0.25f }
        } ?: run { invadeAt = null; return null }
        if (invadeAt == null || hypot(t.first - invadeAt!!.first, t.second - invadeAt!!.second) > 0.05f) { invadeAt = t; invadeArrived = 0L }
        val d = hypot(t.first - me.first, t.second - me.second)
        if (d < 0.06f) {
            if (invadeArrived == 0L) invadeArrived = now
            if (now - invadeArrived > 5000) {         // nothing here (or killed): leave
                invadeSkip[cell(t.first, t.second)] = now + 90_000; invadeAt = null
                invadeStatus = "invade: done here"; return null
            }
            invadeStatus = "invade: at the enemy camp"
            // the monsters' bars show only once hit (the MONSTERS job takes over then)
            return TacticalDecision(Intent.JUNGLE, 0f, 0f, listOf("attack"), -1f, -1f, "camp", -1f, -1,
                "invading: first hit at the enemy camp")
        }
        invadeStatus = "invade: walking to an enemy ${if (pick?.buff == true) "buff" else "camp"} (low risk)"
        val (mx, my) = com.sphynx.vision.WallMap.direction(me, t) ?: mapDir(me, t)
        return TacticalDecision(Intent.JUNGLE, mx, my, emptyList(), -1f, -1f, "-", -1f, -1,
            "invading the enemy jungle: ${seen.size} enemies seen elsewhere, survival ${surv.toInt()}")
    }

    // Walking the jungle path, or standing at a camp: attack (MLBB's attack button locks on to
    // the nearest monster) and use skills until the camp's icon is gone from the minimap.
    private fun jungleStep(s: GameState, p: HeroProfile, nav: Triple<Float, Float, String>): TacticalDecision {
        if (!rules.jungle.atCamp) {
            return TacticalDecision(Intent.JUNGLE, nav.first, nav.second, emptyList(), -1f, -1f, "-", -1f, -1, "jungle path: ${nav.third}")
        }
        val activeCont = rules.channelling(s)
        val casts = ArrayList<String>()
        if (activeCont == null || activeCont.allowBasicAttack) casts.add("attack")
        if (activeCont == null) {
            val ultToo = CooldownTracker.lowCdUlt(s.ownHero)
            p.waveClearSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            for (sk in p.combo) if ((sk != "ult" || ultToo) && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
        }
        val m = s.creeps.minByOrNull { s.distToSelf(it.x, it.y) }
        if (rules.isSmite(s.spell) && rules.jungle.atBuff && rules.jungle.atCampMs > 5000L && CooldownTracker.ready("spell", p)) casts.add("spell")
        if (m != null) {
            // issue #2: inside your own attack range, never out of the monster's aggro zone
            val (mx, my) = monsterStep(s, p, m.x, m.y)
            return TacticalDecision(Intent.JUNGLE, mx, my, casts, m.x, m.y, "monster", m.hp, -1, rules.jungle.status)
        }
        // Full-HP creep / buff (no HP bar yet): ALWAYS start with a Basic Attack first for the opening 320 ms before casting skills! (v37.14)
        val startCasts = if (rules.jungle.atCampMs > 320L) casts else arrayListOf("attack")
        val self = s.self
        val pitDir = rules.scenery.pitAimDirection(s)
        val aimX = if (self != null && pitDir != null) (self.x + pitDir.first * 0.16f / s.aspect).coerceIn(0.1f, 0.9f) else -1f
        val aimY = if (self != null && pitDir != null) (self.y + pitDir.second * 0.16f).coerceIn(0.1f, 0.9f) else -1f
        return TacticalDecision(Intent.JUNGLE, nav.first, nav.second, startCasts, aimX, aimY, "camp", -1f, -1,
            rules.jungle.status + ", hitting camp")
    }

    private fun dashSlot(s: GameState, p: HeroProfile): String? {
        if (!Cfg.useDash) return null
        val slot = rules.dashSlot(s.ownHero)?.takeIf { it != "ult" } ?: return null
        return if (CooldownTracker.ready(slot, p)) slot else null
    }

    // dash to close the gap when the target is a bit out of reach
    private fun chaseDash(s: GameState, p: HeroProfile, d: Float): String? =
        if (d > p.attackRange * 1.5f && d <= p.engageRange * 1.4f) dashSlot(s, p) else null

    // Attack a jungle monster / turtle / lord; Retribution (if you have it) to finish it
    // the monster last fought: its bar at the last 1-3% can flicker out of sight
    private var lastMonX = -1f; private var lastMonY = -1f; private var lastMonHp = 1f; private var lastMonAt = 0L
    private var lastMonSpecies = ""; private var lastMonBoss = false
    private val objCommit = ObjectiveCommit()
    private val hitWatch = HitWatch()

    // allies near the Lord (on the screen near it, or on the minimap around you) and whether that is enough
    // (BossRule); boss = null: the Lord is out of view, allies anywhere on the screen count
    private fun lordAllies(s: GameState, boss: Seen?): Int {
        val onScreen = if (boss == null) s.allies.size else s.allies.count { s.dist(it.x, it.y, boss.x, boss.y) < 0.5f }
        val me = s.minimapSelf
        val onMap = if (me == null) 0 else s.map.allies.count { kotlin.math.hypot(it.first - me.first, it.second - me.second) < 0.12f }
        return rules.alliesNearBoss(onScreen, onMap)
    }
    private fun lordOk(s: GameState, boss: Seen?): Boolean =
        boss != null && boss.species != rules.teamBossId || rules.mayTakeTeamBoss(lordAllies(s, boss))
    private var monComboStartedAt = 0L
    // Retribution is kept for the Purple / Orange Buffs, the Turtle and the Lord (and hero fights)
    private fun retriWorthy(species: String, boss: Boolean) = boss || rules.isBuffCamp(species) ||
        species.isEmpty() && rules.jungle.atBuff

    private fun hitMonster(s: GameState, p: HeroProfile, m: Seen, retriPct: Int, intent: Intent, why: String): TacticalDecision {
        if (s.ts - lastMonAt > 1600L || s.dist(m.x, m.y, lastMonX, lastMonY) > 0.22f) {
            monComboStartedAt = s.ts
        }
        lastMonX = m.x; lastMonY = m.y; lastMonHp = m.hp; lastMonAt = s.ts; lastMonSpecies = m.species; lastMonBoss = m.hero
        val d = s.distToSelf(m.x, m.y)
        // press attack / skills from this far (MLBB's attack button walks you in from there)
        val stopDist = maxOf(p.attackRange * 1.85f, if (p.ranged) 0.42f else 0.34f)
        val activeCont = rules.channelling(s)
        val casts = ArrayList<String>()
        // Always start attacking jungle monsters and buffs with a Basic Attack first (first 260 ms), then chain skills! (v37.14)
        // If a continuous-damage skill that disallows basic attacks (e.g. Balmond s2) is currently active on badge/icon, do not interrupt it! (v37.15)
        if ((activeCont == null || activeCont.allowBasicAttack) && d <= maxOf(stopDist * 1.35f, 0.48f)) casts.add("attack")
        val openingBasicDone = s.ts - monComboStartedAt >= 260L && m.hp < 0.995f
        if (activeCont == null && openingBasicDone && d <= maxOf(stopDist * 1.25f, 0.42f)) {
            val ultToo = CooldownTracker.lowCdUlt(s.ownHero)
            p.waveClearSkill?.let { if (CooldownTracker.ready(it, p)) casts.add(it) }
            for (sk in p.combo) if ((sk != "ult" || ultToo) && sk !in casts && CooldownTracker.ready(sk, p)) casts.add(sk)
        }
        val mhp = if (m.hero && s.bossHp >= 0f) s.bossHp else m.hp
        if (rules.isSmite(s.spell) && retriWorthy(m.species, m.hero) && mhp * 100 <= retriPct && d <= 0.45f && CooldownTracker.ready("spell", p)) casts.add("spell")
        // Issue #2: walk in until the monster is inside YOUR attack range (CombatRange), then stand still.
        // (It used to stop at about twice a melee hero's range and rely on the attack button.)
        val (mx, my) = monsterStep(s, p, m.x, m.y)
        return TacticalDecision(intent, mx, my, casts, m.x, m.y, if (m.hero) "objective" else "monster", mhp, -1, why)
    }

    // A monster fights back only inside the round indicator on the ground around it (seen as a
    // wide ellipse because of the camera tilt); step out and it stops and heals. Measured on
    // your screenshots: about 0.6 screen heights across and 0.27 up / down. Its numbers live in
    // universal.CombatRange.Leash (issue #2).
    // Issue #2: while a monster you are fighting is alive, a jungle / objective move may not take you
    // out of its aggro zone (it would walk home and heal). Retreat, recall and fights with enemy heroes
    // are never held back: survival comes first.
    private fun leashGuard(s: GameState, d: TacticalDecision): TacticalDecision {
        if (d.intent != Intent.JUNGLE && d.intent != Intent.OBJECTIVE) return d
        if (d.moveX == 0f && d.moveY == 0f) return d
        val self = s.self ?: return d
        val onMonster = d.targetX >= 0f && (d.targetLabel == "monster" || d.targetLabel == "objective")
        val alive = !(lastMonHp <= 0.18f && s.ts - lastMonAt > 1100)
        val (mx, my) = when {
            onMonster -> d.targetX to d.targetY
            lastMonAt > 0 && s.ts - lastMonAt in 0..2500 && alive -> lastMonX to lastMonY
            else -> return d
        }
        val (kx, ky) = com.sphynx.universal.CombatRange.keepInLeash((mx - self.x) * s.aspect, my - self.y, d.moveX, d.moveY)
        if (kx == d.moveX && ky == d.moveY) return d
        return TacticalDecision(d.intent, 0f, 0f, d.casts, d.targetX, d.targetY, d.targetLabel, d.targetHp, d.targetId,
            d.reason + " (held: would leave the monster's aggro zone)")
    }

    // Issue #2: the step toward a monster at (x, y) - approach until it is inside your own attack
    // range, then stand; and never a step that leaves the monster's aggro zone.
    private val monsterContact = com.sphynx.universal.CombatRange.ContactDetector()

    private fun monsterStep(s: GameState, p: HeroProfile, x: Float, y: Float): Pair<Float, Float> {
        val self = s.self ?: return 0f to 0f
        val dx = (x - self.x) * s.aspect; val dy = y - self.y
        val st = com.sphynx.universal.CombatRange.approach(dx, dy, p.attackRange)
        // in range, or pressed against a big monster's body: stand still so the basic attacks go out
        if (monsterContact.inContact(s.ts, kotlin.math.hypot(dx, dy), !st.inRange) || st.inRange) return 0f to 0f
        val (kx, ky) = com.sphynx.universal.CombatRange.keepInLeash(dx, dy, st.moveX, st.moveY)
        // the same frame as toward(): x already multiplied by the aspect ratio
        return if (kx == 0f && ky == 0f) 0f to 0f else norm(kx, ky)
    }

    // Fighting an enemy HERO: melee walks up close and stays there; ranged keeps a safe distance
    // at the edge of its attack range, steps back if the enemy gets too close (kiting), and
    // steps in if it is out of range.
    // Continuous-spin heroes (Balmond s2, Akai ult, X.Borg ult, Khaleed s1) and Yu Zhong Black Dragon
    // steer directly onto the target (down to d <= 0.04f) so their AoE spin stays glued to the enemy! (v37.15)
    private fun heroSpacing(s: GameState, p: HeroProfile, t: Track, d: Float): Pair<Float, Float> {
        val cont = rules.channelling(s)
        if (cont != null && !cont.allowMove) return 0f to 0f
        if (rules.steeringTransform(s) || (cont != null && cont.allowMove && !cont.allowBasicAttack)) {
            return if (d > 0.04f) toward(s, t.x, t.y) else (0f to 0f)
        }
        val rangedNow = rules.rangedNow(s.ownHero) ?: p.ranged
        if (!rangedNow) return if (d > p.attackRange * 0.8f) toward(s, t.x, t.y) else (0f to 0f)
        val effRange = (if (rangedNow) rules.rangedReach(s.ownHero) else null) ?: p.attackRange
        return when {
            d < effRange * 0.30f -> away(s, listOf(t))
            d > effRange -> toward(s, t.x, t.y)
            else -> 0f to 0f
        }
    }

    // the lane corridors, the roam patrol and the outer towers are map data: the game's rules
    // (MLBB: MlbbTacticalRules.laneCorridor; the corridors are drawn for "EXP lane on top" and mirrored there)
    private fun corridorOf(lane: Lane): List<Pair<Float, Float>> = rules.laneCorridor(lane)

    // Lane route from base: EXP = up left corridor then right, Gold = right bottom corridor then up, Mid = diagonal corridor.
    private fun laneVector(s: GameState): Triple<Float, Float, String> {
        val travelling = s.sinceSpawnMs < Cfg.laneTravelSec * 1000L
        fun t(v: Pair<Float, Float>, what: String) = Triple(v.first, v.second, what)
        return when (s.lane) {
            Lane.EXP, Lane.GOLD, Lane.MID -> laneByMap(s) ?: when (rules.mapLane(s.lane)) {
                // the top lane / the bottom lane (which of EXP / Gold is on top: MapSide)
                Lane.EXP -> if (travelling) t(0f to -1f, "walking up the ${s.lane.label} corridor (top lane)") else t(1f to 0f, "advancing along the ${s.lane.label} (top lane)")
                Lane.GOLD -> if (travelling) t(1f to 0f, "walking right along the ${s.lane.label} corridor (bottom lane)") else t(0f to -1f, "advancing along the ${s.lane.label} (bottom lane)")
                else -> t(pushVector(), "walking Mid lane (${Cfg.pushDir})")
            }
            Lane.JUNGLE -> {
                rules.jungle.direction(s, 9f, allowBuffs = true)?.let { return Triple(it.first, it.second, it.third) }
                t(norm(1f, -1f), "heading into the jungle")
            }
            Lane.ROAM -> {
                val me = s.minimapSelf
                if (me != null) {
                    val pt = rules.roamPatrol[(s.ts / 7000L).toInt().mod(rules.roamPatrol.size)]
                    val (mx, my) = mapDir(me, pt)
                    return t(mx to my, "roaming river & lane vision")
                }
                t(norm(1f, -1f), "heading to mid river")
            }
        }
    }

    // Multi-waypoint corridor pathing for EXP Laner, Gold Laner, and Mid Laner (v37.13):
    // Walks step-by-step along the lane's safe perimeter/diagonal corridor without cutting through the
    // jungle, stops safely outside enemy tower range until friendly minions arrive, and holds steady at
    // the clash point when waiting for the wave.
    private fun laneByMap(s: GameState): Triple<Float, Float, String>? {
        val me = s.minimapSelf ?: return null
        val corridor = corridorOf(s.lane)
        val curIdx = corridor.indices.minByOrNull { i ->
            kotlin.math.hypot(corridor[i].first - me.first, corridor[i].second - me.second)
        } ?: 0

        // Determine the enemy frontline tower in this lane (with safe early/mid fallback if the minimap tower icon is covered)
        val detectedTower = rules.towers.laneTarget(s, s.lane)
        val defaultOuterIdx = rules.outerTowerIndex(s.lane)   // the enemy outer tower's waypoint on the corridor
        val frontPos = detectedTower?.pos ?: if (s.matchSec in 0..480) corridor[defaultOuterIdx] else (s.enemyBase ?: corridor.last())
        val frontIdx = corridor.indices.minByOrNull { i ->
            kotlin.math.hypot(corridor[i].first - frontPos.first, corridor[i].second - frontPos.second)
        } ?: corridor.lastIndex

        val distToFront = kotlin.math.hypot(frontPos.first - me.first, frontPos.second - me.second)
        val safeStopDist = rules.towers.range() + 0.014f
        val what = if (detectedTower?.bonus == true) "plated tower" else if (s.towers.isEmpty()) "enemy base" else "${s.lane.label} tower"

        // Already at the enemy tower frontline without minion cover: step back to the safe clash waypoint or hold
        if (distToFront < safeStopDist && !rules.towers.canPush(s)) {
            val safeIdx = (frontIdx - 1).coerceAtLeast(0)
            val safeSpot = corridor[safeIdx]
            if (kotlin.math.hypot(safeSpot.first - me.first, safeSpot.second - me.second) < 0.035f && distToFront >= rules.towers.range() + 0.01f) {
                return Triple(0f, 0f, "holding ${s.lane.label} wave position outside $what range")
            }
            val (bx, by) = mapDir(me, safeSpot)
            return Triple(bx, by, "positioning outside $what range for next minion wave")
        }

        // Advance along the lane corridor one waypoint at a time (never skipping across the jungle!)
        val maxAllowedIdx = if (rules.towers.canPush(s)) frontIdx else (frontIdx - 1).coerceAtLeast(0)
        val nextIdx = (if (kotlin.math.hypot(corridor[curIdx].first - me.first, corridor[curIdx].second - me.second) < 0.055f) curIdx + 1 else curIdx)
            .coerceAtMost(maxAllowedIdx)
        val goal = if (rules.towers.canPush(s) && curIdx >= maxAllowedIdx) frontPos else corridor[nextIdx]
        if (kotlin.math.hypot(goal.first - me.first, goal.second - me.second) < 0.03f && !rules.towers.canPush(s)) {
            return Triple(0f, 0f, "at ${s.lane.label} clash point: waiting for minion wave")
        }
        val (mx, my) = mapDir(me, goal)
        return Triple(mx, my, "walking ${s.lane.label} corridor (${nextIdx + 1}/${corridor.size}) toward $what")
    }

    // walk on the minimap from `me` to `to`, around walls when the wall map knows the way
    // A thin wall straight ahead with a long way round: go straight and jump it (see wallHop).
    private fun mapDir(me: Pair<Float, Float>, to: Pair<Float, Float>): Pair<Float, Float> {
        val straight = norm(to.first - me.first, to.second - me.second)
        val sd = kotlin.math.hypot(to.first - me.first, to.second - me.second)
        val wm = com.sphynx.vision.WallMap
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
        // to the nearest allied tower (around walls), unless that way runs into the enemies
        val (tx, ty) = com.sphynx.vision.WallMap.direction(me, home) ?: mapDir(me, home)
        if (from.isEmpty()) return tx to ty
        if (tx * ax + ty * ay < -0.2f) return ax to ay
        return norm(ax * 0.25f + tx * 0.75f, ay * 0.25f + ty * 0.75f)
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
                // ranged heroes poke with everything; a melee hero only with its RANGED skills, from
                // that skill's range (it does not walk into basic-attack range)
                if (!p.ranged) {
                    val pk = SkillBook.get(s.ownHero).pokeSkills
                    if (pk.isEmpty()) return null
                    val reach = pk.maxOf { it.range }
                    val casts = pk.filter { CooldownTracker.ready(it.slot, p) && d <= it.range * 1.1f }.map { it.slot }
                    val (mx, my) = when {
                        d < reach * 0.7f -> away(s, listOf(t))
                        d > reach -> toward(s, t.x, t.y)
                        else -> 0f to 0f
                    }
                    return TacticalDecision(Intent.ENGAGE, mx, my, casts, t.x, t.y, "enemy #${t.id}", t.hp, t.id,
                        "$tag with ${pk.joinToString { it.slot }}, ${(d * 100).toInt()} away")
                }
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
        if (enemies <= 0 || d > maxOf(p.engageRange * 1.08f, 0.48f)) return false
        if (!CooldownTracker.ready("ult", p) && !rules.canRecast(s.ownHero, "ult")) return false
        if (rules.multiPhaseUlt(s.ownHero) && d <= p.engageRange * 1.05f) return true
        return when (p.ult) {
            UltRule.EXECUTE -> t.hp <= rules.executeUltBelow(s.ownHero, s.level) && d <= p.engageRange * 1.08f
            UltRule.ENGAGE -> d <= p.engageRange && hp > 0.45f && enemies <= allies + 1
            UltRule.TEAMFIGHT -> (enemies >= 2 || t.hp < 0.48f) && d <= p.engageRange
            UltRule.DEFENSIVE -> hp < 0.35f && d <= p.engageRange
            UltRule.BUFF -> d <= maxOf(p.attackRange * 1.45f, 0.36f) && hp > 0.3f
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
    // Nothing to decide (your HP bar hidden for a moment): never stand still - walk towards the
    // enemy base (around walls, by the minimap), else the push direction, else towards the middle
    fun fallbackMove(s: GameState): TacticalDecision {
        val me = s.minimapSelf
        val goal = (if (me != null) s.towers.minByOrNull { hypot(it.x - me.first, it.y - me.second) } else null)?.let { it.x to it.y } ?: s.enemyBase
        val dir = if (me != null && goal != null) com.sphynx.vision.WallMap.direction(me, goal) ?: mapDir(me, goal)
            else pushVector().takeIf { it.first != 0f || it.second != 0f } ?: me?.let { norm(0.5f - it.first, 0.5f - it.second) } ?: norm(1f, -1f)
        return TacticalDecision(Intent.PUSH, dir.first, dir.second, emptyList(), -1f, -1f, "-", -1f, -1, "keep moving (nothing seen this moment)")
    }

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
            if (!force && now - heldSince in 0..(if (Cfg.fastMode) 300 else 600) && held != Intent.IDLE && held != Intent.PUSH) {
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
