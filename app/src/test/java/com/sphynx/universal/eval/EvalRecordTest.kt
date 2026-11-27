package com.sphynx.universal.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 10 E1: the run record, its comparability rule and its file
class EvalRecordTest {

    private val good = EvalRecord.Conditions(inMatch = false, captureOn = false, batteryPct = 80, charging = false, thermalStatus = 0, batteryC = 31f)
    private val run = EvalRecord.RunInfo("r1", "mlbb-tactical", 1, "tactical", "a".repeat(64), "gemma.litertlm", "LITERT_LM", null,
        "LITERT_LM", "GPU", "Xiaomi 2201117TG", "Android 14", "38.44", "litertlm 0.19.1", mapOf("temperature" to "0"), 3, good, 1000)

    @Test fun conditions() {
        assertTrue(good.ok)
        assertEquals(listOf("a match is running"), good.copy(inMatch = true).problems())
        assertTrue(good.copy(batteryPct = 20).problems().single().contains("battery"))
        assertTrue(good.copy(batteryPct = 20, charging = true).ok)
        assertTrue(good.copy(thermalStatus = 2).problems().single().contains("warm"))
        assertTrue(good.copy(thermalStatus = 1).ok)
    }

    @Test fun comparability() {
        assertNull(EvalRecord.whyNotComparable(run, run.copy(runId = "r2", appVersion = "38.50", startedAt = 5)))
        assertEquals(EvalRecord.comparabilityKey(run), EvalRecord.comparabilityKey(run.copy(appVersion = "39.00")))
        assertEquals("different processor", EvalRecord.whyNotComparable(run, run.copy(processor = "CPU")))
        assertEquals("different evaluation set, model settings",
            EvalRecord.whyNotComparable(run, run.copy(setVersion = 2, settings = emptyMap())))
        assertTrue(EvalRecord.comparabilityKey(run) != EvalRecord.comparabilityKey(run.copy(kind = EvalRecord.KIND_SUSTAINED)))
    }

    @Test fun theFileKeepsEverything() {
        val s = EvalRecord.Sample("t01", 0, true, 5, 120, 300, 900, 40, 1800, 0, 31.5f, 80, "RETREAT", 1.0, "best")
        val s2 = s.copy(itemId = "t02", score = null, verdict = null, error = "timeout", batteryC = null, batteryPct = null)
        val lines = EvalRecord.lines(run, listOf(s, s2)) + "not json" + """{"sample":{"broken":1},"runId":"r1"}"""
        val back = EvalRecord.read(lines).single()
        assertEquals(run, back.first)
        assertEquals(listOf(s, s2), back.second)
        assertNotNull(EvalRecord.run(EvalRecord.toJson(run)))
        assertNull(EvalRecord.run(EvalRecord.toJson(run).put("schema", 99)))
    }
}
