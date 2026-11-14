package com.sphynx.controller

import org.junit.Assert.assertEquals
import org.junit.Test

// owner, 2026-11-12: the touch queue (~240 ms) came from 320 ms joystick slices
class JoyDurationTest {

    @Test fun aHeldJoystickContinuesInShortSlices() {
        assertEquals(CastTiming.JOY_SLICE_MS, CastTiming.joyDuration(held = true, joyMs = 320L, otherMs = 0L))
        // a skill pressed in the same gesture keeps the stroke down as long as it needs
        assertEquals(250L, CastTiming.joyDuration(held = true, joyMs = 320L, otherMs = 250L))
    }

    @Test fun theFirstPressIsLongEnoughToRegister() {
        assertEquals(CastTiming.JOY_HOLD_MS, CastTiming.joyDuration(held = false, joyMs = 100L, otherMs = 0L))
        assertEquals(400L, CastTiming.joyDuration(held = false, joyMs = 400L, otherMs = 0L))
    }
}
