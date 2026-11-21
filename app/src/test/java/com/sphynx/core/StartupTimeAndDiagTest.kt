package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTimeAndDiagTest {

    @Test fun startupLine() {
        assertEquals("Start-up: 640 ms from the process start, 210 ms in Application.onCreate", StartupTime.line(640, 210))
        assertTrue(StartupTime.line(2400, 1300).endsWith("(slow: over 1000 ms on the main thread)"))
    }

    // the diagnostics log is checked for size every 50 lines, and trimmed only when it is over 200 KB
    @Test fun diagTrimRule() {
        assertFalse(Diag.needsTrim(Diag.TRIM_EVERY - 1, 500_000))
        assertFalse(Diag.needsTrim(Diag.TRIM_EVERY, Diag.MAX_BYTES))
        assertTrue(Diag.needsTrim(Diag.TRIM_EVERY, Diag.MAX_BYTES + 1))
    }
}
