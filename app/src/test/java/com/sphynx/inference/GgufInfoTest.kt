package com.sphynx.inference

import com.sphynx.testing.GgufFixture
import com.sphynx.testing.GgufFixture.V
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

// Phase 8 G2: a GGUF file's description, read before native code opens it
class GgufInfoTest {

    private fun read(b: ByteArray, size: Long = b.size.toLong()) = GgufInfo.read(ByteArrayInputStream(b), size)

    @Test fun aModelIsDescribed() {
        val i = read(GgufFixture.model(arch = "llama", fileType = 15, ctx = 131072)).getOrThrow()
        assertEquals("llama", i.architecture); assertEquals("Q4_K_M", i.quantization); assertEquals(131072L, i.contextLength)
        assertTrue(i.hasChatTemplate); assertFalse(i.isProjector); assertEquals("Test Model", i.name)
        assertEquals("llama · Q4_K_M · 1B · trained for 131,072 tokens · chat format", i.summary())
        assertNull(i.problemAsModel())
    }

    @Test fun aProjectorIsNotAModel() {
        val b = GgufFixture.bytes(listOf("general.architecture" to V.S("clip"), "general.type" to V.S("mmproj"),
            "clip.has_vision_encoder" to V.B(true)))
        val i = read(b).getOrThrow()
        assertTrue(i.isProjector)
        assertTrue(i.problemAsModel()!!.contains("picture projector"))
    }

    @Test fun aNewerFamilyIsRefusedBeforeLoading() =
        assertTrue(read(GgufFixture.model(arch = "brand-new-arch")).getOrThrow().problemAsModel()!!.contains("newer than this app's llama.cpp"))

    @Test fun noChatTemplateIsSaid() =
        assertTrue(read(GgufFixture.model(template = null)).getOrThrow().summary().endsWith("no chat format"))

    @Test fun aCutFileIsTheUnfinishedDownload() {
        val b = GgufFixture.model()
        assertTrue(read(b.copyOf(b.size - 10), -1).exceptionOrNull()!!.message!!.contains("did not finish"))
    }

    @Test fun aLengthLongerThanTheFileIsRefused() {
        val b = GgufFixture.model()
        // the size bound says the file is shorter than its own strings
        assertTrue(read(b, 60).exceptionOrNull()!!.message!!.contains("longer than the file"))
    }

    @Test fun hostileCountsAreRefusedWithoutAllocating() {
        val b = GgufFixture.bytes(emptyList()).copyOf(24)
        // key count = 2^40
        b[16] = 0; b[17] = 0; b[18] = 0; b[19] = 0; b[20] = 0; b[21] = 1; b[22] = 0; b[23] = 0
        assertTrue(read(b, -1).exceptionOrNull()!!.message!!.contains("claims"))
        val bigString = GgufFixture.bytes(listOf("general.architecture" to V.S("llama"))).also {
            // the key name length (right after the header) set to 1 TB
            it[24] = 0; it[25] = 0; it[26] = 0; it[27] = 0; it[28] = 0; it[29] = 1
        }
        assertTrue(read(bigString, -1).exceptionOrNull()!!.message!!.contains("MB long"))
    }

    @Test fun notGgufAndOldVersions() {
        assertTrue(read("GGML....".toByteArray()).exceptionOrNull()!!.message!!.contains("not a GGUF"))
        assertTrue(read(GgufFixture.bytes(emptyList(), version = 1)).exceptionOrNull()!!.message!!.contains("version 1"))
        assertTrue(read(GgufFixture.bytes(emptyList())).exceptionOrNull()!!.message!!.contains("general.architecture"))
    }

    @Test fun unknownFileTypesAreShownAsNumbers() =
        assertEquals("type 99", read(GgufFixture.model(fileType = 99)).getOrThrow().quantization)

    // the list of model families must match the vendored llama.cpp (a G7 update that adds families fails here)
    @Test fun theFamilyListMatchesTheBuiltInLlamaCpp() {
        val src = listOf("src/main/cpp/llama.cpp/src/llama-arch.cpp", "app/src/main/cpp/llama.cpp/src/llama-arch.cpp")
            .map { File(it) }.first { it.isFile }.readText()
        val block = src.substringAfter("LLM_ARCH_NAMES = {").substringBefore("};")
        val names = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.filter { it != "clip" && it != "(unknown)" }.toSet()
        assertEquals(names, GgufArchitectures.KNOWN)
    }
}
