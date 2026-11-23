package com.sphynx.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 8 G11 (owner, 2026-11-23): the tactical model looks at the minimap now and then, never during a fight
class TacticalSightTest {

    @Test fun looksEveryFewSecondsOutsideFights() {
        assertTrue(TacticalSight.shouldLook(now = 10_000, lastLookAt = 4_000, inFight = false, busy = false, canSee = true))
        assertFalse(TacticalSight.shouldLook(now = 10_000, lastLookAt = 6_000, inFight = false, busy = false, canSee = true))
    }

    @Test fun neverInAFightOrWhileTheModelIsBusyOrBlind() {
        assertFalse(TacticalSight.shouldLook(100_000, 0, inFight = true, busy = false, canSee = true))
        assertFalse(TacticalSight.shouldLook(100_000, 0, inFight = false, busy = true, canSee = true))
        assertFalse(TacticalSight.shouldLook(100_000, 0, inFight = false, busy = false, canSee = false))
    }

    @Test fun aFreshReadingGoesIntoTheFightQuestion() {
        assertEquals("\nminimap picture (4 s ago): three enemies grouped near the turtle",
            TacticalSight.note(now = 14_000, reading = "three enemies grouped near the turtle\n", readingAt = 10_000))
    }

    @Test fun anOldOrEmptyReadingIsLeftOut() {
        assertEquals("", TacticalSight.note(now = 30_000, reading = "x", readingAt = 10_000))
        assertEquals("", TacticalSight.note(now = 11_000, reading = " ", readingAt = 10_000))
        assertEquals("", TacticalSight.note(now = 11_000, reading = "x", readingAt = 0))
    }

    @Test fun theMinimapCropStaysInsideTheFrame() {
        assertArrayEquals(intArrayOf(0, 0, 152, 133), TacticalSight.cropRect(800, 360, floatArrayOf(0f, 0f, 0.19f, 0.37f)))
        assertArrayEquals(intArrayOf(799, 359, 1, 1), TacticalSight.cropRect(800, 360, floatArrayOf(1.2f, 1.5f, 1.3f, 2f)))
    }
}
