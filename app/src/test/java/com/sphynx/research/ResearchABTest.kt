package com.sphynx.research

import com.sphynx.research.ResearchAB.Arm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchABTest {

    // plays one match: n fights with the given reward
    private fun ResearchAB.play(id: Long, n: Int, reward: Float, seconds: Int = 600, deaths: Int = 2): Boolean {
        repeat(n) { fight(id, reward, false) }
        return matchEnd(5, deaths, 5, seconds, true)
    }

    @Test fun armIsFixedByMatchId() {
        assertEquals(Arm.ARBITER, ResearchAB.armOf(1000L))
        assertEquals(Arm.NORMAL, ResearchAB.armOf(1001L))
        assertEquals(Arm.NORMAL, ResearchAB.armOf(-1L))         // never crashes on odd ids
    }

    @Test fun keepsOnlyRealMeasuredMatches() {
        val ab = ResearchAB()
        assertFalse(ab.matchEnd(1, 1, 1, 600, true))          // no fights measured
        assertFalse(ab.play(10L, 5, 0.3f, seconds = 60))      // too short
        ab.fight(0L, 0.5f, false)                              // unidentified match: ignored
        assertFalse(ab.matchEnd(1, 1, 1, 600, null))
        assertTrue(ab.play(12L, 5, 0.3f))
        assertEquals(1, ab.all().size)
        assertEquals(Arm.ARBITER, ab.all()[0].arm)
    }

    @Test fun notEnoughDataUntilBothArmsHaveFiveMatches() {
        val ab = ResearchAB()
        for (i in 0 until 5) ab.play(100L + 2 * i, 10, 0.4f)  // arbiter only
        val r = ab.report()
        assertFalse(r.enough); assertNull(r.diff)
        assertTrue(r.verdict.startsWith("not enough data"))
    }

    @Test fun detectsAClearDifference() {
        val ab = ResearchAB()
        for (i in 0 until 6) {
            repeat(20) { k -> ab.fight(200L + 2 * i, if (k % 2 == 0) 0.6f else 0.4f, false) }; ab.matchEnd(5, 1, 5, 600, true)
            repeat(20) { k -> ab.fight(201L + 2 * i, if (k % 2 == 0) 0.1f else -0.1f, k == 0) }; ab.matchEnd(2, 4, 2, 600, false)
        }
        val r = ab.report()
        assertTrue(r.enough)
        assertEquals(0.5, r.diff!!, 1e-6)
        assertTrue(r.low!! > 0)
        assertEquals("the arbiter scores better in fights", r.verdict)
        assertEquals(6, r.normal!!.known); assertEquals(0, r.normal!!.wins)
    }

    @Test fun noDifferenceWhenArmsAreEqual() {
        val ab = ResearchAB()
        for (i in 0 until 10) repeat(10) { k -> ab.fight(300L + i, if (k % 2 == 0) 1f else -1f, false) }.also { ab.matchEnd(1, 1, 1, 600, null) }
        assertEquals("no clear difference yet", ab.report().verdict)
    }

    @Test fun saveAndLoad() {
        val ab = ResearchAB()
        ab.play(400L, 3, 0.2f); ab.play(401L, 4, -0.2f)
        val copy = ResearchAB(); copy.load(ab.toJson())
        assertEquals(ab.all(), copy.all())
        copy.load("not json")
        assertEquals(0, copy.all().size)
    }
}
