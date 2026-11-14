package com.sphynx.research

import com.sphynx.research.ScoreboardNameCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

// The scoreboard decision on row scores (no pictures). Scores are the ones measured on the owner's
// 2026-11-10 screenshots: the 5v5 practice scoreboard and the solo practice one (you vs Zilong "Computer").
class ScoreboardDecideTest {

    private val all = List(5) { true }
    private val first = listOf(true, false, false, false, false)

    @Test fun soloPracticeVerifies() {
        val r = ScoreboardNameCheck.decide(0, listOf(0.21f, 0.10f, 0.30f, 0.10f, 0.08f), listOf(0.94f, 0.10f, 0.18f, 0.16f, 0.12f), first, first)
        assertEquals(r.toString(), Verdict.ALL_COMPUTER, r.verdict)
    }

    @Test fun soloEmptyRowsWouldHaveLockedWithoutTheGoldCheck() {
        val r = ScoreboardNameCheck.decide(0, listOf(0.21f, 0.10f, 0.30f, 0.10f, 0.08f), listOf(0.94f, 0.10f, 0.18f, 0.16f, 0.12f), all, all)
        assertEquals(Verdict.HUMAN_PRESENT, r.verdict)
    }

    @Test fun soloAgainstAHumanIsDetected() {
        val r = ScoreboardNameCheck.decide(0, listOf(0.21f, 0f, 0f, 0f, 0f), listOf(0.35f, 0f, 0f, 0f, 0f), first, first)
        assertEquals(Verdict.HUMAN_PRESENT, r.verdict)
    }

    @Test fun fullPracticeVerifies() {
        val r = ScoreboardNameCheck.decide(0, listOf(0.16f, 1.0f, 0.96f, 0.97f, 0.92f), listOf(0.94f, 0.93f, 0.95f, 0.93f, 0.90f), all, all)
        assertEquals(Verdict.ALL_COMPUTER, r.verdict)
    }

    // the device-test frame: the app's "3%" / "9%" labels over two enemy names (0.54 / 0.52)
    @Test fun namesCoveredByTheOverlayReadAsHuman() {
        val r = ScoreboardNameCheck.decide(0, listOf(0.16f, 1.0f, 0.96f, 0.97f, 0.92f), listOf(0.94f, 0.54f, 0.52f, 0.93f, 0.90f), all, all)
        assertEquals(Verdict.HUMAN_PRESENT, r.verdict)
    }

    @Test fun noOtherPlayerFoundIsUnreadable() {
        val none = List(5) { false }
        assertEquals(Verdict.UNREADABLE, ScoreboardNameCheck.decide(0, List(5) { 0f }, List(5) { 0f }, first, none).verdict)
        assertEquals(Verdict.UNREADABLE, ScoreboardNameCheck.decide(-1, List(5) { 1f }, List(5) { 1f }, all, all).verdict)
    }
}
