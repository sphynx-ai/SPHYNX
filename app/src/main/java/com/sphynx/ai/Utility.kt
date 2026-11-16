package com.sphynx.ai

import com.sphynx.game.CooldownTracker
import com.sphynx.game.GameState
import com.sphynx.game.GameStateTracker
import com.sphynx.game.HeroProfile
import com.sphynx.game.HeroSkills
import com.sphynx.game.Passive
import com.sphynx.game.Role
import com.sphynx.vision.Track

// Scores instead of fixed thresholds. A good player does not ask "can I use my skill?" but
// "can I win this, can I get out if it goes wrong, is there something better to do?".
//
//  survival (0..100): how safe you are right now. HP is only one part: a tank at 20% can be
//    safer than a marksman at 40%. Ready escape / shield / heal, allies, enemies (and how
//    much damage they can still deal), enemies missing from the minimap, enemy tower.
//  engagement (any number, 50+ = fight, 25-49 = look for a better moment, below 25 = no):
//    low-HP target, isolated target, allies, ready ultimate / crowd control, level edge,
//    minus own low HP, extra enemies, no escape, tower, and UNCERTAIN vision.
//  target: not simply the nearest - exposed, low HP, isolated, the one threatening allies.
//  fight state: FARMING / POSITIONING / ENGAGING / RETREATING / OBJECTIVE with hysteresis
//    (it needs a clearly better score to switch), so it does not change its mind every tick.
object Utility {

    enum class State { FARMING, POSITIONING, ENGAGING, RETREATING, OBJECTIVE }

    @Volatile var state = State.FARMING
        private set
    @Volatile private var since = 0L
    @Volatile var lastSurvival = 100f
        private set
    @Volatile var lastEngage = 0f
        private set
    @Volatile var lastWhy = "-"
        private set

    private fun ready(slot: String, p: HeroProfile) = CooldownTracker.ready(slot, p)

    fun survival(s: GameState, p: HeroProfile, k: HeroSkills = TacticalEngine.rules.skills(s.ownHero)): Float {
        val hp = s.selfHp.coerceAtLeast(0f)
        var v = hp * 70f
        v += when (TacticalEngine.rules.role(s.ownHero)) { Role.TANK -> 18f; Role.FIGHTER -> 9f; Role.SUPPORT -> 3f; Role.MARKSMAN, Role.MAGE -> -6f; else -> 0f }
        k.escape?.let { if (ready(it.slot, p)) v += 12f }
        if (k.skills.values.any { (it.shield || it.heal) && ready(it.slot, p) }) v += 8f
        if (k.has(Passive.SHIELD)) v += 4f
        if (TacticalEngine.rules.isBlink(s.spell) && ready("spell", p)) v += 6f
        v += s.alliesWithin(0.5f).size * 8f
        val near = s.enemiesWithin(0.6f)
        // what the enemies can still do: a nearly dead enemy is much less of a threat
        v -= near.sumOf { (6.0 + 12.0 * it.hp) }.toFloat()
        // enemies missing from the minimap may be waiting nearby (after the first minute)
        val onMap = if (s.map.fromDetector) s.map.enemies.size else s.minimapEnemies
        // (dead enemies are not hiding anywhere - the red respawn countdowns at the top, v36.86)
        val deadE = com.sphynx.vision.DeathTimers.enemiesDeadNow()
        if (s.matchSec > 60) v -= (5 - deadE - onMap.coerceIn(0, 5) - near.size).coerceAtLeast(0) * 2.5f
        // fewer enemies alive = less danger; fewer allies alive = less help
        v += deadE * 4f - com.sphynx.vision.DeathTimers.alliesDeadNow() * 3f
        // enemies close on the minimap but not on screen: in a bush or the fog, about to arrive
        val hidden = (com.sphynx.game.MapIntel.enemiesNear(s, 0.15f) - s.enemies.size).coerceAtLeast(0)
        v -= hidden * 8f
        // remembered enemies that left the map and could be right here by now
        v -= com.sphynx.game.GameMemory.fogDanger(s.minimapSelf, s.ts) * 6f
        // they are coming at you (a gank)
        if (com.sphynx.game.MapIntel.gank(s)) v -= 12f
        val me = s.minimapSelf
        if (me != null && s.towers.any { kotlin.math.hypot(it.x - me.first, it.y - me.second) < TacticalEngine.rules.towers.range() } && s.allyMinions.isEmpty()) v -= 20f
        if (k.has(Passive.LOW_HP_POWER) && hp < 0.5f) v += 6f
        // Transformation Bar & Buff Ultimate Badge survival boosts (v37.15):
        //  - Argus Eternal Evil (4s death immunity on badge/icon): cannot die, stays in fight at full aggression
        //  - Thamuz Cauterant Inferno (transformation bar + badge): massive HP regen per hit
        //  - Yu Zhong Black Dragon / Dragonoid, Leomord Phantom Steed, Lukas Sacred Beast, etc.
        v = TacticalEngine.rules.survivalWithHeroState(s, v)
        return v.coerceIn(0f, 100f)
    }

