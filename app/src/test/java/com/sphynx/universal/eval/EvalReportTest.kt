package com.sphynx.universal.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 10 E6: the run report and the comparison
class EvalReportTest {

    private val good = EvalRecord.Conditions(false, false, 80, false, 0, 30f)
    private val run = EvalRecord.RunInfo("r1", "mlbb-tactical", 1, "tactical", "a".repeat(64), "g.litertlm", "LITERT_LM", null,
        "LITERT_LM", "GPU", "phone", "Android 14", "38.49", "litertlm 0.19.1", emptyMap(), 3, good, 0)
    private fun s(id: String, v: EvalScoring.Verdict, usable: Long, err: String? = null) =
        EvalRecord.Sample(id, 0, false, 0, usable / 2, usable, usable + 100, 7, 1000, 0, 30f, 80, "x", v.score, v.name, err)

    private fun samples(bestCount: Int, usable: Long) = listOf(s(EvalReport.WARMUP, EvalScoring.Verdict.BEST, 3000)) +
        (0 until 20).map { i -> s("t$i", if (i < bestCount) EvalScoring.Verdict.BEST else EvalScoring.Verdict.WRONG, usable + i) }

    @Test fun summaryKeepsQualityAndSpeedApart() {
        val r = EvalReport.summarize(run, samples(15, 400) + s("t99", EvalScoring.Verdict.WRONG, -1, "timeout"))
        assertEquals(20, r.answers); assertEquals(1, r.errors)
        assertEquals(0.75, r.best.p, 1e-9)
        assertEquals(0.75, r.meanScore, 1e-9)
        assertEquals(3000L, r.coldUsableMs)                 // the warm-up is reported apart, not counted
        assertEquals(409.5, r.usable.median, 1e-9)
        val t = EvalReport.text(r, reviewed = false)
        assertTrue(t, t.contains("QUALITY (20 answers): best 75%"))
        assertTrue(t, t.contains("SPEED: usable answer median 0.41 s"))
        assertTrue(t, t.contains("don't count yet"))
        assertTrue(t, t.contains("1 questions failed"))
        assertFalse(EvalReport.text(r, reviewed = true).contains("don't count"))
    }

    @Test fun comparisons() {
        val a = EvalReport.summarize(run, samples(5, 400))
        val b = EvalReport.summarize(run.copy(runId = "r2"), samples(18, 800))
        val c = EvalReport.compare(a, b)
        assertTrue(c, c.contains("the second scores higher"))
        assertTrue(c, c.contains("slower"))
        assertTrue(EvalReport.compare(a, EvalReport.summarize(run.copy(processor = "CPU"), samples(5, 400)))
            .contains("can't be compared: different processor"))
        val hot = EvalReport.summarize(run.copy(runId = "r3", conditions = good.copy(thermalStatus = 3)), samples(5, 400))
        assertFalse(hot.rankable)
        assertTrue(EvalReport.compare(a, hot).contains("can't be ranked"))
        assertTrue(EvalReport.compare(a, EvalReport.summarize(run.copy(runId = "r4"), samples(6, 402))).contains("no clear difference"))
    }
}
