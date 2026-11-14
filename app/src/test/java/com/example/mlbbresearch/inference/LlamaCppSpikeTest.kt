package com.example.mlbbresearch.inference

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppSpikeTest {

    // the JSON shape below is what llama_spike.cpp returned on the computer-side check
    @Test fun okReportIsReadable() {
        val j = JSONObject("""{"ok":true,"error":"","model":"qwen2 0.5B Q4_K - Medium","loadMs":1200,"promptTokens":42,
            "promptMs":800,"genTokens":32,"genMs":2000,"sizeBytes":${400L * 1024 * 1024},"threads":4,"templated":true,"text":" Farm your camps. "}""")
        val r = LlamaCppSpike.format("qwen.gguf", j, 4100)
        assertTrue(r.startsWith("qwen.gguf: OK\n"))
        assertTrue(r.contains("Model: qwen2 0.5B Q4_K - Medium (400 MB)"))
        assertTrue(r.contains("Load: 1.2 s · threads 4 · chat format yes"))
        assertTrue(r.contains("Prompt: 42 tokens in 0.8 s (52.5 tok/s)"))
        assertTrue(r.contains("Answer: 32 tokens in 2.0 s (16.0 tok/s)"))
        assertTrue(r.endsWith("Text: Farm your camps."))
    }

    @Test fun failureShowsTheReason() {
        val j = JSONObject("""{"ok":false,"error":"the model could not be loaded","model":"","loadMs":0,"promptTokens":0,"promptMs":0,"genTokens":0,"genMs":0,"sizeBytes":0,"threads":4,"templated":false,"text":""}""")
        assertEquals("x.gguf: FAILED - the model could not be loaded", LlamaCppSpike.format("x.gguf", j, 10))
    }

    @Test fun brokenUtf8FromTheModelIsReplacedNotFatal() {
        // a 4-byte emoji cut after 2 bytes at the token limit, then the end of the JSON
        val bytes = """{"ok":true,"text":"hi """.toByteArray() + byteArrayOf(0xF0.toByte(), 0x9F.toByte()) + "\"}".toByteArray()
        val j = JSONObject(String(bytes, Charsets.UTF_8))
        assertTrue(j.getString("text").startsWith("hi "))
    }

    @Test fun threadsStayBetweenOneAndFour() {
        assertTrue(LlamaCppSpike.threads() in 1..4)
    }

    // the picked file is checked before llama.cpp loads it, so a bad download says what is wrong
    @Test fun aRealGgufHeaderPasses() {
        val head = "GGUF".toByteArray() + byteArrayOf(3, 0, 0, 0)
        assertEquals(null, LlamaCppSpike.checkHeader(head, 400L * 1024 * 1024))
        assertEquals(null, LlamaCppSpike.checkHeader(head, -1))   // size unknown: let llama.cpp decide
    }

    @Test fun badDownloadsAreNamed() {
        assertTrue(LlamaCppSpike.checkHeader("GGUF".toByteArray(), 2048)!!.contains("did not finish"))
        assertTrue(LlamaCppSpike.checkHeader("<!DOCTYPE html>".toByteArray(), 9000)!!.contains("web page"))
        assertTrue(LlamaCppSpike.checkHeader("version https://git-lfs.github.com/spec/v1".toByteArray(), 130)!!.contains("Git LFS"))
        assertTrue(LlamaCppSpike.checkHeader(ByteArray(0), 0)!!.contains("empty"))
        assertTrue(LlamaCppSpike.checkHeader(byteArrayOf(0x50, 0x4B, 3, 4), 5000)!!.contains("50 4B 03 04"))
    }

    @Test fun aFailureShowsWhatLlamaCppSaid() {
        val log = "llama_model_load: error loading model: unknown model architecture: 'qwen9'\n.......\n" +
            "llama_model_load_from_file_impl: failed to load model\n"
        val j = JSONObject().put("ok", false).put("error", "the model could not be loaded").put("log", log)
        val r = LlamaCppSpike.format("q.gguf", j, 10, "read in place and copied: both failed", 1800)
        assertTrue(r.startsWith("q.gguf: FAILED - the model could not be loaded\n"))
        assertTrue(r.contains("File: read in place and copied: both failed"))
        assertTrue(r.contains("Free memory: 1800 MB"))
        assertTrue(r.contains("unknown model architecture: 'qwen9'"))
        assertTrue(!r.contains("......."))   // progress dots are left out
    }
}