    // how sure the eyes are about this enemy (seen for a while, steady HP)
    fun confidence(t: Track, now: Long): Float {
        val age = (now - t.firstSeen).coerceAtLeast(0L)
        val fresh = now - t.lastSeen < 250
        return ((if (age > 800) 1f else age / 800f) * (if (fresh) 1f else 0.6f)).coerceIn(0.2f, 1f)
    }

    fun engagement(s: GameState, p: HeroProfile, t: Track, k: HeroSkills = TacticalEngine.rules.skills(s.ownHero)): Float {
        val hp = s.selfHp.coerceAtLeast(0f)
        var v = 0f
        val why = ArrayList<String>()
        fun add(x: Float, w: String) { if (x != 0f) { v += x; why.add((if (x > 0) "+" else "") + x.toInt() + " " + w) } }
        add((1f - t.hp) * 35f, "target HP ${(t.hp * 100).toInt()}%")
        if (com.sphynx.game.EnemyIntel.dashedRecently(t, s.ts)) add(12f, "it just used its dash (cannot escape now)")
        // can the ready skills finish it?
        val burst = k.burst { ready(it, p) } + 0.08f
        if (t.hp <= burst) add(15f, "killable with ready skills")
        val others = s.enemies.count { it.id != t.id && s.dist(it.x, it.y, t.x, t.y) < 0.4f }
        if (others == 0) add(20f, "isolated")
        add(minOf(s.alliesWithin(0.5f).size, 2) * 15f, "allies near")
        if (ready("ult", p)) add(15f, "ultimate ready")
        if (k.cc.any { ready(it.slot, p) }) add(8f, "crowd control ready")
        val argusImmortal = TacticalEngine.rules.deathImmune(s)
        if (hp < 0.3f && !argusImmortal) add(-30f * (0.3f - hp) / 0.3f - 10f, "own HP low")
        TacticalEngine.rules.engageHeroState(s)?.let { (bonus, why) -> add(bonus, why) }
        // more enemies close on the minimap than on screen = hidden ones joining the fight
        val hiddenNear = (com.sphynx.game.MapIntel.enemiesNear(s, 0.15f) - s.enemies.size).coerceAtLeast(0)
        if (hiddenNear > 0) add(-15f * hiddenNear, "$hiddenNear enemy hidden nearby (minimap)")
        if (com.sphynx.game.MapIntel.gank(s)) add(-15f, "enemy coming on the minimap")
        // enemies dead (respawn countdowns): nobody of theirs can come to help; allies dead: less help
        val deadE = com.sphynx.vision.DeathTimers.enemiesDeadNow()
        val deadA = com.sphynx.vision.DeathTimers.alliesDeadNow()
        if (deadE > 0) add(6f * deadE, "$deadE enemy dead")
        if (deadA > deadE) add(-6f * (deadA - deadE), "${deadA - deadE} more allies dead than enemies")
        val extra = s.enemiesWithin(0.6f).size - 1
        if (extra > 0) add(-25f * extra, "$extra more enemies")
        val esc = k.escape
        if (TacticalEngine.rules.role(s.ownHero) != Role.TANK && (esc == null || !ready(esc.slot, p))) add(-5f, "no escape ready")
        // under its tower the tower shoots YOU first (even with our minions there)
        val ring = s.towerRing
        val inRing = ring.zone != com.sphynx.vision.TowerRing.Zone.NONE &&
            ((t.x - ring.cx) / ring.rx).let { it * it } + ((t.y - ring.cy) / ring.ry).let { it * it } < 1f
        if (inRing || TacticalEngine.rules.towers.enemyUnderTower(s, t)) add(-30f, "under their tower (it shoots me first)")
        // tank roamers start fights for the team; supports do not fight alone (they protect)
        val myRole = TacticalEngine.rules.role(s.ownHero)
        // (the wiki's roamer kinds: setters / initiators start fights, utility roamers protect)
        val engager = myRole == Role.TANK || TacticalEngine.rules.engager(s.ownHero)
        val protector = myRole == Role.SUPPORT || TacticalEngine.rules.protector(s.ownHero)
        if (engager && s.alliesWithin(0.5f).isNotEmpty()) add(15f, "starting the fight for the team")
        if (protector && !engager && s.alliesWithin(0.5f).isEmpty()) add(-20f, "protector: not without allies")
        // late-game carries avoid even fights early; early-game heroes press before their power fades
        if (TacticalEngine.rules.lateGame(s.ownHero) && s.matchSec in 0 until 600) add(-8f, "late-game hero: patience early")
        val needPurple = TacticalEngine.rules.needsBuffFirst(s.ownHero)
        if (needPurple && s.matchSec in 0 until 120) add(-12f, "weak until the Purple Buff")
        else if (TacticalEngine.rules.earlyGame(s.ownHero) && s.matchSec in 0 until 480) add(6f, "strong early")
        if (s.enemyLevel > 0) add((s.level - s.enemyLevel).coerceIn(-3, 3) * 5f, "level difference")
        else if (s.level < 4) add(-10f, "no ultimate yet")
        if (k.has(Passive.LOW_HP_POWER) && hp < 0.5f) add(10f, "passive: stronger when hurt")
        if (k.has(Passive.EXECUTE_BONUS) && t.hp < 0.5f) add(8f, "passive: bonus vs low HP")
        if (k.has(Passive.BUSH_BONUS) && s.inBush) add(8f, "passive: from a bush")
        // comeback: behind in kills / deaths, take only clear fights; ahead, press
        val kd = GameStateTracker.kills + GameStateTracker.assists / 2 - GameStateTracker.deaths
        add(kd.coerceIn(-4, 4) * 3f, "K/D trend")
        // vision not sure about this enemy: no risky decision on it
        val c = confidence(t, s.ts)
        if (c < 0.6f) add(-(0.6f - c) * 15f, "new sighting")
        lastWhy = why.joinToString(", ")
        return v
    }

