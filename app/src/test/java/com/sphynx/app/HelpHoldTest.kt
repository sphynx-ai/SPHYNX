package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpHoldTest {

    @Test fun aWrittenOnceExplanationIsADescription() {
        assertTrue(HelpHold.isDescription("Buys only when no enemy is close. Calibrate the buttons on Setup.", bold = false, describe = true))
    }

    @Test fun emptyBoldAndMarkedLinesAreNot() {
        // created empty: a live status line the page fills in
        assertFalse(HelpHold.isDescription("", bold = false, describe = true))
        assertFalse(HelpHold.isDescription("   ", bold = false, describe = true))
        assertFalse(HelpHold.isDescription(null, bold = false, describe = true))
        // bold: a heading or an emphasis
        assertFalse(HelpHold.isDescription("Six short steps", bold = true, describe = true))
        // field labels and the app version are marked as not a description
        assertFalse(HelpHold.isDescription("My hero (Enter = absolute)", bold = false, describe = false))
    }

    @Test fun aRewrittenLineIsALiveLine() {
        assertTrue(HelpHold.staysDescription("Tap a training.", "Tap a training."))
        assertFalse(HelpHold.staysDescription("Loading...", "Gemma loaded on the GPU"))
    }

    @Test fun aControlsLinesMakeOneBubble() {
        assertEquals("Shows boxes on the game.\n\nOnly while capturing.",
            HelpHold.bubbleText(listOf("  Shows boxes on the game. ", "", "Only while capturing.")))
        assertEquals("", HelpHold.bubbleText(emptyList()))
    }

    @Test fun theScreenReaderHearsTheFirstSentence() {
        assertEquals("Help: Buys only when no enemy is close.",
            HelpHold.spokenLabel(listOf("Buys only when no enemy is close. Calibrate the buttons on Setup.")))
        val long = "x".repeat(120)
        assertTrue(HelpHold.spokenLabel(listOf(long)).endsWith("…"))
        assertTrue(HelpHold.spokenLabel(listOf(long)).length <= 6 + 80)
    }

    // owner, 2026-11-15: the long live lines (practice stats, the match timeline) go behind a "?" too
    @Test fun longInformationLinesGoBehindTheHelp() {
        assertTrue(HelpHold.isLongInfo("x".repeat(HelpHold.LONG_CHARS + 1)))
        assertTrue(HelpHold.isLongInfo("Practice moments: 0\nReadiness test: not run yet\nArbiter: no matches"))
        assertFalse(HelpHold.isLongInfo("Screen: not captured · 0 fps · frame→touch 0 ms"))
        assertFalse(HelpHold.isLongInfo("Match: Standard (detected by itself) · the power button on the floating icon turns the AI on or off in game"))
        assertFalse(HelpHold.isLongInfo("two\nlines"))
        assertFalse(HelpHold.isLongInfo(""))
        assertFalse(HelpHold.isLongInfo(null))
    }
}
