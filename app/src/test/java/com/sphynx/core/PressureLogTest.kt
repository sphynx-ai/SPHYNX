package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureLogTest {

    // ComponentCallbacks2.TRIM_MEMORY_* numbers must never drift
    @Test fun trimNumbersMatchAndroid() {
        assertEquals(listOf(5, 10, 15, 20, 40, 60, 80), listOf(PressureLog.RUNNING_MODERATE, PressureLog.RUNNING_LOW,
            PressureLog.RUNNING_CRITICAL, PressureLog.UI_HIDDEN, PressureLog.BACKGROUND, PressureLog.MODERATE, PressureLog.COMPLETE))
    }

    @Test fun switchingToTheGameIsNotLogged() {
        assertFalse(PressureLog.shouldLogTrim(PressureLog.UI_HIDDEN, 0, 0, 1_000_000))
    }

    @Test fun worseAtOnceSameAtMostOncePerMinute() {
        val t = 1_000_000L
        assertTrue(PressureLog.shouldLogTrim(PressureLog.RUNNING_LOW, PressureLog.RUNNING_MODERATE, t, t + 1))
        assertFalse(PressureLog.shouldLogTrim(PressureLog.RUNNING_LOW, PressureLog.RUNNING_LOW, t, t + 59_999))
        assertTrue(PressureLog.shouldLogTrim(PressureLog.RUNNING_LOW, PressureLog.RUNNING_LOW, t, t + 60_000))
        assertFalse(PressureLog.shouldLogTrim(PressureLog.RUNNING_MODERATE, PressureLog.COMPLETE, t, t + 10_000))
    }

    @Test fun seriousLevels() {
        assertTrue(PressureLog.isSerious(PressureLog.RUNNING_CRITICAL))
        assertTrue(PressureLog.isSerious(PressureLog.COMPLETE))
        assertFalse(PressureLog.isSerious(PressureLog.RUNNING_MODERATE))
        assertFalse(PressureLog.isSerious(PressureLog.BACKGROUND))
    }

    @Test fun thermalWords() {
        assertEquals("no throttling", PressureLog.thermalName(0))
        assertEquals("severe throttling", PressureLog.thermalName(3))
        assertEquals("not reported", PressureLog.thermalName(-1))
    }
}
