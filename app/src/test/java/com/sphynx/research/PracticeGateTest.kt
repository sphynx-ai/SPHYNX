package com.sphynx.research

import com.sphynx.research.ScoreboardNameCheck.Result
import com.sphynx.research.ScoreboardNameCheck.Verdict
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
        g.observe(r(Verdict.HUMAN_PRESENT)); g.observe(r(Verdict.HUMAN_PRESENT))
        assertEquals(PracticeGate.State.REVOKED, g.state)
        g.observe(r(Verdict.ALL_COMPUTER))                 // sticky: cannot be re-verified
        assertFalse(g.allowsAutonomy())
    }

    @Test fun humanBeforeVerificationAlsoRevokes() {
        val g = PracticeGate(); g.newMatch(1L)
        g.observe(r(Verdict.HUMAN_PRESENT)); g.observe(r(Verdict.HUMAN_PRESENT)); g.observe(r(Verdict.ALL_COMPUTER))
        assertFalse(g.allowsAutonomy())
    }

    // device test 2026-11-10: one frame with the app's boxes over the names locked a practice match
    @Test fun oneHumanReadingDoesNotLock() {
        val g = PracticeGate(); g.newMatch(1L)
        g.observe(r(Verdict.HUMAN_PRESENT))
        assertEquals(PracticeGate.State.UNVERIFIED, g.state)
        g.observe(r(Verdict.ALL_COMPUTER))
        assertTrue(g.allowsAutonomy())
        g.observe(r(Verdict.HUMAN_PRESENT)); g.observe(r(Verdict.ALL_COMPUTER)); g.observe(r(Verdict.HUMAN_PRESENT))
        assertTrue(g.allowsAutonomy())                     // never two in a row
    }

    @Test fun loadingScreenVerifiesButNeverLocks() {
        val g = PracticeGate(); g.newMatch(1L)
        g.observeLoading(LoadingNameCheck.Result(LoadingNameCheck.Verdict.NOT_PROVEN, "x"))
        assertEquals(PracticeGate.State.UNVERIFIED, g.state)
        g.observeLoading(LoadingNameCheck.Result(LoadingNameCheck.Verdict.ALL_COMPUTER, "x"))
        assertTrue(g.allowsAutonomy())
        g.observe(r(Verdict.HUMAN_PRESENT)); g.observe(r(Verdict.HUMAN_PRESENT))
        assertEquals(PracticeGate.State.REVOKED, g.state)  // the scoreboard still catches a real player
        g.observeLoading(LoadingNameCheck.Result(LoadingNameCheck.Verdict.ALL_COMPUTER, "x"))
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
