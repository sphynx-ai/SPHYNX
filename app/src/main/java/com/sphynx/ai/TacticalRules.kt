package com.sphynx.ai

import com.sphynx.game.GameState

// THE GAME'S OWN TACTICAL RULES (Phase 2 adapter split, step 4): what TacticalEngine needs to know about one
// game's heroes, spells, jungle, objectives and towers. The engine asks these questions and never names a
// game's heroes, spells or monsters itself. Mobile Legends' answers are adapters/mlbb/MlbbTacticalRules, set
// by the app at start (app/ProbesWiring) and by the tests. NONE answers "nothing special" for every question.
interface TacticalRules {

    // ---- hero special cases ----
    /** A skill that keeps working for a while and must not be interrupted (null when none is running). */
    fun channelling(s: GameState): Channel?
    /** A transformation the AI steers straight into the nearest enemy (e.g. a hero flying as a dragon). */
    fun steeringTransform(s: GameState): Boolean
    /** Its name in the decision's reason. */
    val steeringTransformName: String
    /** A self-buff ultimate is running (fight harder, retreat later). */
    fun buffUltActive(s: GameState): Boolean
    /** The hero cannot die for now (e.g. an ultimate that blocks death): it may fight on at any HP. */
    fun deathImmune(s: GameState): Boolean
    /** How much the retreat HP moves because of the hero's bar (shield, armour, stored energy). */
    fun retreatAdjust(s: GameState): Float
    /** How much lower (negative) the retreat HP is while a buff ultimate runs. */
    fun buffUltRetreatShift(s: GameState): Float
    /** The HP fraction under which the hero's ultimate finishes an enemy (0 = it does not). */
    fun executeUltBelow(hero: String?, level: Int): Float
    /** The hero's active skill slots ("s1", "s2", "s3", "ult"). */
    fun skillSlots(hero: String?): List<String>
    /** Every skill slot a hero can have. */
    val allSkillSlots: List<String>
    /** Is the hero attacking from range right now (a form change); null = as its profile says. */
    fun rangedNow(hero: String?): Boolean?
    /** The basic attack reach to keep when ranged, if the hero's differs from its profile (null = the profile's). */
    fun rangedReach(hero: String?): Float?
    /** May the skill be cast again now although it is on cooldown (a recast window)? */
    fun canRecast(hero: String?, slot: String): Boolean
    /** Does the hero's ultimate come in several phases (cast it whenever an enemy is close)? */
    fun multiPhaseUlt(hero: String?): Boolean
    /** The skill slot that dashes (null when none). */
    fun dashSlot(hero: String?): String?
    /** The hero's role (UNKNOWN when not known). */
    fun role(hero: String?): com.sphynx.game.Role

    // ---- the battle / summoner spells ----
    /** The spell that finishes monsters (MLBB: Retribution). */
    fun isSmite(spell: String?): Boolean
    /** The short teleport (MLBB: Flicker): chase, escape, walls. */
    fun isBlink(spell: String?): Boolean
    /** The spell that finishes a low enemy hero (MLBB: Execute), and under which HP. */
    fun isExecute(spell: String?): Boolean
    val executeSpellBelow: Float
    /** A long-range damage spell (MLBB: Flameshot). */
    fun isNuke(spell: String?): Boolean
    /** The smite's HP % on the big objectives and on the camps (owner's rulebook). */
    fun smiteBossPct(): Int
    fun smiteCreepPct(): Int
    /** Cast the spell now, in a fight or while running away? (the spell's own use) */
    fun useSpellInFight(spell: String, s: GameState, p: com.sphynx.game.HeroProfile, fight: Boolean, running: Boolean,
                        targetDist: Float, enemiesClose: Int, enemiesNear: Int, hurt: Boolean, targetHp: Float): Boolean

    // ---- the map: jungle path, towers, objectives, lanes, roles, scenery ----
    val jungle: JungleRoute
    val towers: Towers
    val scenery: Scenery
    /** The objective that is up at this time ("turtle", "lord"...). */
    fun objectiveName(matchSec: Int): String
    /** The id of the big objective that needs the team there (MLBB: the Lord), and how many allies it needs. */
    val teamBossId: String
    val teamBossAllies: Int
    fun mayTakeTeamBoss(alliesNear: Int): Boolean
    fun alliesNearBoss(onScreenNearBoss: Int, onMinimapNearYou: Int): Int
    /** Is the camp a buff (worth the smite)? */
    fun isBuffCamp(species: String): Boolean
    /** A monster's name for the decision's reason (null when unknown). */
    fun monsterLabel(species: String?): String?
    /** The lane's position on the map as drawn (the map can be mirrored). */
    fun mapLane(lane: com.sphynx.game.Lane): com.sphynx.game.Lane
    fun roleProfile(lane: com.sphynx.game.Lane, hero: String?, matchSec: Int): com.sphynx.game.RoleProfile
    fun midRoamTarget(s: GameState): Pair<Pair<Float, Float>, String>?
    fun roamWaypoint(s: GameState): Pair<Pair<Float, Float>, String>?

    interface JungleRoute {
        val atCamp: Boolean
        val atBuff: Boolean
        val atCampMs: Long
        val status: String
        val next: com.sphynx.vision.Camp?
        fun direction(s: GameState, maxDist: Float = 9f, allowBuffs: Boolean = true): Triple<Float, Float, String>?
        fun reset()
    }

