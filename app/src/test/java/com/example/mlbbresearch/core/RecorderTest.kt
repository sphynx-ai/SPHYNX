package com.example.mlbbresearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecorderTest {

    @Test fun videoSizeIsEvenAndCapped() {
        assertEquals(800 to 360, VideoRecorder.videoSize(800, 360))
        assertEquals(800 to 360, VideoRecorder.videoSize(801, 361))       // odd sizes rounded down to even
        val (w, h) = VideoRecorder.videoSize(2712, 1220)
        assertEquals(1280, w); assertEquals(0, h % 2); assertEquals(574, h)
    }

    @Test fun roundsToThreeDecimals() {
        assertEquals(0.123, SessionRecorder.r3(0.12345f), 1e-9)
        assertEquals(-0.5, SessionRecorder.r3(-0.5f), 1e-9)
    }

    @Test fun idleRecorderRecordsNothing() {
        assertFalse(SessionRecorder.on)
        SessionRecorder.log("INFO", "nothing should happen")
        SessionRecorder.aimEnd("s1", true)
        assertEquals(0, SessionRecorder.rows)
    }

    @Test fun autoStopLimitsFitTheUploadLimits() {
        assertEquals(listOf(25, 95), VideoRecorder.SIZE_CHOICES_MB)
        // stops a little before the limit so the finished file (index included) still fits
        assertEquals(true, VideoRecorder.limitBytes(25) < 25L * 1024 * 1024)
        assertEquals(true, VideoRecorder.limitBytes(95) < 100L * 1000 * 1000)
        assertEquals(true, VideoRecorder.limitBytes(25) > 24L * 1024 * 1024)
    }
}
