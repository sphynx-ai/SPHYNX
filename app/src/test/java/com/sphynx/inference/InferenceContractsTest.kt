package com.sphynx.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceContractsTest {

    private fun cfg(name: String = "gemma-4-e2b.litertlm", backend: BackendType = BackendType.LITERT_LM, vision: Boolean = true,
                    accs: List<Accelerator> = listOf(Accelerator.GPU, Accelerator.CPU)) =
        ModelConfiguration("m1", name, backend, "/data/models/$name", wantsVision = vision, accelerators = accs)

    // ---- backend type ----
    @Test fun backendTypeFromFileName() {
        assertEquals(BackendType.LITERT_LM, BackendType.forFileName("gemma-3-270m.litertlm"))
        assertEquals(BackendType.LITERT_LM, BackendType.forFileName("OLD.TASK"))
        assertEquals(BackendType.LLAMA_CPP, BackendType.forFileName("qwen2.5-0.5b-q4_k_m.gguf"))
        assertNull(BackendType.forFileName("model.bin"))
        assertNull(BackendType.forFileName("noextension"))
        assertTrue(BackendType.LLAMA_CPP.shippedNative)     // Phase 8: built from app/src/main/cpp
    }

    // ---- configuration ----
    @Test fun validConfigurationHasNoProblems() {
        assertEquals(emptyList<String>(), cfg().problems())
    }

    @Test fun configurationProblemsAreListed() {
        val p = ModelConfiguration("", "x.gguf", BackendType.LLAMA_CPP, "", wantsVision = true, accelerators = emptyList(),
            contextTokens = 64, maxOutputTokens = 0, threads = 99).problems()
        assertTrue(p.contains("no model id"))
        assertTrue(p.contains("no file location"))
        assertTrue(p.contains("no accelerator to run on"))
        assertTrue(p.any { it.startsWith("context length 64") })
        assertTrue(p.any { it.startsWith("max output 0") })
        assertTrue(p.any { it.startsWith("threads 99") })
        assertTrue(p.contains("image input needs a LiteRT-LM multimodal model"))
    }

    @Test fun fileTypeMustMatchTheBackend() {
        assertTrue(cfg(name = "tiny.gguf", backend = BackendType.LITERT_LM, vision = false).problems()
            .contains("the file looks like llama.cpp (GGUF), not LiteRT-LM"))
    }

    @Test fun storageModeMustMatchTheLocation() {
        val ref = cfg().copy(storage = StorageMode.DOCUMENT_REFERENCE)
        assertTrue(ref.problems().contains("a document reference must be a content:// URI"))
        assertEquals(emptyList<String>(), ref.copy(location = "content://docs/tree/x/gemma.litertlm").problems())
        assertTrue(cfg().copy(location = "content://x").problems().contains("an app-storage copy cannot be a document URI"))
    }

    // ---- load plan: identical to today's LlmSlot order ----
    @Test fun loadPlanMatchesTodaysLiteRtOrder() {
        fun plan(c: ModelConfiguration) = LoadPlan.attempts(c).map { it.label }
        assertEquals(listOf("GPU", "CPU", "CPU, text only"), plan(cfg()))                                   // vision, GPU preferred
        assertEquals(listOf("CPU", "CPU, text only"), plan(cfg(accs = listOf(Accelerator.CPU))))           // vision, CPU only
        assertEquals(listOf("GPU, text only", "CPU, text only"), plan(cfg(vision = false)))                // text only, GPU preferred
        assertEquals(listOf("CPU, text only"), plan(cfg(vision = false, accs = listOf(Accelerator.CPU))))  // text only, CPU
    }

    @Test fun npuIsTriedOnlyWhenAvailableAndCpuIsAlwaysLast() {
        val c = cfg(vision = false, accs = listOf(Accelerator.NPU, Accelerator.GPU))
        assertEquals(listOf("GPU, text only", "CPU, text only"), LoadPlan.attempts(c).map { it.label })
        assertEquals(listOf("NPU, text only", "GPU, text only", "CPU, text only"), LoadPlan.attempts(c, npuAvailable = true).map { it.label })
    }

    // ---- runtime states ----
    @Test fun legalAndIllegalTransitions() {
        val s = RuntimeStatus(BackendType.LITERT_LM)
        val ready = s.moveTo(RuntimeState.LOADING, config = cfg()).moveTo(RuntimeState.READY, accelerator = Accelerator.GPU, visionOk = true)
        assertTrue(ready.ready)
        assertEquals("LiteRT-LM: ready on GPU, images yes", ready.describe())
        val unloaded = ready.moveTo(RuntimeState.UNLOADED)
        assertNull(unloaded.accelerator); assertFalse(unloaded.visionOk); assertNull(unloaded.config)
        assertFalse(RuntimeState.UNLOADED.canMoveTo(RuntimeState.READY))
        assertFalse(RuntimeState.FAILED.canMoveTo(RuntimeState.BUSY))
        try { s.moveTo(RuntimeState.BUSY); throw AssertionError("expected an illegal transition") } catch (e: IllegalStateException) { }
    }

    @Test fun failedStatusKeepsTheError() {
        val f = RuntimeStatus(BackendType.LLAMA_CPP).moveTo(RuntimeState.LOADING)
            .moveTo(RuntimeState.FAILED, error = InferenceError.BackendUnavailable(BackendType.LLAMA_CPP))
        assertEquals("backend_unavailable", f.error?.code)
        assertEquals("llama.cpp (GGUF): failed - llama.cpp (GGUF) is not included in this build", f.describe())
    }

    // ---- requests ----
    @Test fun requestChecksAgainstTheLoadedModel() {
        val textOnly = RuntimeStatus(BackendType.LITERT_LM).moveTo(RuntimeState.LOADING, config = cfg(vision = false))
            .moveTo(RuntimeState.READY, accelerator = Accelerator.CPU, visionOk = false)
        assertEquals(emptyList<String>(), InferenceRequest("r1", "plan?").problems(textOnly))
        val p = InferenceRequest("r2", "draft?", image = ByteArray(4), maxOutputTokens = 9999, temperature = 3f).problems(textOnly)
        assertTrue(p.contains("this model has no image input"))
        assertTrue(p.any { it.startsWith("max output 9999") })
        assertTrue(p.any { it.startsWith("temperature 3.0") })
        assertTrue(InferenceRequest("r3", "x").problems(RuntimeStatus(BackendType.LITERT_LM)).contains("model is not loaded, not ready"))
        assertTrue(InferenceRequest("", " ").problems().containsAll(listOf("no request id", "empty prompt")))
    }

    @Test fun errorsAreReadable() {
        val e = InferenceError.NotEnoughMemory(3L * 1024 * 1024 * 1024, 1536L * 1024 * 1024)
        assertEquals("needs about 3072 MB of memory, 1536 MB free", e.message)
        assertTrue(e.retryable)
        assertFalse(InferenceError.CrashSuspected("gemma").retryable)
    }

    // ---- events ----
    @Test fun streamedAnswerIsCollected() {
        val c = EventCollector("r1")
        c.accept(InferenceEvent.Started("r1"))
        c.accept(InferenceEvent.Token("r1", "RETREAT"))
        assertEquals("RETREAT", c.partial())
        c.accept(InferenceEvent.Token("r1", " now"))
        c.accept(InferenceEvent.Completed("r1", "RETREAT now", GenerationStats(120, 400)))
        assertTrue(c.done); assertEquals("RETREAT now", c.text); assertEquals(emptyList<String>(), c.violations)
    }

    @Test fun orderViolationsAreCaught() {
        val c = EventCollector("r1")
        c.accept(InferenceEvent.Token("r1", "x"))                           // before Started
        c.accept(InferenceEvent.Started("r1"))
        c.accept(InferenceEvent.Token("r2", "y"))                           // wrong request
        c.accept(InferenceEvent.Completed("r1", "different", GenerationStats(1, 2)))  // differs from tokens
        c.accept(InferenceEvent.Cancelled("r1"))                            // after the end
        assertEquals(4, c.violations.size)
        assertEquals("different", c.text)
    }

    @Test fun cancelEndsWithCancelled() {
        val c = EventCollector("r1")
        c.accept(InferenceEvent.Started("r1")); c.accept(InferenceEvent.Cancelled("r1"))
        assertTrue(c.done); assertTrue(c.error === InferenceError.Cancelled); assertNull(c.text)
    }

    // ---- a fake runtime that follows the contract (what Phase Two's LiteRT-LM wrapper must also pass) ----
    private class FakeBackend(private val failOn: Accelerator? = null) : InferenceBackend {
        override val type = BackendType.LITERT_LM
        private var st = RuntimeStatus(type)
        val tried = ArrayList<String>()
        override fun status() = st
        override fun check(config: ModelConfiguration): InferenceError? =
            if (config.backend != type) InferenceError.UnsupportedFormat("not a ${type.label} model") else null
        override fun load(config: ModelConfiguration): RuntimeStatus {
            check(config)?.let { st = st.moveTo(RuntimeState.LOADING, config = config).moveTo(RuntimeState.FAILED, error = it); return st }
            st = st.moveTo(RuntimeState.LOADING, config = config)
            for (a in LoadPlan.attempts(config)) {
                tried.add(a.label)
                if (a.accelerator != failOn) { st = st.moveTo(RuntimeState.READY, accelerator = a.accelerator, visionOk = a.vision); return st }
            }
            st = st.moveTo(RuntimeState.FAILED, error = InferenceError.LoadFailed("every attempt failed")); return st
        }
        override fun generate(request: InferenceRequest, onEvent: (InferenceEvent) -> Unit) {
            onEvent(InferenceEvent.Started(request.id))
            val bad = request.problems(st)
            if (bad.isNotEmpty()) { onEvent(InferenceEvent.Failed(request.id, InferenceError.InvalidRequest(bad))); return }
            st = st.moveTo(RuntimeState.BUSY)
            val answer = "PUSH mid"
            if (request.stream) answer.split(" ").forEachIndexed { i, w -> onEvent(InferenceEvent.Token(request.id, if (i == 0) w else " $w")) }
            st = st.moveTo(RuntimeState.READY)
            onEvent(InferenceEvent.Completed(request.id, answer, GenerationStats(5, 10)))
        }
        override fun cancel(requestId: String) {}
        override fun unload() { if (st.state != RuntimeState.UNLOADED) st = st.moveTo(RuntimeState.UNLOADED) }
    }

    @Test fun fakeRuntimeFollowsTheContract() {
        val b = FakeBackend(failOn = Accelerator.GPU)
        val s = b.load(cfg())
        assertEquals(listOf("GPU", "CPU"), b.tried)                          // GPU failed, CPU with images worked
        assertEquals(Accelerator.CPU, s.accelerator); assertTrue(s.visionOk)
        for (stream in listOf(false, true)) {
            val c = EventCollector("q$stream")
            b.generate(InferenceRequest("q$stream", "what now?", stream = stream), c::accept)
            assertTrue(c.done); assertEquals("PUSH mid", c.text); assertEquals(emptyList<String>(), c.violations)
        }
        val bad = EventCollector("q3")
        b.generate(InferenceRequest("q3", "x", maxOutputTokens = 100_000), bad::accept)
        assertNotNull(bad.error); assertEquals("invalid_request", bad.error?.code)
        b.unload(); assertEquals(RuntimeState.UNLOADED, b.status().state)
        assertEquals("unsupported_format", b.load(cfg(name = "x.gguf", backend = BackendType.LLAMA_CPP, vision = false)).error?.code)
    }
}
