package com.example.mlbbresearch.research

import com.example.mlbbresearch.research.PlayerGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeBenchmarkTest {

    // plays `seconds` of a match, one tick every 500 ms, with the given intent
    private fun PracticeBenchmark.play(id: Long, fromMs: Long, seconds: Int, verdict: Verdict = Verdict.PLAY,
                                       intent: String = "JUNGLE", idle: Boolean = false, stuck: Boolean = false,
                                       level: Int = 5, deathAt: Long = 0L, outnumbered: Boolean = false): Long {
        var t = fromMs
        repeat(seconds * 2) {
            val playing = verdict == Verdict.PLAY
            tick(id, t, verdict, true, playing, if (playing) intent else null, idle, stuck, (t / 1000).toInt(), level, deathAt, outnumbered)
            t += 500
        }
        return t
    }

    @Test fun verifiedPracticeMatchIsKept() {
        val b = PracticeBenchmark()
        b.play(7L, 0L, 300)
        val r = b.matchEnd("Chou", "EXP", 4, 2, 6, 12, 600, true, 1L)
        assertNotNull(r)
        assertEquals(1, b.all().size)
        assertEquals(5.0, r!!.kda, 1e-9)
        assertTrue(r.playedMs in 299_000L..300_000L)
        assertTrue((r.intentMs["JUNGLE"] ?: 0L) > 290_000L)
    }

    @Test fun unverifiedMatchIsNotKept() {
        val b = PracticeBenchmark()
        b.play(7L, 0L, 300, verdict = Verdict.WAIT)
        assertNull(b.matchEnd("Chou", "EXP", 4, 2, 6, 12, 600, true, 1L))
        assertEquals(0, b.all().size)
    }

    @Test fun aRealPlayerAnywhereInTheMatchExcludesIt() {
        val b = PracticeBenchmark()
        val t = b.play(7L, 0L, 300)
        b.play(7L, t, 10, verdict = Verdict.LOCKED)
        assertNull(b.matchEnd("Chou", "EXP", 4, 2, 6, 12, 600, true, 1L))
    }

    @Test fun tooShortIsNotKept() {
        val b = PracticeBenchmark()
        b.play(7L, 0L, 30)                                        // the Player played 30 s only
        assertNull(b.matchEnd("Chou", "EXP", 0, 0, 0, 3, 600, null, 1L))
        b.play(8L, 0L, 300)
        assertNull(b.matchEnd("Chou", "EXP", 0, 0, 0, 3, 90, null, 1L))   // the match lasted 90 s
    }

    @Test fun idleAndStuckShares() {
        val b = PracticeBenchmark()
        val t = b.play(7L, 0L, 100)
        val t2 = b.play(7L, t, 50, idle = true)
        b.play(7L, t2, 50, stuck = true)
        val r = b.matchEnd("Chou", "EXP", 0, 0, 0, 8, 300, false, 1L)!!
        assertEquals(0.25, r.idleShare, 0.01)
        assertEquals(0.25, r.stuckShare, 0.01)
    }

    @Test fun deathCauseIsWhatTheAiWasDoingBefore() {
        val b = PracticeBenchmark()
        val t = b.play(7L, 0L, 120, intent = "JUNGLE")
        val t2 = b.play(7L, t, 10, intent = "ENGAGE", outnumbered = true)
        b.play(7L, t2, 120, intent = "JUNGLE", deathAt = t2 - 200)   // died at the end of the fight
        val r = b.matchEnd("Chou", "EXP", 0, 1, 0, 8, 300, false, 1L)!!
        assertEquals(1, r.deaths.size)
        assertEquals("fighting, outnumbered", r.deaths[0].cause)
        assertEquals(listOf("fighting, outnumbered" to 1), b.deathCauses())
    }

    @Test fun deathBeforeTheBenchmarkSawTheMatchIsIgnored() {
        val b = PracticeBenchmark()
        b.play(7L, 10_000L, 200, deathAt = 5_000L)                   // an old death time from before
        assertEquals(0, b.matchEnd("Chou", "EXP", 0, 0, 0, 8, 300, true, 1L)!!.deaths.size)
    }

    @Test fun levelAtTenMinutes() {
        val b = PracticeBenchmark()
        val t = b.play(7L, 0L, 600, level = 9)
        b.play(7L, t, 100, level = 13)
        val r = b.matchEnd("Chou", "EXP", 0, 0, 0, 13, 700, true, 1L)!!
        assertEquals(9, r.levelAt5)
        assertEquals(9, r.levelAt10)
        assertEquals(13, r.level)
    }

    @Test fun jsonRoundTripAndCsv() {
        val b = PracticeBenchmark()
        b.play(7L, 0L, 300)
        b.matchEnd("Chou", "EXP", 4, 2, 6, 12, 600, true, 1L)
        val c = PracticeBenchmark().apply { load(b.toJson()) }
        assertEquals(b.all(), c.all())
        val lines = c.csv().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains(",Chou,EXP,true,4,2,6,5.00,"))
    }

    @Test fun trendNeedsTenMatches() {
        val b = PracticeBenchmark()
        for (i in 1..9) { b.play(i.toLong(), 0L, 200); b.matchEnd("Chou", "EXP", 1, 1, 1, 8, 300, i % 2 == 0, i.toLong()) }
        assertTrue(b.text().contains("TREND: needs 10 matches (9 so far)"))
        b.play(10L, 0L, 200); b.matchEnd("Chou", "EXP", 1, 1, 1, 8, 300, true, 10L)
        assertTrue(b.text().contains("TREND (last 5 vs the 5 before)"))
    }

    @Test fun sameMatchIsNeverCountedTwice() {
        val b = PracticeBenchmark()
        b.play(7L, 0L, 300); assertNotNull(b.matchEnd("Chou", "EXP", 0, 0, 0, 8, 300, true, 1L))
        b.play(7L, 0L, 300); assertNull(b.matchEnd("Chou", "EXP", 0, 0, 0, 8, 300, true, 2L))
    }
}
