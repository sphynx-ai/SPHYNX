package com.sphynx.core

import org.json.JSONObject
import java.util.Locale

// SCREEN TO TOUCH RECORDER (owner, 2026-11-10: "also add the screen to touch data to it"). Pure
// logic, unit tested; TouchSpeed feeds it. While the Player plays, exact samples are collected and
// summed up every 5 s into one row:
//   frame -> decision : from the start of reading a frame to the Player's touches for it being queued
//   touch queue       : how long a touch waited before Android started it
//   reading / waiting / deciding : the parts of frame -> decision
// The total screen-to-touch time is about frame -> decision + touch queue.
class TouchSpeedLog(private val maxRows: Int = 3000, private val windowMs: Long = 5_000L) {

    data class Row(
        val t: Long, val samples: Int,
        val f2dMedian: Double, val f2dP90: Double, val f2dMax: Double,
        val queueMedian: Double?, val queueP90: Double?,
        val readMedian: Double?, val waitMedian: Double?, val decideMedian: Double?,
        val captureFps: Float?, val readFps: Float?,
        val thermal: Int? = null, val batteryC: Float? = null, val ramAvailMb: Long? = null
    ) {
        /** Screen to touch, about: frame -> decision plus the touch queue. */
        val totalMedian: Double get() = f2dMedian + (queueMedian ?: 0.0)

        fun toJson(): JSONObject = JSONObject().put("t", t).put("n", samples).put("f2d", f2dMedian).put("f2d90", f2dP90)
            .put("f2dmax", f2dMax).apply {
                queueMedian?.let { put("q", it) }; queueP90?.let { put("q90", it) }; readMedian?.let { put("read", it) }
                waitMedian?.let { put("wait", it) }; decideMedian?.let { put("dec", it) }
                captureFps?.let { put("cfps", it.toDouble()) }; readFps?.let { put("rfps", it.toDouble()) }
                thermal?.let { put("thermal", it) }; batteryC?.let { put("batt", it.toDouble()) }; ramAvailMb?.let { put("ram", it) }
            }

        companion object {
            private fun JSONObject.d(k: String) = if (has(k)) getDouble(k) else null
            fun fromJson(o: JSONObject): Row? = try {
                Row(o.getLong("t"), o.getInt("n"), o.getDouble("f2d"), o.getDouble("f2d90"), o.getDouble("f2dmax"),
                    o.d("q"), o.d("q90"), o.d("read"), o.d("wait"), o.d("dec"), o.d("cfps")?.toFloat(), o.d("rfps")?.toFloat(),
                    if (o.has("thermal")) o.getInt("thermal") else null, o.d("batt")?.toFloat(), if (o.has("ram")) o.getLong("ram") else null)
            } catch (e: Exception) { null }
        }
    }

    // ---- the window being collected ----
    private val f2d = ArrayList<Double>()
    private val queue = ArrayList<Double>()
    private val read = ArrayList<Double>()
    private val wait = ArrayList<Double>()
    private val decide = ArrayList<Double>()
    private var windowStart = 0L

    private fun cap(l: ArrayList<Double>, v: Double) { if (l.size < 2000) l.add(v) }
    @Synchronized fun frameToDecision(ms: Long) { cap(f2d, ms.toDouble()) }
    @Synchronized fun touchQueue(ms: Long) { cap(queue, ms.toDouble()) }
    @Synchronized fun reading(ms: Long) { cap(read, ms.toDouble()) }
    @Synchronized fun decided(waitMs: Long, decideMs: Long) { cap(wait, waitMs.toDouble()); cap(decide, decideMs.toDouble()) }

    /**
     * Closes the window when it is [windowMs] old: returns its row when the Player made decisions in it
     * (else null: nothing is recorded while the Player does not play). Samples start a new window.
     */
    @Synchronized fun flush(nowMs: Long, captureFps: Float?, readFps: Float?, phone: PhoneState.Reading? = null): Row? {
        if (windowStart == 0L) { windowStart = nowMs; return null }
        if (nowMs - windowStart < windowMs) return null
        val row = if (f2d.isEmpty()) null else Row(nowMs, f2d.size,
            median(f2d)!!, percentile(f2d, 90)!!, f2d.max(),
            median(queue), percentile(queue, 90), median(read), median(wait), median(decide),
            captureFps, readFps, phone?.thermal, phone?.batteryC, phone?.ramAvailMb)
        f2d.clear(); queue.clear(); read.clear(); wait.clear(); decide.clear()
        windowStart = nowMs
        if (row != null) { rows.add(row); while (rows.size > maxRows) rows.removeAt(0) }
        return row
    }

    // ---- stored rows ----
    private val rows = ArrayList<Row>()
    @Synchronized fun all(): List<Row> = rows.toList()
    @Synchronized fun clear() = rows.clear()
    @Synchronized fun toJsonLines(): String = rows.joinToString("\n") { it.toJson().toString() }
    @Synchronized fun loadJsonLines(text: String) {
        rows.clear()
        text.lineSequence().filter { it.isNotBlank() }.forEach { l -> try { Row.fromJson(JSONObject(l))?.let { rows.add(it) } } catch (e: Exception) {} }
        while (rows.size > maxRows) rows.removeAt(0)
    }

    companion object {
        fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        fun percentile(xs: List<Double>, p: Int): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
            s[(kotlin.math.ceil(p / 100.0 * s.size).toInt() - 1).coerceIn(0, s.size - 1)] }
    }

    @Synchronized fun text(): String {
        if (rows.isEmpty()) return "No screen-to-touch data yet: it is recorded while the Player plays (practice vs Computer)."
        fun ms(x: Double?) = if (x == null) "-" else "%.0f ms".format(Locale.US, x)
        val n = rows.sumOf { it.samples }
        return buildString {
            append("${rows.size} periods of 5 s, $n decisions\n")
            append("Screen to touch, about: ${ms(median(rows.map { it.totalMedian }))} (slowest 10% of periods: ${ms(percentile(rows.map { it.totalMedian }, 90))})\n")
            append("  frame -> decision ${ms(median(rows.map { it.f2dMedian }))}, worst seen ${ms(rows.maxOf { it.f2dMax })}\n")
            append("  touch queue ${ms(median(rows.mapNotNull { it.queueMedian }))}\n")
            append("  reading the screen ${ms(median(rows.mapNotNull { it.readMedian }))}, waiting ${ms(median(rows.mapNotNull { it.waitMedian }))}, " +
                "deciding ${ms(median(rows.mapNotNull { it.decideMedian }))}")
        }
    }

    @Synchronized fun csv(): String = buildString {
        append("time,decisions,screen_to_touch_ms,frame_to_decision_ms,frame_to_decision_p90_ms,frame_to_decision_max_ms," +
            "touch_queue_ms,touch_queue_p90_ms,reading_ms,waiting_ms,deciding_ms,capture_fps,read_fps,thermal,battery_c,ram_free_mb\n")
        fun d(x: Double?) = if (x == null) "" else "%.1f".format(Locale.US, x)
        fun f(x: Float?) = if (x == null) "" else "%.1f".format(Locale.US, x)
        rows.forEach { r ->
            append(listOf(r.t, r.samples, d(r.totalMedian), d(r.f2dMedian), d(r.f2dP90), d(r.f2dMax), d(r.queueMedian), d(r.queueP90),
                d(r.readMedian), d(r.waitMedian), d(r.decideMedian), f(r.captureFps), f(r.readFps), r.thermal ?: "",
                f(r.batteryC), r.ramAvailMb ?: "").joinToString(",")).append('\n')
        }
    }
}
