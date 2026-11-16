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
}