    // the best enemy to fight for this hero's role (not just the nearest)
    fun bestTarget(s: GameState, p: HeroProfile, near: List<Track>, current: Int): Track? {
        if (near.isEmpty()) return null
        val role = TacticalEngine.rules.role(s.ownHero)
        val k = TacticalEngine.rules.skills(s.ownHero)
        val self = s.self ?: return near.minByOrNull { it.hp }
        // enemies' middle: an enemy far from it (towards us) is exposed
        val cx = s.enemies.map { it.x }.average().toFloat()
        val cy = s.enemies.map { it.y }.average().toFloat()
        return near.maxByOrNull { t ->
            val d = s.distToSelf(t.x, t.y)
            var sc = (1f - t.hp) * 40f - d * 40f
            val exposed = s.dist(t.x, t.y, cx, cy)
            sc += exposed * 30f
            // the one hitting our allies (closest to them)
            val toAlly = s.allies.minOfOrNull { s.dist(it.x, it.y, t.x, t.y) } ?: 9f
            when (role) {
                Role.TANK, Role.SUPPORT -> if (toAlly < 0.2f) sc += 25f        // peel for allies
                Role.ASSASSIN -> sc += exposed * 30f + (if (t.hp < 0.5f) 15f else 0f)
                Role.MARKSMAN, Role.MAGE -> sc -= maxOf(0f, p.attackRange - d) * 30f   // the one we can hit from range
                Role.FIGHTER -> sc += if (s.enemies.count { s.dist(it.x, it.y, t.x, t.y) < 0.4f } <= 1) 15f else 0f
                else -> {}
            }
            // passive builds stacks on one target: stay on it
            if (t.id == current) sc += if (k.has(Passive.STACKS)) 25f else 10f
            if (TacticalEngine.rules.towers.enemyUnderTower(s, t)) sc -= 30f
            sc
        }
    }

    // ---- fight state with hysteresis ----
    // returns the state to play; switching needs a clear reason, and RETREATING beats all
    fun update(s: GameState, surv: Float, engage: Float, objective: Boolean, threat: Boolean): State {
        val now = s.ts
        lastSurvival = surv; lastEngage = engage
        val want = when {
            threat && surv < 26f -> State.RETREATING
            state == State.RETREATING && threat && surv < 42f -> State.RETREATING     // until clearly safe
            engage >= 34f || state == State.ENGAGING && engage >= 20f && now - since < 8000 -> State.ENGAGING
            objective -> State.OBJECTIVE
            threat && (engage >= 18f || surv < 55f) -> State.POSITIONING
            else -> State.FARMING
        }
        // Engaging and retreating take effect immediately so skills never stall waiting 500ms
        if (want != state && (want == State.ENGAGING || want == State.RETREATING || now - since > 350 || now < since ||
                GameStateTracker.hurtRecently(now))) { state = want; since = now }
        return state
    }
}
