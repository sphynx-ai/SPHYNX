package com.sphynx.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionLogTest {

    private val caps = CapabilitySet(mapOf(Capability.UNIT_HEALTH to SupportStatus.PARTIAL,
        Capability.TACTICAL_ADVICE to SupportStatus.EXPERIMENTAL, Capability.STRATEGIC_ADVICE to SupportStatus.PARTIAL))

    private fun st(hp: Float) = UniversalMatchState("test", 0L, MatchPhase.IN_MATCH,
        self = UnitState(Side.SELF, UnitKind.CHARACTER, health = hp),
        units = listOf(UnitState(Side.ENEMY, UnitKind.CHARACTER), UnitState(Side.ENEMY, UnitKind.MINION)), capabilities = caps)

    private fun d(k: DecisionKind, src: EngineSource) = Decision(k, src, 1000L, 1000L)

    @Test fun recordsArbiterChoiceNextToPlayAndCountsAgreement() {
        val log = DecisionLog()
        log.record(st(0.8f), listOf(d(DecisionKind.FARM, EngineSource.LEARNED)), DecisionKind.FARM, 1100L)
        log.record(st(0.8f), listOf(d(DecisionKind.POKE, EngineSource.LEARNED)), DecisionKind.ENGAGE, 1100L)
        assertEquals(2, log.total)
        assertEquals(2, log.compared)
        assertEquals(1, log.agreed)
        assertTrue(log.summary().contains("50%"))
    }

    @Test fun rejectionsAndAdvisoryAreRecordedNotActedOn() {
        val log = DecisionLog()
        val e = log.record(st(0.1f), listOf(
            d(DecisionKind.ENGAGE, EngineSource.LEARNED),          // rejected: engage at 10%
            d(DecisionKind.RETREAT, EngineSource.TACTICAL)),       // advisory only: 270M is experimental
            DecisionKind.ENGAGE, 1100L)
        assertNull(e.arbiterChoice)
        assertNull(e.agrees)                                       // nothing to compare
        assertEquals(EngineSource.LEARNED, e.rejected.single().first)
        assertEquals(listOf(EngineSource.TACTICAL), e.advisoryOnly)
        assertEquals(1, log.rejectionsByReason.values.sum())
    }

    @Test fun logIsBounded() {
        val log = DecisionLog(capacity = 3)
        repeat(10) { log.record(st(0.8f), listOf(d(DecisionKind.FARM, EngineSource.LEARNED)), DecisionKind.FARM, 1100L) }
        assertEquals(10, log.total)
        assertEquals(3, log.recent(50).size)
    }
}
