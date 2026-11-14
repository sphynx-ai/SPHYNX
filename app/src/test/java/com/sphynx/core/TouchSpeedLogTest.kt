package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSpeedLogTest {

    @Test fun aRowEveryFiveSecondsOnlyWhileThePlayerDecides() {
        val l = TouchSpeedLog()
        assertNull(l.flush(1_000, 30f, 15f))                   // starts the first window
        l.reading(40); l.touchQueue(10)                         // no decisions: the Player does not play
        assertNull(l.flush(6_000, 30f, 15f))
        assertEquals(0, l.all().size)
        for (ms in listOf(60L, 80L, 100L, 120L, 400L)) l.frameToDecision(ms)
        l.touchQueue(5); l.touchQueue(15); l.reading(30); l.decided(20, 8)
        assertNull(l.flush(9_000, 30f, 15f))                    // window not 5 s old yet
        val r = l.flush(11_000, 30f, 14f, PhoneState.Reading(1, 36.5f, 3000))
        assertNotNull(r)
        r!!
        assertEquals(5, r.samples)
        assertEquals(100.0, r.f2dMedian, 1e-9)
        assertEquals(400.0, r.f2dP90, 1e-9)
        assertEquals(400.0, r.f2dMax, 1e-9)
        assertEquals(10.0, r.queueMedian!!, 1e-9)
        assertEquals(110.0, r.totalMedian, 1e-9)
        assertEquals(1, r.thermal)
    }

    @Test fun summaryAndCsv() {
        val l = TouchSpeedLog()
        l.flush(0 + 1, null, null)
        l.frameToDecision(90); l.touchQueue(20); l.reading(35); l.decided(25, 10)
        l.flush(6_001, 28f, 13f)
        val t = l.text()
        assertTrue(t.startsWith("1 periods of 5 s, 1 decisions\nScreen to touch, about: 110 ms"))
        assertTrue(t.contains("reading the screen 35 ms, waiting 25 ms, deciding 10 ms"))
        val lines = l.csv().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("6001,1,110.0,90.0,90.0,90.0,20.0,20.0,35.0,25.0,10.0,28.0,13.0,"))
        val back = TouchSpeedLog().apply { loadJsonLines(l.toJsonLines()) }
        assertEquals(l.all(), back.all())
    }

    @Test fun nothingRecordedYetIsExplained() {
        assertTrue(TouchSpeedLog().text().startsWith("No screen-to-touch data yet"))
    }
}
