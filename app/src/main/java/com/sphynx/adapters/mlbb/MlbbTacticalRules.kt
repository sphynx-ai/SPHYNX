package com.sphynx.adapters.mlbb

import com.sphynx.ai.TacticalRules
import com.sphynx.game.GameState
import com.sphynx.game.HeroDb
import com.sphynx.game.HeroKit

// Mobile Legends' tactical rules for TacticalEngine (Phase 2 adapter split, step 4): the same calls the engine
// made before, gathered here so the engine itself names no MLBB hero. No behaviour change
// (TacticalEngineGoldenTest).
object MlbbTacticalRules : TacticalRules {

    override fun channelling(s: GameState): TacticalRules.Channel? =
        HeroKit.activeContinuousSkill(s)?.let { TacticalRules.Channel(it.allowBasicAttack, it.allowMove, it.executeUltHp) }

    // Yu Zhong's Black Dragon flight
    override fun steeringTransform(s: GameState) = HeroKit.isYuZhongBlackDragonFlight(s)
    override val steeringTransformName = "Yu Zhong Black Dragon"

    override fun buffUltActive(s: GameState) = HeroKit.isBuffUltActive(s)

    // Argus's Eternal Evil: no death for 4 s while the ultimate badge is up
    override fun deathImmune(s: GameState) = s.ownHero == "Argus" && HeroKit.isBuffUltActive(s)

    override fun retreatAdjust(s: GameState) = HeroKit.retreatAdjust(s.ownHero, s.selfExtra)

    // the buff ultimates fight on longer (Argus much longer, Thamuz a little)
    override fun buffUltRetreatShift(s: GameState): Float = when {
        s.ownHero == "Argus" && HeroKit.isBuffUltActive(s) -> -0.50f
        s.ownHero == "Thamuz" && HeroKit.isBuffUltActive(s) -> -0.10f
        HeroKit.isBuffUltActive(s) -> -0.06f
        else -> 0f
    }

    override fun executeUltBelow(hero: String?, level: Int) = HeroKit.executeUltThreshold(hero, level)
    override fun skillSlots(hero: String?) = HeroKit.slots(hero)
    override val allSkillSlots: List<String> get() = HeroKit.ALL
    override fun rangedNow(hero: String?) = HeroKit.rangedNow(hero)
    // Edith in her ranged form keeps a longer reach than her profile's
    override fun rangedReach(hero: String?): Float? = if (hero == "Edith") 0.42f else null
    override fun canRecast(hero: String?, slot: String) = HeroKit.canRecastNow(hero, slot)
    override fun multiPhaseUlt(hero: String?) = hero in HeroKit.MULTI_PHASE_HEROES
    override fun dashSlot(hero: String?) = HeroDb.dashSlot(hero)
    override fun role(hero: String?) = HeroDb.role(hero)

    // ---- the battle spells ----
    override fun isSmite(spell: String?) = spell == "Retribution"
    override fun isBlink(spell: String?) = spell == "Flicker"
    override fun isExecute(spell: String?) = spell == "Execute"
    override val executeSpellBelow: Float get() = com.sphynx.game.SpellInfo.EXECUTE_BELOW
    override fun isNuke(spell: String?) = spell == "Flameshot"
    override fun smiteBossPct() = com.sphynx.core.Cfg.retriBossPct
    override fun smiteCreepPct() = com.sphynx.core.Cfg.retriCreepPct

    // THE BATTLE SPELL, by what it does (wiki descriptions) - not only at very low HP. (Moved unchanged from
    // TacticalEngine.spellCasts.) Retribution on a hero: in a close fight that is going on (slow / burn / drain =
    // the edge), only when no buff, Turtle or Lord is on screen to save it for.
    override fun useSpellInFight(spell: String, s: GameState, p: com.sphynx.game.HeroProfile, fight: Boolean,
                                 running: Boolean, targetDist: Float, enemiesClose: Int, enemiesNear: Int,
                                 hurt: Boolean, targetHp: Float): Boolean {
        val hp = s.selfHp
        val tDist = targetDist; val close = enemiesClose; val near = enemiesNear
        return when (spell) {
            "Execute" -> fight && targetHp in 0f..com.sphynx.game.SpellInfo.EXECUTE_BELOW && tDist <= p.engageRange
            "Flameshot" -> fight && targetHp in 0f..0.3f && tDist <= 0.8f || running && close > 0
            "Petrify" -> (fight && (close >= 2 || close >= 1 && targetHp in 0f..0.6f)) || running && close > 0
            "Inspire" -> fight && tDist <= p.attackRange * 1.3f && targetHp > 0.15f
            "Sprint" -> running && near > 0 || fight && targetHp in 0f..0.2f && tDist > p.attackRange * 1.5f && tDist <= p.engageRange * 1.6f
            "Aegis" -> (fight || running) && near > 0 && hp in 0f..0.45f
            "Revitalize" -> (fight || running) && near > 0 && hp in 0f..0.5f
            "Purify" -> (fight || running) && hurt && close > 0 && hp in 0f..0.45f
            "Vengeance" -> fight && close >= 1 && hurt && hp in 0f..0.75f
            "Retribution" -> fight && tDist <= 0.3f && targetHp in 0f..0.6f && s.boss == null &&
                s.creeps.none { com.sphynx.game.JungleDb.isBuff(it.species) }
            else -> false      // Flicker (chase / escape / walls) and Arrival are handled elsewhere
        }
    }

