package com.example.mlbbresearch.inference

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// PHASE 8: LlamaCppBackend keeps the shared InferenceBackend contract. A fake llama.cpp stands in
// for the native library, so states, events, streaming, cancel and timeout are checked on a computer.
class LlamaCppBackendTest {

    private class FakeNative : LlamaNative {
        var unavailable: String? = null
        var loadOk = true
        var pieces: List<ByteArray> = listOf("Farm ".toByteArray(), "your camps.".toByteArray())
        var waitForCancel = false
        val cancelFlag = AtomicBoolean(false)
        var freed = 0
        var lastMaxTokens = -1
        override val unavailableReason: String? get() = unavailable
        override fun info() = "fake llama.cpp"
        override fun load(path: String, contextTokens: Int, threads: Int): Long = if (loadOk) 42L else 0L
        override fun loadError() = "the model could not be loaded\nllama_model_load: error loading model: unknown model architecture: 'abc'"
        override fun describe(handle: Long): JSONObject = JSONObject().put("model", "fake 1B Q4_K").put("nCtx", 2048).put("threads", 4)
        override fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, seed: Int, onPiece: (ByteArray) -> Unit): JSONObject {
            lastMaxTokens = maxTokens
            cancelFlag.set(false)                      // like the engine: a new answer starts uncancelled
            var n = 0
            if (waitForCancel) {
                onPiece("Thinking".toByteArray()); n++
                while (!cancelFlag.get()) Thread.sleep(5)
                return report("cancelled", n)
            }
            for (p in pieces) { if (cancelFlag.get()) return report("cancelled", n); onPiece(p); n++ }
            return report("eos", n)
        }
        private fun report(stop: String, n: Int) = JSONObject().put("ok", true).put("error", "").put("stop", stop)
            .put("promptTokens", 12).put("promptMs", 200).put("genTokens", n).put("genMs", 400).put("firstTokenMs", 250)
        override fun cancel(handle: Long) { cancelFlag.set(true) }
        override fun free(handle: Long) { freed++ }
    }

    private val files = ArrayList<File>()
    @After fun cleanUp() { files.forEach { it.delete() } }

    private fun ggufFile(size: Long = 3L * 1024 * 1024, head: ByteArray = "GGUF".toByteArray() + byteArrayOf(3, 0, 0, 0)): File {
        val f = File.createTempFile("model", ".gguf"); files.add(f)
        RandomAccessFile(f, "rw").use { it.write(head); it.setLength(size) }
        return f
    }

    private fun cfg(f: File, storage: StorageMode = StorageMode.APP_COPY, backend: BackendType = BackendType.LLAMA_CPP) =
        ModelConfiguration(id = "gguf:m", displayName = "m.gguf", backend = backend,
            location = if (storage == StorageMode.APP_COPY) f.absolutePath else "content://downloads/1",
            storage = storage, accelerators = listOf(Accelerator.CPU), contextTokens = 2048, maxOutputTokens = 512, threads = 4)

    private fun ask(be: LlamaCppBackend, req: InferenceRequest, beforeWait: () -> Unit = {}): EventCollector {
        val c = EventCollector(req.id)
        val end = CountDownLatch(1)
        be.generate(req) { e -> c.accept(e); if (e.terminal) end.countDown() }
        beforeWait()
        assertTrue("the request did not end", end.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), c.violations)
        return c
    }

    // ---- loading ----

    @Test fun loadsOnTheCpuAndReportsTheModel() {
        val be = LlamaCppBackend(FakeNative())
        val st = be.load(cfg(ggufFile()))
        assertEquals(RuntimeState.READY, st.state)
        assertEquals(Accelerator.CPU, st.accelerator)
        assertFalse(st.visionOk)
        assertEquals("fake 1B Q4_K", be.modelInfo!!.optString("model"))
    }

    @Test fun aFailedLoadSaysWhatLlamaCppSaid() {
        val n = FakeNative().apply { loadOk = false }
        val st = LlamaCppBackend(n).load(cfg(ggufFile()))
        assertEquals(RuntimeState.FAILED, st.state)
        assertTrue(st.error is InferenceError.LoadFailed)
        assertTrue(st.error!!.message.contains("unknown model architecture"))
    }

    @Test fun checkNamesWhatIsWrongBeforeLoading() {
        val n = FakeNative()
        val be = LlamaCppBackend(n) { 8L * 1024 * 1024 * 1024 }
        assertNull(be.check(cfg(ggufFile())))
        assertTrue(be.check(cfg(ggufFile(), backend = BackendType.LITERT_LM)) is InferenceError.UnsupportedFormat)
        assertTrue(be.check(cfg(ggufFile(), storage = StorageMode.DOCUMENT_REFERENCE))!!.message.contains("add the model to the app"))
        val gone = ggufFile(); gone.delete()
        assertTrue(be.check(cfg(gone)) is InferenceError.ModelNotFound)
        assertTrue(be.check(cfg(ggufFile(head = "<!DOCTYPE html>".toByteArray())))!!.message.contains("web page"))
        n.unavailable = "this phone's processor lacks dot product (asimddp), which the GGUF engine needs"
        assertTrue(be.check(cfg(ggufFile()))!!.message.contains("asimddp"))
    }

    @Test fun notEnoughMemoryIsRefusedBeforeLoading() {
        val be = LlamaCppBackend(FakeNative()) { 100L * 1024 * 1024 }      // 100 MB free
        val st = be.load(cfg(ggufFile(size = 800L * 1024 * 1024)))
        assertEquals(RuntimeState.FAILED, st.state)
        assertTrue(st.error is InferenceError.NotEnoughMemory)
    }

    @Test fun unloadFreesTheModel() {
        val n = FakeNative()
        val be = LlamaCppBackend(n)
        be.load(cfg(ggufFile()))
        be.unload()
        assertEquals(RuntimeState.UNLOADED, be.status().state)
        assertEquals(1, n.freed)
        assertNull(be.modelInfo)
    }

    @Test fun loadingAnotherModelFreesTheFirst() {
        val n = FakeNative()
        val be = LlamaCppBackend(n)
        be.load(cfg(ggufFile()))
        be.load(cfg(ggufFile()))
        assertEquals(1, n.freed)
        assertEquals(RuntimeState.READY, be.status().state)
    }

    // ---- answering ----

    @Test fun streamsTokensThenCompletes() {
        val be = LlamaCppBackend(FakeNative())
        be.load(cfg(ggufFile()))
        val c = ask(be, InferenceRequest("r1", "What should a jungler do?", stream = true, maxOutputTokens = 64))
        assertEquals("Farm your camps.", c.text)
        assertEquals(12, c.stats!!.promptTokens)
        assertEquals(2, c.stats!!.outputTokens)
        assertEquals(RuntimeState.READY, be.status().state)
        assertEquals(1, be.status().calls)
    }

    @Test fun aCharacterSplitBetweenPiecesIsNotBroken() {
        val n = FakeNative()
        val emoji = "😀".toByteArray(Charsets.UTF_8)            // 4 bytes
        n.pieces = listOf("hi ".toByteArray(), emoji.copyOfRange(0, 2), emoji.copyOfRange(2, 4))
        val be = LlamaCppBackend(n)
        be.load(cfg(ggufFile()))
        val c = ask(be, InferenceRequest("r2", "hello", stream = true))
        assertEquals("hi 😀", c.text)
        assertFalse(c.text!!.contains('�'))
    }

    @Test fun askingWithoutAModelFailsCleanly() {
        val c = ask(LlamaCppBackend(FakeNative()), InferenceRequest("r3", "hello"))
        assertTrue(c.error is InferenceError.InvalidRequest)
    }

    @Test fun cancelStopsARunningAnswer() {
        val n = FakeNative().apply { waitForCancel = true }
        val be = LlamaCppBackend(n)
        be.load(cfg(ggufFile()))
        val c = ask(be, InferenceRequest("r4", "hello", stream = true)) {
            Thread.sleep(100)
            be.cancel("r4")
        }
        assertTrue(c.error is InferenceError.Cancelled)
        assertEquals(RuntimeState.READY, be.status().state)
    }

    @Test fun aCancelBeforeTheAnswerStartsIsKept() {
        val be = LlamaCppBackend(FakeNative())
        be.load(cfg(ggufFile()))
        be.cancel("r5")
        val c = ask(be, InferenceRequest("r5", "hello"))
        assertTrue(c.error is InferenceError.Cancelled)
    }

    @Test fun aSlowAnswerTimesOut() {
        val be = LlamaCppBackend(FakeNative().apply { waitForCancel = true })
        be.load(cfg(ggufFile()))
        val c = ask(be, InferenceRequest("r6", "hello", timeoutMs = 150))
        assertTrue(c.error is InferenceError.Timeout)
        assertEquals(RuntimeState.READY, be.status().state)
    }

    @Test fun theAnswerLimitIsPassedToTheEngine() {
        val n = FakeNative()
        val be = LlamaCppBackend(n)
        be.load(cfg(ggufFile()))
        ask(be, InferenceRequest("r7", "hello", maxOutputTokens = 128))
        assertEquals(128, n.lastMaxTokens)
    }

    @Test fun aListenerThatThrowsDoesNotStopTheRuntime() {
        val be = LlamaCppBackend(FakeNative())
        be.load(cfg(ggufFile()))
        val end = CountDownLatch(1)
        be.generate(InferenceRequest("r8", "hello", stream = true)) { e -> if (e.terminal) end.countDown(); throw IllegalStateException("listener bug") }
        assertTrue(end.await(5, TimeUnit.SECONDS))
        val c = ask(be, InferenceRequest("r9", "again"))
        assertNotNull(c.text)
    }

    @Test fun speedLineReadsTheEngineReport() {
        val r = JSONObject().put("promptTokens", 32).put("promptMs", 400).put("genTokens", 64).put("genMs", 4800).put("firstTokenMs", 900)
        assertEquals("Prompt: 32 tokens (80.0 tok/s) · Answer: 64 tokens (13.3 tok/s) · first word after 0.9 s", LlamaCppBackend.speedLine(r))
        assertEquals("", LlamaCppBackend.speedLine(null))
    }
}
