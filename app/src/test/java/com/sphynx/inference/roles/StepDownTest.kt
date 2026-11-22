package com.sphynx.inference.roles

import com.sphynx.inference.Accelerator
import com.sphynx.inference.BackendType
import com.sphynx.inference.ModelConfiguration
import com.sphynx.inference.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Owner decision 2026-11-22: step down to the next smaller compatible model; strategic only to a picture-reading one.
class StepDownTest {

    private val mb = 1024L * 1024
    private fun lite(name: String, sizeMb: Long) = ModelConfiguration(id = "litertlm:$name", displayName = name,
        backend = BackendType.LITERT_LM, location = "/in/$name", sizeBytes = sizeMb * mb)
    private fun gguf(name: String, sizeMb: Long) = ModelConfiguration(id = "gguf:$name", displayName = name,
        backend = BackendType.LLAMA_CPP, location = "/gguf/$name", sizeBytes = sizeMb * mb, accelerators = listOf(Accelerator.CPU))

    private val e4b = lite("gemma-4-E4B.litertlm", 4300)
    private val e2b = lite("gemma-4-E2B.litertlm", 2600)
    private val n3 = lite("gemma-3n-E2B.litertlm", 3100)
    private val small = lite("gemma3-270m.litertlm", 300)
    private val qwen = gguf("qwen2.5-0.5b-q4.gguf", 462)
    private val llama = gguf("llama-3.2-1b-q4.gguf", 762)
    private val all = listOf(e4b, e2b, n3, small, qwen, llama)
    private val readsPictures = setOf(e4b.id, e2b.id, n3.id)
    private fun vision(c: ModelConfiguration) = c.id in readsPictures
    private val ok: (ModelConfiguration) -> String? = { null }

    // the smallest step down: the largest model that is still smaller
    @Test fun theNextSmallerModelIsChosen() {
        assertEquals(n3, StepDown.choose(ModelRole.STRATEGIC, e4b, all, ::vision, ok))
        assertEquals(e2b, StepDown.choose(ModelRole.STRATEGIC, n3, all, ::vision, ok))
    }

    // strategic steps down only to a model that also reads pictures
    @Test fun strategicNeedsPictures() {
        assertNull(StepDown.choose(ModelRole.STRATEGIC, e2b, all, ::vision, ok))           // only text-only models are smaller
    }

    // tactical: any smaller compatible model, on either runtime
    @Test fun tacticalCanStepDownAcrossRuntimes() {
        assertEquals(qwen, StepDown.choose(ModelRole.TACTICAL, llama, all, ::vision, ok))
        assertEquals(small, StepDown.choose(ModelRole.TACTICAL, qwen, all, ::vision, ok))
        assertNull(StepDown.choose(ModelRole.TACTICAL, small, all, ::vision, ok))           // nothing smaller
    }

    @Test fun incompatibleModelsAreSkipped() {
        val noGguf: (ModelConfiguration) -> String? = { if (it.backend == BackendType.LLAMA_CPP) "the GGUF engine already serves the other job" else null }
        assertEquals(small, StepDown.choose(ModelRole.TACTICAL, llama, all, ::vision, noGguf))
    }

    @Test fun unknownSizesAndOtherRolesAreNeverChosen() {
        assertNull(StepDown.choose(ModelRole.TACTICAL, llama.copy(sizeBytes = -1), all, ::vision, ok))
        val unknown = qwen.copy(sizeBytes = -1)
        assertEquals(small, StepDown.choose(ModelRole.TACTICAL, llama, listOf(unknown, small), ::vision, ok))
        val forStrategic = qwen.copy(role = ModelRole.STRATEGIC)
        assertEquals(small, StepDown.choose(ModelRole.TACTICAL, llama, listOf(forStrategic, small), ::vision, ok))
    }

    @Test fun configAndNotice() {
        val c = StepDown.configFor(ModelRole.STRATEGIC, n3, visionCapable = true)
        assertTrue(c.wantsVision); assertEquals(ModelRole.STRATEGIC, c.role)
        assertFalse(StepDown.configFor(ModelRole.TACTICAL, qwen, visionCapable = false).wantsVision)
        assertEquals("Phone under pressure: strategic job on gemma-3n-E2B.litertlm (smaller) for now", StepDown.notice(ModelRole.STRATEGIC, n3))
        assertEquals("", StepDown.notice(ModelRole.TACTICAL, null))
    }
}
