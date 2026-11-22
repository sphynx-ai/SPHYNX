package com.sphynx.inference.roles

import com.sphynx.inference.Accelerator
import com.sphynx.inference.BackendType
import com.sphynx.inference.GenerationStats
import com.sphynx.inference.InferenceBackend
import com.sphynx.inference.InferenceError
import com.sphynx.inference.InferenceEvent
import com.sphynx.inference.InferenceRequest
import com.sphynx.inference.ModelConfiguration
import com.sphynx.inference.ModelRole
import com.sphynx.inference.RuntimeState
import com.sphynx.inference.RuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// PHASE 6, step S3 (ADR 0002): one router owns which model serves which role.
class RoleRouterTest {

    /** A runtime that answers at once on the caller's thread. */
    private class FakeRuntime(override val type: BackendType, val vision: Boolean = false) : InferenceBackend {
        var st = RuntimeStatus(type)
        var loads = 0; var unloads = 0; var asked = 0
        override fun status() = st
        override fun check(config: ModelConfiguration): InferenceError? =
            if (config.backend != type) InferenceError.UnsupportedFormat("wrong runtime") else null
        override fun load(config: ModelConfiguration): RuntimeStatus {
            loads++
            st = RuntimeStatus(type).moveTo(RuntimeState.LOADING, config = config)
                .moveTo(RuntimeState.READY, accelerator = Accelerator.CPU, visionOk = vision)
            return st
        }
        override fun generate(request: InferenceRequest, onEvent: (InferenceEvent) -> Unit) {
            asked++
            onEvent(InferenceEvent.Started(request.id))
            onEvent(InferenceEvent.Completed(request.id, "ok", GenerationStats(1, 1)))
        }
        override fun cancel(requestId: String) {}
        override fun unload() { unloads++; if (st.state != RuntimeState.UNLOADED) st = st.moveTo(RuntimeState.UNLOADED) }
    }

    private val gemma = FakeRuntime(BackendType.LITERT_LM, vision = true)     // the strategic LiteRT-LM slot
    private val small = FakeRuntime(BackendType.LITERT_LM)                    // the tactical LiteRT-LM slot
    private val gguf = FakeRuntime(BackendType.LLAMA_CPP)                     // the one GGUF engine

    private fun router() = RoleRouter { role, type ->
        when (type) {
            BackendType.LITERT_LM -> if (role == ModelRole.STRATEGIC) gemma else small
            BackendType.LLAMA_CPP -> gguf
        }
    }

    private fun lite(name: String, vision: Boolean = false) = ModelConfiguration(id = "litertlm:$name", displayName = name,
        backend = BackendType.LITERT_LM, location = "/m/$name", wantsVision = vision, maxOutputTokens = 512)
    private fun ggufModel(name: String) = ModelConfiguration(id = "gguf:$name", displayName = name,
        backend = BackendType.LLAMA_CPP, location = "/m/$name", accelerators = listOf(Accelerator.CPU), maxOutputTokens = 256)

    private fun ask(r: RoleRouter, role: ModelRole, req: InferenceRequest): InferenceEvent {
        val events = ArrayList<InferenceEvent>()
        r.ask(role, req) { events.add(it) }
        assertTrue(events.first() is InferenceEvent.Started)
        return events.last()
    }

    // today's assignment: Gemma (images) strategic on LiteRT-LM, the small model tactical on LiteRT-LM
    @Test fun todaysAssignmentLoadsEachRoleOnItsOwnSlot() {
        val r = router()
        assertNull(r.assign(Assignment(ModelRole.STRATEGIC, lite("gemma-4-E2B.litertlm", vision = true))))
        assertNull(r.assign(Assignment(ModelRole.TACTICAL, lite("gemma3-270m.litertlm"))))
        assertEquals(RuntimeState.READY, r.load(ModelRole.STRATEGIC).state)
        assertEquals(RuntimeState.READY, r.load(ModelRole.TACTICAL).state)
        assertEquals(1, gemma.loads); assertEquals(1, small.loads); assertEquals(0, gguf.loads)
        assertTrue(r.capabilities(ModelRole.STRATEGIC).images)
        assertFalse(r.capabilities(ModelRole.TACTICAL).images)
        assertTrue(ask(r, ModelRole.STRATEGIC, InferenceRequest("a", "draft?", image = byteArrayOf(1))) is InferenceEvent.Completed)
    }

