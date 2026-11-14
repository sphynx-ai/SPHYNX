package com.sphynx.controller

import com.sphynx.vision.ScreenHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayQuietTest {

    @Test fun hiddenOnLoadingAndScoreboardOnly() {
        for (h in ScreenHint.values()) assertEquals(h.name, h == ScreenHint.LOADING || h == ScreenHint.SCOREBOARD, OverlayQuiet.quietScreen(h))
    }

    @Test fun hidesAtOnceAndComesBackAfterTheHold() {
        val q = OverlayQuiet(showAfterMs = 600L)
        assertFalse(q.update(ScreenHint.NONE, 1000L, 1000L))
        assertTrue(q.update(ScreenHint.SCOREBOARD, 1100L, 1100L)); assertTrue(q.hidden)
        assertEquals(400L, q.hiddenFor(1500L))
        assertFalse(q.update(ScreenHint.NONE, 1500L, 1500L)); assertTrue(q.hidden)   // one frame is not enough
        assertFalse(q.update(ScreenHint.SCOREBOARD, 1600L, 1600L))
        assertFalse(q.update(ScreenHint.NONE, 1700L, 1700L))
        assertTrue(q.update(ScreenHint.NONE, 2300L, 2300L)); assertFalse(q.hidden)
        assertEquals(-1L, q.hiddenFor(2400L))
    }

    @Test fun staleOrMissingFramesShowTheOverlay() {
        val q = OverlayQuiet(showAfterMs = 0L, staleMs = 1500L)
        q.update(ScreenHint.LOADING, 0L, 0L)
        assertTrue(q.hidden)
        q.update(ScreenHint.LOADING, 0L, 5000L)          // the AI stopped reading the screen
        assertFalse(q.hidden)
        assertFalse(q.update(null, 0L, 6000L))
    }
}
