package com.sphynx.app

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
}
