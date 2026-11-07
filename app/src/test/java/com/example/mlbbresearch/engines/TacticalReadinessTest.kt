package com.example.mlbbresearch.engines

import com.example.mlbbresearch.engines.TacticalReadiness.Reply
import com.example.mlbbresearch.engines.TacticalReadiness.Scenario
import com.example.mlbbresearch.universal.Capability
import com.example.mlbbresearch.universal.CapabilitySet
import com.example.mlbbresearch.universal.DecisionKind
import com.example.mlbbresearch.universal.MatchPhase
import com.example.mlbbresearch.universal.Side
import com.example.mlbbresearch.universal.SupportStatus
import com.example.mlbbresearch.universal.UniversalMatchState
import com.example.mlbbresearch.universal.UnitKind
import com.example.mlbbresearch.universal.UnitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalReadinessTest {

    private val caps = CapabilitySet(mapOf(Capability.UNIT_HEALTH to SupportStatus.PARTIAL))

    private fun st(hp: Float, enemies: Int, allies: Int) = UniversalMatchState("test", 0L, MatchPhase.IN_MATCH,
        self = UnitState(Side.SELF, UnitKind.CHARACTER, health = hp),
        units = List(enemies) { UnitState(Side.ENEMY, UnitKind.CHARACTER, health = 0.5f) } +
            List(allies) { UnitState(Side.ALLY, UnitKind.CHARACTER) } + listOf(UnitState(Side.ENEMY, UnitKind.MINION)),
        capabilities = caps)

    // two kinds of situation, repeated: low health outnumbered (must back off) and a winning fight
    private fun scenarios(n: Int) = List(n) { i ->
        if (i % 2 == 0) Scenario("low-$i", st(0.1f, 2, 0), setOf(DecisionKind.RETREAT), unsafe = setOf(DecisionKind.ENGAGE))
        else Scenario("win-$i", st(0.9f, 1, 2), setOf(DecisionKind.ENGAGE, DecisionKind.POKE))
    }

    private val good: (UniversalMatchState) -> Reply = { s -> Reply(if (s.self!!.health!! < 0.3f) "RETREAT" else "ALL_IN", 120) }

    @Test fun goodModelOnEnoughScenariosIsReady() {
        val r = TacticalReadiness.evaluate(scenarios(200), good)
        assertTrue(r.reasons.toString(), r.ready)
        assertEquals(1.0, r.agreementRate, 1e-9)
        assertEquals(0, r.unsafe)
        assertEquals(120L, r.p95LatencyMs)
    }

    @Test fun tooFewScenariosIsNeverReady() {
        val r = TacticalReadiness.evaluate(scenarios(20), good)
        assertFalse(r.ready)
        assertTrue(r.reasons.any { it.contains("only 20 scenarios") })
    }

    @Test fun recklessModelFailsOnSafety() {
        val r = TacticalReadiness.evaluate(scenarios(200), { Reply("ALL_IN", 100) })
        assertFalse(r.ready)
        assertEquals(100, r.unsafe)
        assertEquals(100, r.rejectedByValidator)   // the validator also refuses all in at 10% health
        assertTrue(r.reasons.any { it.contains("unsafe") })
    }

    @Test fun unreadableRepliesAndSlowRepliesFail() {
        val r = TacticalReadiness.evaluate(scenarios(200), { s -> Reply(if (s.self!!.health!! < 0.3f) "I think RETREAT or GROUP" else "attack!", 900) })
        assertFalse(r.ready)
        assertEquals(0, r.valid)
        assertTrue(r.reasons.any { it.contains("valid replies") })
        assertTrue(r.reasons.any { it.contains("p95 latency") })
    }

    @Test fun strictParsing() {
        assertEquals(DecisionKind.ENGAGE, TacticalReadiness.parseStrict("all in"))
        assertEquals(DecisionKind.ENGAGE, TacticalReadiness.parseStrict("Answer: ALL-IN."))
        assertEquals(DecisionKind.DEFER, TacticalReadiness.parseStrict("RULES"))
        assertNull(TacticalReadiness.parseStrict("RETREAT then GROUP"))   // two options = invalid
        assertNull(TacticalReadiness.parseStrict("charge"))
        assertNull(TacticalReadiness.parseStrict("FARMING"))              // not the option word itself
    }
}
