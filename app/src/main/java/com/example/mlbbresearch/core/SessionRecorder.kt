package com.example.mlbbresearch.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// SESSION RECORDER (for bug reports, owner request): while it is on, it writes what the AI saw and
// decided, about 10 times a second, plus every aimed skill and every log line, to one file:
//   sphynx-session-<date>.jsonl   (one JSON object per line)
// in /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage/recordings (or Download/MLBB-Research/recordings
// without all files access). Record the screen at the same time: every row has the wall-clock time
// ("t", ms) and the in-game match timer ("ms" = match seconds), so file and video line up.
// Row types ("k"):
//   start  - app version, phone, screen aspect, the settings that matter
//   f      - a decision frame: your hero, HP, minimap position, the decision (intent, reason, joystick,
//            casts, target), enemy heroes on screen (id, x, y, hp, vx, vy, age), monsters (x, y, hp),
//            the fused map enemies (id, x, y, occluded, sources), the jungle route, speed
//   aim    - an aimed skill as planned: button, drag end, target used, lead, decision at that moment
//   aim2   - the same skill at release: the re-computed drag end and the decision THEN (what changed)
//   aimEnd - the release finished or was cancelled
//   log    - a log line (Logs page)
//   stop   - row count, duration
// Positions are screen fractions (0..1) unless marked map ("mm", map enemies: 0..1 inside the minimap).
// Nothing here touches the game; it stops by itself at MAX_BYTES.
object SessionRecorder {

    const val FRAME_EVERY_MS = 100L
    const val MAX_BYTES = 40L * 1024 * 1024
    const val FOLDER = "recordings"

    @Volatile var on = false; private set
    @Volatile var rows = 0; private set
    @Volatile var bytes = 0L; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var lastExport = "-"; private set
    @Volatile private var lastFrameAt = 0L
    private var tmp: File? = null
    private var out: java.io.Writer? = null
    private var name = ""
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var appCtx: Context? = null

    fun r3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

    fun status(): String = if (on)
        "RECORDING: $rows rows, ${bytes / 1024} KB, ${(System.currentTimeMillis() - startedAt) / 1000} s; video: " +
            (if (VideoRecorder.on || VideoRecorder.frames > 0) "${VideoRecorder.bytesWritten / (1024 * 1024)} of ${VideoRecorder.limitMb} MB, " +
                "${VideoRecorder.frames} frames (${VideoRecorder.note})" else "off (start screen capture first)") +
            " - stops and saves by itself at the limit"
    else "Off. Last file: $lastExport"

    @Synchronized fun start(ctx: Context) {
        if (on) return
        appCtx = ctx.applicationContext
        name = "sphynx-session-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        val f = File(ctx.applicationContext.cacheDir, name)
        tmp = f; out = f.bufferedWriter(); rows = 0; bytes = 0L; startedAt = System.currentTimeMillis(); lastFrameAt = 0L; autoFull = false
        on = true
        write(JSONObject().put("k", "start").put("t", startedAt)
            .put("app", AppVersion.VERSION).put("phone", Build.MANUFACTURER + " " + Build.MODEL).put("android", Build.VERSION.SDK_INT)
            .put("aimSkills", Cfg.aimSkills)
            .put("note", "t = wall-clock ms; ms = match seconds (the in-game timer). Screen positions are 0..1."))
        val app = ctx.applicationContext
        // auto-stop: when the video reaches its size limit (or 20 minutes) everything is stopped and saved
        VideoRecorder.onLimit = { if (on) stop(app) { msg -> lastExport = "auto-stopped: $msg" } }
        if (com.example.mlbbresearch.capture.ScreenCaptureService.running) {
            VideoRecorder.start(File(ctx.applicationContext.cacheDir, name.removeSuffix(".jsonl") + ".mp4"))
        }
        Diag.info("Session recorder started ($name)" + if (VideoRecorder.on) " with video" else " (no video: screen capture is off)")
    }

    // stops and saves the file; done(message) on the main thread's caller side
    fun stop(ctx: Context, done: (String) -> Unit) {
        val f: File; val w: java.io.Writer?
        synchronized(this) {
            if (!on) { done("Not recording"); return }
            write(JSONObject().put("k", "stop").put("t", System.currentTimeMillis()).put("rows", rows)
                .put("sec", (System.currentTimeMillis() - startedAt) / 1000))
            on = false
            f = tmp ?: run { done("Nothing recorded"); return }
            w = out; out = null; tmp = null
        }
        val app = ctx.applicationContext
        io.execute {
            val video = try { VideoRecorder.stop() } catch (e: Exception) { null }
            val msg = try {
                w?.close()
                val m1 = export(app, f).also { f.delete() }
                val m2 = video?.let { v -> try { export(app, v, "video/mp4").also { v.delete() } } catch (e: Exception) { "video not saved: ${e.message}" } }
                    ?: "no video (screen capture was off, or no frame arrived)"
                "$m1; $m2"
            } catch (e: Exception) { "Saving the recording failed: ${e.message}" }
            lastExport = msg
            Diag.info(msg)
            done(msg)
        }
    }

