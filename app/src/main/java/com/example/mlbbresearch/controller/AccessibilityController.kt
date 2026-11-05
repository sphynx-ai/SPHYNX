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
    @Volatile private var joyStroke: GestureDescription.StrokeDescription? = null
    @Volatile private var joyStrokeEndedAt = 0L
    private var joyX = 0f
    private var joyY = 0f
    private var nudgeRight = false

    private fun nudge(x: Float): Float {
        nudgeRight = !nudgeRight
        return if (nudgeRight) x + 0.5f else x - 0.5f
    }

    private val touchLock = Any()
    private var inFlight = false
    private var inFlightAt = 0L
    private var pendingActs: ArrayList<Act>? = null
    private var pendingShow = false
    private var pendingSince = 0L
    private val touchThread = android.os.HandlerThread("touches").apply { start() }
    private val touchHandler = android.os.Handler(touchThread.looper)

    @Volatile private var lastWantedJoy: Act.Joy? = null

    fun clearWantedJoystick() {
        synchronized(touchLock) {
            lastWantedJoy = null
            pendingActs?.removeAll { it is Act.Joy }
        }
    }

    fun dispatch(acts: List<Act>, showTouches: Boolean) {
        synchronized(touchLock) {
            val now = System.currentTimeMillis()
            for (a in acts) {
                if (a is Act.Joy) lastWantedJoy = a
                else if (a is Act.JoyRelease) lastWantedJoy = null
            }
            if (inFlight && now - inFlightAt < 920) {
                val q = pendingActs ?: ArrayList<Act>().also { pendingActs = it }
                for (a in acts) {
                    when (a) {
                        is Act.Joy -> { q.removeAll { it is Act.Joy || it is Act.JoyRelease }; q.add(a) }
                        is Act.JoyRelease -> { q.removeAll { it is Act.Joy || it is Act.JoyRelease }; q.add(a) }
                        is Act.SkillAim -> { q.removeAll { it is Act.SkillAim }; q.add(a) }
                        is Act.Tap -> {
                            q.removeAll { it is Act.Tap && kotlin.math.abs(it.x - a.x) < 0.01f && kotlin.math.abs(it.y - a.y) < 0.01f && it.holdMs <= a.holdMs }
                            q.add(a)
                        }
                        else -> q.add(a)
                    }
                }
                pendingShow = pendingShow || showTouches
                if (pendingSince == 0L) pendingSince = now
                return
            }
            inFlight = true
            inFlightAt = now
            com.example.mlbbresearch.core.Speed.touch(0L)
        }
        if (!send(acts, showTouches)) next()
    }

    private fun requeueNonJoy(acts: List<Act>, show: Boolean) {
        val nonJoy = acts.filter { it !is Act.Joy && it !is Act.JoyRelease }
        if (nonJoy.isEmpty()) return
        synchronized(touchLock) {
            val q = pendingActs ?: ArrayList<Act>().also { pendingActs = it }
            for (i in nonJoy.indices.reversed()) {
                val item = nonJoy[i]
                if (item is Act.SkillAim && q.any { it is Act.SkillAim }) continue
                q.add(0, item)
            }
            pendingShow = pendingShow || show
        }
    }

    // the screen is free: send what waited, or continue holding the active joystick in fast 85ms slices
    private fun next() {
        val q: List<Act>
        val show: Boolean
        synchronized(touchLock) {
            val now = System.currentTimeMillis()
            joyStrokeEndedAt = now
            val p = pendingActs
            if (p.isNullOrEmpty()) {
                val wj = lastWantedJoy
                if (wj != null && com.example.mlbbresearch.ai.AiRuntime.playerOn) {
                    q = listOf(Act.Joy(wj.cx, wj.cy, wj.x, wj.y, CastTiming.JOY_HOLD_MS))
                    show = false
                    inFlightAt = now
                } else {
                    joyStroke = null
                    inFlight = false
                    pendingActs = null
                    return
                }
            } else {
                val hasSkillHold = p.any { it is Act.SkillAim || (it is Act.Tap && it.holdMs >= CastTiming.TAP_HOLD_MS) }
                if (hasSkillHold && p.any { it is Act.Tap && it.holdMs < CastTiming.TAP_HOLD_MS }) {
                    val nowBatch = ArrayList<Act>()
                    val laterBatch = ArrayList<Act>()
                    for (item in p) {
                        if (item is Act.Tap && item.holdMs < CastTiming.TAP_HOLD_MS) laterBatch.add(item)
                        else nowBatch.add(item)
                    }
                    q = nowBatch
                    show = pendingShow
                    pendingActs = laterBatch
                } else {
                    q = p; show = pendingShow
                    pendingActs = null; pendingShow = false
                }
                inFlightAt = now
                if (pendingSince > 0L) com.example.mlbbresearch.core.Speed.touch(inFlightAt - pendingSince)
                pendingSince = 0L
            }
        }
        if (!send(q, show)) next()
    }

    @Synchronized
    private fun send(acts: List<Act>, showTouches: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val (rw, rh) = com.example.mlbbresearch.core.ScreenGeom.size(this)
        val w = rw.toFloat()
        val h = rh.toFloat()
        val g = GestureDescription.Builder()
        var n = 0
        var joyAct: Act.Joy? = null
        var release = false
        for (a in acts) {
            when (a) {
                is Act.Joy -> { joyAct = a; release = false }
                is Act.JoyRelease -> { release = true; joyAct = null }
                else -> {}
            }
        }
        if (joyAct == null && !release && lastWantedJoy != null && com.example.mlbbresearch.ai.AiRuntime.playerOn) {
            joyAct = lastWantedJoy
        }
        val skillAim = acts.filterIsInstance<Act.SkillAim>().firstOrNull()
        val maxOtherDur = acts.maxOfOrNull {
            when (it) {
                is Act.SkillAim -> it.pressHoldMs
                is Act.Tap -> it.startMs + it.holdMs
                is Act.Drag -> it.startMs + it.ms
                else -> CastTiming.JOY_HOLD_MS
            }
        } ?: CastTiming.JOY_HOLD_MS

        val held = joyStroke?.takeIf { joyStrokeEndedAt == 0L || now - joyStrokeEndedAt <= 60L }
        var newJoy: GestureDescription.StrokeDescription? = null
        try {
            if (joyAct != null && !release) {
                val ty = joyAct.y * h
                val tx = if (held != null && joyAct.x * w == joyX && ty == joyY) nudge(joyX) else joyAct.x * w
                val p = Path()
                val joyDur = maxOf(joyAct.ms, maxOtherDur, CastTiming.JOY_HOLD_MS)
                if (held == null) {
                    val cx = joyAct.cx * w; val cy = joyAct.cy * h
                    p.moveTo(cx, cy)
                    p.lineTo(tx, ty)
                    newJoy = GestureDescription.StrokeDescription(p, 0L, joyDur.coerceAtLeast(CastTiming.JOY_HOLD_MS), true)
                } else {
                    p.moveTo(joyX, joyY)
                    for ((vx, vy) in joyAct.via) p.lineTo(vx * w, vy * h)
                    p.lineTo(tx, ty)
                    newJoy = held.continueStroke(p, 0L, joyDur.coerceAtLeast(60L), true)
                }
                g.addStroke(newJoy)
                n++
                joyX = tx
                joyY = ty
                if (showTouches) overlay?.showTouch(Act.Drag(joyAct.cx, joyAct.cy, joyAct.x, joyAct.y, joyDur))
            } else if (held != null && release) {
                val p = Path()
                p.moveTo(joyX, joyY)
                val ex = nudge(joyX)
                p.lineTo(ex, joyY)
                g.addStroke(held.continueStroke(p, 0L, 35L, false))
                n++
            }
        } catch (e: Exception) {
            Diag.error("Joystick stroke", e)
            newJoy = null
        }
        joyStroke = newJoy

        // 2) Two-stage continuous SkillAim with Live Stage-2 Target Re-Aiming (v37.09):
        // Stage 1: Press skill button (x1, y1) and hold for 0.3s (pressHoldMs = 300ms) with willContinue = true
        // Stage 2: Compute LIVE target aim endpoint via ActionExecutor.freshAimEnd(skillAim) and drag while holding for 0.3s / 0.5s, then release!
        if (skillAim != null && n < GestureDescription.getMaxStrokeCount()) {
            val sx1 = skillAim.x1 * w
            val sy1 = skillAim.y1 * h
            val sx1End = sx1 + 0.5f
            val p1 = Path().apply { moveTo(sx1, sy1); lineTo(sx1End, sy1) }
            val stroke1 = GestureDescription.StrokeDescription(p1, 0L, skillAim.pressHoldMs.coerceAtLeast(50L), true)
            g.addStroke(stroke1)
            n++
            if (showTouches) overlay?.showTouch(skillAim)
            val mine = newJoy
            val ok1 = try {
                dispatchGesture(g.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        joyStrokeEndedAt = System.currentTimeMillis()
                        val g2 = GestureDescription.Builder()
                        var nextJoy: GestureDescription.StrokeDescription? = null
                        val aimDur = skillAim.aimHoldMs.coerceAtLeast(50L)
                        if (mine != null && joyStroke === mine && lastWantedJoy != null) {
                            try {
                                val wj = lastWantedJoy
                                val tx2 = if (wj != null) (if (wj.x * w == joyX && wj.y * h == joyY) nudge(joyX) else wj.x * w) else nudge(joyX)
                                val ty2 = if (wj != null) wj.y * h else joyY
                                val jp = Path().apply { moveTo(joyX, joyY); lineTo(tx2, ty2); joyX = tx2; joyY = ty2 }
                                nextJoy = mine.continueStroke(jp, 0L, aimDur, true)
                                g2.addStroke(nextJoy)
                            } catch (_: Exception) { nextJoy = null }
                        }
                        joyStroke = nextJoy
                        // Re-evaluate the target's live position right at the start of Stage 2 so moving enemies/creeps are hit accurately!
                        val (liveX2, liveY2) = ActionExecutor.freshAimEnd(skillAim)
                        val p2 = Path().apply {
                            moveTo(sx1End, sy1)
                            lineTo(liveX2 * w, liveY2 * h)
                        }
                        val stroke2 = stroke1.continueStroke(p2, 0L, aimDur, false)
                        g2.addStroke(stroke2)
                        val mine2 = nextJoy
                        val ok2 = try {
                            dispatchGesture(g2.build(), object : GestureResultCallback() {
                                override fun onCompleted(gd: GestureDescription?) { next() }
                                override fun onCancelled(gd: GestureDescription?) {
                                    joyStroke = null
                                    if (mine2 == null && !com.example.mlbbresearch.ai.AiRuntime.playerOn) {
                                        userTouchAt = System.currentTimeMillis()
                                    }
                                    next()
                                }
                            }, touchHandler)
                        } catch (e: Exception) { Diag.error("SkillAim stage 2", e); false }
                        if (!ok2) {
                            joyStroke = null
                            next()
                        }
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        joyStroke = null
                        if (mine != null) {
                            requeueNonJoy(acts, showTouches)
                        } else if (!com.example.mlbbresearch.ai.AiRuntime.playerOn) {
                            userTouchAt = System.currentTimeMillis()
                        }
                        next()
                    }
                }, touchHandler)
            } catch (e: Exception) { Diag.error("SkillAim stage 1", e); false }
            if (!ok1) {
                joyStroke = null
                if (mine != null) {
                    val savedJoy = lastWantedJoy
                    lastWantedJoy = null
                    val retried = send(acts.filter { it !is Act.Joy }, showTouches)
                    lastWantedJoy = savedJoy
                    return retried
                }
            }
            return ok1
        }

        // 3) taps (including 0.5s target-lock skill holds and 220-340ms basic attack holds) and one-off drags
        for (a in acts) {
            if (n >= GestureDescription.getMaxStrokeCount()) break
            val p = Path()
            var start = 0L
            val dur: Long = when (a) {
                is Act.Tap -> { p.moveTo(a.x * w, a.y * h); p.lineTo(a.x * w + 0.5f, a.y * h); start = a.startMs; a.holdMs.coerceAtLeast(50L) }
                is Act.Drag -> { p.moveTo(a.x1 * w, a.y1 * h); p.lineTo(a.x2 * w, a.y2 * h); start = a.startMs; a.ms.coerceAtLeast(50L) }
                else -> continue
            }
            g.addStroke(GestureDescription.StrokeDescription(p, start.coerceAtLeast(0L), dur))
            n++
            if (showTouches) overlay?.showTouch(a)
        }
        if (n == 0) return false
        val mine = newJoy
        val hasNonJoy = acts.any { it !is Act.Joy && it !is Act.JoyRelease }
        val ok = try {
            dispatchGesture(g.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { next() }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    joyStroke = null
                    if (mine != null && hasNonJoy) {
                        requeueNonJoy(acts, showTouches)
                    } else if (!com.example.mlbbresearch.ai.AiRuntime.playerOn) {
                        userTouchAt = System.currentTimeMillis()
                    }
                    next()
                }
            }, touchHandler)
        } catch (e: Exception) { Diag.error("Touch", e); false }
        if (!ok) {
            joyStroke = null
            if (mine != null && hasNonJoy) {
                val savedJoy = lastWantedJoy
                lastWantedJoy = null
                val retried = send(acts.filter { it !is Act.Joy }, showTouches)
                lastWantedJoy = savedJoy
                return retried
            }
        }
        return ok
    }

    fun joystickHeld() = joyStroke != null || (lastWantedJoy != null && com.example.mlbbresearch.ai.AiRuntime.playerOn)

    fun releaseJoystick() {
        clearWantedJoystick()
        if (joyStroke != null) dispatch(listOf(Act.JoyRelease), false)
        joyStroke = null
    }

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
