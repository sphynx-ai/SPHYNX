package com.sphynx.game

import org.junit.Assert.assertEquals
import org.junit.Test

// owner, 2026-11-12: a sudden 0% HP reading (the bar covered by effects) is not believed
class HpGuardTest {

    @Test fun aSuddenZeroKeepsTheLastGoodReading() {
        val g = HpGuard()
        assertEquals(0.9f, g.filter(0.9f, 0L, false), 1e-6f)
        assertEquals(0.9f, g.filter(0f, 100L, false), 1e-6f)       // the recording: full bar read as 0%
        assertEquals(0.9f, g.filter(0.01f, 1_000L, false), 1e-6f)
        assertEquals(0f, g.filter(0f, 1_700L, false), 1e-6f)        // 0% for 1.5 s: believed
    }

    @Test fun theDeathScreenIsBelievedAtOnce() {
        val g = HpGuard()
        g.filter(0.9f, 0L, false)
        assertEquals(0f, g.filter(0f, 100L, true), 1e-6f)
    }

    @Test fun lowBeforeZeroIsBelieved() {
        val g = HpGuard()
        g.filter(0.08f, 0L, false)                                  // already almost dead
        assertEquals(0f, g.filter(0f, 100L, false), 1e-6f)
        assertEquals(-1f, g.filter(-1f, 200L, false), 1e-6f)        // no bar: unknown, as before
    }
}
