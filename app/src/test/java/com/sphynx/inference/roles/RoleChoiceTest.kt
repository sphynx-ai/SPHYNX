package com.sphynx.inference.roles

import com.sphynx.inference.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleChoiceTest {

    @Test fun theDefaultIsTheLiteRtLmFile() {
        assertNull(RoleChoice.ggufFile(null))
        assertNull(RoleChoice.ggufFile(RoleChoice.LITERT))
        assertNull(RoleChoice.ggufFile("gguf:"))
        assertNull(RoleChoice.ggufFile("something"))
        assertEquals("qwen.gguf", RoleChoice.ggufFile(RoleChoice.gguf("qwen.gguf")))
    }

    @Test fun oneGgufModelAtATime() {
        val why = RoleChoice.problem(ModelRole.STRATEGIC, RoleChoice.gguf("llama.gguf"), RoleChoice.gguf("qwen.gguf"))
        assertTrue(why!!.contains("tactical job already uses qwen.gguf"))
        assertNull(RoleChoice.problem(ModelRole.STRATEGIC, RoleChoice.gguf("llama.gguf"), RoleChoice.LITERT))
        assertNull(RoleChoice.problem(ModelRole.TACTICAL, RoleChoice.LITERT, RoleChoice.gguf("qwen.gguf")))
    }

    @Test fun labels() {
        assertEquals("Gemma 4 E2B (LiteRT-LM)", RoleChoice.label(ModelRole.STRATEGIC, null, "Gemma 4 E2B"))
        assertEquals("qwen.gguf (GGUF, CPU, text only)", RoleChoice.label(ModelRole.STRATEGIC, RoleChoice.gguf("qwen.gguf"), "x"))
        assertEquals("qwen.gguf (GGUF, CPU)", RoleChoice.label(ModelRole.TACTICAL, RoleChoice.gguf("qwen.gguf"), "x"))
        assertEquals("gemma-3-4b.gguf (GGUF, CPU, reads pictures)", RoleChoice.label(ModelRole.STRATEGIC, RoleChoice.gguf("gemma-3-4b.gguf"), "x", withPictures = true))
    }

    // Phase 9 M3: a .litertlm from the app's LiteRT-LM library can do a job
    @Test fun aLibraryLiteRtLmFile() {
        assertEquals("gemma-3n-E2B.litertlm", RoleChoice.liteFile(RoleChoice.lite("gemma-3n-E2B.litertlm")))
        assertEquals(null, RoleChoice.liteFile(RoleChoice.LITERT))
        assertEquals(null, RoleChoice.liteFile(RoleChoice.gguf("x.gguf")))
        assertEquals(null, RoleChoice.ggufFile(RoleChoice.lite("x.litertlm")))
        assertEquals("gemma-3n-E2B.litertlm (LiteRT-LM)", RoleChoice.label(ModelRole.STRATEGIC, RoleChoice.lite("gemma-3n-E2B.litertlm"), "Gemma"))
        // a library file and a GGUF can share the two jobs
        assertEquals(null, RoleChoice.problem(ModelRole.TACTICAL, RoleChoice.lite("a.litertlm"), RoleChoice.gguf("b.gguf")))
    }
}
