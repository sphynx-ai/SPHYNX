package com.sphynx.universal.eval

import java.util.Locale

/**
 * PHASE 10, step E8: what a sustained run shows. The tactical set is asked again and again at the in-match pace for a
 * fixed time (owner, 2026-11-27: 10 minutes, on battery). This reads its samples minute by minute: how the usable
 * answer slows as the phone heats up (throttling), the heat status and battery temperature over time, and the battery
 * used per minute. Pure, unit-tested.
 */
object EvalSustained {

    const val PACE_MS = 330L          // one question every 330 ms at most, as TacticalAdvisor asks in a fight
    const val MINUTES = 10

    class Minute(val minute: Int, val answers: Int, val medianUsableMs: Double, val maxThermal: Int, val batteryC: Float?)

    class Result(val minutes: List<Minute>, val answers: Int, val slowdown: Double?, val batteryPerMinute: Double?,
                 val batteryRiseC: Float?, val maxThermal: Int, val stoppedHot: Boolean, val charging: Boolean?) {
        fun text(): String = listOfNotNull(
            "HEAT AND BATTERY: $answers answers in ${minutes.size} min" + (if (stoppedHot) ", stopped early: the phone got too hot" else ""),
            slowdown?.let { String.format(Locale.US, "usable answer %s by %.0f%% from the first to the last minute (median %.2f s → %.2f s)",
                if (it >= 0) "slower" else "faster", kotlin.math.abs(it) * 100, minutes.first().medianUsableMs / 1000, minutes.last().medianUsableMs / 1000) },
            when {
                charging == true -> "battery use not measured: the phone was charging"
                batteryPerMinute == null -> "battery use: not enough readings"
                else -> String.format(Locale.US, "battery %.2f%% per minute", batteryPerMinute)
            },
            batteryRiseC?.let { String.format(Locale.US, "battery temperature %+.1f °C", it) },
            "highest heat status $maxThermal (0 none, 1 light, 2 moderate, 3 severe)",
        ).joinToString("\n")
    }

    fun analyse(samples: List<EvalRecord.Sample>, charging: Boolean?): Result {
        val s = samples.filter { it.itemId != EvalReport.WARMUP && it.error == null }.sortedBy { it.atMs }
        if (s.isEmpty()) return Result(emptyList(), 0, null, null, null, -1, false, charging)
        val byMin = s.groupBy { (it.atMs / 60_000).toInt() }.toSortedMap()
        val minutes = byMin.map { (m, l) ->
            Minute(m, l.size, EvalStats.median(l.map { it.usableMs.toDouble() }.filter { it >= 0 }),
                l.maxOf { it.thermalStatus }, l.lastOrNull { it.batteryC != null }?.batteryC)
        }
        val slow = if (minutes.size >= 2 && minutes.first().medianUsableMs > 0)
            minutes.last().medianUsableMs / minutes.first().medianUsableMs - 1 else null
        val withPct = s.filter { it.batteryPct != null }
        val perMin = if (charging != true && withPct.size >= 2 && withPct.last().atMs - withPct.first().atMs >= 60_000)
            (withPct.first().batteryPct!! - withPct.last().batteryPct!!) / ((withPct.last().atMs - withPct.first().atMs) / 60_000.0) else null
        val temps = s.mapNotNull { it.batteryC }
        val maxT = s.maxOf { it.thermalStatus }
        return Result(minutes, s.size, slow, perMin, if (temps.size >= 2) temps.last() - temps.first() else null, maxT,
            maxT >= HOT, charging)
    }

    const val HOT = 3                 // severe: the run stops (Phase 5 heat policy)
}
