package com.sphynx.adapters.mlbb

import com.sphynx.adapters.mlbb.MlbbStrategyEvidence.Context
import com.sphynx.adapters.mlbb.MlbbStrategyEvidence.Hypothesis
import com.sphynx.adapters.mlbb.MlbbStrategyEvidence.Kind
import com.sphynx.adapters.mlbb.MlbbStrategyEvidence.Level
import com.sphynx.adapters.mlbb.MlbbStrategyEvidence.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 13 D5: hypotheses stay hypotheses until the evidence passes the thresholds
class MlbbStrategyEvidenceTest {

    private fun m(i: Int, won: Boolean, patch: String = "1.9.20", team: String = "T${i % 4}", tour: String = "MPL ${i % 2}", complete: Boolean = true) =
        Match("m$i", "test", null, patch, i * 1000L, team, tour, won, complete)
    private fun h(ms: List<Match>) = Hypothesis("h1", Kind.ROTATION, "Purple buff first, 4-1", "d", Context.PROFESSIONAL,
        setOf("purple-buff", "4-1", "early-game", "professional"), ms)

    @Test fun levelsFollowTheThresholds() {
        assertEquals(Level.EXPERIMENTAL, MlbbStrategyEvidence.assess(h(emptyList()), "1.9.20").level)
        assertEquals(Level.EXPERIMENTAL, MlbbStrategyEvidence.assess(h((0 until 3).map { m(it, true) }), "1.9.20").level)
        assertEquals(Level.EMERGING, MlbbStrategyEvidence.assess(h((0 until 10).map { m(it, true) }), "1.9.20").level)
        val strong = (0 until 40).map { m(it, it % 10 < 8) }                       // 80% over 40 matches, 4 teams, 2 tournaments
        val a = MlbbStrategyEvidence.assess(h(strong), "1.9.20")
        assertEquals(Level.VALIDATED, a.level); assertEquals(0.8, a.winRate!!, 1e-9)
        val coinFlip = (0 until 40).map { m(it, it % 2 == 0) }                     // 50%: not above the baseline
        assertTrue(MlbbStrategyEvidence.assess(h(coinFlip), "1.9.20").why.contains("not clearly above"))
        val oneTeam = (0 until 40).map { m(it, true, team = "Same") }
        assertTrue(MlbbStrategyEvidence.assess(h(oneTeam), "1.9.20").why.contains("not one team's habit"))
    }

    @Test fun oldPatchesAndIncompleteMatchesDontCount() {
        assertEquals(Level.OUTDATED, MlbbStrategyEvidence.assess(h((0 until 40).map { m(it, true, patch = "1.9.10") }), "1.9.20").level)
        assertEquals(Level.EXPERIMENTAL, MlbbStrategyEvidence.assess(h((0 until 40).map { m(it, true, complete = false) }), "1.9.20").level)
    }

    @Test fun tagsAndSearch() {
        assertEquals("purple-buff", MlbbStrategyEvidence.tag(" Purple Buff "))
        assertEquals("1.9.20", MlbbStrategyEvidence.tag("1.9.20"))
        val hs = listOf(h(emptyList()), h(emptyList()).copy(id = "h2", title = "Split push 1-3-1", tags = setOf("1-3-1", "late-game")))
        assertEquals(listOf("h1"), MlbbStrategyEvidence.search(hs, "purple early").map { it.id })
        assertEquals(listOf("h2"), MlbbStrategyEvidence.search(hs, "split").map { it.id })
        assertEquals(2, MlbbStrategyEvidence.search(hs, "").size)
    }
}
