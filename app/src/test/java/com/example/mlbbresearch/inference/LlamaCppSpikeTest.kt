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
}
