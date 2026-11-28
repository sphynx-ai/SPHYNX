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
import com.sphynx.universal.autonomy.AutonomyGates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 12 C7: the replayable coach scenarios (test resources phase12/coach_scenarios.json) and the gates' path
class CoachScenariosTest {

    private val now = 10_000L

    @Test fun everyScenarioDoesWhatItMust() {
        val text = javaClass.classLoader!!.getResource("phase12/coach_scenarios.json")!!.readText()
        val a = JSONObject(text).getJSONArray("scenarios")
        assertTrue(a.length() >= 10)
        for (i in 0 until a.length()) {
            val sc = a.getJSONObject(i)
            val id = sc.getString("id")
            val caps = CapabilitySet(mapOf(Capability.UNIT_HEALTH to SupportStatus.PARTIAL, Capability.STRATEGIC_ADVICE to SupportStatus.PARTIAL,
                Capability.TACTICAL_ADVICE to if (sc.optBoolean("tacticalExperimental")) SupportStatus.EXPERIMENTAL else SupportStatus.PARTIAL))
            val units = List(sc.getInt("enemies")) { UnitState(Side.ENEMY, UnitKind.CHARACTER) } +
                List(sc.getInt("allies")) { UnitState(Side.ALLY, UnitKind.CHARACTER) } + List(sc.getInt("minions")) { UnitState(Side.ENEMY, UnitKind.MINION) }
            val st = UniversalMatchState("test", now, MatchPhase.IN_MATCH, self = UnitState(Side.SELF, UnitKind.CHARACTER,
                health = sc.getDouble("hp").toFloat()), units = units, capabilities = caps)
            val at = now - sc.optLong("ageMs", 100)
            val adv = sc.getJSONArray("advice")
            val ds = (0 until adv.length()).map { k -> val d = adv.getJSONArray(k)
                Decision(DecisionKind.valueOf(d.getString(0)), EngineSource.valueOf(d.getString(1)), at,
                    if (d.getString(1) == "STRATEGIC") 30_000 else 1_000, d.getDouble(2).toFloat()) }
            val o = Coordinator.coordinate(ds, st, now)
            val expect = if (sc.isNull("expect")) null else sc.getString("expect")
            assertEquals(id, expect, o.tactical?.kind?.name)
            assertEquals("$id actionable", sc.getBoolean("actionable"), o.actionable != null)
            assertEquals("$id conflict", sc.optBoolean("conflict"), o.conflict != null)
            if (sc.has("strategic")) assertEquals(id, sc.getString("strategic"), o.strategic?.kind?.name)
            assertTrue(id, o.shown.size <= Coordinator.BUDGET)
            assertFalse(id, o.lines().isEmpty())
        }
    }

    @Test fun theGatesClimbOneStageAtATimeAndStopBeforeBounded() {
        var e = AutonomyGates.Evidence()
        val stages = ArrayList<AutonomyGates.Stage>()
        stages.add(AutonomyGates.evaluate(e).stage)
        e = e.copy(evalAnswers = 120, evalUnsafeShare = 0.01, evalMeanScore = 0.7, setReviewed = true); stages.add(AutonomyGates.evaluate(e).stage)
        e = e.copy(shadowDecisions = 250, shadowAgreed = 200, shadowUnsafe = 2); stages.add(AutonomyGates.evaluate(e).stage)
        e = e.copy(ownerSupervised = true); stages.add(AutonomyGates.evaluate(e).stage)
        e = e.copy(ownerBounded = true); stages.add(AutonomyGates.evaluate(e).stage)
        assertEquals(listOf(AutonomyGates.Stage.OBSERVE, AutonomyGates.Stage.SHADOW, AutonomyGates.Stage.SHADOW,
            AutonomyGates.Stage.SUPERVISED, AutonomyGates.Stage.SUPERVISED), stages)
        // turning the owner's switch off drops supervised at once
        assertEquals(AutonomyGates.Stage.SHADOW, AutonomyGates.evaluate(e.copy(ownerSupervised = false)).stage)
    }
}
