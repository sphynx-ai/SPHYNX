package com.example.mlbbresearch.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.capture.FrameHub
import com.example.mlbbresearch.capture.ScreenCaptureService
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.CrashLog
import com.example.mlbbresearch.core.Diag

// The "hands": performs touches through Android's accessibility service and owns the
// floating overlay. If screen capture was not allowed, it also grabs a screenshot about
// once a second as a slower fallback.
class AccessibilityController : AccessibilityService() {

    companion object {
        @Volatile var instance: AccessibilityController? = null
            private set

        fun screenAspect(): Float {
            val m = instance?.resources?.displayMetrics ?: return 1.78f
            return m.widthPixels.toFloat() / m.heightPixels.coerceAtLeast(1)
        }
    }

    var overlay: OverlayManager? = null
        private set
    private var shotBusy = false

    override fun onServiceConnected() {
        Cfg.init(this)
        Diag.init(this)
        CrashLog.install()
        instance = this
        overlay = OverlayManager(this)
        Diag.info("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AiRuntime.emergencyStop("accessibility service turned off")
        overlay?.hide()
        overlay = null
        instance = null
        Diag.info("Accessibility service disconnected")
        return super.onUnbind(intent)
    }

    // All actions go out as ONE gesture, so moving and casting happen at the same time
    fun dispatch(acts: List<Act>, showTouches: Boolean) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val g = GestureDescription.Builder()
        var n = 0
        for (a in acts) {
            if (n >= GestureDescription.getMaxStrokeCount()) break
            val p = Path()
            val dur: Long = when (a) {
                is Act.Tap -> { p.moveTo(a.x * w, a.y * h); p.lineTo(a.x * w + 1, a.y * h); 60L }
                is Act.Drag -> { p.moveTo(a.x1 * w, a.y1 * h); p.lineTo(a.x2 * w, a.y2 * h); a.ms }
            }
            g.addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            n++
            if (showTouches) overlay?.showTouch(a)
        }
        if (n > 0) dispatchGesture(g.build(), null, null)
    }

    // Slow fallback when screen capture is not running (max about 1 per second)
    fun grabScreenshot() {
        if (shotBusy || ScreenCaptureService.running) return
        shotBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        if (soft != null) {
                            val w = FrameHub.CAPTURE_WIDTH
                            val h = (soft.height.toLong() * w / soft.width).toInt().coerceAtLeast(1)
                            val small = Bitmap.createScaledBitmap(soft, w, h, true)
                            FrameHub.submit(small, w, h, "accessibility (1 fps)")
                        }
                    } catch (e: Exception) {
                        Diag.error("Screenshot fallback failed", e)
                    }
                    shotBusy = false
                }

                override fun onFailure(errorCode: Int) {
                    shotBusy = false
                }
            })
        } catch (e: Exception) {
            shotBusy = false
        }
    }
}
