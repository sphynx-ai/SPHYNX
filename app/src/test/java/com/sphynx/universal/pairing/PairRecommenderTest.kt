package com.sphynx.universal.pairing

import com.sphynx.universal.pairing.PairRecommender.Candidate
import com.sphynx.universal.pairing.PairRecommender.Confidence
import com.sphynx.universal.pairing.PairRecommender.Measured
import com.sphynx.universal.pairing.Workloads.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 11 P2: the pair recommender
class PairRecommenderTest {

    private fun m(score: Double, p90: Double, whole: Double, n: Int = 120, reviewed: Boolean = true) =
        Measured(score, score - 0.05, score + 0.05, n, p90, whole, reviewed)
    private val gemma4 = Candidate("g4", "Gemma 4 E2B", Job.STRATEGIC, 2_468, true, false, true, m(0.8, 4_000.0, 9_000.0))
    private val qwenS = Candidate("q15", "Qwen2.5 1.5B", Job.STRATEGIC, 1_524, false, false, true, m(0.6, 2_000.0, 6_000.0))
    private val g270 = Candidate("g270", "Gemma 3 270M", Job.TACTICAL, 290, false, false, true, m(0.5, 600.0, 900.0))
    private val q06 = Candidate("q06", "Qwen3 0.6B", Job.TACTICAL, 585, false, false, true, m(0.7, 1_400.0, 1_800.0))
    private val smol = Candidate("smol", "SmolVLM 256M", Job.TACTICAL, 266, true, true, false, null)
    private val any: (Candidate, Candidate) -> Boolean = { _, _ -> true }

    @Test fun confidenceAndBudgets() {
        assertEquals(Confidence.HIGH, PairRecommender.confidence(gemma4))
        assertEquals(Confidence.MEDIUM, PairRecommender.confidence(gemma4.copy(measured = m(0.8, 1.0, 1.0, reviewed = false))))
        assertEquals(Confidence.MEDIUM, PairRecommender.confidence(gemma4.copy(measured = m(0.8, 1.0, 1.0, n = 10))))
        assertEquals(Confidence.LOW, PairRecommender.confidence(smol))
        assertTrue(PairRecommender.budgetMiss(q06)!!.contains("over the 1.0 s budget"))      // 1.4 s usable p90
        assertEquals(null, PairRecommender.budgetMiss(g270))
        assertEquals(null, PairRecommender.budgetMiss(smol))                                 // unknown is not a miss
    }

    @Test fun theFrontKeepsTradeOffsAndNeverOneBest() {
        val r = PairRecommender.recommend(listOf(gemma4, qwenS, g270, q06, smol), roomMb = 8_000, allowed = any)
        // gemma4+q06: best quality; qwenS+g270: fastest and lighter; smol pairs stay (unmeasured, lightest)
        assertTrue(r.size >= 3)
        assertTrue(r.any { "best measured quality" in it.labels })
        assertTrue(r.any { "fastest" in it.labels })
        assertTrue(r.any { "lightest" in it.labels })
        assertEquals(Confidence.HIGH, r.first().confidence)                                  // measured pairs first
        val low = r.first { it.tactical.id == "smol" }
        assertEquals(Confidence.LOW, low.confidence)
        assertTrue(low.reasons.any { it.contains("not measured on this phone yet") })
        assertTrue(low.reasons.any { it.contains("not on the phone yet") })
        assertTrue(r.first { it.strategic.id == "q15" }.reasons.any { it.contains("text only") })
    }

    @Test fun rulesAndMemoryRemovePairs() {
        val noGguf: (Candidate, Candidate) -> Boolean = { a, b -> !(a.gguf && b.gguf) }
        val ggufS = qwenS.copy(id = "qg", gguf = true)
        val r = PairRecommender.recommend(listOf(ggufS, smol), roomMb = -1, allowed = noGguf)
        assertTrue(r.isEmpty())                                          // two GGUF models can't run together
        val tight = PairRecommender.recommend(listOf(gemma4, g270), roomMb = 2_000, allowed = any)
        assertTrue(tight.isEmpty())                                      // 2,668 + 490 MB don't fit in 2 GB
        assertFalse(PairRecommender.recommend(listOf(gemma4, g270), roomMb = -1, allowed = any).isEmpty())
    }

    @Test fun aDominatedPairIsDropped() {
        val worse = g270.copy(id = "slow", label = "Slow 270M", sizeMb = 400, measured = m(0.4, 900.0, 1_200.0))
        val r = PairRecommender.recommend(listOf(gemma4, g270, worse), roomMb = -1, allowed = any)
        assertFalse(r.any { it.tactical.id == "slow" })                  // worse quality, slower, heavier
        assertTrue(PairRecommender.text(r.first()).startsWith("Gemma 4 E2B + Gemma 3 270M"))
    }
}
