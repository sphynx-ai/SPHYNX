package com.sphynx.universal.eval

import java.util.Locale
import kotlin.math.sqrt

/**
 * PHASE 10, step E1: the statistics of the benchmark protocol. Pure and deterministic (the bootstrap uses a fixed
 * seed, so the same samples always give the same interval), unit-tested.
 */
object EvalStats {

    class Summary(val n: Int, val mean: Double, val median: Double, val p90: Double, val sd: Double,
                  val low: Double, val high: Double) {
        /** "median 410 ms, 90% under 620 ms (n 120; mean 430, 95% CI 405–455)". */
        fun ms(): String = if (n == 0) "no samples" else
            "median ${r(median)} ms, 90% under ${r(p90)} ms (n $n; mean ${r(mean)}, 95% CI ${r(low)}–${r(high)})"
        private fun r(d: Double) = String.format(Locale.US, "%.0f", d)
    }

    fun mean(x: List<Double>): Double = if (x.isEmpty()) Double.NaN else x.sum() / x.size

    /** The q-quantile (0..1), linear between the closest ranks. */
    fun quantile(x: List<Double>, q: Double): Double {
        if (x.isEmpty()) return Double.NaN
        val s = x.sorted()
        val pos = q.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = pos.toInt(); val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun median(x: List<Double>) = quantile(x, 0.5)

    /** The sample standard deviation (n - 1); 0 for fewer than two samples. */
    fun sd(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    /** A 95% percentile-bootstrap interval of the mean; [rounds] resamples with a fixed seed. */
    fun bootstrapMean(x: List<Double>, rounds: Int = 2000, seed: Long = 20261127L): Pair<Double, Double> {
        if (x.isEmpty()) return Double.NaN to Double.NaN
        if (x.size == 1) return x[0] to x[0]
        val rnd = java.util.Random(seed)
        val means = DoubleArray(rounds) { var s = 0.0; repeat(x.size) { s += x[rnd.nextInt(x.size)] }; s / x.size }
        val l = means.toList()
        return quantile(l, 0.025) to quantile(l, 0.975)
    }

    fun summary(x: List<Double>): Summary {
        if (x.isEmpty()) return Summary(0, Double.NaN, Double.NaN, Double.NaN, 0.0, Double.NaN, Double.NaN)
        val (lo, hi) = bootstrapMean(x)
        return Summary(x.size, mean(x), median(x), quantile(x, 0.9), sd(x), lo, hi)
    }

    /** A 95% Wilson interval for [k] successes out of [n] (for a share of best or unsafe answers). */
    fun wilson(k: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n <= 0) return 0.0 to 1.0
        val p = k.toDouble() / n
        val d = 1 + z * z / n
        val c = (p + z * z / (2 * n)) / d
        val h = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
        return (c - h).coerceAtLeast(0.0) to (c + h).coerceAtMost(1.0)
    }
}
