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
}
