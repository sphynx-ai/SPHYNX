package com.sphynx.universal.coach

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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 12 C1: the coordinator and its recommendation record
class CoordinatorTest {

    private val caps = CapabilitySet(mapOf(Capability.UNIT_HEALTH to SupportStatus.PARTIAL,
        Capability.TACTICAL_ADVICE to SupportStatus.PARTIAL, Capability.STRATEGIC_ADVICE to SupportStatus.PARTIAL))
    private fun st(hp: Float, c: CapabilitySet = caps) = UniversalMatchState("test", 0L, MatchPhase.IN_MATCH,
        self = UnitState(Side.SELF, UnitKind.CHARACTER, health = hp),
        units = listOf(UnitState(Side.ENEMY, UnitKind.CHARACTER), UnitState(Side.ENEMY, UnitKind.MINION), UnitState(Side.ALLY, UnitKind.CHARACTER)),
        capabilities = c)
    private fun d(k: DecisionKind, src: EngineSource, conf: Float? = 0.7f, at: Long = 1000L, ttl: Long = 1000L) = Decision(k, src, at, ttl, conf, rationale = "r")

    @Test fun twoHorizonsStayApartAndAreRanked() {
        val o = Coordinator.coordinate(listOf(d(DecisionKind.ENGAGE, EngineSource.TACTICAL), d(DecisionKind.FARM, EngineSource.STRATEGIC, ttl = 30_000)), st(0.8f), 1100L)
        assertEquals(DecisionKind.ENGAGE, o.tactical!!.kind); assertEquals(Recommendation.Horizon.TACTICAL, o.tactical!!.horizon)
        assertEquals(DecisionKind.FARM, o.strategic!!.kind); assertEquals(Recommendation.Horizon.STRATEGIC, o.strategic!!.horizon)
        assertEquals(listOf(DecisionKind.ENGAGE, DecisionKind.FARM), o.shown.map { it.kind })       // high urgency first
        assertEquals(DecisionKind.ENGAGE, o.actionable!!.kind)
        assertTrue(o.lines().first().startsWith("NOW · fight now"))
    }

    @Test fun safetyFirstSettlesAFightConflict() {
        val o = Coordinator.coordinate(listOf(d(DecisionKind.ENGAGE, EngineSource.LEARNED, 0.9f), d(DecisionKind.RETREAT, EngineSource.TACTICAL, 0.5f)), st(0.25f), 1100L)
        assertEquals(DecisionKind.RETREAT, o.tactical!!.kind)
        assertEquals(Recommendation.Urgency.CRITICAL, o.tactical!!.urgency)
        assertTrue(o.tactical!!.evidence.any { it.contains("safety first") })
    }

    @Test fun aNearTieWithoutARuleIsAConflictNotAdvice() {
        val o = Coordinator.coordinate(listOf(d(DecisionKind.FARM, EngineSource.LEARNED, 0.62f), d(DecisionKind.GROUP, EngineSource.TACTICAL, 0.6f)), st(0.8f), 1100L)
        assertNull(o.tactical); assertNull(o.actionable)
        assertTrue(o.conflict!!.contains("disagree"))
        assertTrue(o.lines().single().startsWith("COACH · no recommendation (the engines disagree"))
        // a rule decides: no conflict
        val r = Coordinator.coordinate(listOf(d(DecisionKind.FARM, EngineSource.RULES, 0.62f), d(DecisionKind.GROUP, EngineSource.TACTICAL, 0.6f)), st(0.8f), 1100L)
        assertEquals(DecisionKind.FARM, r.tactical!!.kind)
    }

    @Test fun weakStaleOrMissingGivesNoRecommendation() {
        assertNull(Coordinator.coordinate(listOf(d(DecisionKind.FARM, EngineSource.TACTICAL, 0.2f)), st(0.8f), 1100L).tactical)
        val stale = Coordinator.coordinate(listOf(d(DecisionKind.FARM, EngineSource.TACTICAL, at = 0, ttl = 500)), st(0.8f), 5000L)
        assertNull(stale.tactical); assertTrue(stale.noAdvice.first().startsWith("now:"))
        val none = Coordinator.coordinate(emptyList(), st(0.8f), 1L)
        assertTrue(none.shown.isEmpty()); assertEquals(listOf("now: no advice", "strategy: no advice"), none.noAdvice)
        // all in at 10% HP is rejected by the validator: never advised
        assertNull(Coordinator.coordinate(listOf(d(DecisionKind.ENGAGE, EngineSource.TACTICAL, 0.9f)), st(0.1f), 1100L).actionable)
    }

    @Test fun advisoryOnlyIsShownAsExperimentalNeverActionable() {
        val exp = CapabilitySet(mapOf(Capability.UNIT_HEALTH to SupportStatus.PARTIAL, Capability.TACTICAL_ADVICE to SupportStatus.EXPERIMENTAL))
        val o = Coordinator.coordinate(listOf(d(DecisionKind.POKE, EngineSource.TACTICAL, 0.8f)), st(0.8f, exp), 1100L)
        assertTrue(o.tactical!!.experimental)
        assertNull(o.actionable)
        assertTrue(o.lines().first().contains("experimental"))
    }

    @Test fun theBudgetAndTheRecordFormat() {
        val o = Coordinator.coordinate(listOf(d(DecisionKind.ENGAGE, EngineSource.TACTICAL), d(DecisionKind.FARM, EngineSource.STRATEGIC, ttl = 30_000)), st(0.8f), 1100L, budget = 1)
        assertEquals(1, o.shown.size)
        val r = o.tactical!!
        assertEquals(r, Recommendation.fromJson(JSONObject(r.toJson().toString())))
        assertNull(Recommendation.fromJson(r.toJson().put("schema", 9)))
    }
}