    private fun export(c: Context, f: File, mime: String = "application/json"): String {
        Storage.dir(FOLDER)?.let { d ->
            f.copyTo(File(d, f.name), overwrite = true)
            return "Saved ${f.name} (${f.length() / 1024} KB) in /${Storage.REL}/$FOLDER"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Storage.LEGACY_NAME + "/" + FOLDER)
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("cannot create ${f.name}")
        c.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return "Saved ${f.name} (${f.length() / 1024} KB) in ${Storage.LEGACY_REL}/$FOLDER"
    }

    private fun write(o: JSONObject) {
        val line = o.toString() + "\n"
        val w = out ?: return
        rows++; bytes += line.length
        io.execute { try { w.write(line) } catch (e: Exception) { } }
        if (bytes > MAX_BYTES && !autoFull) {
            autoFull = true
            Diag.info("Session recorder: data limit reached - stopping and saving")
            val c = appCtx
            if (c != null) Thread { stop(c) { msg -> lastExport = "auto-stopped: $msg" } }.start()
        }
    }
    @Volatile var autoFull = false; private set

    private fun accept(): Boolean = on && !autoFull

    // ---- what is recorded ----

    @Volatile var lastDecision: com.example.mlbbresearch.ai.TacticalDecision? = null; private set

    fun frame(s: com.example.mlbbresearch.game.GameState, d: com.example.mlbbresearch.ai.TacticalDecision) {
        if (!on) return
        lastDecision = d
        if (!accept()) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < FRAME_EVERY_MS) return
        lastFrameAt = now
        try {
            val o = JSONObject().put("k", "f").put("t", now).put("ms", s.matchSec).put("ph", s.phase.name)
                .put("age", now - s.ts).put("hp", r3(s.selfHp)).put("lvl", s.level).put("lane", s.lane.name)
            s.self?.let { o.put("me", JSONArray(listOf(r3(it.x), r3(it.y)))) }
            s.minimapSelf?.let { o.put("mm", JSONArray(listOf(r3(it.first), r3(it.second)))) }
            o.put("in", d.intent.name).put("why", d.reason.take(140))
                .put("mv", JSONArray(listOf(r3(d.moveX), r3(d.moveY)))).put("cast", JSONArray(d.casts))
            if (d.targetX >= 0f) o.put("tg", JSONArray(listOf(r3(d.targetX), r3(d.targetY), d.targetLabel, r3(d.targetHp), d.targetId)))
            o.put("en", JSONArray(s.enemies.map { JSONArray(listOf(it.id, r3(it.x), r3(it.y), r3(it.hp), r3(it.vx), r3(it.vy), now - it.lastSeen)) }))
            o.put("mon", JSONArray(s.monsters.map { JSONArray(listOf(r3(it.x), r3(it.y), r3(it.hp), if (it.hero) 1 else 0, it.species)) }))
            o.put("map", JSONArray(com.example.mlbbresearch.game.MapIntel.targets.map {
                JSONArray(listOf(it.id, r3(it.x), r3(it.y), if (it.occluded) 1 else 0, it.sources.joinToString("+") { s2 -> s2.name.take(3) }))
            }))
            o.put("jg", com.example.mlbbresearch.game.JungleNav.status.take(80))
            o.put("lat", Speed.total().toInt())
            write(o)
        } catch (e: Exception) { }
    }

    fun aim(kind: String, slot: String, bx: Float, by: Float, ex: Float, ey: Float, d: com.example.mlbbresearch.ai.TacticalDecision?,
            extra: JSONObject? = null) {
        if (!on) return
        val a = VideoRecorder.Aim(slot, bx, by, ex, ey, System.currentTimeMillis())
        if (kind == "aim") VideoRecorder.planned = a else if (kind == "aim2") VideoRecorder.released = a
        if (!accept()) return
        try {
            val o = JSONObject().put("k", kind).put("t", System.currentTimeMillis()).put("slot", slot)
                .put("btn", JSONArray(listOf(r3(bx), r3(by)))).put("end", JSONArray(listOf(r3(ex), r3(ey))))
            if (d != null) o.put("in", d.intent.name).put("tg", JSONArray(listOf(r3(d.targetX), r3(d.targetY), d.targetLabel, d.targetId)))
                .put("mv", JSONArray(listOf(r3(d.moveX), r3(d.moveY))))
            extra?.let { e -> e.keys().forEach { o.put(it, e.get(it)) } }
            write(o)
        } catch (e: Exception) { }
    }

    fun aimEnd(slot: String, completed: Boolean) {
        if (!accept()) return
        try { write(JSONObject().put("k", "aimEnd").put("t", System.currentTimeMillis()).put("slot", slot).put("ok", completed)) } catch (e: Exception) { }
    }

    fun log(level: String, msg: String) {
        if (!accept() || msg.startsWith("Session recorder")) return
        try { write(JSONObject().put("k", "log").put("t", System.currentTimeMillis()).put("lv", level).put("msg", msg.take(300))) } catch (e: Exception) { }
    }
}
