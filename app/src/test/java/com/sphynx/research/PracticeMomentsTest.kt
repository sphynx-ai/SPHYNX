package com.sphynx.research

import com.sphynx.engines.TacticalReadiness
import com.sphynx.universal.Capability
import com.sphynx.universal.CapabilitySet
import com.sphynx.universal.DecisionKind
import com.sphynx.universal.SupportStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeMomentsTest {

    private val caps = CapabilitySet(mapOf(
        Capability.UNIT_HEALTH to SupportStatus.PARTIAL,
        Capability.TACTICAL_ADVICE to SupportStatus.EXPERIMENTAL))

    private fun m(match: Long = 7L, at: Long = 10_000L, played: DecisionKind = DecisionKind.RETREAT,
                  unsafe: Set<DecisionKind> = setOf(DecisionKind.ENGAGE), hp: Float? = 0.15f, summary: String = "hero x\nme: HP 15%") =
        PracticeMoment(match, at, summary, played, unsafe, hp, 2, 1, 3, 0)

    @Test fun jsonRoundTrip() {
        val a = m()
        assertEquals(a, PracticeMoment.fromJson(JSONObject(a.toJson().toString())))
        val noHp = m(hp = null)
        assertEquals(noHp, PracticeMoment.fromJson(JSONObject(noHp.toJson().toString())))
    }

    @Test fun damagedLinesAreSkipped() {
        val set = PracticeMomentSet()
        set.loadJsonLines(m().toJson().toString() + "\n{not json\n{\"match\":1}\n" + m(at = 20_000L).toJson())
        assertEquals(2, set.size)
        assertNull(PracticeMoment.fromJson(JSONObject("{\"match\":1,\"at\":2,\"summary\":\"s\",\"played\":\"NOPE\",\"enemies\":0,\"allies\":0,\"minions\":0}")))
    }

    @Test fun refusesUnidentifiedMatchesAndTooCloseMoments() {
        val set = PracticeMomentSet(minGapMs = 1500L)
        assertFalse(set.add(m(match = 0L)))
        assertTrue(set.add(m(at = 10_000L)))
        assertFalse(set.add(m(at = 11_000L)))           // 1 s after the last one
        assertTrue(set.add(m(at = 11_600L)))
        assertTrue(set.add(m(match = 8L, at = 11_700L))) // another match is not throttled
        assertEquals(3, set.size); assertEquals(2, set.matches)
    }

    @Test fun boundedOldestFirst() {
        val set = PracticeMomentSet(max = 3, minGapMs = 0L)
        for (i in 1..5) set.add(m(at = i * 10_000L))
        assertEquals(listOf(30_000L, 40_000L, 50_000L), set.all().map { it.atMs })
        val copy = PracticeMomentSet(max = 3); copy.loadJsonLines(set.toJsonLines())
        assertEquals(set.all(), copy.all())
    }

    @Test fun momentsDriveTheReadinessHarness() {
        val moments = listOf(
            m(at = 1L, played = DecisionKind.RETREAT, summary = "A"),
            m(at = 2L, played = DecisionKind.FARM, unsafe = emptySet(), hp = 0.9f, summary = "B"),
            m(at = 3L, played = DecisionKind.RETREAT, summary = "C"))
        // a fake model that reads the prompt: engage on C (unsafe there), otherwise the played answer
        val replies = mapOf("A" to "RETREAT", "B" to "FARM", "C" to "ALL_IN")
        val r = TacticalReadiness.evaluateScenarios(moments.map { it.scenario(caps) },
            { TacticalReadiness.Reply(replies.getValue(it.prompt), 120L) },
            TacticalReadiness.Criteria(minScenarios = 3))
        assertEquals(3, r.valid); assertEquals(2, r.agreed); assertEquals(1, r.unsafe)
        assertEquals(1, r.rejectedByValidator)           // engage at 15% health
        assertFalse(r.ready)
        assertTrue(r.reasons.any { it.contains("unsafe") })
    }

    @Test fun mixCountsAnswers() {
        val set = PracticeMomentSet(minGapMs = 0L)
        set.add(m(at = 1L)); set.add(m(at = 2L)); set.add(m(at = 3L, played = DecisionKind.FARM))
        assertEquals(mapOf(DecisionKind.RETREAT to 2, DecisionKind.FARM to 1), set.mix())
    }
}
