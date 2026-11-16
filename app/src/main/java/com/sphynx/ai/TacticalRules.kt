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
    }
}
