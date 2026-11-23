package com.sphynx.inference

import com.sphynx.testing.InferenceContractKit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

// PHASE 6, step S2: both runtimes keep the one shared contract (ADR 0002), each over a fake engine.
class InferenceContractKitTest {

    // ---- LiteRT-LM over a fake slot ----

    private class FakeSlot : LiteRtSlot {
        @Volatile var loaded = false
        var vision = false
        var whereLoaded = "-"
        val hold = AtomicBoolean(false)
        val cancelFlag = AtomicBoolean(false)
        var lastPreferGpu: Boolean? = null
        var lastTextOnly: Boolean? = null
        var lastPurpose = ""
        override val ready get() = loaded
        override val visionOk get() = vision
        override val where get() = whereLoaded
        override fun fileExists(location: String) = location.endsWith("good.litertlm")
        var lastPreferNpu: Boolean? = null
        override fun load(location: String, preferGpu: Boolean, textOnly: Boolean, preferNpu: Boolean): String? {
            lastPreferGpu = preferGpu; lastTextOnly = textOnly; lastPreferNpu = preferNpu; loads++
            loaded = true; vision = !textOnly; whereLoaded = if (preferGpu) "GPU" else "CPU, text only"
            return null
        }
        var lastStopWhen: ((String) -> Boolean)? = null
        var failNext: String? = null
        var loads = 0
        override fun ask(prompt: String, imageJpeg: ByteArray?, temperature: Double, purpose: String, timeoutMs: Long,
                         stopWhen: ((String) -> Boolean)?): String {
            lastPurpose = purpose; lastStopWhen = stopWhen
            check(loaded) { "the model is not loaded" }
            failNext?.let { failNext = null; throw RuntimeException(it) }
            cancelFlag.set(false)
            if (hold.getAndSet(false)) {
                while (!cancelFlag.get()) Thread.sleep(5)
                throw IllegalStateException("cancelled by the slot")
            }
            return "Farm your camps."
        }
        override fun cancel() { cancelFlag.set(true) }
        override fun unload() { loaded = false; whereLoaded = "-" }
    }

    private fun liteCfg(name: String, vision: Boolean = false, accs: List<Accelerator> = listOf(Accelerator.CPU)) =
        ModelConfiguration(id = "litertlm:$name", displayName = name, backend = BackendType.LITERT_LM, location = "/models/$name",
            accelerators = accs, wantsVision = vision, contextTokens = 2048, maxOutputTokens = 512)

    @Test fun liteRtLmKeepsTheContract() {
        var slot = FakeSlot()
        val h = object : InferenceContractKit.Harness {
            override fun newBackend(): InferenceBackend { slot = FakeSlot(); return LiteRtLmBackend(slot) }
            override fun goodConfig() = liteCfg("good.litertlm")
            override fun badConfig() = liteCfg("missing.litertlm")
            override fun holdNextAnswer() { slot.hold.set(true) }
        }
        assertEquals(emptyList<String>(), InferenceContractKit.check(h))
    }

    // the load order is the slot's own: GPU first when listed first, text only when no images are wanted
    // Phase 7 L4: a failure on the GPU reloads the model on the CPU once; the next failure unloads it
    @Test fun aGpuFailureFallsBackToTheCpuOnceThenUnloads() {
        val s = FakeSlot()
        val be = LiteRtLmBackend(s)
        val notices = ArrayList<String>()
        be.onNotice = { notices.add(it) }
        be.load(liteCfg("good.litertlm", vision = true, accs = listOf(Accelerator.GPU, Accelerator.CPU)).copy(role = ModelRole.STRATEGIC))
        assertEquals(Accelerator.GPU, be.status().accelerator)

        s.failNext = "OpenCL kernel failed"
        val first = askOnce(be)
        assertTrue(first is InferenceEvent.Failed && first.error is InferenceError.AcceleratorFailed)
        assertEquals(RuntimeState.READY, be.status().state)
        assertEquals(Accelerator.CPU, be.status().accelerator)
        assertEquals(false, s.lastPreferGpu); assertEquals(false, s.lastTextOnly)   // still with images, on the CPU
        assertTrue(be.fellBack)
        assertTrue(notices.single().startsWith("The strategic model failed on the GPU"))

        assertTrue(askOnce(be) is InferenceEvent.Completed)                           // the CPU answers

        s.failNext = "native error"
        askOnce(be)
        assertEquals(RuntimeState.UNLOADED, be.status().state)
        assertFalse(s.loaded)
        assertEquals(2, s.loads)                                                        // no second reload
        assertTrue(notices.last().contains("unloaded"))
    }

