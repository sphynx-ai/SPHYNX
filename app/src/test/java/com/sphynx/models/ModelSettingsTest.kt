package com.sphynx.models

import com.sphynx.inference.Accelerator
import com.sphynx.inference.BackendType
import com.sphynx.inference.ModelConfiguration
import com.sphynx.models.ModelRecord.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 9 M4: per-model settings with safe limits; none set = as before
class ModelSettingsTest {

    private val base = ModelConfiguration(id = "gguf:x", displayName = "x.gguf", backend = BackendType.LLAMA_CPP, location = "/m/x.gguf",
        accelerators = listOf(Accelerator.CPU), contextTokens = 2048, maxOutputTokens = 512, threads = 4)

    @Test fun nothingSetChangesNothing() {
        assertEquals(base, ModelSettings.apply(base, emptyMap()))
        assertEquals(0.2, ModelSettings.temperature(emptyMap(), 0.2), 0.0)
    }

    @Test fun settingsAreApplied() {
        val c = ModelSettings.apply(base, mapOf("context" to "4096", "threads" to "2", "kv8" to "true", "processors" to "GPU", "pictures" to "false"))
        assertEquals(4096, c.contextTokens); assertEquals(2, c.threads); assertTrue(c.kvCache8Bit)
        assertEquals(listOf(Accelerator.GPU, Accelerator.CPU), c.accelerators)       // the CPU is always the fallback
        assertEquals(false, c.wantsVision)
        assertEquals(0.7, ModelSettings.temperature(mapOf("temperature" to "0.7"), 0.2), 0.0)
        // a smaller context also caps the answer length
        assertEquals(512, ModelSettings.apply(base.copy(maxOutputTokens = 1024, contextTokens = 4096), mapOf("context" to "512")).maxOutputTokens)
    }

    @Test fun limitsAreChecked() {
        assertTrue(ModelSettings.problem("context", "100", Format.GGUF)!!.contains("512 to 32768"))
        assertTrue(ModelSettings.problem("context", "4096", Format.LITERT_LM)!!.contains("inside the file"))
        assertTrue(ModelSettings.problem("temperature", "3", Format.GGUF)!!.contains("0 to 1.5"))
        assertTrue(ModelSettings.problem("threads", "4", Format.LITERT_LM)!!.contains("GGUF models only"))
        assertTrue(ModelSettings.problem("threads", "12", Format.GGUF)!!.contains("1 to 8"))
        assertTrue(ModelSettings.problem("kv8", "yes", Format.GGUF)!!.contains("true or false"))
        assertTrue(ModelSettings.problem("processors", "NPU,GPU", Format.GGUF)!!.contains("can't use the NPU"))
        assertTrue(ModelSettings.problem("processors", "GPU,GPU", Format.LITERT_LM)!!.contains("twice"))
        assertTrue(ModelSettings.problem("processors", "TPU", Format.LITERT_LM)!!.contains("NPU, GPU and CPU"))
        assertNull(ModelSettings.problem("processors", "npu, gpu", Format.LITERT_LM))
        assertTrue(ModelSettings.problem("speed", "1", Format.GGUF)!!.contains("unknown"))
    }

    @Test fun anEmptyValueGoesBackToTheDefault() {
        val s = ModelSettings.change(mapOf("threads" to "2"), "threads", "", Format.GGUF).getOrThrow()
        assertEquals(emptyMap<String, String>(), s)
        assertTrue(ModelSettings.change(emptyMap(), "threads", "99", Format.GGUF).isFailure)
    }
}
