package com.example.mlbbresearch.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// IN-APP VIDEO RECORDER (owner request, goes with SessionRecorder): encodes the frames the AI already
// captures (screen capture, ~15 fps at the capture size) into an MP4 - so it shows exactly what the AI
// saw - and draws on top what it thought:
//   - top line: clock time, match timer, intent and reason   (the same "t" / "ms" as the data file)
//   - green ring: your hero; red boxes: enemy heroes (id, HP); orange rings: monsters (HP)
//   - yellow cross: the decision's target; white arrow: the joystick direction
//   - cyan line: the latest aimed skill as PLANNED; magenta line: the same skill as RELEASED
// Android 14+ allows one capture stream per screen-capture permission, so this re-uses the AI's frames
// instead of opening a second one. It never touches the game.
object VideoRecorder {

    // (owner, 2026-11-13: 3 Mbit/s filled 25 MB in ~70 s; half of it still shows the overlay clearly)
    const val BITRATE = 1_500_000
    const val MAX_MS = 20 * 60_000L          // never longer than 20 minutes

    // PART SIZE: at this size the video is closed and the recording continues in a new file (SessionRecorder).
    // 25 MB fits a GitHub website upload; 50 MB (default) is about 4 minutes; GitHub refuses files over 100 MB.
    val SIZE_CHOICES_MB = listOf(25, 50, 95)
    const val DEFAULT_MB = 50
    var limitMb: Int
        get() = Cfg.raw("rec_video_limit_mb2")?.toIntOrNull()?.takeIf { it in SIZE_CHOICES_MB } ?: DEFAULT_MB
        set(v) = Cfg.setRaw("rec_video_limit_mb2", v.toString())
    // about how long a part of this size is (pure, unit tested)
    fun partSeconds(mb: Int): Int = (limitBytes(mb) * 8 / BITRATE).toInt()
    // stop a little early: the file's index is written at the end
    fun limitBytes(mb: Int): Long = mb * 1024L * 1024L * 97 / 100
    @Volatile var bytesWritten = 0L; private set
    @Volatile var onLimit: (() -> Unit)? = null      // set by SessionRecorder: stop and save
    @Volatile private var limitFired = false

    @Volatile var on = false; private set
    @Volatile var frames = 0; private set
    @Volatile var note = "-"; private set
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var input: Surface? = null
    private var track = -1
    private var muxing = false
    private var vw = 0; private var vh = 0
    private var file: File? = null
    private var startedAt = 0L
    private var drainer: Thread? = null
    private val lock = Object()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // the latest aimed skill, planned and released (set by SessionRecorder.aim)
    class Aim(val slot: String, val bx: Float, val by: Float, val ex: Float, val ey: Float, val at: Long)
    @Volatile var planned: Aim? = null
    @Volatile var released: Aim? = null

    // even size, at most 1280 wide (encoders want even sizes)
    fun videoSize(w: Int, h: Int): Pair<Int, Int> {
        val scale = if (w > 1280) 1280f / w else 1f
        return ((w * scale).toInt() and 1.inv()).coerceAtLeast(2) to ((h * scale).toInt() and 1.inv()).coerceAtLeast(2)
    }

    // starts on the next captured frame (its size is not known before)
    fun start(f: File) { synchronized(lock) { if (on) return; file = f; frames = 0; bytesWritten = 0L; limitFired = false; startedAt = 0L; note = "waiting for the first frame"; on = true } }

    private fun fireLimit(why: String) {
        if (limitFired) return
        limitFired = true; note = why
        Thread { onLimit?.invoke() }.start()      // never stop from inside the capture or encoder thread
    }

    // called by ScreenCaptureService for each captured frame (capture thread)
    fun frame(b: Bitmap, w: Int, h: Int) {
        if (!on) return
        synchronized(lock) {
            if (!on) return
            try {
                if (codec == null) open(w, h)
                if (System.currentTimeMillis() - startedAt > MAX_MS) { fireLimit("20 minute limit reached - saved"); return }
                if (limitFired) return
                val s = input ?: return
                // the hardware canvas is what encoder surfaces accept most reliably; software as a fallback
                val c = try { s.lockHardwareCanvas() } catch (e: Exception) { s.lockCanvas(null) }
                try { draw(c, b, w, h) } finally { s.unlockCanvasAndPost(c) }
                frames++
            } catch (e: Exception) {
                note = "video stopped: ${e.message}"; Diag.error("Video recorder", e); release(false)
            }
        }
    }

