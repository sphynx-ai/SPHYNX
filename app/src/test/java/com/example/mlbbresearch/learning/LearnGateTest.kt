package com.example.mlbbresearch.learning

import com.example.mlbbresearch.game.MatchKind
import com.example.mlbbresearch.game.MatchKind.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-13: no learning at all in Solo practice; a 5v5 match is learned from by itself
class LearnGateTest {

    @Test fun onlyAFiveVFiveMatchIsLearnedFrom() {
        assertTrue(LearnGate.learns(Kind.FIVE_V_FIVE))
        assertFalse(LearnGate.learns(Kind.SOLO))
        assertFalse(LearnGate.learns(Kind.UNKNOWN))
    }

    @Test fun thePanelSaysWhy() {
        assertTrue(LearnGate.panelTitle(Kind.SOLO, true, false).startsWith("NOT LEARNING: Solo"))
        assertEquals("LEARNING FROM YOU (5v5)", LearnGate.panelTitle(Kind.FIVE_V_FIVE, true, false))
        assertTrue(LearnGate.panelTitle(Kind.FIVE_V_FIVE, true, true).contains("take over"))
        assertTrue(LearnGate.panelTitle(Kind.UNKNOWN, true, false).startsWith("NOT LEARNING YET"))
        assertTrue(LearnGate.panelTitle(Kind.UNKNOWN, false, false).contains("5v5"))
    }

    @Test fun theLoadingScreenTellsTheKind() {
        MatchKind.reset()
        var fired = 0
        MatchKind.onFiveVFive = { fired++ }
        try {
            MatchKind.fromLoading(0L, false)
            assertEquals(Kind.UNKNOWN, MatchKind.of(7L))
            MatchKind.fromLoading(7L, true)
            assertEquals(Kind.SOLO, MatchKind.of(7L))
            MatchKind.fromLoading(7L, false)                 // a later loading frame does not undo Solo
            assertEquals(Kind.SOLO, MatchKind.of(7L))
            assertEquals(0, fired)
            MatchKind.fromLoading(8L, false)
            assertEquals(Kind.FIVE_V_FIVE, MatchKind.of(8L))
            MatchKind.fromLoading(8L, false)
            assertEquals(1, fired)                           // once per match
            MatchKind.set(7L, MatchKind.fromPlayers(10))     // the scoreboard decides
            assertEquals(Kind.FIVE_V_FIVE, MatchKind.of(7L))
        } finally { MatchKind.onFiveVFive = null; MatchKind.reset() }
    }
}
