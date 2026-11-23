package com.sphynx.inference

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// PHASE 6, step S8: a GGUF model reads pictures once its mmproj file loads; without it, it stays text only.
class LlamaVisionTest {

    private class FakeNative(val projectorOk: Boolean) : LlamaNative {
        var projectorPath: String? = null
        var imageBytes: ByteArray? = null
        override val unavailableReason: String? = null
        override fun info() = "fake"
        override fun load(path: String, contextTokens: Int, threads: Int) = 9L
        override fun loadError() = ""
        override fun describe(handle: Long): JSONObject = JSONObject().put("model", "fake vision")
        override fun loadProjector(handle: Long, path: String, threads: Int): String { projectorPath = path; return if (projectorOk) "" else "bad mmproj" }
        override fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, seed: Int, onPiece: (ByteArray) -> Unit): JSONObject {
            onPiece("text answer".toByteArray()); return JSONObject().put("ok", true).put("stop", "eos")
        }
        override fun generateWithImage(handle: Long, prompt: String, image: ByteArray, maxTokens: Int, temperature: Float, seed: Int,
                                       onPiece: (ByteArray) -> Unit): JSONObject {
            imageBytes = image; onPiece("a draft screen".toByteArray()); return JSONObject().put("ok", true).put("stop", "eos")
        }
        override fun cancel(handle: Long) {}
        override fun free(handle: Long) {}
    }

    private val files = ArrayList<File>()
    @After fun cleanUp() { files.forEach { it.delete() } }
    private fun gguf(): File {
        val f = File.createTempFile("model", ".gguf"); files.add(f)
        RandomAccessFile(f, "rw").use { it.write(com.sphynx.testing.GgufFixture.model()); it.setLength(3L * 1024 * 1024) }
        return f
    }
    private fun cfg(f: File, vision: Boolean, projector: String?) = ModelConfiguration(id = "gguf:v", displayName = "gemma-3-4b-it-Q4_K_M.gguf",
        backend = BackendType.LLAMA_CPP, location = f.absolutePath, accelerators = listOf(Accelerator.CPU), contextTokens = 2048,
        maxOutputTokens = 256, wantsVision = vision, projector = projector)

    private fun ask(be: LlamaCppBackend, req: InferenceRequest): EventCollector {
        val c = EventCollector(req.id); val end = CountDownLatch(1)
        be.generate(req) { e -> c.accept(e); if (e.terminal) end.countDown() }
        assertTrue(end.await(5, TimeUnit.SECONDS))
        return c
    }

    @Test fun withItsProjectorTheModelReadsPictures() {
        val n = FakeNative(projectorOk = true)
        val be = LlamaCppBackend(n)
        val st = be.load(cfg(gguf(), vision = true, projector = "/lib/mmproj-gemma-3-4b-it-f16.gguf"))
        assertTrue(st.visionOk)
        assertEquals("/lib/mmproj-gemma-3-4b-it-f16.gguf", n.projectorPath)
        val c = ask(be, InferenceRequest("i", "What is on the screen?", image = byteArrayOf(1, 2, 3)))
        assertEquals("a draft screen", c.text)
        assertEquals(3, n.imageBytes?.size)
        assertEquals("text answer", ask(be, InferenceRequest("t", "hi")).text)          // text questions as before
    }

    @Test fun aBadProjectorLeavesTheModelTextOnly() {
        val be = LlamaCppBackend(FakeNative(projectorOk = false))
        val st = be.load(cfg(gguf(), vision = true, projector = "/lib/broken-mmproj.gguf"))
        assertEquals(RuntimeState.READY, st.state)
        assertFalse(st.visionOk)
        assertEquals("bad mmproj", be.projectorError)
        val c = ask(be, InferenceRequest("i", "What is on the screen?", image = byteArrayOf(1)))
        assertEquals("invalid_request", c.error?.code)                               // refused, never silently dropped
    }

    // the tactical job does not want pictures: its projector is not loaded (it would only cost memory)
    @Test fun noProjectorWhenPicturesAreNotWanted() {
        val n = FakeNative(projectorOk = true)
        val st = LlamaCppBackend(n).load(cfg(gguf(), vision = false, projector = "/lib/mmproj-x.gguf"))
        assertFalse(st.visionOk)
        assertEquals(null, n.projectorPath)
    }
}
