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

        // last time a real finger interrupted the AI's touches (coach mode: the AI steps back)
        @Volatile var userTouchAt = 0L
        fun screenAspect(): Float {
            val svc = instance ?: return 1.78f
            val (w, h) = com.example.mlbbresearch.core.ScreenGeom.size(svc)
            return w.toFloat() / h.coerceAtLeast(1)
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

    // ---- joystick held between gestures ----
    // The finger stays on the joystick (a "continued" stroke) and is only moved, so the hero
    // walks smoothly instead of stopping each time a new touch starts.
    private var joyStroke: GestureDescription.StrokeDescription? = null
    private var joyX = 0f
    private var joyY = 0f
    private var nudgeRight = false

    // strokes need a non-zero length: move half a pixel, alternating sides so it never drifts
    private fun nudge(x: Float): Float {
        nudgeRight = !nudgeRight
        return if (nudgeRight) x + 0.5f else x - 0.5f
    }

    // All actions go out as ONE gesture, so moving and casting happen at the same time
    @Synchronized
    fun dispatch(acts: List<Act>, showTouches: Boolean) {
        // touches are in whole-screen pixels (camera cut-out included), like the capture
        val (rw, rh) = com.example.mlbbresearch.core.ScreenGeom.size(this)
        val w = rw.toFloat()
        val h = rh.toFloat()
        val g = GestureDescription.Builder()
        var n = 0
        var joyAct: Act.Joy? = null
        var release = false
        for (a in acts) {
            when (a) {
                is Act.Joy -> joyAct = a
                is Act.JoyRelease -> release = true
                else -> {}
            }
        }
        // 1) the joystick finger: start, move, keep holding, or lift
        val held = joyStroke
        var newJoy: GestureDescription.StrokeDescription? = null
        try {
            if (joyAct != null) {
                val ty = joyAct.y * h
                // strokes need a non-zero length, so an unchanged target is nudged
                val tx = if (held != null && joyAct.x * w == joyX && ty == joyY) nudge(joyX) else joyAct.x * w
                val p = Path()
                if (held == null) {
                    // like a thumb: press the joystick's CENTRE, hold, then swipe one way to the edge
                    // (the swipe follows a moment later as a continuation of this press)
                    val cx = joyAct.cx * w; val cy = joyAct.cy * h
                    p.moveTo(cx, cy)
                    p.lineTo(cx + 0.5f, cy)
                    newJoy = GestureDescription.StrokeDescription(p, 0, 40, true)
                    g.addStroke(newJoy)
                    n++
                    joyX = cx + 0.5f
                    joyY = cy
                    joyStroke = newJoy
                    val swipe = joyAct
                    mainHandler.postDelayed({ try { if (joyStroke != null) dispatch(listOf(swipe), false) } catch (_: Exception) {} }, 55)
                    if (showTouches) overlay?.showTouch(Act.Drag(joyAct.cx, joyAct.cy, joyAct.x, joyAct.y, joyAct.ms))
                } else {
                    // a continuation must start exactly where the last stroke ended; a turn
                    // slides through the in-between directions along the ring
                    p.moveTo(joyX, joyY)
                    for ((vx, vy) in joyAct.via) p.lineTo(vx * w, vy * h)
                    p.lineTo(tx, ty)
                    newJoy = held.continueStroke(p, 0, joyAct.ms, true)
                    g.addStroke(newJoy)
                    n++
                    joyX = tx
                    joyY = ty
                    if (showTouches) overlay?.showTouch(Act.Drag(joyAct.cx, joyAct.cy, joyAct.x, joyAct.y, joyAct.ms))
                }
            } else if (held != null) {
                val p = Path()
                p.moveTo(joyX, joyY)
                val ex = nudge(joyX)
                p.lineTo(ex, joyY)
                if (release) {
                    g.addStroke(held.continueStroke(p, 0, 40, false))
                } else {
                    // another touch is going out: keep the finger where it is
                    newJoy = held.continueStroke(p, 0, 60, true)
                    g.addStroke(newJoy)
                    joyX = ex
                }
                n++
            }
        } catch (e: Exception) {
            // the system refused the continuation (old gesture already gone): start fresh next time
            Diag.error("Joystick stroke", e)
            newJoy = null
        }
        joyStroke = newJoy

        // 2) taps and one-off drags
        for (a in acts) {
            if (n >= GestureDescription.getMaxStrokeCount()) break
            val p = Path()
            val dur: Long = when (a) {
                is Act.Tap -> { p.moveTo(a.x * w, a.y * h); p.lineTo(a.x * w + 1, a.y * h); if (Cfg.fastMode) 35L else 60L }
                is Act.Drag -> { p.moveTo(a.x1 * w, a.y1 * h); p.lineTo(a.x2 * w, a.y2 * h); a.ms }
                else -> continue
            }
            g.addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            n++
            if (showTouches) overlay?.showTouch(a)
        }
        if (n == 0) return
        val mine = newJoy
        val seq = ++dispatchSeq
        val ok = dispatchGesture(g.build(), object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                // interrupted (e.g. the user touched the screen): the finger is lifted
                if (mine != null && joyStroke === mine) joyStroke = null
                // not replaced by a newer gesture of ours = YOUR finger touched the screen
                if (seq == dispatchSeq) userTouchAt = System.currentTimeMillis()
            }
        }, null)
        if (!ok && joyStroke === mine) joyStroke = null
    }

    fun joystickHeld() = joyStroke != null
    @Volatile private var dispatchSeq = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Lifts the joystick finger at once (emergency stop, AI off)
    fun releaseJoystick() {
        if (joyStroke != null) dispatch(listOf(Act.JoyRelease), false)
        joyStroke = null
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
