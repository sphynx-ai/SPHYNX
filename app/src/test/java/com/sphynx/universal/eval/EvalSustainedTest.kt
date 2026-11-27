package com.sphynx.universal.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 10 E8: the sustained heat and battery run
class EvalSustainedTest {

    // one answer every 2 s for [min] minutes; it slows from 400 to 600 ms, the battery falls 1% a minute
    private fun run(min: Int, hotAt: Int = Int.MAX_VALUE) = (0 until min * 30).map { i ->
        val at = i * 2000L
        val m = (at / 60_000).toInt()
        EvalRecord.Sample("t${i % 40}", i / 40, false, at, 100, 400L + m * 200L / maxOf(1, min - 1), 500, 7, 1500,
            if (m >= hotAt) 3 else minOf(m / 4, 2), 30f + m * 0.5f, 90 - m, "RETREAT", 1.0, "BEST")
    }

    @Test fun throttlingBatteryAndHeat() {
        val r = EvalSustained.analyse(run(10), charging = false)
        assertEquals(10, r.minutes.size); assertEquals(300, r.answers)
        assertEquals(0.5, r.slowdown!!, 1e-9)                    // 400 ms -> 600 ms
        assertTrue(r.batteryPerMinute!! in 0.9..1.1)
        assertEquals(4.5f, r.batteryRiseC!!, 1e-4f)
        assertEquals(2, r.maxThermal)
        val t = r.text()
        assertTrue(t, t.contains("usable answer slower by 50%"))
        assertTrue(t, t.contains("battery 0.90% per minute"))
    }

    @Test fun chargingAndHeatStop() {
        val c = EvalSustained.analyse(run(3), charging = true)
        assertNull(c.batteryPerMinute)
        assertTrue(c.text().contains("the phone was charging"))
        val h = EvalSustained.analyse(run(6, hotAt = 5), charging = false)
        assertTrue(h.stoppedHot); assertTrue(h.text().contains("stopped early"))
        assertEquals(0, EvalSustained.analyse(emptyList(), false).answers)
    }
}
