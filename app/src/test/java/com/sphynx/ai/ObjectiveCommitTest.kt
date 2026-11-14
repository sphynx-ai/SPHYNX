package com.sphynx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-12: commit to the Turtle / Lord rotation; give up after a time limit instead of flip-flopping
class ObjectiveCommitTest {

    @Test fun theRotationGoesFirstUntilDangerOrTimeOut() {
        val c = ObjectiveCommit()
        assertFalse(c.first(0L, false, 1f))                    // no rotation yet
        c.rotating(1_000L)
        assertTrue(c.first(10_000L, inDanger = false, hp = 0.9f))
        assertFalse(c.first(10_000L, inDanger = true, hp = 0.9f))     // enemies near: the usual rules
        assertFalse(c.first(10_000L, inDanger = false, hp = 0.3f))    // low HP: the usual rules
        c.rotating(20_000L)                                    // still the same commitment (the clock is not restarted)
        assertTrue(c.first(40_000L, false, 1f))
        // 45 s without arriving: give up for 60 s
        assertFalse(c.first(47_000L, false, 1f))
        assertTrue(c.gaveUp(50_000L))
        assertFalse(c.gaveUp(108_000L))
    }

    @Test fun arrivingEndsIt() {
        val c = ObjectiveCommit()
        c.rotating(0L)
        c.done()
        assertFalse(c.first(5_000L, false, 1f))
        assertFalse(c.gaveUp(5_000L))
    }
}
