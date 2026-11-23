package com.sphynx.ai

import android.graphics.Bitmap
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.core.Probes
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

// THE TACTICAL MODEL LOOKS AT THE MINIMAP (Phase 8, step G11; owner 2026-11-23: "all strategic and tactical models use
// pictures"; how: "a picture now and then"). Fight advice stays a fast text question. Outside fights, every few seconds,
// a tactical model that reads pictures (a .litertlm with vision, or a GGUF with its mmproj) gets the minimap crop and
// answers in one line; that line is added to the next fight questions while it is fresh. A fight never waits for a
// picture: a picture question that is still running when a fight starts is cancelled.
// Each reading is written next to what the detectors saw at the same moment (research/tactical_sight.jsonl, exported and
// uploaded from the Debug data page), so a match shows whether the pictures add anything.
// The switch (Model page) is OFF on a new install, like every feature switch.
object TacticalSight {

    const val EVERY_MS = 5_000L
    const val KEEP_MS = 15_000L
    const val PROMPT = "This is the minimap of Mobile Legends: Bang Bang (red icons are enemies, blue are allies). " +
        "In one short line: where are the enemy heroes, are they grouped, and is the turtle or lord being taken?"

    // ---- pure rules (unit-tested) ----

    /** Time for a new look: pictures possible, no fight, the tactical model free, and the last look old enough. */
    fun shouldLook(now: Long, lastLookAt: Long, inFight: Boolean, busy: Boolean, canSee: Boolean): Boolean =
        canSee && !inFight && !busy && now - lastLookAt >= EVERY_MS

    /** The line added to a fight question, or "" when there is no fresh reading. */
    fun note(now: Long, reading: String, readingAt: Long): String =
        if (reading.isBlank() || readingAt <= 0 || now - readingAt > KEEP_MS) ""
        else "\nminimap picture (${(now - readingAt) / 1000} s ago): " + reading.replace('\n', ' ').trim().take(160)

    /** The minimap part of a frame; [rect] is left, top, right, bottom as fractions of the screen. */
    fun cropRect(w: Int, h: Int, rect: FloatArray): IntArray {
        val l = (rect[0] * w).toInt().coerceIn(0, w - 1); val t = (rect[1] * h).toInt().coerceIn(0, h - 1)
        val r = (rect[2] * w).toInt().coerceIn(l + 1, w); val b = (rect[3] * h).toInt().coerceIn(t + 1, h)
        return intArrayOf(l, t, r - l, b - t)
    }

    // ---- state ----

    @Volatile var reading = ""; private set
    @Volatile var readingAt = 0L; private set
    @Volatile var looks = 0; private set
    @Volatile var lastMs = 0L; private set
    @Volatile private var lastLookAt = 0L
    @Volatile private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    /** The owner's switch, and a tactical model that really reads pictures. */
    val canSee: Boolean get() = Cfg.tacticalSightOn && Roles.ready(Roles.TACTICAL) && Roles.images(Roles.TACTICAL)

    fun noteNow(): String = note(System.currentTimeMillis(), reading, readingAt)

    /** Called by the fast loop: starts a look when due; a fight cancels a look that is still running. */
    fun tick(inFight: Boolean, adviceBusy: Boolean, detections: () -> List<com.sphynx.vision.Detection>, missing: () -> Int) {
        if (inFight) { job?.let { if (it.isActive) it.cancel() }; return }
        val now = System.currentTimeMillis()
        if (!shouldLook(now, lastLookAt, inFight, adviceBusy || running, canSee)) return
        val frame = com.sphynx.capture.FrameHub.bitmap() ?: return
        lastLookAt = now
        val c = cropRect(frame.width, frame.height, com.sphynx.vision.MinimapAnalyzer.rect)
        val crop = try { Bitmap.createBitmap(frame, c[0], c[1], c[2], c[3]) } catch (e: Exception) { return }
        job = AiRuntime.scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val r = Roles.ask(Roles.TACTICAL, PROMPT, crop, 0.0, purpose = "minimap picture")
                lastMs = System.currentTimeMillis() - t0
                reading = r.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: ""
                readingAt = System.currentTimeMillis()
                looks++
                record(reading, lastMs, detections(), missing())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Diag.error("Tactical model, minimap picture", t)
            }
        }
    }

    // one line per reading, next to the detectors' view of the same moment
    private fun record(text: String, ms: Long, dets: List<com.sphynx.vision.Detection>, missing: Int) {
        if (!com.sphynx.learning.LearnGate.on()) return     // no records in Solo practice vs Computer (owner rule)
        val c = Probes.appContext() ?: return
        try {
            val counts = JSONObject()
            for ((k, n) in dets.groupingBy { if (it.team.isEmpty()) it.label else it.label + "/" + it.team }.eachCount()) counts.put(k, n)
            val row = JSONObject().put("t", System.currentTimeMillis()).put("reading", text).put("ms", ms)
                .put("detector", counts).put("missingEnemies", missing)
            File(File(c.filesDir, "research").apply { mkdirs() }, "tactical_sight.jsonl").appendText(row.toString() + "\n")
        } catch (e: Exception) { }
    }
}