    private fun open(w: Int, h: Int) {
        val (cw, ch) = videoSize(w, h); vw = cw; vh = ch
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cw, ch).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        input = c.createInputSurface()
        c.start()
        codec = c
        muxer = MediaMuxer(file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        track = -1; muxing = false
        startedAt = System.currentTimeMillis()
        note = "recording ${cw}x$ch"
        drainer = Thread { drainLoop(c) }.apply { name = "video-drain"; start() }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { c.dequeueOutputBuffer(info, 20_000) } catch (e: Exception) { return }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    val m = muxer ?: return
                    track = m.addTrack(c.outputFormat); m.start(); muxing = true
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) synchronized(lock) {
                        if (muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer?.writeSampleData(track, buf, info); bytesWritten += info.size }
                    }
                    if (bytesWritten >= limitBytes(limitMb)) fireLimit("$limitMb MB limit reached - saved")
                    c.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // stops and finishes the MP4; returns the file when it holds video
    fun stop(): File? {
        val c: MediaCodec?; val d: Thread?
        synchronized(lock) {
            if (!on) return null
            on = false
            c = codec; d = drainer
            try { c?.signalEndOfInputStream() } catch (e: Exception) { }
        }
        try { d?.join(3000) } catch (e: InterruptedException) { }
        synchronized(lock) { return release(true) }
    }

    private fun release(keep: Boolean): File? {
        val ok = muxing && frames > 0
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }
        try { if (muxing) muxer?.stop() } catch (e: Exception) { }
        try { muxer?.release() } catch (e: Exception) { }
        try { input?.release() } catch (e: Exception) { }
        codec = null; muxer = null; input = null; drainer = null; muxing = false; on = false
        val f = file; file = null
        return if (keep && ok) f else { f?.delete(); null }
    }

    // ---- drawing ----
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
    private val pBar = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private fun stroke(c: Int, w: Float = 3f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.STROKE; strokeWidth = w }
    private val pSelf = stroke(Color.GREEN); private val pEnemy = stroke(Color.RED); private val pMon = stroke(Color.rgb(255, 150, 0))
    private val pTarget = stroke(Color.YELLOW, 4f); private val pJoy = stroke(Color.WHITE, 4f)
    private val pPlan = stroke(Color.CYAN, 5f); private val pRel = stroke(Color.MAGENTA, 5f)

    private fun draw(cv: Canvas, b: Bitmap, w: Int, h: Int) {
        cv.drawBitmap(b, Rect(0, 0, w, h), RectF(0f, 0f, vw.toFloat(), vh.toFloat()), null)
        val s = com.example.mlbbresearch.game.GameStateTracker.state
        val d = SessionRecorder.lastDecision
        fun X(x: Float) = x * vw
        fun Y(y: Float) = y * vh
        val aspect = vw.toFloat() / vh
        s.self?.let { me ->
            cv.drawCircle(X(me.x), Y(me.y), 26f, pSelf)
            if (d != null && (d.moveX != 0f || d.moveY != 0f))   // joystick direction (x was multiplied by the aspect)
                cv.drawLine(X(me.x), Y(me.y), X(me.x) + d.moveX / aspect * 0.12f * vw, Y(me.y) + d.moveY * 0.12f * vh, pJoy)
            // aimed skill: direction of the drag (end - button), drawn from your hero
            val now = System.currentTimeMillis()
            for ((a, p) in listOf(planned to pPlan, released to pRel)) if (a != null && now - a.at < 1500) {
                val dx = (a.ex - a.bx) * vw; val dy = (a.ey - a.by) * vh
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
                cv.drawLine(X(me.x), Y(me.y), X(me.x) + dx / l * vh * 0.35f, Y(me.y) + dy / l * vh * 0.35f, p)
                cv.drawText(a.slot, X(me.x) + dx / l * vh * 0.37f, Y(me.y) + dy / l * vh * 0.37f, pText)
            }
        }
        for (e in s.enemies) {
            cv.drawRect(X(e.x) - 24f, Y(e.y) - 40f, X(e.x) + 24f, Y(e.y) + 20f, pEnemy)
            cv.drawText("#${e.id} ${(e.hp * 100).toInt()}%", X(e.x) - 24f, Y(e.y) - 46f, pText)
        }
        for (m in s.monsters) {
            cv.drawCircle(X(m.x), Y(m.y), 22f, pMon)
            cv.drawText("${(m.hp * 100).toInt()}%", X(m.x) + 24f, Y(m.y), pText)
        }
        if (d != null && d.targetX >= 0f) {
            val tx = X(d.targetX); val ty = Y(d.targetY)
            cv.drawLine(tx - 18f, ty, tx + 18f, ty, pTarget); cv.drawLine(tx, ty - 18f, tx, ty + 18f, pTarget)
        }
        val ms = s.matchSec
        val head = fmt.format(Date()) + "  match " + (if (ms >= 0) "%d:%02d".format(ms / 60, ms % 60) else "?") +
            "  " + (d?.let { it.intent.name + ": " + it.reason.take(70) } ?: "")
        // the banner at the BOTTOM (owner, 2026-11-12: at the top it covered the minimap's lane mark corner)
        cv.drawRect(0f, vh - 30f, vw.toFloat(), vh.toFloat(), pBar)
        cv.drawText(head, 6f, vh - 7f, pText)
    }
}
