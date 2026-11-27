package com.sphynx.universal.eval

import java.util.Locale

/**
 * PHASE 10, step E6: what a run shows, by the protocol: quality and speed side by side, each with its interval, never
 * combined into one number; and a comparison only between runs the comparability rule allows. Pure, unit-tested.
 */
object EvalReport {

    const val WARMUP = "_warmup"

    class Share(val k: Int, val n: Int) {
        val p: Double get() = if (n == 0) 0.0 else k.toDouble() / n
        val ci: Pair<Double, Double> get() = EvalStats.wilson(k, n)
    }

    class RunSummary(
        val run: EvalRecord.RunInfo, val answers: Int, val errors: Int,
        val best: Share, val ok: Share, val unsafe: Share, val noAnswer: Share,
        val meanScore: Double, val scoreCi: Pair<Double, Double>,
        val firstWord: EvalStats.Summary, val usable: EvalStats.Summary, val total: EvalStats.Summary,
        val coldUsableMs: Long?, val marked: List<String>,
    ) {
        val rankable: Boolean get() = marked.isEmpty() && answers > 0
    }

    fun summarize(run: EvalRecord.RunInfo, samples: List<EvalRecord.Sample>): RunSummary {
        val counted = samples.filter { it.itemId != WARMUP }
        val good = counted.filter { it.error == null }
        val n = good.size
        fun share(v: EvalScoring.Verdict) = Share(good.count { it.verdict == v.name }, n)
        val scores = good.mapNotNull { it.score }
        val (lo, hi) = EvalStats.bootstrapMean(scores)
        fun times(f: (EvalRecord.Sample) -> Long) = EvalStats.summary(good.map(f).filter { it >= 0 }.map { it.toDouble() })
        return RunSummary(run, n, counted.size - n, share(EvalScoring.Verdict.BEST), share(EvalScoring.Verdict.OK),
            share(EvalScoring.Verdict.UNSAFE), share(EvalScoring.Verdict.NO_ANSWER),
            if (scores.isEmpty()) Double.NaN else EvalStats.mean(scores), lo to hi,
            times { it.firstWordMs }, times { it.usableMs }, times { it.totalMs },
            samples.firstOrNull { it.itemId == WARMUP && it.error == null }?.usableMs, run.conditions.problems())
    }

    private fun pct(d: Double) = String.format(Locale.US, "%.0f%%", d * 100)
    private fun pctCi(s: Share) = "${pct(s.p)} (${pct(s.ci.first)}–${pct(s.ci.second)})"
    private fun num(d: Double) = String.format(Locale.US, "%.2f", d)
    private fun sec(ms: Double) = String.format(Locale.US, "%.2f s", ms / 1000)
    private fun times(s: EvalStats.Summary) = if (s.n == 0) "not measured" else "median ${sec(s.median)}, 90% under ${sec(s.p90)}"

    /** The run's lines for the report card: what was run, quality, speed, conditions. */
    fun text(s: RunSummary, reviewed: Boolean): String = listOfNotNull(
        "${s.run.setId} v${s.run.setVersion} · ${s.run.fileName} · ${s.run.backend}/${s.run.processor ?: "?"} · app ${s.run.appVersion}",
        "QUALITY (${s.answers} answers): best ${pctCi(s.best)}, acceptable ${pct(s.ok.p)}, unsafe ${pctCi(s.unsafe)}, " +
            "no answer ${pct(s.noAnswer.p)}; mean score ${num(s.meanScore)} (${num(s.scoreCi.first)}–${num(s.scoreCi.second)})" +
            (if (!reviewed) " — set not yet reviewed by you, so these scores don't count yet" else ""),
        "SPEED: usable answer ${times(s.usable)}; first word ${times(s.firstWord)}; whole answer ${times(s.total)}" +
            (s.coldUsableMs?.let { "; cold (warm-up) ${sec(it.toDouble())}" } ?: ""),
        if (s.errors > 0) "${s.errors} questions failed" else null,
        if (s.marked.isEmpty()) "Conditions: as the protocol asks" else "Marked, never ranked: " + s.marked.joinToString(),
    ).joinToString("\n")

    /** Two runs side by side, or why they can't be compared. Quality and speed are compared apart. */
    fun compare(a: RunSummary, b: RunSummary): String {
        EvalRecord.whyNotComparable(a.run, b.run)?.let { return "These runs can't be compared: $it." }
        if (!a.rankable || !b.rankable) return "These runs can't be ranked: one of them was not under the protocol's conditions."
        val q = when {
            a.scoreCi.second < b.scoreCi.first -> "the second scores higher (the intervals don't overlap)"
            b.scoreCi.second < a.scoreCi.first -> "the first scores higher (the intervals don't overlap)"
            else -> "no clear difference (the intervals overlap)"
        }
        val sp = if (a.usable.n == 0 || b.usable.n == 0) "speed not measured" else {
            val r = b.usable.median / a.usable.median
            when { r < 0.95 -> "the second is ${pct(1 - r)} faster"; r > 1.05 -> "the second is ${pct(r - 1)} slower"; else -> "about the same speed" }
        }
        return "Quality: $q. Speed (median usable answer ${sec(a.usable.median)} vs ${sec(b.usable.median)}): $sp."
    }
}
