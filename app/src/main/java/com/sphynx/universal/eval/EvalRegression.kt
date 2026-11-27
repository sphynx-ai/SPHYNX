package com.sphynx.universal.eval

import java.util.Locale

/**
 * PHASE 10, step E7: the regression check of the protocol (section 6). For each comparable group of runs (the same
 * comparability key), the first run under the protocol's conditions is the baseline. A later rankable run in the group is
 * flagged when its median time to a usable answer is more than [SLOWER] slower, or its quality interval lies wholly
 * below the baseline's. It only reports: nothing is changed. Pure, unit-tested.
 */
object EvalRegression {

    const val SLOWER = 0.15

    class Flag(val run: EvalReport.RunSummary, val baseline: EvalReport.RunSummary, val reasons: List<String>) {
        fun text(): String = "app ${run.run.appVersion} vs baseline app ${baseline.run.appVersion} (${run.run.setId}, " +
            "${run.run.fileName}, ${run.run.processor ?: "?"}): " + reasons.joinToString("; ")
    }

    /** The baseline of each comparable group: its first rankable run. */
    fun baselines(runs: List<EvalReport.RunSummary>): Map<String, EvalReport.RunSummary> =
        runs.filter { it.rankable }.groupBy { EvalRecord.comparabilityKey(it.run) }
            .mapValues { e -> e.value.minByOrNull { it.run.startedAt }!! }

    /** Why [now] is a regression against [base] (empty = it is not). */
    fun reasons(base: EvalReport.RunSummary, now: EvalReport.RunSummary): List<String> {
        val out = ArrayList<String>()
        if (base.usable.n > 0 && now.usable.n > 0 && now.usable.median > base.usable.median * (1 + SLOWER))
            out.add(String.format(Locale.US, "usable answer %.0f%% slower (median %.2f s vs %.2f s)",
                (now.usable.median / base.usable.median - 1) * 100, now.usable.median / 1000, base.usable.median / 1000))
        if (!now.scoreCi.second.isNaN() && !base.scoreCi.first.isNaN() && now.scoreCi.second < base.scoreCi.first)
            out.add(String.format(Locale.US, "quality lower (mean score %.2f, interval %.2f–%.2f, below the baseline's %.2f–%.2f)",
                now.meanScore, now.scoreCi.first, now.scoreCi.second, base.scoreCi.first, base.scoreCi.second))
        return out
    }

    /** Every flagged later run, newest first. */
    fun flags(runs: List<EvalReport.RunSummary>): List<Flag> {
        val base = baselines(runs)
        return runs.filter { it.rankable }.mapNotNull { r ->
            val b = base[EvalRecord.comparabilityKey(r.run)] ?: return@mapNotNull null
            if (b.run.runId == r.run.runId) null else reasons(b, r).takeIf { it.isNotEmpty() }?.let { Flag(r, b, it) }
        }.sortedByDescending { it.run.run.startedAt }
    }
}
