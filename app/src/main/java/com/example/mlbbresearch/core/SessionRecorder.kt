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
// in /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage/recordings (or Download/Sphynx-Auto-Pilot-Storage/recordings
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
            (if (parts > 0) "; part ${parts + 1}" else "") + " - at the limit it continues in a new file by itself"
    else "Off. Last file: $lastExport"

    // AUTOMATIC RECORDING (owner, 2026-11-12): started at the start of every match (the loading screen, or your hero
    // first seen) when the switch on the Logs page is on, and stopped / saved at the match end. A recording you
    // started yourself is never stopped by the match end.
    @Volatile private var ctxRef: Context? = null
    @Volatile var autoStarted = false; private set
    fun init(c: Context) { ctxRef = c.applicationContext }
    fun currentName(): String = name
    @Volatile var parts = 0; private set          // files this recording was continued into
    fun matchStarted() {
        val c = ctxRef ?: return
        if (!Cfg.autoRecord || on) return
        // only a 5v5 match is recorded by itself: Solo practice is for calibration (owner, 2026-11-13). When the
        // kind is not known yet, the recording starts as soon as it is (game.MatchKind.onFiveVFive)
        if (!com.example.mlbbresearch.learning.LearnGate.on()) return
        begin(c, 0, true)
        Diag.info("Session recorder: started by itself at the start of the match")
    }
    fun matchEnded() {
        val c = ctxRef ?: return
        if (!on || !autoStarted) return
        stop(c) { msg -> lastExport = "match end: $msg" }
    }

    @Synchronized fun start(ctx: Context) = begin(ctx, 0, false)

    // part: 0 = a new recording; 1.. = the same recording continued in a new file at the video's size limit
    @Synchronized private fun begin(ctx: Context, part: Int, auto: Boolean) {
        if (on) return
        autoStarted = auto; parts = part
        appCtx = ctx.applicationContext
        name = "sphynx-session-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        val f = File(ctx.applicationContext.cacheDir, name)
        tmp = f; out = f.bufferedWriter(); rows = 0; bytes = 0L; startedAt = System.currentTimeMillis(); lastFrameAt = 0L; autoFull = false
        snaps.clear(); snapAt.clear()
        on = true
        write(JSONObject().put("k", "start").put("t", startedAt)
            .put("app", AppVersion.VERSION).put("commit", com.example.mlbbresearch.BuildConfig.GIT_COMMIT).put("auto", autoStarted).put("part", part + 1).put("phone", Build.MANUFACTURER + " " + Build.MODEL).put("android", Build.VERSION.SDK_INT)
            .put("aimSkills", Cfg.aimSkills)
            .put("note", "t = wall-clock ms; ms = match seconds (the in-game timer). Screen positions are 0..1."))
        val app = ctx.applicationContext
        // AT THE VIDEO'S SIZE LIMIT (owner, 2026-11-13): the recording ALWAYS continues in a new file, at once - the
        // full one is saved (and uploaded when that switch is on) in the background - so a whole match is covered.
        VideoRecorder.onLimit = { if (on) roll(app) }
        if (com.example.mlbbresearch.capture.ScreenCaptureService.running) {
            VideoRecorder.start(File(ctx.applicationContext.cacheDir, name.removeSuffix(".jsonl") + ".mp4"))
        }
        Diag.info("Session recorder started ($name)" + if (VideoRecorder.on) " with video" else " (no video: screen capture is off)")
    }

    // the full file is closed and the next part started at once (no gap); the full one is saved in the background
    private fun roll(app: Context) {
        val wasAuto = autoStarted
        // (a recording started by the match is not continued once that match is over)
        if (wasAuto && com.example.mlbbresearch.game.GameStateTracker.matchId == 0L) { stop(app) { msg -> lastExport = "auto-stopped: $msg" }; return }
        val video = try { VideoRecorder.stop() } catch (e: Exception) { null }
        val f: File; val w: java.io.Writer?; val shots: List<File>
        synchronized(this) {
            if (!on) { video?.delete(); return }
            write(JSONObject().put("k", "stop").put("t", System.currentTimeMillis()).put("rows", rows)
                .put("sec", (System.currentTimeMillis() - startedAt) / 1000).put("continues", true))
            on = false
            val t = tmp
            if (t == null) { video?.delete(); return }
            f = t; w = out; out = null; tmp = null
            shots = takeShots()
            val part = parts
            begin(app, part + 1, wasAuto)
        }
        io.execute {
            val msg = save(app, f, w, video, shots, last = false)
            lastExport = "continued in a new file; saved: $msg"
            Diag.info(msg)
            uploadIfOn(app)
        }
    }

    // stops and saves the file; done(message) on the main thread's caller side
    fun stop(ctx: Context, done: (String) -> Unit) {
        val f: File; val w: java.io.Writer?; val shots: List<File>
        synchronized(this) {
            if (!on) { done("Not recording"); return }
            write(JSONObject().put("k", "stop").put("t", System.currentTimeMillis()).put("rows", rows)
                .put("sec", (System.currentTimeMillis() - startedAt) / 1000))
            on = false
            f = tmp ?: run { done("Nothing recorded"); return }
            w = out; out = null; tmp = null
            shots = takeShots()
        }
        val app = ctx.applicationContext
        io.execute {
            val video = try { VideoRecorder.stop() } catch (e: Exception) { null }
            val msg = save(app, f, w, video, shots, last = true)
            lastExport = msg
            Diag.info(msg)
            done(msg)
            uploadIfOn(app)
        }
    }

    // the debug data goes to GitHub by itself (Debug data page switch; only with a token)
    private fun uploadIfOn(app: Context) {
        try { if (Cfg.autoUpload && GitHubUpload.token(app) != null) GitHubUpload.uploadAll(app) } catch (e: Exception) { }
    }

    // one part's files: the data file, the video and its screenshots. The files that are the same for the whole
    // recording (research files, settings, the log, the scenery picture) are saved ONCE, with the last part
    // (owner, 2026-11-13: every part used to carry its own identical copies).
    private fun save(app: Context, f: File, w: java.io.Writer?, video: File?, shots: List<File>, last: Boolean): String = try {
        w?.close()
        val base = f.name.removeSuffix(".jsonl")
        val m1 = export(app, f).also { f.delete() }
        val m2 = video?.let { v -> try { export(app, v, "video/mp4").also { v.delete() } } catch (e: Exception) { "video not saved: ${e.message}" } }
            ?: "no video (screen capture was off, or no frame arrived)"
        var n = 0
        for (s in shots) try { if (s.length() > 0) { export(app, s, "image/jpeg"); n++ }; s.delete() } catch (e: Exception) { }
        val m3 = if (last) exportResearch(app, base) else ""
        val m4 = if (last) exportDebug(app, base) else ""
        "$m1; $m2; $n screenshot(s)" + (if (m3.isNotEmpty()) "; $m3" else "") + (if (m4.isNotEmpty()) "; $m4" else "")
    } catch (e: Exception) { "Saving the recording failed: ${e.message}" }

    private fun takeShots(): List<File> = synchronized(snaps) { ArrayList(snaps).also { snaps.clear() } }

    // the research files now, without a recording (Debug data page button)
    fun exportResearchNow(c: Context): String =
        exportResearch(c.applicationContext, "export-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).ifEmpty { "Nothing to export yet" }

    // the research files (R5 comparison ab_log.json, R4 practice_moments.jsonl, readiness_report.txt) live in
    // the app's private storage; copies go next to the recording: <session>-ab_log.json etc.
    private fun exportResearch(c: Context, base: String): String {
        try { com.example.mlbbresearch.research.ResearchMode.saveMoments() } catch (e: Exception) { }
        try { com.example.mlbbresearch.ai.ModelSpeed.save(sync = true); TouchSpeed.save(sync = true) } catch (e: Exception) { }
        val dir = File(c.filesDir, "research")
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return ""
        if (files.isEmpty()) return ""
        val tmp = File(c.cacheDir, "research-export").apply { mkdirs() }
        var n = 0
        for (src in files) try {
            val copy = File(tmp, "$base-${src.name}")
            src.copyTo(copy, overwrite = true)
            export(c, copy, if (src.name.endsWith(".txt")) "text/plain" else "application/json")
            copy.delete(); n++
        } catch (e: Exception) { }
        return if (n > 0) "$n research file(s) (R5 comparison, practice moments, readiness report, model speed, screen to touch)" else ""
    }

    // ---- DEBUG DATA WITH EVERY RECORDING (owner, 2026-11-12) ----
    // Clean screenshots (the frame as the app saw it, nothing drawn on it) at key moments: <session>-snap-<time>-<tag>.jpg
    private val snaps = java.util.Collections.synchronizedList(ArrayList<File>())
    private val snapAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    const val MAX_SNAPS = 80
    private fun snapFile(tag: String, now: Long): File? {
        val c = appCtx ?: return null
        val clean = tag.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(30)
        return File(c.cacheDir, name.removeSuffix(".jsonl") + "-snap-" + SimpleDateFormat("HHmmss", Locale.US).format(Date(now)) + "-" + clean + ".jpg")
    }
    private fun snapDue(tag: String, now: Long, minGapMs: Long): Boolean {
        if (!accept() || snaps.size >= MAX_SNAPS) return false
        val last = snapAt[tag] ?: 0L
        if (now - last < minGapMs) return false
        snapAt[tag] = now
        return true
    }
    // a frame's pixels (copied now: the buffer is reused)
    fun snap(tag: String, px: IntArray, w: Int, h: Int, minGapMs: Long = 4000L) {
        val now = System.currentTimeMillis()
        if (w <= 0 || h <= 0 || px.size < w * h || !snapDue(tag, now, minGapMs)) return
        val copy = px.copyOf(w * h)
        val f = snapFile(tag, now) ?: return
        io.execute {
            try {
                for (i in copy.indices) copy[i] = copy[i] or (0xff shl 24)
                val bmp = android.graphics.Bitmap.createBitmap(copy, w, h, android.graphics.Bitmap.Config.ARGB_8888)
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle()
                snaps.add(f)
                event(JSONObject().put("k", "snap").put("tag", tag).put("file", f.name))
            } catch (e: Exception) { }
        }
    }
    // a picture already made (what Gemma was given)
    fun snapBitmap(tag: String, bmp: android.graphics.Bitmap?, minGapMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (bmp == null || bmp.isRecycled || !snapDue(tag, now, minGapMs)) return
        val f = snapFile(tag, now) ?: return
        try {
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            snaps.add(f)
            event(JSONObject().put("k", "snap").put("tag", tag).put("file", f.name))
        } catch (e: Exception) { }
    }

    // the settings as they were (no keys / tokens / folder links, no big saved blobs)
    fun settingsJson(all: Map<String, *>): JSONObject {
        val o = JSONObject()
        for ((k, v) in all.toSortedMap()) {
            val lk = k.lowercase()
            if (v == null || listOf("key", "token", "secret", "pass", "uri", "kaggle").any { it in lk }) continue
            val sv = v.toString()
            if (sv.length > 300 && k != "stuck_cells") continue      // (the remembered stuck places are debug data: kept whole)
            o.put(k, v)
        }
        return o
    }

    // the files saved next to the recording at its end: screenshots, settings, the log (with crashes), the scenery picture
    private fun exportDebug(c: Context, base: String): String {
        var n = 0
        val tmpDir = File(c.cacheDir, "debug-export").apply { mkdirs() }
        fun put(name: String, mime: String, write: (File) -> Unit) = try {
            val f = File(tmpDir, name); write(f); if (f.length() > 0) { export(c, f, mime); n++ }; f.delete()
        } catch (e: Exception) { }
        put("$base-settings.json", "application/json") { it.writeText(settingsJson(Cfg.all()).toString(1)) }
        put("$base-diagnostics.log", "text/plain") { f -> Diag.logFile()?.takeIf { it.exists() }?.copyTo(f, overwrite = true) }
        put("$base-scenery.png", "image/png") { f ->
            f.outputStream().use { com.example.mlbbresearch.game.SceneryMap.renderPicture().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return if (n > 0) "$n debug file(s) (settings, log, scenery picture)" else ""
    }

    // one file into the recordings folder (used by the Practice and Speed pages' exports)
    fun exportFile(c: Context, f: File, mime: String): String = export(c, f, mime)

    // a new file in the recordings folder, written as a stream (big files: the training picture parts)
    fun openOut(c: Context, name: String, mime: String): java.io.OutputStream {
        Storage.dir(FOLDER)?.let { d -> return File(d, name).outputStream() }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Storage.downloadsRel(FOLDER))
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("cannot create $name")
        return c.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("cannot write $name")
    }

    // remove a file from the recordings folder (the picture parts once they are on GitHub)
    fun deleteFile(c: Context, name: String): Boolean {
        var gone = Storage.dir(FOLDER)?.let { File(it, name).delete() } ?: false
        try {
            gone = c.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(name, Storage.downloadsRel(FOLDER) + "/")) > 0 || gone
        } catch (e: Exception) { }
        return gone
    }

    private fun export(c: Context, f: File, mime: String = "application/json"): String {
        Storage.dir(FOLDER)?.let { d ->
            f.copyTo(File(d, f.name), overwrite = true)
            return "Saved ${f.name} (${f.length() / 1024} KB) in /${Storage.REL}/$FOLDER"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Storage.downloadsRel(FOLDER))
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("cannot create ${f.name}")
        c.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return "Saved ${f.name} (${f.length() / 1024} KB) in ${Storage.FALLBACK_REL}/$FOLDER"
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
            // EVERYTHING THE APP SAW (owner, 2026-11-12): allies, minions, towers, the minimap reading, the gate,
            // which lane is on top, the kind of match
            fun pts(l: List<Pair<Float, Float>>) = JSONArray(l.take(12).map { JSONArray(listOf(r3(it.first), r3(it.second))) })
            o.put("al", JSONArray(s.allies.take(6).map { JSONArray(listOf(r3(it.x), r3(it.y), r3(it.hp))) }))
            o.put("emn", JSONArray(s.enemyMinions.take(8).map { JSONArray(listOf(r3(it.x), r3(it.y), r3(it.hp))) }))
            o.put("amn", s.allyMinions.size)
            o.put("tw", JSONArray(s.towers.take(12).map { JSONArray(listOf(r3(it.x), r3(it.y))) }))
            if (s.towerRing.zone != com.example.mlbbresearch.vision.TowerRing.Zone.NONE) o.put("ring", s.towerRing.zone.name)
            o.put("mmE", pts(s.map.enemies)).put("mmA", pts(s.map.allies)).put("ew", pts(s.enemyWaves))
            s.map.objective?.let { o.put("obj", JSONArray(listOf(r3(it.first), r3(it.second)))) }
            o.put("gate", com.example.mlbbresearch.ai.AiRuntime.playerGate?.name ?: "?")
            o.put("side", when (com.example.mlbbresearch.game.MapSide.goldTop) { true -> "GOLD_TOP"; false -> "EXP_TOP"; null -> "?" })
            o.put("kind", com.example.mlbbresearch.game.MatchKind.current().name)
            o.put("lat", Speed.total().toInt())
            // research mode: rs = [verified practice?, arm (ARBITER / NORMAL)] - R3 / R5
            if (com.example.mlbbresearch.research.ResearchMode.on) o.put("rs", JSONArray(listOf(
                com.example.mlbbresearch.research.ResearchMode.allowsAutonomy(), com.example.mlbbresearch.research.ResearchMode.arm().name)))
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

    // a research row (R3 arbiter choice "arb", R5 scored fight "fight"); "t" is added here
    fun event(o: JSONObject) {
        if (!accept()) return
        try { write(o.put("t", System.currentTimeMillis())) } catch (e: Exception) { }
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
