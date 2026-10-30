package com.example.mlbbresearch.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.vision.FrameProcessor
import com.example.mlbbresearch.vision.ScreenMasks
import com.example.mlbbresearch.vision.Team
import kotlin.math.abs

// Draws on top of the game: where the AI touches, what it sees (boxes), calibration
// markers, and the floating icon that opens a resizable status window.
// Colours avoid red/green/blue so the AI's own vision never mistakes them for HP bars.
class OverlayManager(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var useAppOverlay = false
    private var pointer: PointerView? = null
    private var icon: FrameLayout? = null
    private var badge: TextView? = null
    private var iconLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelText: TextView? = null
    private var panelLp: WindowManager.LayoutParams? = null

    @Volatile var markersOn = false

    val shown: Boolean get() = icon != null

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    // ---------------- public ----------------

    fun show(): Boolean {
        if (icon != null) return true
        return try {
            build()
            ui.post(refresher)
            true
        } catch (e: Exception) {
            Diag.error("Floating icon failed", e)
            hide()
            false
        }
    }

    fun hide() {
        ui.removeCallbacks(refresher)
        for (v in listOf<View?>(icon, panel, pointer)) {
            if (v != null) try { wm.removeView(v) } catch (e: Exception) { }
        }
        icon = null; panel = null; pointer = null; badge = null; panelText = null
        ScreenMasks.overlay = emptyList()
    }

    fun showTouch(a: Act) {
        val pv = pointer ?: return
        val w = ctx.resources.displayMetrics.widthPixels.toFloat()
        val h = ctx.resources.displayMetrics.heightPixels.toFloat()
        when (a) {
            is Act.Tap -> pv.addTouch(a.x * w, a.y * h, a.x * w, a.y * h, false)
            is Act.Drag -> pv.addTouch(a.x1 * w, a.y1 * h, a.x2 * w, a.y2 * h, true)
        }
    }

    fun flashMarker(x: Float, y: Float, label: String) {
        if (pointer == null) show()
        pointer?.flash(x, y, label)
    }

    // ---------------- building ----------------

    private fun addOverlay(v: View, lp: WindowManager.LayoutParams) {
        if (!useAppOverlay) {
            try {
                lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                wm.addView(v, lp)
                return
            } catch (e: WindowManager.BadTokenException) {
                Diag.error("Accessibility overlay refused, using 'display over other apps' instead", e)
                useAppOverlay = true
            }
        }
        if (!Settings.canDrawOverlays(ctx)) {
            throw IllegalStateException("Floating icon needs the 'Display over other apps' permission")
        }
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        wm.addView(v, lp)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun build() {
        val flagsPass = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val flagsTouch = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        // 1) full-screen drawing layer (touch markers, boxes) that never takes touches
        val pv = PointerView(ctx)
        addOverlay(pv, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flagsPass, PixelFormat.TRANSLUCENT
        ))
        pointer = pv

        // 2) round icon with a red X (X = emergency stop, tap icon = open window, drag = move)
        val ic = FrameLayout(ctx)
        val b = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        ic.addView(b, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(0, dp(10), dp(10), 0)
        })
        val close = TextView(ctx).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            background = circle(Color.parseColor("#D32F2F")).apply { setStroke(dp(1), Color.WHITE) }
            setOnClickListener { AiRuntime.emergencyStop("floating X") }
        }
        ic.addView(close, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
        val ilp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flagsTouch, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(140) }
        dragToMove(ic, ilp, onTap = { expand() })

        // 3) status window: drag the title to move, drag the corner to resize
        val pn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE15171C"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#5F6368"))
            }
            setPadding(dp(8), dp(6), dp(8), dp(4))
            visibility = View.GONE
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "MLBB AI  (drag me)"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "—"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(12), 0, dp(6), 0)
            setOnClickListener { collapse() }
        })
        pn.addView(header)
        val body = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 11.5f }
        pn.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(ctx).apply {
            text = "■ STOP ALL"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#C62828")); cornerRadius = dp(8).toFloat() }
            setOnClickListener { AiRuntime.emergencyStop("status window") }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val handle = TextView(ctx).apply {
            text = "◢"; textSize = 22f; setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(16), 0, 0, 0)
        }
        bottom.addView(handle)
        pn.addView(bottom)
        val plp = WindowManager.LayoutParams(
            dp(300), dp(280), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flagsTouch, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = ilp.x; y = ilp.y }
        dragToMove(header, plp, onTap = null, target = pn)
        var rw = 0; var rh = 0; var rtx = 0f; var rty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { rw = plp.width; rh = plp.height; rtx = e.rawX; rty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    plp.width = (rw + (e.rawX - rtx).toInt()).coerceIn(dp(200), ctx.resources.displayMetrics.widthPixels)
                    plp.height = (rh + (e.rawY - rty).toInt()).coerceIn(dp(150), ctx.resources.displayMetrics.heightPixels)
                    try { wm.updateViewLayout(pn, plp) } catch (ex: Exception) { }
                }
            }
            true
        }

        addOverlay(pn, plp)
        addOverlay(ic, ilp)
        icon = ic; iconLp = ilp; badge = b
        panel = pn; panelLp = plp; panelText = body
        refresh()
    }

    private fun dragToMove(v: View, lp: WindowManager.LayoutParams, onTap: (() -> Unit)?, target: View = v) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt()
                        try { wm.updateViewLayout(target, lp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    private fun expand() {
        val i = iconLp ?: return
        val p = panelLp ?: return
        p.x = i.x; p.y = i.y
        icon?.visibility = View.GONE
        panel?.visibility = View.VISIBLE
        try { wm.updateViewLayout(panel, p) } catch (e: Exception) { }
        refresh()
    }

    private fun collapse() {
        val i = iconLp ?: return
        val p = panelLp ?: return
        i.x = p.x; i.y = p.y
        panel?.visibility = View.GONE
        icon?.visibility = View.VISIBLE
        try { wm.updateViewLayout(icon, i) } catch (e: Exception) { }
    }

    private fun refresh() {
        val running = AiRuntime.anyOn()
        badge?.let {
            val err = AiRuntime.lastError.isNotEmpty() && !running
            it.text = when { running && Cfg.dryRun && Cfg.autonomous -> "DRY"; running -> "ON"; err -> "ERR"; else -> "OFF" }
            it.background = circle(Color.parseColor(if (running) "#2E7D32" else if (err) "#C62828" else "#616161"))
        }
        if (panel?.visibility == View.VISIBLE) panelText?.text = AiRuntime.panelText()
        pointer?.invalidate()
        // tell vision where our own windows are, so it ignores them
        val sw = ctx.resources.displayMetrics.widthPixels.toFloat()
        val sh = ctx.resources.displayMetrics.heightPixels.toFloat()
        val rects = ArrayList<FloatArray>()
        val visibleLp = if (panel?.visibility == View.VISIBLE) panelLp else iconLp
        val visibleView = if (panel?.visibility == View.VISIBLE) panel else icon
        if (visibleLp != null && visibleView != null) {
            val vw = if (visibleView.width > 0) visibleView.width else dp(70)
            val vh = if (visibleView.height > 0) visibleView.height else dp(70)
            rects.add(floatArrayOf(visibleLp.x / sw, visibleLp.y / sh, (visibleLp.x + vw) / sw, (visibleLp.y + vh) / sh))
        }
        ScreenMasks.overlay = rects
    }

    // ---------------- drawing layer ----------------

    private inner class PointerView(c: Context) : View(c) {
        private inner class Mark(val x: Float, val y: Float, val x2: Float, val y2: Float, val drag: Boolean, val t: Long)
        private val marks = ArrayList<Mark>()
        private var flashX = -1f
        private var flashY = -1f
        private var flashLabel = ""
        private var flashUntil = 0L
        private val life = 700L
        private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f }
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13f * density; setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        fun addTouch(x: Float, y: Float, x2: Float, y2: Float, drag: Boolean) {
            synchronized(marks) { marks.add(Mark(x, y, x2, y2, drag, System.currentTimeMillis())) }
            postInvalidate()
        }

        fun flash(x: Float, y: Float, label: String) {
            flashX = x; flashY = y; flashLabel = label
            flashUntil = System.currentTimeMillis() + 2500
            postInvalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val now = System.currentTimeMillis()

            // what the AI sees
            val v = FrameProcessor.latest
            if (Cfg.showDetections && AiRuntime.anyOn() && v != null && now - v.ts < 1000) {
                for (s in v.all) {
                    boxPaint.color = when (s.team) {
                        Team.SELF -> Color.WHITE
                        Team.ALLY -> Color.parseColor("#B0BEC5")
                        Team.ENEMY -> if (s.hero) Color.parseColor("#FFD600") else Color.parseColor("#FFAB00")
                    }
                    val r = RectF(s.barLeft * w, s.barTop * h, s.barRight * w, s.y * h + h * 0.04f)
                    c.drawRect(r, boxPaint)
                    if (s.hero) c.drawText("${(s.hp * 100).toInt()}%", r.left, r.top - 4f, textPaint)
                }
            }

            // calibration markers
            if (markersOn) {
                boxPaint.color = Color.parseColor("#FFD600")
                for (ctl in Layout.defaults) {
                    val (fx, fy) = Layout.get(ctl.key)
                    c.drawCircle(fx * w, fy * h, 16f * density, boxPaint)
                    c.drawText(ctl.label, fx * w + 18f * density, fy * h, textPaint)
                }
            }

            // marker for a dry-run tap
            if (now < flashUntil && flashX >= 0f) {
                boxPaint.color = Color.parseColor("#FFD600")
                c.drawCircle(flashX * w, flashY * h, 30f * density, boxPaint)
                c.drawText(flashLabel, flashX * w + 34f * density, flashY * h, textPaint)
            }

            // where the AI touched
            synchronized(marks) {
                marks.removeAll { now - it.t > life }
                for (m in marks) {
                    val a = (255 * (1f - (now - m.t).toFloat() / life)).toInt().coerceIn(0, 255)
                    touchPaint.color = Color.argb(a, 255, 170, 0)
                    if (m.drag) {
                        c.drawLine(m.x, m.y, m.x2, m.y2, touchPaint)
                        c.drawCircle(m.x2, m.y2, 18f * density, touchPaint)
                    }
                    c.drawCircle(m.x, m.y, 24f * density, touchPaint)
                }
                if (marks.isNotEmpty() || now < flashUntil) postInvalidateOnAnimation()
            }
        }
    }
}
