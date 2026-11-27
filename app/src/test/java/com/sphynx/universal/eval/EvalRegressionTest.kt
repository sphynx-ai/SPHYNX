package com.sphynx.universal.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 10 E7: the regression thresholds
class EvalRegressionTest {

    private val good = EvalRecord.Conditions(false, false, 80, false, 0, 30f)
    private val run = EvalRecord.RunInfo("r1", "mlbb-tactical", 1, "tactical", "a".repeat(64), "g.litertlm", "LITERT_LM", null,
        "LITERT_LM", "GPU", "phone", "Android 14", "38.49", "litertlm 0.19.1", emptyMap(), 3, good, 100)

    private fun sum(id: String, at: Long, app: String, best: Int, usable: Long, cond: EvalRecord.Conditions = good, proc: String = "GPU") =
        EvalReport.summarize(run.copy(runId = id, startedAt = at, appVersion = app, conditions = cond, processor = proc),
            (0 until 20).map { i ->
                val v = if (i < best) EvalScoring.Verdict.BEST else EvalScoring.Verdict.WRONG
                EvalRecord.Sample("t$i", 0, false, 0, 100, usable + i, usable + 200, 7, 1000, 0, 30f, 80, "x", v.score, v.name)
            })

    @Test fun slowerOrWorseIsFlagged() {
        val base = sum("a", 1, "38.49", 15, 400)
        val same = sum("b", 2, "38.50", 15, 440)          // 10% slower: within the threshold
        val slow = sum("c", 3, "38.51", 15, 480)          // 20% slower
        val worse = sum("d", 4, "38.52", 3, 400)          // quality interval wholly below
        val flags = EvalRegression.flags(listOf(base, same, slow, worse))
        assertEquals(listOf("d", "c"), flags.map { it.run.run.runId })
        assertTrue(flags[1].reasons.single(), flags[1].reasons.single().startsWith("usable answer 20% slower"))
        assertTrue(flags[0].reasons.single().startsWith("quality lower"))
        assertTrue(flags[1].text().startsWith("app 38.51 vs baseline app 38.49"))
    }

    @Test fun theBaselineIsTheFirstGoodRunOfItsGroup() {
        val hot = sum("h", 1, "38.48", 20, 300, good.copy(thermalStatus = 3))   // not rankable: never the baseline
        val base = sum("a", 2, "38.49", 15, 400)
        val cpu = sum("p", 3, "38.49", 2, 2000, proc = "CPU")                   // another group: its own baseline
        assertEquals("a", EvalRegression.baselines(listOf(hot, base, cpu))[EvalRecord.comparabilityKey(base.run)]!!.run.runId)
        assertTrue(EvalRegression.flags(listOf(hot, base, cpu)).isEmpty())
    }
}