    // a model on the CPU that fails once is left as it is (no fallback happened)
    @Test fun aCpuFailureWithoutFallbackKeepsTheModel() {
        val s = FakeSlot()
        val be = LiteRtLmBackend(s)
        be.load(liteCfg("good.litertlm"))
        s.failNext = "something odd"
        val e = askOnce(be)
        assertTrue(e is InferenceEvent.Failed && e.error is InferenceError.Internal)
        assertEquals(RuntimeState.READY, be.status().state)
        assertEquals(1, s.loads)
    }

    private fun askOnce(be: LiteRtLmBackend): InferenceEvent {
        val done = java.util.concurrent.LinkedBlockingQueue<InferenceEvent>()
        be.generate(InferenceRequest(id = "q" + System.nanoTime(), prompt = "hi")) { e ->
            if (e is InferenceEvent.Completed || e is InferenceEvent.Failed || e is InferenceEvent.Cancelled) done.add(e) }
        return done.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
    }

    // Phase 7 L8: NPU first only when the configuration lists it first
    @Test fun theNpuIsTriedFirstOnlyWhenListedFirst() {
        val s = FakeSlot()
        val be = LiteRtLmBackend(s)
        be.load(liteCfg("good.litertlm", vision = true, accs = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)))
        assertEquals(true, s.lastPreferNpu); assertEquals(true, s.lastPreferGpu)
        be.load(liteCfg("good.litertlm", vision = true, accs = listOf(Accelerator.GPU, Accelerator.NPU, Accelerator.CPU)))
        assertEquals(false, s.lastPreferNpu)
    }

    @Test fun liteRtLmPassesTheLoadPlanToTheSlot() {
        val s = FakeSlot()
        val be = LiteRtLmBackend(s)
        val st = be.load(liteCfg("good.litertlm", vision = true, accs = listOf(Accelerator.GPU, Accelerator.CPU)))
        assertEquals(true, s.lastPreferGpu); assertEquals(false, s.lastTextOnly)
        assertEquals(Accelerator.GPU, st.accelerator)
        assertTrue(st.visionOk)
        be.load(liteCfg("good.litertlm", accs = listOf(Accelerator.CPU, Accelerator.GPU)))
        assertEquals(false, s.lastPreferGpu); assertEquals(true, s.lastTextOnly)
        assertEquals(Accelerator.CPU, be.status().accelerator)
        assertFalse(be.status().visionOk)
    }

    @Test fun aGgufFileIsNotALiteRtLmModel() {
        val be = LiteRtLmBackend(FakeSlot())
        val c = ModelConfiguration(id = "gguf:x", displayName = "x.gguf", backend = BackendType.LLAMA_CPP, location = "/m/good.litertlm",
            accelerators = listOf(Accelerator.CPU))
        assertEquals("unsupported_format", be.check(c)?.code)
    }

    @Test fun purposeReachesTheSlotForTheSpeedRecords() {
        val s = FakeSlot()
        val be = LiteRtLmBackend(s)
        be.load(liteCfg("good.litertlm"))
        val done = java.util.concurrent.CountDownLatch(1)
        be.generate(InferenceRequest("p", "plan?", purpose = "plan")) { if (it.terminal) done.countDown() }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("plan", s.lastPurpose)
    }

    // Phase 7 (L2): the caller's stop rule reaches the LiteRT-LM slot
    @Test fun stopWhenReachesTheSlot() {
        val s = FakeSlot()
        val be = LiteRtLmBackend(s)
        be.load(liteCfg("good.litertlm"))
        val done = java.util.concurrent.CountDownLatch(1)
        val rule: (String) -> Boolean = { it.contains("RETREAT") }
        be.generate(InferenceRequest("s", "fight?", stopWhen = rule)) { if (it.terminal) done.countDown() }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(s.lastStopWhen === rule)
    }

    // Phase 7 (L2): llama.cpp stops as soon as the caller has its answer, and it ends as Completed with the text so far
    @Test fun llamaCppStopsEarlyWithTheTextSoFar() {
        val good = gguf()
        val n = object : LlamaNative {
            val cancelFlag = AtomicBoolean(false)
            var piecesSent = 0
            override val unavailableReason: String? = null
            override fun info() = "fake"
            override fun load(path: String, contextTokens: Int, threads: Int) = 7L
            override fun loadError() = ""
            override fun describe(handle: Long): JSONObject = JSONObject()
            override fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, seed: Int, onPiece: (ByteArray) -> Unit): JSONObject {
                cancelFlag.set(false)
                for (p in listOf("RET", "REAT", " because", " the enemy", " is strong")) {
                    if (cancelFlag.get()) return JSONObject().put("ok", true).put("stop", "cancelled")
                    onPiece(p.toByteArray()); piecesSent++
                }
                return JSONObject().put("ok", true).put("stop", "eos")
            }
            override fun cancel(handle: Long) { cancelFlag.set(true) }
            override fun free(handle: Long) {}
        }
        val be = LlamaCppBackend(n)
        be.load(ModelConfiguration(id = "gguf:m", displayName = "m.gguf", backend = BackendType.LLAMA_CPP, location = good.absolutePath,
            accelerators = listOf(Accelerator.CPU), contextTokens = 2048, maxOutputTokens = 512))
        val c = EventCollector("e")
        val done = java.util.concurrent.CountDownLatch(1)
        be.generate(InferenceRequest("e", "fight?", stopWhen = { it.contains("RETREAT") })) { c.accept(it); if (it.terminal) done.countDown() }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("RETREAT", c.text)
        assertEquals(2, n.piecesSent)
    }

    @Test fun acceleratorFromTheSlotsLabel() {
        assertEquals(Accelerator.GPU, LiteRtLmBackend.acceleratorOf("GPU, text only"))
        assertEquals(Accelerator.CPU, LiteRtLmBackend.acceleratorOf("CPU"))
        assertEquals(Accelerator.CPU, LiteRtLmBackend.acceleratorOf("-"))
        assertTrue(LiteRtLmBackend.preferGpu(listOf(Accelerator.GPU, Accelerator.CPU)))
        assertFalse(LiteRtLmBackend.preferGpu(listOf(Accelerator.CPU, Accelerator.GPU)))
        assertTrue(LiteRtLmBackend.preferGpu(listOf(Accelerator.GPU)))
        assertFalse(LiteRtLmBackend.preferGpu(listOf(Accelerator.CPU)))
    }

    // ---- llama.cpp over a fake native library (the same kit) ----

    private class FakeNative : LlamaNative {
        val hold = AtomicBoolean(false)
        val cancelFlag = AtomicBoolean(false)
        override val unavailableReason: String? = null
        override fun info() = "fake"
        override fun load(path: String, contextTokens: Int, threads: Int): Long = 7L
        override fun loadError() = ""
        override fun describe(handle: Long): JSONObject = JSONObject().put("model", "fake")
        override fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, seed: Int, onPiece: (ByteArray) -> Unit): JSONObject {
            cancelFlag.set(false)
            if (hold.getAndSet(false)) {
                onPiece("Thinking".toByteArray())
                while (!cancelFlag.get()) Thread.sleep(5)
                return JSONObject().put("ok", true).put("stop", "cancelled")
            }
            onPiece("Farm ".toByteArray()); onPiece("your camps.".toByteArray())
            return JSONObject().put("ok", true).put("stop", "eos").put("genTokens", 2)
        }
        override fun cancel(handle: Long) { cancelFlag.set(true) }
        override fun free(handle: Long) {}
    }

    private val files = ArrayList<File>()
    @After fun cleanUp() { files.forEach { it.delete() } }

    private fun gguf(): File {
        val f = File.createTempFile("model", ".gguf"); files.add(f)
        RandomAccessFile(f, "rw").use { it.write(com.sphynx.testing.GgufFixture.model()); it.setLength(3L * 1024 * 1024) }
        return f
    }

    @Test fun llamaCppKeepsTheContract() {
        var native = FakeNative()
        val good = gguf()
        val h = object : InferenceContractKit.Harness {
            override fun newBackend(): InferenceBackend { native = FakeNative(); return LlamaCppBackend(native) }
            override fun goodConfig() = ModelConfiguration(id = "gguf:m", displayName = "m.gguf", backend = BackendType.LLAMA_CPP,
                location = good.absolutePath, accelerators = listOf(Accelerator.CPU), contextTokens = 2048, maxOutputTokens = 512)
            override fun badConfig() = goodConfig().copy(location = good.absolutePath + ".missing")
            override fun holdNextAnswer() { native.hold.set(true) }
        }
        assertEquals(emptyList<String>(), InferenceContractKit.check(h))
    }
}
