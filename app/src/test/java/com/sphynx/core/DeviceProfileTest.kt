package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    private fun facts(
        android: Int = 34, abis: List<String> = listOf("arm64-v8a"), ramMb: Long = 11_500, gguf: String? = null,
        gpu: String? = "Mali-G615 MC6 (OpenGL ES 3.2)", acc: List<String> = listOf("MediaTek NeuroPilot (Neuron)")
    ) = DeviceProfile.Facts("Xiaomi POCO X6 Pro", "Mediatek MT6897", android, abis, ramMb, 8, 3.35f, gpu, acc, gguf)

    @Test fun memoryAgainstTheApprovedRequirements() {
        // phones report less than the advertised size: a "6 GB" phone (~5.6 GB) is the minimum, a "8 GB" one (~7.5 GB) recommended
        assertEquals(false, DeviceProfile.memoryCheck(3_700).ok)
        assertEquals(true, DeviceProfile.memoryCheck(5_700).ok)
        assertTrue(DeviceProfile.memoryCheck(5_700).detail.contains("minimum"))
        assertTrue(DeviceProfile.memoryCheck(7_600).detail.contains("recommended"))
        assertNull(DeviceProfile.memoryCheck(0).ok)
        assertEquals("5.6 GB: the minimum (Lite use only, not full autonomy)", DeviceProfile.memoryCheck(5_734).detail)
    }

    @Test fun aSupportedPhonePassesEveryHardCheck() {
        val f = facts()
        assertTrue(DeviceProfile.supported(f))
        val byName = DeviceProfile.checks(f).associateBy { it.name }
        assertEquals(true, byName.getValue("GGUF engine (CPU)").ok)
        // presence of a GPU / NPU driver is reported, never claimed as support
        assertNull(byName.getValue("GPU").ok)
        assertNull(byName.getValue("NPU / DSP drivers").ok)
    }

    @Test fun hardFailuresAreNotSupported() {
        assertFalse(DeviceProfile.supported(facts(abis = listOf("armeabi-v7a"))))
        assertFalse(DeviceProfile.supported(facts(ramMb = 3_800)))
        assertFalse(DeviceProfile.supported(facts(android = 29)))
    }

    @Test fun ggufRefusalIsAFailedCheckButNotAHardOne() {
        val f = facts(gguf = "this phone's processor lacks dot product (asimddp)")
        val g = DeviceProfile.checks(f).first { it.name == "GGUF engine (CPU)" }
        assertEquals(false, g.ok)
        assertEquals("this phone's processor lacks dot product (asimddp)", g.detail)
        assertTrue(DeviceProfile.supported(f))   // the app still runs; only the GGUF engine is refused
    }

    @Test fun summaryIsOneReadableLine() {
        val s = DeviceProfile.summary(facts())
        assertEquals("Xiaomi POCO X6 Pro · Mediatek MT6897 · Android API 34 · arm64-v8a · RAM 11.2 GB · 8 cores up to 3.35 GHz" +
            " · GPU Mali-G615 MC6 (OpenGL ES 3.2) · GGUF yes · NPU/DSP drivers 1", s)
        assertFalse(s.contains("\n"))
        assertTrue(DeviceProfile.summary(facts(gpu = null, acc = emptyList(), gguf = "x")).endsWith("GPU ? · GGUF no"))
    }
}
