package com.sphynx.universal.coach

import com.sphynx.adapters.mlbb.MlbbEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 12 C2: the shared match context and MLBB's events
class MatchContextTest {

    private fun e(t: MatchContext.Type, at: Long, d: String = "") = MatchContext.Event(t, at, null, d, "test")

    @Test fun orderDuplicatesGapsAndStaleness() {
        val c = MatchContext()
        assertTrue(c.add(e(MatchContext.Type.ENEMY_DOWN, 5_000)))
        assertTrue(c.add(e(MatchContext.Type.SELF_DOWN, 3_000)))               // out of order: put in order
        assertFalse(c.add(e(MatchContext.Type.ENEMY_DOWN, 5_400)))             // the same event again
        assertEquals(listOf(MatchContext.Type.SELF_DOWN, MatchContext.Type.ENEMY_DOWN), c.all().map { it.type })
        assertTrue(c.stale(6_000))                                             // never observed
        c.observe(6_000, 100); c.observe(7_000, 160)                           // a 60 s jump in the match clock
        assertEquals(1, c.count(MatchContext.Type.GAP))
        assertFalse(c.stale(8_000)); assertTrue(c.stale(20_000))
        assertTrue(c.summary(8_000).startsWith("last 3 min: 1 × you went down, 1 × enemy down, 1 × no evidence"))
        assertTrue(c.summary(20_000).endsWith("(out of date)"))
        c.reset(); assertTrue(c.all().isEmpty()); assertEquals("no recent evidence", c.summary(1))
    }

    @Test fun aBoundedHistory() {
        val c = MatchContext(maxEvents = 3)
        for (i in 0 until 5) c.add(e(MatchContext.Type.ENEMY_DOWN, i * 2_000L, "$i"))
        assertEquals(listOf("2", "3", "4"), c.all().map { it.detail })
    }

    private fun r(at: Long, inMatch: Boolean = true, dead: Boolean = false, en: Int? = 0, al: Int? = 0, obj: String? = null, hp: Float? = null) =
        MlbbEvents.Reading(at, inMatch, dead, 60, en, al, obj, hp)

    @Test fun mlbbReadingsBecomeEvents() {
        assertEquals(listOf(MatchContext.Type.MATCH_START), MlbbEvents.diff(null, r(0)).map { it.type })
        assertEquals(listOf(MatchContext.Type.ENEMY_DOWN, MatchContext.Type.ENEMY_DOWN), MlbbEvents.diff(r(0), r(1, en = 2)).map { it.type })
        assertEquals(listOf(MatchContext.Type.SELF_DOWN, MatchContext.Type.ALLY_DOWN), MlbbEvents.diff(r(0), r(1, dead = true, al = 1)).map { it.type })
        assertEquals(listOf(MatchContext.Type.OBJECTIVE_SEEN), MlbbEvents.diff(r(0), r(1, obj = "lord", hp = 1f)).map { it.type })
        assertEquals(listOf(MatchContext.Type.OBJECTIVE_TAKEN), MlbbEvents.diff(r(0, obj = "lord", hp = 0.3f), r(1, obj = "lord", hp = 0f)).map { it.type })
        assertEquals(listOf(MatchContext.Type.MATCH_END), MlbbEvents.diff(r(0), r(1, inMatch = false)).map { it.type })
        assertTrue(MlbbEvents.diff(r(0, en = null), r(1, en = 3)).isEmpty())  // not read before: nothing guessed
        assertEquals("death timers", MlbbEvents.diff(r(0), r(1, en = 1)).single().source)
        // two enemies down at once are two events, and the context keeps both
        val c = MatchContext(); MlbbEvents.diff(r(0), r(1, en = 2)).forEach { c.add(it) }
        assertEquals(2, c.count(MatchContext.Type.ENEMY_DOWN))
    }
}
