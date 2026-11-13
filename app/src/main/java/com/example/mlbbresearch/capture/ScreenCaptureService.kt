package com.example.mlbbresearch.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.core.Diag

// Live screen frames (10-15 fps) through MediaProjection.
// Android requires this to be a foreground service with a visible notification.
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.example.mlbbresearch.STOP_CAPTURE"
        const val ACTION_EMERGENCY = "com.example.mlbbresearch.EMERGENCY_STOP"
        const val CHANNEL = "capture"
        private const val NOTIF_ID = 7
        // fast mode about 30 fps, balanced about 15 fps
        private val minFrameGapMs: Long get() = if (com.example.mlbbresearch.core.Cfg.fastMode) 33L else 66L

        @Volatile var running = false
            private set

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var buffer: Bitmap? = null
    private var padBuffer: java.nio.ByteBuffer? = null
    private var lastFrame = 0L
    private var capW = 0
    private var capH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_EMERGENCY -> { AiRuntime.emergencyStop("notification button"); return START_NOT_STICKY }
        }
        if (projection != null) return START_NOT_STICKY

        // Must be in the foreground BEFORE asking for the projection (Android 14+)
        ensureChannel(this)
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Diag.error("Capture: could not start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            Diag.error("Capture: permission result missing")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)!!
            val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Diag.info("Screen capture stopped by the system")
                    stopSelf()
                }
            }, Handler(mainLooper))
            startCapture(mp)
            running = true
            Diag.info("Screen capture started (${capW}x$capH)")
            // learning from you runs whenever the screen is captured, Player on or off
            if (!com.example.mlbbresearch.ai.AiRuntime.learnOn) com.example.mlbbresearch.ai.AiRuntime.start("learn")
        } catch (e: Exception) {
            Diag.error("Capture: could not start", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun screenSize(): Pair<Int, Int> {
        return com.example.mlbbresearch.core.ScreenGeom.size(this)
    }

    private fun startCapture(mp: MediaProjection) {
        val t = HandlerThread("capture").apply { start() }
        thread = t
        handler = Handler(t.looper)
        val (sw, sh) = screenSize()
        capW = FrameHub.CAPTURE_WIDTH
        capH = (capW.toLong() * sh / sw).toInt().coerceAtLeast(1)
        val r = newReader(capW, capH)
        reader = r
        display = mp.createVirtualDisplay(
            "mlbb-capture", capW, capH, resources.displayMetrics.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        return r
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrame < minFrameGapMs) return
            lastFrame = now
            val plane = img.planes[0]
            val stridePx = plane.rowStride / plane.pixelStride
            val w = img.width
            val h = img.height
            var b = buffer
            if (b == null || b.width != stridePx || b.height != h) {
                b = Bitmap.createBitmap(stridePx, h, Bitmap.Config.ARGB_8888)
                buffer = b
            }
            val src = plane.buffer
            src.rewind()
            val needed = stridePx * h * 4
            if (src.remaining() >= needed) {
                b!!.copyPixelsFromBuffer(src)
            } else {
                // some phones leave the last row short: pad it
                var pad = padBuffer
                if (pad == null || pad.capacity() != needed) {
                    pad = java.nio.ByteBuffer.allocateDirect(needed)
                    padBuffer = pad
                }
                pad!!.clear()
                pad.put(src)
                pad.rewind()
                b!!.copyPixelsFromBuffer(pad)
            }
            FrameHub.submit(b, w, h, "screen capture")
            if (com.example.mlbbresearch.core.VideoRecorder.on) com.example.mlbbresearch.core.VideoRecorder.frame(b, w, h)
        } catch (e: Exception) {
            Diag.error("Capture frame failed", e)
        } finally {
            img.close()
        }
    }

    // Phone rotated: resize the capture so it keeps the screen's shape
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = display ?: return
        val (sw, sh) = screenSize()
        val h = (FrameHub.CAPTURE_WIDTH.toLong() * sh / sw).toInt().coerceAtLeast(1)
        if (h == capH) return
        handler?.post {
            try {
                val old = reader
                val r = newReader(FrameHub.CAPTURE_WIDTH, h)
                vd.resize(FrameHub.CAPTURE_WIDTH, h, resources.displayMetrics.densityDpi / 2)
                vd.surface = r.surface
                reader = r
                capH = h
                buffer = null
                old?.close()
            } catch (e: Exception) {
                Diag.error("Capture resize failed", e)
            }
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_EMERGENCY),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Sphynx Auto Pilot is watching the screen")
            .setContentText("Tap STOP AI to stop everything")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "STOP AI", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        try { display?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        thread?.quitSafely()
        display = null; reader = null; projection = null
        FrameHub.reset()
        com.example.mlbbresearch.game.GameStateTracker.captureStopped()   // an open match is closed after a minute without capture
        super.onDestroy()
    }
}
