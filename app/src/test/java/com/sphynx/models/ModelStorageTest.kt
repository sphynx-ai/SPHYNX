package com.sphynx.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStorageTest {
    private val now = 10_000_000_000L
    private val old = now - ModelStorage.BUSY_MS - 1
    private fun f(path: String, name: String, mod: Long = old) = ModelStorage.FileInfo(path, name, 100, mod)
    private val inFiles: (ModelStorage.FileInfo) -> Boolean = { it.path == "files" }

    @Test fun leftoversAreUnlistedModelsAndOldTemporaryFiles() {
        val files = listOf(
            f("files", "gemma-strategic.litertlm"),          // listed
            f("files", "gemma-strategic.litertlm.tmp"),      // old copy left behind
            f("files", "settings.json"),                     // the app's own file, never touched
            f("files", "other.tmp"),                         // not a model's temporary file
            f("gguf", "llama.gguf"),                         // listed
            f("gguf", "gone.gguf"),                          // no record
            f("gguf", "new.gguf.part", now - 1000),          // a copy still running
            f("gguf", "new2.gguf.part"),                     // an old broken copy
            f("litertlm", "notes.txt"),                      // nothing else belongs there
        )
        val left = ModelStorage.leftovers(files, setOf("gemma-strategic.litertlm", "llama.gguf"), now, inFiles).map { it.name }
        assertEquals(listOf("gemma-strategic.litertlm.tmp", "gone.gguf", "new2.gguf.part", "notes.txt"), left)
    }

    @Test fun roomWarnings() {
        assertNull(ModelStorage.roomWarning(5L shl 30))
        assertNull(ModelStorage.roomWarning(-1))
        assertTrue(ModelStorage.roomWarning(500L shl 20)!!.startsWith("Only 500 MB free"))
        assertTrue(ModelStorage.roomWarning(100L shl 20)!!.contains("almost full"))
    }

    @Test fun aCopyKeepsTheSpareRoom() {
        assertNull(ModelStorage.copyProblem(1L shl 30, 2L shl 30))
        assertNotNull(ModelStorage.copyProblem(1L shl 30, (1L shl 30) + (100L shl 20)))
        assertNull(ModelStorage.copyProblem(0, 0))
    }

    @Test fun summaryAddsUpByFormat() {
        val r = listOf(ModelRecord("a", "a.litertlm", ModelRecord.Format.LITERT_LM, 2000L shl 20),
            ModelRecord("b", "b.gguf", ModelRecord.Format.GGUF, 300L shl 20))
        assertEquals("Models 2,300 MB (LiteRT-LM 2,000, GGUF 300) · free 1,024 MB", ModelStorage.summary(r, 1L shl 30))
        assertEquals("Models 300 MB", ModelStorage.summary(r.drop(1), -1))
    }
}