    // ---- the map: the MLBB jungle path, towers, scenery, turtle / lord, lanes and role paths ----
    override val jungle = object : TacticalRules.JungleRoute {
        private val n = com.sphynx.game.JungleNav
        override val atCamp get() = n.atCamp
        override val atBuff get() = n.atBuff
        override val atCampMs get() = n.atCampMs
        override val status get() = n.status
        override val next get() = n.next
        override fun direction(s: GameState, maxDist: Float, allowBuffs: Boolean) = n.direction(s, maxDist, allowBuffs)
        override fun reset() = n.reset()
    }
    override val towers = object : TacticalRules.Towers {
        private val t = com.sphynx.game.TowerLogic
        override fun range() = t.range()
        override fun effectiveTowers(s: GameState) = t.effectiveTowers(s)
        override fun nearest(s: GameState) = t.nearest(s)
        override fun selfInRange(s: GameState, margin: Float) = t.selfInRange(s, margin)
        override fun canPush(s: GameState) = t.canPush(s)
        override fun enemyUnderTower(s: GameState, e: com.sphynx.vision.Track) = t.enemyUnderTower(s, e)
        override fun guard(s: GameState, mx: Float, my: Float) = t.guard(s, mx, my)
        override fun laneTarget(s: GameState, lane: com.sphynx.game.Lane) = t.laneTarget(s, lane)
        override fun towerToDefend(s: GameState) = t.towerToDefend(s)
    }
    override val scenery = object : TacticalRules.Scenery {
        private val m = com.sphynx.game.SceneryMap
        override val status get() = m.status
        override fun observe(s: GameState) = m.observe(s)
        override fun roamDecision(s: GameState, p: com.sphynx.game.HeroProfile) = m.roamDecision(s, p)
        override fun pitAimDirection(s: GameState) = m.pitAimDirection(s)
    }
    override fun objectiveName(matchSec: Int) = com.sphynx.game.LaneLogic.objectiveName(matchSec)
    override val teamBossId: String get() = com.sphynx.game.JungleDb.LORD.id
    override val teamBossAllies: Int get() = com.sphynx.ai.BossRule.LORD_ALLIES
    override fun mayTakeTeamBoss(alliesNear: Int) = com.sphynx.ai.BossRule.mayHitLord(alliesNear)
    override fun alliesNearBoss(onScreenNearBoss: Int, onMinimapNearYou: Int) =
        com.sphynx.ai.BossRule.alliesNear(onScreenNearBoss, onMinimapNearYou)
    override fun isBuffCamp(species: String) = com.sphynx.game.JungleDb.isBuff(species)
    override fun monsterLabel(species: String?): String? = com.sphynx.game.JungleDb.byId(species)?.let { m ->
        m.name + if (m.aliases.isNotEmpty()) " (${m.aliases.first()})" else ""
    }
    override fun mapLane(lane: com.sphynx.game.Lane) = com.sphynx.game.MapSide.geo(lane)
    override fun roleProfile(lane: com.sphynx.game.Lane, hero: String?, matchSec: Int) =
        com.sphynx.game.RoleProfile.of(lane, hero, matchSec)
    override fun midRoamTarget(s: GameState) = com.sphynx.game.RoleProfile.midSemiRoamTarget(s)
    override fun roamWaypoint(s: GameState) = com.sphynx.game.RoleProfile.roamMetaWaypoint(s)

    // Multi-waypoint corridors for EXP Lane, Gold Lane, Mid Lane, and Roam Patrol (v37.13; moved unchanged from
    // TacticalEngine). Drawn for "EXP lane on top"; MapSide.geo swaps them when the Gold lane is on top.
    private val EXP_CORRIDOR = listOf(
        0.12f to 0.74f, 0.10f to 0.56f, 0.10f to 0.38f, 0.11f to 0.22f,
        0.15f to 0.13f, 0.28f to 0.10f, 0.48f to 0.10f, 0.68f to 0.10f, 0.86f to 0.12f
    )
    private val GOLD_CORRIDOR = listOf(
        0.26f to 0.88f, 0.44f to 0.90f, 0.62f to 0.90f, 0.78f to 0.89f,
        0.87f to 0.85f, 0.89f to 0.68f, 0.89f to 0.48f, 0.89f to 0.28f, 0.87f to 0.14f
    )
    private val MID_CORRIDOR = listOf(
        0.24f to 0.76f, 0.34f to 0.66f, 0.43f to 0.57f, 0.50f to 0.50f,
        0.58f to 0.42f, 0.67f to 0.33f, 0.76f to 0.24f, 0.86f to 0.14f
    )
    override val roamPatrol = listOf(
        0.45f to 0.55f, 0.39f to 0.61f, 0.56f to 0.44f, 0.74f to 0.76f, 0.34f to 0.32f
    )

    override fun laneCorridor(lane: com.sphynx.game.Lane): List<Pair<Float, Float>> = when (mapLane(lane)) {
        com.sphynx.game.Lane.EXP -> EXP_CORRIDOR
        com.sphynx.game.Lane.GOLD -> GOLD_CORRIDOR
        else -> MID_CORRIDOR
    }

    override fun outerTowerIndex(lane: com.sphynx.game.Lane): Int = when (lane) {
        com.sphynx.game.Lane.EXP -> 5   // (0.28, 0.10) Enemy EXP outer tower
        com.sphynx.game.Lane.GOLD -> 5  // (0.89, 0.68) Enemy Gold outer tower
        else -> 4                       // (0.58, 0.42) Enemy Mid outer tower
    }
}
