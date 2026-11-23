package com.sphynx.inference

import com.sphynx.inference.GgufTuning.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 8 G5/G6: choices made from the GGUF benchmark
class GgufTuningTest {

    // writing speed [tps] for 64 tokens
    private fun r(threads: Int, tps: Double, kv8: Boolean = false, cold: Boolean = false, answer: String = "farm the jungle") =
        Run(threads, kv8, cold, 40, 400, 64, (64_000 / tps).toLong(), 500, answer)

    @Test fun theDefaultStaysUnlessAnotherIsClearlyFaster() {
        val runs = listOf(r(4, 13.0), r(4, 13.2), r(2, 9.0), r(6, 13.8))     // 6 threads only ~5% faster: noise
        assertEquals(4, GgufTuning.bestThreads(runs, cores = 8))
    }

    @Test fun aClearlyFasterCountIsKept() {
        assertEquals(6, GgufTuning.bestThreads(listOf(r(4, 10.0), r(2, 7.0), r(6, 12.0)), cores = 8))
        assertEquals(2, GgufTuning.bestThreads(listOf(r(4, 8.0), r(2, 9.5), r(6, 6.0)), cores = 8))
    }

    @Test fun coldRunsAndMissingSettingsAreIgnored() {
        val runs = listOf(r(4, 10.0), r(6, 30.0, cold = true))               // only a cold run for 6 threads
        assertEquals(4, GgufTuning.bestThreads(runs, cores = 8))
        assertEquals(4, GgufTuning.bestThreads(emptyList(), cores = 8))
        assertEquals(4, GgufTuning.bestThreads(listOf(r(4, 5.0), r(6, 20.0)), cores = 4))   // 6 threads on a 4-core phone
    }

    @Test fun eightBitCacheNoLossNeedsSameAnswersAndSpeed() {
        assertEquals(true, GgufTuning.kv8NoLoss(listOf(r(4, 13.0), r(4, 12.8, kv8 = true))))
        assertEquals(false, GgufTuning.kv8NoLoss(listOf(r(4, 13.0), r(4, 13.0, kv8 = true, answer = "retreat"))))
        assertEquals(false, GgufTuning.kv8NoLoss(listOf(r(4, 13.0), r(4, 11.0, kv8 = true))))
        assertNull(GgufTuning.kv8NoLoss(listOf(r(4, 13.0))))
    }

    @Test fun theSummaryLine() {
        val s = GgufTuning.summary(listOf(r(4, 13.0), r(2, 9.0), r(6, 13.5), r(4, 12.9, kv8 = true)), cores = 8)
        assertEquals("Writing (tokens/s): 2 threads 9.0, 4 threads 13.0, 6 threads 13.5; 8-bit cache 12.9 (no loss). Best: 4 threads", s)
    }

    @Test fun memoryEstimateHalvesTheContextWithTheEightBitCache() {
        val f16 = GgufFile.estimatedMemoryBytes(800L shl 20, 4096)
        val q8 = GgufFile.estimatedMemoryBytes(800L shl 20, 4096, kv8 = true)
        assertEquals(4096L * 32 * 1024, f16 - q8)
        assertTrue(q8 < f16); assertFalse(q8 <= 0)
    }

    // Phase 8 G9: the memory after each unload must come back (counted from the second unload)
    @Test fun leakCheck() {
        assertEquals("Load and unload 5 times: memory after unload 410 → 418 MB (+8 MB: comes back)",
            GgufTuning.leakSummary(listOf(520, 410, 412, 415, 418)))
        assertTrue(GgufTuning.leakSummary(listOf(520, 410, 450, 500, 560)).contains("+150 MB: possible leak"))
        assertNull(GgufTuning.leakGrowthMb(listOf(500, 400)))
        assertEquals(-10L, GgufTuning.leakGrowthMb(listOf(-1, 500, 410, 400)))
    }
}
