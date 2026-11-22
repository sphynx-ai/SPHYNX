package com.sphynx.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmprojPairingTest {

    private val projectors = listOf("mmproj-gemma-3-4b-it-f16.gguf", "Qwen2.5-VL-3B-Instruct-mmproj-f16.gguf", "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf")

    @Test fun projectorsAreRecognised() {
        assertTrue(MmprojPairing.isProjector("mmproj-gemma-3-4b-it-f16.gguf"))
        assertTrue(MmprojPairing.isProjector("Qwen2.5-VL-3B-Instruct-MMPROJ-f16.gguf"))
        assertFalse(MmprojPairing.isProjector("gemma-3-4b-it-Q4_K_M.gguf"))
    }

    @Test fun eachModelGetsItsOwnProjector() {
        assertEquals("mmproj-gemma-3-4b-it-f16.gguf", MmprojPairing.pairFor("gemma-3-4b-it-Q4_K_M.gguf", projectors))
        assertEquals("Qwen2.5-VL-3B-Instruct-mmproj-f16.gguf", MmprojPairing.pairFor("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", projectors))
        assertEquals("mmproj-SmolVLM-500M-Instruct-Q8_0.gguf", MmprojPairing.pairFor("SmolVLM-500M-Instruct-Q4_K_M.gguf", projectors))
    }

    @Test fun textModelsGetNone() {
        assertNull(MmprojPairing.pairFor("llama-3.2-1b-instruct-q4_k_m.gguf", projectors))
        assertNull(MmprojPairing.pairFor("qwen2.5-0.5b-instruct-q4_k_m.gguf", projectors))
        assertNull(MmprojPairing.pairFor("mmproj-gemma-3-4b-it-f16.gguf", projectors))   // a projector is not a model
    }

    @Test fun stems() {
        assertEquals("gemma34bit", MmprojPairing.stem("gemma-3-4b-it-Q4_K_M.gguf"))
        assertEquals("gemma34bit", MmprojPairing.stem("mmproj-gemma-3-4b-it-f16.gguf"))
    }
}