    interface Towers {
        fun range(): Float
        fun effectiveTowers(s: GameState): List<com.sphynx.vision.MapTower>
        fun nearest(s: GameState): Pair<Pair<Float, Float>, Float>?
        fun selfInRange(s: GameState, margin: Float = 0f): Boolean
        fun canPush(s: GameState): Boolean
        fun enemyUnderTower(s: GameState, e: com.sphynx.vision.Track): Boolean
        fun guard(s: GameState, mx: Float, my: Float): Triple<Float, Float, Boolean>
        fun laneTarget(s: GameState, lane: com.sphynx.game.Lane): com.sphynx.vision.MapTower?
        fun towerToDefend(s: GameState): Pair<Float, Float>?
    }

    interface Scenery {
        val status: String
        fun observe(s: GameState)
        fun roamDecision(s: GameState, p: com.sphynx.game.HeroProfile): TacticalDecision?
        fun pitAimDirection(s: GameState): Pair<Float, Float>?
    }

    class Channel(val allowBasicAttack: Boolean, val allowMove: Boolean, val executeUltHp: Float)

    /** No game-specific rules at all: nothing channels, transforms, buffs or recasts. */
    object NONE : TacticalRules {
        override fun channelling(s: GameState): Channel? = null
        override fun steeringTransform(s: GameState) = false
        override val steeringTransformName = "transformation"
        override fun buffUltActive(s: GameState) = false
        override fun deathImmune(s: GameState) = false
        override fun retreatAdjust(s: GameState) = 0f
        override fun buffUltRetreatShift(s: GameState) = 0f
        override fun executeUltBelow(hero: String?, level: Int) = 0f
        override fun skillSlots(hero: String?) = listOf("s1", "s2", "ult")
        override val allSkillSlots = listOf("s1", "s2", "s3", "ult")
        override fun rangedNow(hero: String?): Boolean? = null
        override fun rangedReach(hero: String?): Float? = null
        override fun canRecast(hero: String?, slot: String) = false
        override fun multiPhaseUlt(hero: String?) = false
        override fun dashSlot(hero: String?): String? = null
        override fun role(hero: String?) = com.sphynx.game.Role.UNKNOWN
        override fun isSmite(spell: String?) = false
        override fun isBlink(spell: String?) = false
        override fun isExecute(spell: String?) = false
        override val executeSpellBelow = 0f
        override fun isNuke(spell: String?) = false
        override fun smiteBossPct() = 0
        override fun smiteCreepPct() = 0
        override fun useSpellInFight(spell: String, s: GameState, p: com.sphynx.game.HeroProfile, fight: Boolean,
                                     running: Boolean, targetDist: Float, enemiesClose: Int, enemiesNear: Int,
                                     hurt: Boolean, targetHp: Float) = false
        // no map knowledge: no jungle path, no towers, no scenery, no named objectives
        override val jungle = object : JungleRoute {
            override val atCamp = false
            override val atBuff = false
            override val atCampMs = 0L
            override val status = "-"
            override val next: com.sphynx.vision.Camp? = null
            override fun direction(s: GameState, maxDist: Float, allowBuffs: Boolean): Triple<Float, Float, String>? = null
            override fun reset() {}
        }
        override val towers = object : Towers {
            override fun range() = 0.06f
            override fun effectiveTowers(s: GameState) = emptyList<com.sphynx.vision.MapTower>()
            override fun nearest(s: GameState): Pair<Pair<Float, Float>, Float>? = null
            override fun selfInRange(s: GameState, margin: Float) = false
            override fun canPush(s: GameState) = false
            override fun enemyUnderTower(s: GameState, e: com.sphynx.vision.Track) = false
            override fun guard(s: GameState, mx: Float, my: Float) = Triple(mx, my, false)
            override fun laneTarget(s: GameState, lane: com.sphynx.game.Lane): com.sphynx.vision.MapTower? = null
            override fun towerToDefend(s: GameState): Pair<Float, Float>? = null
        }
        override val scenery = object : Scenery {
            override val status = "-"
            override fun observe(s: GameState) {}
            override fun roamDecision(s: GameState, p: com.sphynx.game.HeroProfile): TacticalDecision? = null
            override fun pitAimDirection(s: GameState): Pair<Float, Float>? = null
        }
        override fun objectiveName(matchSec: Int) = "objective"
        override val teamBossId = ""
        override val teamBossAllies = 0
        override fun mayTakeTeamBoss(alliesNear: Int) = true
        override fun alliesNearBoss(onScreenNearBoss: Int, onMinimapNearYou: Int) = maxOf(onScreenNearBoss, onMinimapNearYou)
        override fun isBuffCamp(species: String) = false
        override fun monsterLabel(species: String?): String? = null
        override fun mapLane(lane: com.sphynx.game.Lane) = lane
        // the role's job order is by role and lane, not by game data
        override fun roleProfile(lane: com.sphynx.game.Lane, hero: String?, matchSec: Int) =
            com.sphynx.game.RoleProfile.of(lane, hero, matchSec)
        override fun midRoamTarget(s: GameState): Pair<Pair<Float, Float>, String>? = null
        override fun roamWaypoint(s: GameState): Pair<Pair<Float, Float>, String>? = null
    }
}
