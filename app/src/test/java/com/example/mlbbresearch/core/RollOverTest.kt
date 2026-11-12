package com.example.mlbbresearch.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-12: a new file at the limit only when the automatic upload is possible
class RollOverTest {
    @Test fun onlyWithTheAutomaticUploadAndAToken() {
        assertTrue(SessionRecorder.rollsOver(autoUpload = true, hasToken = true))
        assertFalse(SessionRecorder.rollsOver(autoUpload = true, hasToken = false))
        assertFalse(SessionRecorder.rollsOver(autoUpload = false, hasToken = true))
    }
}
