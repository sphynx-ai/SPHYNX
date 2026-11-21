package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceBaselineTest {

    private fun r(capture: Boolean = false, strategic: Boolean = false, tactical: Boolean = false, gguf: Boolean = false) =
        ResourceBaseline.Reading(1_000L, capture, strategic, tactical, gguf, 11_500, 4_200, false, 610, 120, 380, 12.4f,
            "No thermal throttling", 0.31f, 33.5f, 78, false)

    // the four baselines of the Phase 5 acceptance criterion
    @Test fun theStateFollowsWhatIsLoaded() {
        assertEquals("idle", ResourceBaseline.state(false, false, false, false))
        assertEquals("capture on", ResourceBaseline.state(true, false, false, false))
        assertEquals("capture on + strategic model", ResourceBaseline.state(true, true, false, false))
        assertEquals("GGUF model", ResourceBaseline.state(false, false, false, true))
    }

    @Test fun theFileGoesToRecordings() {
        val n = ResourceBaseline.fileName("20261121-160000")
        assertEquals("diagnostics-baseline-20261121-160000.json", n)
        assertEquals(StorageLayout.RECORDINGS, StorageLayout.areaFor(n))
    }

    @Test fun jsonCarriesEveryMeasurement() {
        val j = ResourceBaseline.toJson(r(capture = true, gguf = true), "Xiaomi POCO X6 Pro", "37.91")
        assertEquals("capture on + GGUF model", j.getString("state"))
        assertEquals(4_200L, j.getLong("phoneRamAvailMb"))
        assertEquals(610L, j.getLong("appRamMb"))
        assertTrue(j.getJSONObject("loaded").getBoolean("gguf"))
        assertEquals(78, j.getInt("batteryPct"))
        assertEquals("37.91", j.getString("app"))
        val missing = ResourceBaseline.toJson(r().copy(batteryC = null, thermalHeadroom = null), null, "x")
        assertTrue(missing.isNull("batteryC") && missing.isNull("thermalHeadroom"))
        assertEquals("not read yet", missing.getString("device"))
    }

    @Test fun summary() {
        assertEquals("Snapshot (idle): phone 4200 MB free of 11500 MB, app 610 MB, CPU 12%, No thermal throttling",
            ResourceBaseline.summary(r()))
    }
}
