package com.sphynx.inference

import com.sphynx.inference.GpuFallback.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 7 L4 (owner, 2026-11-23): fall back to the CPU once
class GpuFallbackTest {

    private val gpuErr = InferenceError.AcceleratorFailed(Accelerator.GPU, "OpenCL")

    @Test fun theFirstGpuFailureReloadsOnTheCpu() =
        assertEquals(Action.RELOAD_ON_CPU, GpuFallback.decide(Accelerator.GPU, false, gpuErr))

    @Test fun anNpuFailureAlsoReloadsOnTheCpu() {
        assertEquals(Action.RELOAD_ON_CPU, GpuFallback.decide(Accelerator.NPU, false, InferenceError.AcceleratorFailed(Accelerator.NPU, "x")))
        assertTrue(GpuFallback.reloadedNotice("strategic", true, gpuErr, Accelerator.NPU).contains("failed on the NPU"))
    }

    @Test fun aFailureAfterTheFallbackUnloads() =
        assertEquals(Action.UNLOAD, GpuFallback.decide(Accelerator.CPU, true, InferenceError.Internal("x")))

    @Test fun aCpuModelThatNeverFellBackIsLeftAlone() =
        assertEquals(Action.NONE, GpuFallback.decide(Accelerator.CPU, false, InferenceError.Internal("x")))

    @Test fun timeOutsAndCancelsAreNotTheProcessorsFault() {
        assertEquals(Action.NONE, GpuFallback.decide(Accelerator.GPU, false, InferenceError.Timeout(1000)))
        assertEquals(Action.NONE, GpuFallback.decide(Accelerator.GPU, true, InferenceError.Cancelled))
        assertEquals(Action.NONE, GpuFallback.decide(Accelerator.GPU, false, InferenceError.InvalidRequest(listOf("x"))))
    }

    @Test fun notices() {
        assertTrue(GpuFallback.reloadedNotice("strategic", true, gpuErr).contains("now runs on the CPU"))
        assertTrue(GpuFallback.reloadedNotice("strategic", false, gpuErr).contains("play goes on with the rules"))
        assertTrue(GpuFallback.unloadedNotice("strategic", gpuErr).contains("unloaded"))
    }
}
