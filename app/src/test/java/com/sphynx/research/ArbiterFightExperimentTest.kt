package com.sphynx.research

import com.sphynx.learning.FightOption
import com.sphynx.universal.Capability
import com.sphynx.universal.CapabilitySet
import com.sphynx.universal.Decision
import com.sphynx.universal.DecisionKind
import com.sphynx.universal.EngineSource
import com.sphynx.universal.MatchPhase
import com.sphynx.universal.Side
import com.sphynx.universal.SupportStatus
import com.sphynx.universal.UniversalMatchState
import com.sphynx.universal.UnitKind
import com.sphynx.universal.UnitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArbiterFightExperimentTest {

    private val caps = CapabilitySet(mapOf(
        Capability.UNIT_HEALTH to SupportStatus.PARTIAL,
        Capability.STRATEGIC_ADVICE to SupportStatus.PARTIAL,
        Capability.TACTICAL_ADVICE to SupportStatus.EXPERIMENTAL))

    private val st = UniversalMatchState("test", 1000L, MatchPhase.IN_MATCH,
        self = UnitState(Side.SELF, UnitKind.CHARACTER, health = 0.8f),
        units = listOf(UnitState(Side.ENEMY, UnitKind.CHARACTER), UnitState(Side.ALLY, UnitKind.CHARACTER),
            UnitState(Side.ENEMY, UnitKind.MINION)),
        capabilities = caps)

    private fun d(k: DecisionKind, src: EngineSource, conf: Float? = null) = Decision(k, src, 1000L, 1000L, conf)
    private val pass: (FightOption) -> String? = { null }

    @Test fun notAllowedLeavesTheNormalChoiceUntouched() {
        val e = ArbiterFightExperiment()
        val r = e.choose(false, FightOption.FARM, listOf(d(DecisionKind.RETREAT, EngineSource.STRATEGIC, 0.9f)), st, 1200L, pass)
        assertEquals(FightOption.FARM, r.option)
        assertFalse(r.changed)
        assertEquals(0, e.choices)
    }

    @Test fun noFightChoiceMeansNothingToDecide() {
        val r = ArbiterFightExperiment().choose(true, null, listOf(d(DecisionKind.ENGAGE, EngineSource.STRATEGIC)), st, 1200L, pass)
        assertNull(r.option)
    }

    @Test fun arbiterPicksWhenAllowed() {
        val e = ArbiterFightExperiment()
        val r = e.choose(true, FightOption.FARM, listOf(
            d(DecisionKind.FARM, EngineSource.RULES), d(DecisionKind.POKE, EngineSource.LEARNED, 0.8f)), st, 1200L, pass)
        assertEquals(FightOption.POKE, r.option)
        assertTrue(r.changed)
        assertEquals(1, e.changed)
        assertEquals(1, e.recent().size)
    }

    @Test fun safetyFirstRetreatWins() {
        val r = ArbiterFightExperiment().choose(true, FightOption.ALL_IN, listOf(
            d(DecisionKind.ENGAGE, EngineSource.RULES, 0.9f), d(DecisionKind.RETREAT, EngineSource.STRATEGIC, 0.3f)), st, 1200L, pass)
        assertEquals(FightOption.RETREAT, r.option)
    }

    @Test fun experimentalTacticalModelCannotWin() {
        val r = ArbiterFightExperiment().choose(true, FightOption.FARM, listOf(
            d(DecisionKind.FARM, EngineSource.RULES), d(DecisionKind.ENGAGE, EngineSource.TACTICAL, 1f)), st, 1200L, pass)
        assertEquals(FightOption.FARM, r.option)
        assertFalse(r.changed)
    }

    @Test fun kotlinChecksKeepTheNormalChoice() {
        val e = ArbiterFightExperiment()
        val r = e.choose(true, FightOption.FARM, listOf(
            d(DecisionKind.FARM, EngineSource.RULES), d(DecisionKind.ENGAGE, EngineSource.LEARNED, 0.9f)), st, 1200L) { "too low" }
        assertEquals(FightOption.FARM, r.option)
        assertFalse(r.changed)
        assertEquals(1, e.keptByCheck)
    }

    @Test fun nonFightKindsKeepTheNormalChoice() {
        val r = ArbiterFightExperiment().choose(true, FightOption.FARM, listOf(
            d(DecisionKind.DEFEND, EngineSource.STRATEGIC, 0.9f)), st, 1200L, pass)
        assertEquals(FightOption.FARM, r.option)
        assertFalse(r.changed)
    }

    @Test fun rejectedAdviceCannotWin() {
        // engage at 15% health is rejected by the validator, so the normal choice stays
        val low = st.copy(self = UnitState(Side.SELF, UnitKind.CHARACTER, health = 0.15f))
        val r = ArbiterFightExperiment().choose(true, FightOption.RETREAT, listOf(
            d(DecisionKind.RETREAT, EngineSource.RULES), d(DecisionKind.ENGAGE, EngineSource.STRATEGIC, 1f)), low, 1200L, pass)
        assertEquals(FightOption.RETREAT, r.option)
    }

    // owner, 2026-11-12: safety first - the recording showed the strategic plan's generic "farm" overriding retreats
    @Test fun aRetreatIsNeverOverridden() {
        val e = ArbiterFightExperiment()
        val r = e.choose(true, FightOption.RETREAT, listOf(
            d(DecisionKind.RETREAT, EngineSource.RULES), d(DecisionKind.FARM, EngineSource.STRATEGIC, 1f)), st, 1200L, pass)
        assertEquals(FightOption.RETREAT, r.option)
        assertFalse(r.changed)
    }

    @Test fun aGenericFarmPlanDoesNotOverrideAnAllIn() {
        val e = ArbiterFightExperiment()
        assertEquals("a generic farm plan does not override an all-in",
            e.safetyKeeps(FightOption.ALL_IN, FightOption.FARM, EngineSource.STRATEGIC))
        // a learned engine's farm advice is a stronger reason; retreating from an all-in is always allowed
        assertNull(e.safetyKeeps(FightOption.ALL_IN, FightOption.FARM, EngineSource.LEARNED))
        assertNull(e.safetyKeeps(FightOption.ALL_IN, FightOption.RETREAT, EngineSource.STRATEGIC))
        assertEquals("a retreat is never overridden", e.safetyKeeps(FightOption.RETREAT, FightOption.ALL_IN, EngineSource.LEARNED))
    }
}
