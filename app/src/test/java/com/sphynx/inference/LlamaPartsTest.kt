package com.sphynx.inference

import com.sphynx.models.GgufLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// PHASE 8: the small pure parts of the GGUF backend: CPU check, streaming UTF-8, file check, names.
class LlamaPartsTest {

    // ---- CPU ----

    private val modernCpu = """
        processor : 0
        Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        processor : 4
        Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
    """.trimIndent()

    @Test fun aModernPhoneCanRunTheEngine() {
        assertNull(LlamaCpu.unsupportedReason(listOf("arm64-v8a", "armeabi-v7a"), modernCpu))
    }

    @Test fun anOldCoreIsRefusedInsteadOfCrashing() {
        // Cortex-A53: no dot product, no fp16 arithmetic
        val a53 = "processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"
        val why = LlamaCpu.unsupportedReason(listOf("arm64-v8a"), a53)!!
        assertTrue(why.contains("asimddp"))
        assertTrue(why.contains("fphp"))
        // one core without it is enough to refuse (big.LITTLE phones list each core)
        assertTrue(LlamaCpu.missingFeatures(modernCpu + "\nprocessor : 7\nFeatures : fp asimd fphp").isNotEmpty())
    }

    @Test fun onlyArm64PhonesAreSupported() {
        assertTrue(LlamaCpu.unsupportedReason(listOf("x86_64"), modernCpu)!!.contains("arm64-v8a"))
        assertNull(LlamaCpu.unsupportedReason(listOf("arm64-v8a"), null))     // unreadable list: let the library try
    }

    @Test fun threadsStayBetweenOneAndFour() {
        assertEquals(4, LlamaCpu.threads(8))
        assertEquals(1, LlamaCpu.threads(0))
        assertEquals(2, LlamaCpu.threads(2))
    }

    // ---- streaming UTF-8 ----

    @Test fun anIncompleteCharacterWaitsForTheRest() {
        val s = Utf8Stream()
        val euro = "€".toByteArray(Charsets.UTF_8)                 // 3 bytes
        assertEquals("a", s.push(byteArrayOf('a'.code.toByte(), euro[0])))
        assertEquals("", s.push(byteArrayOf(euro[1])))
        assertEquals("€b", s.push(byteArrayOf(euro[2], 'b'.code.toByte())))
        assertEquals("", s.finish())
        assertEquals("a€b", s.text())
    }

    @Test fun aCharacterCutAtTheEndBecomesAReplacementNotAnError() {
        val s = Utf8Stream()
        s.push("ok ".toByteArray() + "😀".toByteArray(Charsets.UTF_8).copyOf(2))
        assertEquals("�", s.finish())
        assertEquals("ok �", s.text())
    }

    @Test fun completeLengthHoldsBackOnlyTheUnfinishedCharacter() {
        assertEquals(3, Utf8Stream.completeLength("abc".toByteArray()))
        assertEquals(1, Utf8Stream.completeLength(byteArrayOf(0x41, 0xE2.toByte(), 0x82.toByte())))
        assertEquals(3, Utf8Stream.completeLength(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte())))
        assertEquals(0, Utf8Stream.completeLength(byteArrayOf(0xF0.toByte())))
    }

    // ---- the GGUF file check (from the 2026-11-14 spike) ----

    @Test fun aRealGgufHeaderPasses() {
        val head = "GGUF".toByteArray() + byteArrayOf(3, 0, 0, 0)
        assertNull(GgufFile.checkHeader(head, 400L * 1024 * 1024))
        assertNull(GgufFile.checkHeader(head, -1))
    }

    @Test fun badDownloadsAreNamed() {
        assertTrue(GgufFile.checkHeader("GGUF".toByteArray(), 2048)!!.contains("did not finish"))
        assertTrue(GgufFile.checkHeader("<!DOCTYPE html>".toByteArray(), 9000)!!.contains("web page"))
        assertTrue(GgufFile.checkHeader("version https://git-lfs.github.com/spec/v1".toByteArray(), 130)!!.contains("Git LFS"))
        assertTrue(GgufFile.checkHeader(ByteArray(0), 0)!!.contains("empty"))
        assertTrue(GgufFile.checkHeader(byteArrayOf(0x50, 0x4B, 3, 4), 5000)!!.contains("50 4B 03 04"))
    }

    @Test fun memoryEstimateCoversTheFileAndTheContext() {
        val file = 762L * 1024 * 1024
        val need = GgufFile.estimatedMemoryBytes(file, 2048)
        assertTrue(need > file)
        assertTrue(need < file * 2)
    }

    // ---- names of added models ----

    @Test fun anAddedModelKeepsASafeName() {
        assertEquals("Llama-3.2-1B-Instruct-Q4_K_M.gguf", GgufLibrary.safeName("Llama-3.2-1B-Instruct-Q4_K_M.gguf"))
        assertEquals("evil.gguf", GgufLibrary.safeName("../../evil.gguf"))
        assertEquals("my_model__1_.gguf", GgufLibrary.safeName("my model (1).gguf"))
        assertEquals("gguf", GgufLibrary.safeName(".gguf"))
        assertEquals("model.gguf", GgufLibrary.safeName(""))
    }

    // Phase 8 G4: the native engine's out-of-memory is the shared NotEnoughMemory, with words that make sense
    @Test fun nativeOutOfMemoryIsNotEnoughMemory() {
        val e = LlamaCppBackend.nativeError("not enough memory for this answer", loading = false)
        assertTrue(e is InferenceError.NotEnoughMemory)
        assertEquals("the phone ran out of memory for this model", e.message)
        assertTrue(LlamaCppBackend.nativeError("the context could not be created (not enough memory for 4096 tokens?)", true) is InferenceError.NotEnoughMemory)
        assertTrue(LlamaCppBackend.nativeError("unknown model architecture", true) is InferenceError.LoadFailed)
        assertTrue(LlamaCppBackend.nativeError("no model loaded", false) is InferenceError.Internal)
    }
}
