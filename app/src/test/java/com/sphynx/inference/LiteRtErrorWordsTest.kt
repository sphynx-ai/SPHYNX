package com.sphynx.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 7 L5: LiteRT-LM's failures in the shared error words, with what to do
class LiteRtErrorWordsTest {

    private val w = LiteRtErrorWords

    @Test fun memory() {
        assertTrue(w.classify("LiteRtLmJniException", "Failed to allocate tensor buffer", loading = true) is InferenceError.NotEnoughMemory)
        assertTrue(w.classify("OutOfMemoryError", null, loading = false) is InferenceError.NotEnoughMemory)
        assertTrue(w.words(InferenceError.NotEnoughMemory(-1, -1)).contains("smaller model"))
    }

    @Test fun anUnreadableFileIsAnUnsupportedFormat() {
        assertTrue(w.forLoadMessage("Unsupported model type: foo") is InferenceError.UnsupportedFormat)
        assertTrue(w.forLoadMessage("flatbuffer verification failed") is InferenceError.UnsupportedFormat)
    }

    @Test fun anOtherLoadFailureStaysALoadFailure() {
        val e = w.forLoadMessage("something went wrong")
        assertTrue(e is InferenceError.LoadFailed)
        assertTrue(w.words(e).contains("GPU switch off"))
    }

    @Test fun aNativeErrorOnTheGpuIsTheGpus() {
        assertTrue(w.classify("LiteRtLmJniException", "OpenCL error -5", loading = false, on = Accelerator.GPU) is InferenceError.AcceleratorFailed)
        assertTrue(w.classify("LiteRtLmJniException", "INTERNAL: something", loading = false, on = Accelerator.GPU) is InferenceError.AcceleratorFailed)
        // the same message on the CPU is not a GPU failure
        assertTrue(w.classify("LiteRtLmJniException", "INTERNAL: something", loading = false, on = Accelerator.CPU) is InferenceError.Internal)
    }

    @Test fun npuFailures() {
        val e = w.classify("LiteRtLmJniException", "Neuron dispatch failed", loading = false, on = Accelerator.NPU)
        assertTrue(e is InferenceError.AcceleratorFailed)
        assertEquals("accelerator_failed", e.code)
    }

    @Test fun cancelled() {
        assertEquals(InferenceError.Cancelled, w.classify("IllegalStateException", "Process cancelled", loading = false))
    }
}
