package com.example.mlbbresearch.research

import com.example.mlbbresearch.research.ScoreboardNameCheck.Result
import com.example.mlbbresearch.research.ScoreboardNameCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchSessionTest {

    private fun r(v: Verdict) = Result(v, 0, List(5) { 0.95f }, List(5) { 0.95f }, v.name)

    @Test fun verifiesOnlyTheMatchItSaw() {
        val s = ResearchSession()
        s.observe(100L, r(Verdict.ALL_COMPUTER), 1L)
        assertTrue(s.allowsAutonomy(100L))
        assertFalse(s.allowsAutonomy(101L))            // a different match is never covered
    }

    @Test fun newMatchRelocks() {
        val s = ResearchSession()
        s.observe(100L, r(Verdict.ALL_COMPUTER), 1L)
        s.sync(200L, 2L)
        assertFalse(s.allowsAutonomy(200L))
        assertEquals(PracticeGate.State.UNVERIFIED, s.gate.state)
    }

    @Test fun unidentifiedMatchNeverVerifies() {
        val s = ResearchSession()
        repeat(3) { s.observe(0L, r(Verdict.ALL_COMPUTER), it.toLong()) }
        assertFalse(s.allowsAutonomy(0L))
        assertEquals(0, s.readings)
        assertTrue(s.status(true).contains("not identified"))
    }

    @Test fun humanLocksTheMatchAndIsLogged() {
        val s = ResearchSession()
        s.observe(100L, r(Verdict.ALL_COMPUTER), 1L)
        s.observe(100L, r(Verdict.HUMAN_PRESENT), 2L)
        s.observe(100L, r(Verdict.ALL_COMPUTER), 3L)
        assertFalse(s.allowsAutonomy(100L))
        assertTrue(s.status(true).startsWith("Research: LOCKED"))
        val events = s.recent().map { it.event }
        assertTrue(events.toString(), events.any { it.startsWith("VERIFIED") } && events.any { it.startsWith("REVOKED") })
    }

    @Test fun statusTexts() {
        val s = ResearchSession()
        assertEquals("Research: off", s.status(false))
        s.sync(5L, 1L)
        assertTrue(s.status(true).contains("open the scoreboard"))
        s.observe(5L, r(Verdict.ALL_COMPUTER), 2L)
        assertTrue(s.status(true).startsWith("Research: verified practice"))
    }

    @Test fun logIsBounded() {
        val s = ResearchSession(maxLog = 3)
        for (id in 1L..10L) s.sync(id, id)
        assertEquals(3, s.recent().size)
    }
}
