package com.sphynx.inference.roles

import com.sphynx.inference.Accelerator.CPU
import com.sphynx.inference.Accelerator.GPU
import com.sphynx.inference.Accelerator.NPU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessorMemoryTest {

    private class Mem : LoadSentinel.Store {
        val m = HashMap<String, String?>()
        override fun get(key: String) = m[key]
        override fun setNow(key: String, value: String?) { m[key] = value }
    }

    private val store = Mem()
    private fun pm(stamp: String = "app 38.14, litertlm 0.19.1") = ProcessorMemory(store, stamp) { "2026-11-23" }
    private val all = listOf(NPU, GPU, CPU)
    private val gemma = ProcessorMemory.modelKey("gemma-4-E2B.litertlm", 2_600_000_000)

    @Test fun nothingKnownKeepsTheSwitchesOrder() = assertEquals(all, pm().order(gemma, all))

    @Test fun aFailedProcessorIsSkipped() {
        val p = pm(); p.failed(gemma, NPU)
        assertEquals(listOf(GPU, CPU), p.order(gemma, all))
        assertTrue(p.explain(gemma, all).contains("NPU: failed on 2026-11-23 (skipped until"))
    }

    @Test fun theFastestThatAnsweredGoesFirst() {
        val p = pm()
        p.loaded(gemma, NPU, 70_000); p.answered(gemma, NPU, 160, 4_000)   // 10 tok/s
        p.loaded(gemma, GPU, 8_000); p.answered(gemma, GPU, 208, 4_000)    // 13 tok/s
        assertEquals(listOf(GPU, NPU, CPU), p.order(gemma, all))
        assertEquals("Tried first: GPU. NPU: about 10.0 tokens/s, loads in 70.0 s; GPU: about 13.0 tokens/s, loads in 8.0 s; CPU: not tried yet",
            p.explain(gemma, all))
    }

    @Test fun aNewAppOrLiteRtVersionTriesAgain() {
        pm().failed(gemma, NPU)
        assertEquals(all, pm("app 38.15, litertlm 0.19.1").order(gemma, all))
    }

    @Test fun aNewFileOfTheSameNameStartsOver() {
        pm().failed(gemma, NPU)
        assertEquals(all, pm().order(ProcessorMemory.modelKey("gemma-4-E2B.litertlm", 2_700_000_000), all))
    }

    @Test fun theCpuIsNeverSkippedAndTheSwitchesLimit() {
        val p = pm(); p.failed(gemma, CPU); p.failed(gemma, GPU)
        assertEquals(listOf(CPU), p.order(gemma, listOf(GPU, CPU)))
        p.loaded(gemma, NPU, 1); p.answered(gemma, NPU, 400, 1_000)
        assertEquals(listOf(CPU), p.order(gemma, listOf(GPU, CPU)))
    }

    @Test fun encodeDecode() {
        val p = pm(); p.loaded(gemma, GPU, 8_123); p.answered(gemma, GPU, 100, 1_000)
        val r = ProcessorMemory.decode(store.m["proc_$gemma"])[GPU]!!
        assertTrue(r.ok); assertEquals(8_123L, r.loadMs); assertEquals(25.0, r.tokPerS, 0.01); assertEquals(1, r.answers)
    }
}
