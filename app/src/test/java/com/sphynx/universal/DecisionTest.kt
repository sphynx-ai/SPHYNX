package com.sphynx.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTest {

    private val caps = CapabilitySet(mapOf(
        Capability.UNIT_HEALTH to SupportStatus.PARTIAL,
        Capability.STRATEGIC_ADVICE to SupportStatus.PARTIAL,
        Capability.TACTICAL_ADVICE to SupportStatus.EXPERIMENTAL))

    private fun st(hp: Float? = 0.8f, enemies: Int = 1, allies: Int = 1, minions: Int = 2, phase: MatchPhase = MatchPhase.IN_MATCH) =
        UniversalMatchState("test", 1000L, phase,
            self = UnitState(Side.SELF, UnitKind.CHARACTER, health = hp),
            units = List(enemies) { UnitState(Side.ENEMY, UnitKind.CHARACTER) } +
                List(allies) { UnitState(Side.ALLY, UnitKind.CHARACTER) } +
                List(minions) { UnitState(Side.ENEMY, UnitKind.MINION) },
            capabilities = caps)

    private fun d(k: DecisionKind, src: EngineSource = EngineSource.RULES, conf: Float? = null, at: Long = 1000L) =
        Decision(k, src, at, 1000L, conf)

    private fun outcome(dec: Decision, s: UniversalMatchState) = DecisionValidator.validate(dec, s, 1200L).outcome

    @Test fun rejectsKnownContradictions() {
        assertEquals(DecisionValidator.Outcome.REJECTED, outcome(d(DecisionKind.ENGAGE), st(hp = 0.15f)))
        assertEquals(DecisionValidator.Outcome.REJECTED, outcome(d(DecisionKind.ENGAGE), st(enemies = 0)))
        assertEquals(DecisionValidator.Outcome.REJECTED, outcome(d(DecisionKind.FARM), st(minions = 0)))
        assertEquals(DecisionValidator.Outcome.REJECTED, outcome(d(DecisionKind.GROUP), st(allies = 0)))
        assertEquals(DecisionValidator.Outcome.REJECTED, outcome(d(DecisionKind.RETREAT), st(phase = MatchPhase.OUT_OF_MATCH)))
        assertEquals(DecisionValidator.Outcome.REJECTED, outcome(d(DecisionKind.RETREAT, conf = 1.5f), st()))
        assertEquals(DecisionValidator.Outcome.REJECTED, outcome(d(DecisionKind.RETREAT, at = 0L), st()))   // expired
    }

    @Test fun unknownHealthIsNotAReasonToReject() {
        assertEquals(DecisionValidator.Outcome.ACCEPTED, outcome(d(DecisionKind.ENGAGE), st(hp = null)))
    }

    @Test fun unitChecksNeedTheCapability() {
        // a game that cannot read units on screen: "no enemy in view" proves nothing
        val blind = st(enemies = 0).copy(capabilities = CapabilitySet.NONE)
        assertEquals(DecisionValidator.Outcome.ACCEPTED, outcome(d(DecisionKind.ENGAGE), blind))
    }

    @Test fun experimentalEngineIsAdvisoryOnly() {
        val v = DecisionValidator.validate(d(DecisionKind.RETREAT, EngineSource.TACTICAL), st(), 1200L)
        assertEquals(DecisionValidator.Outcome.ADVISORY_ONLY, v.outcome)
        assertTrue(v.reasons.single().contains("Experimental"))
        assertEquals(DecisionValidator.Outcome.ACCEPTED, outcome(d(DecisionKind.RETREAT, EngineSource.STRATEGIC), st()))
    }

    @Test fun arbiterPutsSafetyFirst() {
        val r = DecisionArbiter.resolve(listOf(
            d(DecisionKind.ENGAGE, EngineSource.STRATEGIC, 0.9f), d(DecisionKind.RETREAT, EngineSource.RULES, 0.4f)), st(), 1200L)
        assertEquals(DecisionKind.RETREAT, r.chosen!!.kind)
        assertTrue(r.note.contains("safety"))
    }

    @Test fun arbiterPrefersConfidenceThenStrategic() {
        val hi = DecisionArbiter.resolve(listOf(d(DecisionKind.FARM, EngineSource.RULES, 0.4f), d(DecisionKind.POKE, EngineSource.STRATEGIC, 0.7f)), st(), 1200L)
        assertEquals(DecisionKind.POKE, hi.chosen!!.kind)
        val tie = DecisionArbiter.resolve(listOf(d(DecisionKind.FARM, EngineSource.RULES), d(DecisionKind.GROUP, EngineSource.STRATEGIC)), st(), 1200L)
        assertEquals(EngineSource.STRATEGIC, tie.chosen!!.source)
    }

    @Test fun advisoryAndRejectedAdviceNeverWins() {
        val r = DecisionArbiter.resolve(listOf(
            d(DecisionKind.ENGAGE, EngineSource.TACTICAL, 1f),          // experimental: advisory only
            d(DecisionKind.ENGAGE, EngineSource.RULES, 0.9f)), st(hp = 0.1f), 1200L)   // rejected: low health
        assertNull(r.chosen)
        assertEquals(2, r.rejected.size)   // a known contradiction rejects even the experimental engine's advice
        val onlyAdvisory = DecisionArbiter.resolve(listOf(d(DecisionKind.RETREAT, EngineSource.TACTICAL, 1f)), st(), 1200L)
        assertNull(onlyAdvisory.chosen)
        assertEquals(1, onlyAdvisory.advisory.size)
    }
}
