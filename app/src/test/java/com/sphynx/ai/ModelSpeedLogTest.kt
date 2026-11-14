package com.sphynx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSpeedLogTest {

    private fun call(slot: String, ms: Long, backend: String = "GPU", image: Boolean = false, ok: Boolean = true, cold: Boolean = false,
                     dtps: Double? = null, ttft: Long? = null, purpose: String = "plan") =
        ModelSpeedLog.Row("call", 1000L, slot, if (slot == "strategic") "Gemma 4 E2B" else "Gemma 3 270M", backend, ms, ok,
            if (ok) "" else "boom", purpose, image, 120, 40, cold, ttft, if (dtps != null) 20 else null, if (dtps != null) 300.0 else null,
            if (dtps != null) 30 else null, dtps)

    @Test fun medianAndPercentile() {
        assertEquals(2.0, ModelSpeedLog.median(listOf(3.0, 1.0, 2.0))!!, 1e-9)
        assertEquals(2.5, ModelSpeedLog.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 1e-9)
        assertEquals(9.0, ModelSpeedLog.percentile((1..10).map { it.toDouble() }, 90)!!, 1e-9)
        assertNull(ModelSpeedLog.median(emptyList()))
    }

    @Test fun groupsSplitByEngineBackendAndImageAndLeaveColdAndFailedCallsOutOfTheTimes() {
        val l = ModelSpeedLog()
        l.add(call("strategic", 9000, cold = true))          // first call after loading: slow, not in the times
        l.add(call("strategic", 2000)); l.add(call("strategic", 3000)); l.add(call("strategic", 4000))
        l.add(call("strategic", 1000, ok = false))            // failed: counted, not timed
        l.add(call("strategic", 5000, image = true))
        l.add(call("tactical", 300, backend = "CPU, text only", dtps = 42.0, ttft = 80))
        val g = l.groups()
        assertEquals(3, g.size)
        val text = g.first { it.slot == "strategic" && !it.image }
        assertEquals(5, text.calls); assertEquals(1, text.failed)
        assertEquals(3000.0, text.medianMs!!, 1e-9)
        assertEquals(4000.0, text.p90Ms!!, 1e-9)
        val tac = g.first { it.slot == "tactical" }
        assertEquals(42.0, tac.medianDecodeTps!!, 1e-9); assertEquals(80.0, tac.medianTtftMs!!, 1e-9)
    }

    @Test fun summaryNamesBothEnginesAndTheLastLoad() {
        val l = ModelSpeedLog()
        l.add(ModelSpeedLog.Row("load", 1L, "strategic", "Gemma 4 E2B", "GPU", 6400, true, detailed = true, attempts = 1))
        l.add(call("strategic", 2000))
        val t = l.text()
        assertTrue(t.contains("STRATEGIST\n  Gemma 4 E2B · GPU: 1 calls, median 2.00 s"))
        assertTrue(t.contains("last load: GPU in 6.40 s, detailed on"))
        assertTrue(t.contains("TACTICAL\n  no calls yet"))
    }

    @Test fun jsonLinesRoundTripAndCsv() {
        val l = ModelSpeedLog()
        l.add(call("tactical", 300, backend = "CPU, text only", dtps = 42.0, ttft = 80, purpose = "fight advice"))
        l.add(call("strategic", 2000, ok = false).copy(error = "out of memory, retry", thermal = 2, batteryC = 38.5f, ramAvailMb = 2048))
        val back = ModelSpeedLog().apply { loadJsonLines(l.toJsonLines()) }
        assertEquals(l.all(), back.all())
        val lines = back.csv().trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains(",call,tactical,fight advice,Gemma 3 270M,\"CPU, text only\",false,false,true,300,80,20,300.00,30,42.00,"))
        assertTrue(lines[2].endsWith(",2,38.5,2048,\"out of memory, retry\""))
    }

    @Test fun oldestRowsAreDroppedAtTheLimit() {
        val l = ModelSpeedLog(maxRows = 3)
        for (i in 1..5) l.add(call("tactical", i.toLong()))
        assertEquals(listOf(3L, 4L, 5L), l.all().map { it.ms })
    }
}
