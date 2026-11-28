package com.sphynx.universal.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 12 C6: the post-match review is built from the match's own events
class MatchReviewTest {

    private fun e(t: MatchContext.Type, at: Long, sec: Int? = null, d: String = "") = MatchContext.Event(t, at, sec, d, "test")

    @Test fun factsAndConfidence() {
        val ev = listOf(e(MatchContext.Type.MATCH_START, 0), e(MatchContext.Type.SELF_DOWN, 60_000, 125),
            e(MatchContext.Type.ENEMY_DOWN, 90_000), e(MatchContext.Type.OBJECTIVE_TAKEN, 100_000, d = "turtle"),
            e(MatchContext.Type.GAP, 200_000), e(MatchContext.Type.MATCH_END, 600_000))
        val f = MatchReview.facts(ev, "Layla", "Gold lane")
        assertEquals("Hero Layla, Gold lane", f[0])
        assertTrue(f[1].startsWith("You went down 1 times; 1 enemies and 0 allies"))
        assertTrue(f.contains("Objectives taken (by either team): turtle"))
        assertTrue(f.contains("You went down at 2:05"))
        assertTrue(f.contains("1 parts of the match were not observed (no evidence for them)"))
        assertTrue(f.contains("Watched for 10 min"))
        assertEquals(MatchReview.Confidence.MEDIUM, MatchReview.confidence(ev))
        assertEquals(MatchReview.Confidence.LOW, MatchReview.confidence(ev.take(2)))
        val p = MatchReview.prompt("Mobile Legends: Bang Bang", f)
        assertTrue(p.startsWith("You coach a Mobile Legends: Bang Bang player after a match. Match: Hero Layla"))
        assertTrue(p.contains("Use only these facts"))
    }
}
