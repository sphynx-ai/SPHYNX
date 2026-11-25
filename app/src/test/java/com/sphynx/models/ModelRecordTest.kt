package com.sphynx.models

import com.sphynx.models.ModelRecord.Format
import com.sphynx.models.ModelRecord.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 9 M1: the model registry's records
class ModelRecordTest {

    private val llama = ModelRecord(id = "Llama-3.2-1B.gguf:807403520", fileName = "Llama-3.2-1B.gguf", format = Format.GGUF,
        sizeBytes = 807_403_520, family = "llama", quantization = "Q4_K_M", sizeLabel = "1B", contextLength = 131072,
        pictures = false, source = Source.PICKED, addedAt = 1000, settings = mapOf("temperature" to "0.2"))

    @Test fun encodeDecodeKeepsEverything() {
        val back = ModelRecord.decodeAll(ModelRecord.encodeAll(listOf(llama)))
        assertEquals(listOf(llama), back)
    }

    @Test fun brokenOrNewerFilesGiveNothing() {
        assertEquals(emptyList<ModelRecord>(), ModelRecord.decodeAll("not json"))
        assertEquals(emptyList<ModelRecord>(), ModelRecord.decodeAll("{\"schema\": 99, \"models\": []}"))
        assertEquals(emptyList<ModelRecord>(), ModelRecord.decodeAll(null))
        // one broken entry is skipped, the rest is kept
        val t = "{\"schema\":1,\"models\":[{\"id\":\"x\"}," + llama.toJson() + "]}"
        assertEquals(listOf(llama), ModelRecord.decodeAll(t))
    }

    @Test fun aScanKeepsWhatWasLearntAndDropsGoneFiles() {
        val gemma = ModelRecord(id = "gemma-strategic.litertlm:2600", fileName = "gemma-strategic.litertlm", format = Format.LITERT_LM,
            sizeBytes = 2600, pictures = true, source = Source.INPUT_FOLDER, originalName = "gemma-4-E2B-it.litertlm", addedAt = 5)
        val gone = gemma.copy(id = "old.litertlm:1", fileName = "old.litertlm", sizeBytes = 1)
        // the scan sees the files with less knowledge (no pictures, unknown source, no settings)
        val scanned = listOf(llama.copy(pictures = null, source = Source.UNKNOWN, settings = emptyMap(), addedAt = 9),
            gemma.copy(pictures = null, originalName = null))
        val merged = ModelRecord.merge(listOf(llama, gemma, gone), scanned)
        assertEquals(2, merged.size)
        val l = merged.first { it.fileName == llama.fileName }
        assertEquals(false, l.pictures); assertEquals(Source.PICKED, l.source); assertEquals("0.2", l.settings["temperature"])
        assertEquals(1000L, l.addedAt)
        val g = merged.first { it.fileName == gemma.fileName }
        assertEquals(true, g.pictures); assertEquals("gemma-4-E2B-it.litertlm", g.originalName)
    }

    @Test fun aKnownHashBecomesTheId() {
        val withHash = llama.copy(sha256 = "ab".repeat(32))
        val merged = ModelRecord.merge(listOf(withHash), listOf(llama.copy(sha256 = null)))
        assertEquals("ab".repeat(32), merged.single().id)
    }

    @Test fun formats() {
        assertEquals(Format.LITERT_LM, ModelRecord.formatOf("gemma.litertlm", false))
        assertEquals(Format.GGUF, ModelRecord.formatOf("Llama.gguf", false))
        assertEquals(Format.MMPROJ, ModelRecord.formatOf("mmproj-gemma-3-4b-it-f16.gguf", false))
        assertEquals(Format.MMPROJ, ModelRecord.formatOf("vision.gguf", true))
        assertNull(ModelRecord.formatOf("detector.tflite", false))
    }

    @Test fun summaryLine() {
        assertTrue(llama.summary().startsWith("Llama-3.2-1B.gguf · GGUF · llama · Q4_K_M · 1B · 770 MB · text only"))
    }

    // Phase 9 M6: the loose keys move into the record without loss
    @Test fun theOldKeysMoveIn() {
        val sha = "a".repeat(64)
        val gemma = ModelRecord("gemma-tactical.litertlm:600", "gemma-tactical.litertlm", Format.LITERT_LM, 600)
        val old = mapOf("vision_gemma-tactical.litertlm" to "0", "proc_gemma-tactical.litertlm:600" to "GPU|1|900|12.000|3|s|d",
            "sha256_gemma-tactical.litertlm:600" to sha, "gguf_threads_gemma-tactical.litertlm_600" to "")
        val m = ModelRecord.migrate(gemma, old::get)
        assertEquals(mapOf("vision" to "0", "processors" to "GPU|1|900|12.000|3|s|d"), m.facts)
        assertEquals(sha, m.sha256); assertEquals(sha, m.id); assertEquals(false, m.pictures)
        // nothing there: the same record; the threads key of a GGUF file
        assertTrue(ModelRecord.migrate(gemma) { null } === gemma)
        val t = ModelRecord.migrate(llama, mapOf("gguf_threads_Llama-3.2-1B.gguf_807403520" to "4")::get)
        assertEquals("4", t.facts["threads"]); assertEquals(false, t.pictures)
        // an old key there is never older than the record in this version: it wins; facts survive a save and a scan
        val again = ModelRecord.migrate(m, mapOf("vision_gemma-tactical.litertlm" to "1")::get)
        assertEquals("1", again.facts["vision"])
        val kept = ModelRecord.merge(ModelRecord.decodeAll(ModelRecord.encodeAll(listOf(again))), listOf(gemma.copy(sha256 = sha, id = sha)))
        assertEquals(again.facts, kept.single().facts)
    }

    @Test fun theOldThreadsKeyIsTheOneTheTuningUsed() {
        assertEquals(com.sphynx.inference.GgufTuning.threadsKey("x.gguf", 7), ModelRecord.Fact.THREADS.legacyKey("x.gguf", 7))
        assertEquals(com.sphynx.inference.roles.ProcessorMemory.modelKey("x.gguf", 7).let { "proc_$it" },
            ModelRecord.Fact.PROCESSORS.legacyKey("x.gguf", 7))
    }
}