    // any compatible model can serve either role: a GGUF model as the tactical one
    @Test fun aGgufModelCanServeARole() {
        val r = router()
        assertNull(r.assign(Assignment(ModelRole.TACTICAL, ggufModel("qwen-0.5b.gguf"))))
        r.load(ModelRole.TACTICAL)
        assertEquals(1, gguf.loads)
        assertEquals(BackendType.LLAMA_CPP, r.status(ModelRole.TACTICAL).backend)
        assertTrue(r.capabilities(ModelRole.TACTICAL).streaming)
        assertTrue(ask(r, ModelRole.TACTICAL, InferenceRequest("t", "fight?")) is InferenceEvent.Completed)
    }

    // one GGUF model at a time in Phase 6: it cannot serve both roles
    @Test fun ggufServesOneRoleAtATime() {
        val r = router()
        assertNull(r.assign(Assignment(ModelRole.TACTICAL, ggufModel("a.gguf"))))
        val why = r.assign(Assignment(ModelRole.STRATEGIC, ggufModel("b.gguf")))
        assertTrue(why!!.contains("only one GGUF model"))
    }

    // capability negotiation is explicit: an image to a text-only model is refused with a reason, not dropped
    @Test fun anImageToATextOnlyRoleIsRefusedWithAReason() {
        val r = router()
        r.assign(Assignment(ModelRole.STRATEGIC, ggufModel("llama-1b.gguf")))
        r.load(ModelRole.STRATEGIC)
        val e = ask(r, ModelRole.STRATEGIC, InferenceRequest("i", "what is on screen?", image = byteArrayOf(1)))
        assertTrue(e is InferenceEvent.Failed)
        assertTrue((e as InferenceEvent.Failed).error.message.contains("reads text only"))
        assertEquals(0, gguf.asked)
    }

    @Test fun askingAnUnloadedOrUnassignedRoleFails() {
        val r = router()
        assertTrue(ask(r, ModelRole.TACTICAL, InferenceRequest("x", "hi")) is InferenceEvent.Failed)
        assertEquals(RuntimeState.FAILED, r.load(ModelRole.TACTICAL).state)     // nothing assigned
    }

    // switching a role to another runtime frees the old one first
    @Test fun switchingRuntimeUnloadsTheOldModel() {
        val r = router()
        r.assign(Assignment(ModelRole.TACTICAL, lite("gemma3-270m.litertlm")))
        r.load(ModelRole.TACTICAL)
        r.assign(Assignment(ModelRole.TACTICAL, ggufModel("qwen.gguf")))
        assertEquals(1, small.unloads)
        r.load(ModelRole.TACTICAL)
        assertEquals(BackendType.LLAMA_CPP, r.status(ModelRole.TACTICAL).backend)
    }

    @Test fun aModelSetUpForTheOtherRoleIsRefused() {
        val r = router()
        val why = r.assign(Assignment(ModelRole.TACTICAL, lite("gemma.litertlm").copy(role = ModelRole.STRATEGIC)))
        assertTrue(why!!.contains("strategic role"))
    }

    @Test fun refusalReasons() {
        val req = InferenceRequest("r", "q", maxOutputTokens = 1000)
        val st = RuntimeStatus(BackendType.LITERT_LM).moveTo(RuntimeState.LOADING, config = lite("m.litertlm")).moveTo(RuntimeState.READY)
        assertTrue(RoleRouter.refusal(ModelRole.STRATEGIC, st, req)!!.contains("at most 512 tokens"))
        assertNull(RoleRouter.refusal(ModelRole.STRATEGIC, st, InferenceRequest("r", "q")))
        assertEquals(RoleCapabilities.NONE, RoleRouter.capabilitiesOf(RuntimeStatus(BackendType.LITERT_LM)))
    }
}
