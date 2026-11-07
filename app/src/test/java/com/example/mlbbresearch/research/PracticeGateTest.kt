package com.example.mlbbresearch.research

import com.example.mlbbresearch.research.ScoreboardNameCheck.Result
import com.example.mlbbresearch.research.ScoreboardNameCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeGateTest {

    private fun r(v: Verdict) = Result(v, 0, emptyList(), emptyList(), v.name)

    @Test fun startsBlocked() {
        val g = PracticeGate(); g.newMatch(1L)
        assertEquals(PracticeGate.State.UNVERIFIED, g.state)
        assertFalse(g.allowsAutonomy())
    }

    @Test fun unreadableNeverUnlocks() {
        val g = PracticeGate(); g.newMatch(1L)
        repeat(5) { g.observe(r(Verdict.UNREADABLE)) }
        assertFalse(g.allowsAutonomy())
    }

    @Test fun allComputerUnlocks() {
        val g = PracticeGate(); g.newMatch(1L)
        g.observe(r(Verdict.ALL_COMPUTER))
        assertTrue(g.allowsAutonomy())
    }

    @Test fun humanRevokesForTheRestOfTheMatch() {
        val g = PracticeGate(); g.newMatch(1L)
        g.observe(r(Verdict.ALL_COMPUTER))
        g.observe(r(Verdict.HUMAN_PRESENT))
        assertEquals(PracticeGate.State.REVOKED, g.state)
        g.observe(r(Verdict.ALL_COMPUTER))                 // sticky: cannot be re-verified
        assertFalse(g.allowsAutonomy())
    }

    @Test fun humanBeforeVerificationAlsoRevokes() {
        val g = PracticeGate(); g.newMatch(1L)
        g.observe(r(Verdict.HUMAN_PRESENT)); g.observe(r(Verdict.ALL_COMPUTER))
        assertFalse(g.allowsAutonomy())
    }

    @Test fun newMatchResetsToBlocked() {
        val g = PracticeGate(); g.newMatch(1L)
        g.observe(r(Verdict.ALL_COMPUTER))
        g.newMatch(2L)
        assertFalse(g.allowsAutonomy())
        assertEquals(2L, g.matchId)
    }

    @Test fun noOverrideExists() {
        // the gate has no public way to set its state other than observe() / newMatch()
        val setters = PracticeGate::class.java.methods.map { it.name }.filter { it.startsWith("set") }
        assertTrue("unexpected setters: $setters", setters.isEmpty())
    }
}
