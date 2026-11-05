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
import android.widget.Toast
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.capture.FrameHub
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.vision.ButtonDetector
import com.example.mlbbresearch.vision.FrameProcessor
import com.example.mlbbresearch.vision.ScreenReader
import com.example.mlbbresearch.vision.ScreenMasks
import com.example.mlbbresearch.vision.Team
import kotlin.math.abs

// Draws on top of the game: where the AI touches, what it sees (boxes), calibration
// markers, and the floating icon that opens a resizable status window.
// Colours avoid red/green/blue so the AI's own vision never mistakes them for HP bars.
class OverlayManager(private val ctx: Context) {

    // CLEAN PICTURES (v36.53): the screen picture collector asks for a moment with nothing of the
    // app's on the game (boxes, touch dots, floating icon / window are screen-recorded too)
    companion object {
        @Volatile var cleanFrom = 0L; private set
        @Volatile var cleanUntil = 0L; private set
        @Volatile private var current: OverlayManager? = null
        private val main = Handler(Looper.getMainLooper())
        fun cleanNow(): Boolean = System.currentTimeMillis() in cleanFrom..cleanUntil
        fun requestClean(from: Long, until: Long) {
            cleanFrom = from; cleanUntil = until
            val now = System.currentTimeMillis()
            main.postDelayed({ current?.applyClean() }, (from - now).coerceAtLeast(0L))
            main.postDelayed({ current?.applyClean() }, (until - now).coerceAtLeast(0L) + 20L)
        }
    }
    init { current = this }

    private fun applyClean() {
        val a = if (cleanNow()) 0f else 1f
        icon?.alpha = a; panel?.alpha = a
        pointer?.invalidate()
    }

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
    // the status lines (v36.87): one single-line row per line - rows are never removed while the window
    // is open and a row changes only when its text does, so nothing jumps and scrolling stays smooth
    private var panelLines: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var power: TextView? = null
    private var panelPower: TextView? = null
    private var comboBtn: TextView? = null
    private var calib: FrameLayout? = null

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

    // the X on the icon hides it (the AI and screen capture keep running in the background);
    // it stays hidden until "Show the floating icon" on the app's Home page
    @Volatile var userHidden = false
        private set

    fun hideByUser() {
        hide()
        userHidden = true
        Diag.info("Floating icon hidden (the AI keeps running)")
        try {
            android.widget.Toast.makeText(ctx, "Floating icon hidden. The AI keeps running. Show it again on the app's Home page.",
                android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) { }
    }

    fun showByUser(): Boolean {
        userHidden = false
        return show()
    }

    fun hide() {
        ui.removeCallbacks(refresher)
        stopCalibration()
        for (v in listOf<View?>(icon, panel, pointer)) {
            if (v != null) try { wm.removeView(v) } catch (e: Exception) { }
        }
        icon = null; panel = null; pointer = null; badge = null; panelLines = null; power = null; panelPower = null; comboBtn = null
        ScreenMasks.overlay = emptyList()
    }

    fun showTouch(a: Act) {
        val pv = pointer ?: return
        val (rw, rh) = com.example.mlbbresearch.core.ScreenGeom.size(ctx)
        val w = rw.toFloat()
        val h = rh.toFloat()
        when (a) {
            is Act.Tap -> pv.addTouch(a.x * w, a.y * h, a.x * w, a.y * h, false)
            is Act.Drag -> pv.addTouch(a.x1 * w, a.y1 * h, a.x2 * w, a.y2 * h, true)
            is Act.SkillAim -> pv.addTouch(a.x1 * w, a.y1 * h, a.x2 * w, a.y2 * h, true)
            is Act.Joy -> pv.addTouch(a.cx * w, a.cy * h, a.x * w, a.y * h, true)
            Act.JoyRelease -> {}
        }
    }

    fun flashMarker(x: Float, y: Float, label: String) {
        if (pointer == null) show()
        pointer?.flash(x, y, label)
    }

    // ---------------- building ----------------

    private fun addOverlay(v: View, lp: WindowManager.LayoutParams) {
        // draw over the camera cut-out area too, like the game does
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
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

        // 2) round icon with an X (X = hide the icon, the AI keeps running; tap icon = open window;
        //    drag = move; ⏻ = AI on / off)
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
            background = circle(Color.parseColor("#5F6368")).apply { setStroke(dp(1), Color.WHITE) }
            setOnClickListener { hideByUser() }
        }
        ic.addView(close, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
        // power button: AI on / off without opening the app
        val pw = TextView(ctx).apply {
            text = "⏻"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setOnClickListener { toggleAi() }
        }
        ic.addView(pw, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END))
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
            setTextColor(Color.WHITE); textSize = 11.5f; setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "—"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(12), 0, dp(6), 0)
            setOnClickListener { collapse() }
        })
        pn.addView(header)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pn.addView(ScrollView(ctx).apply {
            isSmoothScrollingEnabled = true; isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true
            addView(body, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(ctx).apply {
            text = "■ STOP ALL"
            setTextColor(Color.WHITE); textSize = 10.5f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = GradientDrawable().apply { setColor(Color.parseColor("#C62828")); cornerRadius = dp(6).toFloat() }
            setOnClickListener { AiRuntime.emergencyStop("status window") }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // TEST COMBO: your hero's combo once, in bursts (disabled when Player is on - v37.01)
        val cBtn = TextView(ctx).apply {
            text = "⚔ COMBO"
            setTextColor(Color.WHITE); textSize = 10.5f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = GradientDrawable().apply { setColor(Color.parseColor("#3949AB")); cornerRadius = dp(6).toFloat() }
            setOnClickListener {
                if (AiRuntime.playerOn) {
                    android.widget.Toast.makeText(ctx, "Test combo is disabled while Player is on", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val msg = com.example.mlbbresearch.controller.ActionExecutor.testCombo()
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        comboBtn = cBtn
        bottom.addView(cBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        val roamBtn = TextView(ctx).apply {
            text = "🗺 ROAM"
            setTextColor(Color.WHITE); textSize = 10.5f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = GradientDrawable().apply { setColor(Color.parseColor("#00695C")); cornerRadius = dp(6).toFloat() }
            setOnClickListener {
                val msg = com.example.mlbbresearch.game.SceneryMap.toggleRoam()
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        bottom.addView(roamBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        val ppw = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 10.5f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setOnClickListener { toggleAi() }
        }
        bottom.addView(ppw, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        val handle = TextView(ctx).apply {
            text = "◢"; textSize = 16f; setTextColor(Color.parseColor("#9AA0A6"))
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
        icon = ic; iconLp = ilp; badge = b; power = pw; panelPower = ppw
        panel = pn; panelLp = plp; panelLines = body
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

    // fill the rows: one per line, single line each (… when too long); extra rows kept blank so the
    // height never shrinks under your finger; a row is touched only when its text changed
    private fun setLines(text: String) {
        val box = panelLines ?: return
        val lines = text.split('\n')
        while (box.childCount < lines.size) box.addView(TextView(box.context).apply {
            setTextColor(Color.WHITE); textSize = 11.5f; maxLines = 1; isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        for (i in 0 until box.childCount) {
            val tv = box.getChildAt(i) as TextView
            val t = lines.getOrNull(i) ?: ""
            if (tv.text.toString() != t) tv.text = t
        }
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
        val on = running
        power?.background = circle(Color.parseColor(if (on) "#43A047" else "#455A64")).apply { setStroke(dp(1), Color.WHITE) }
        panelPower?.text = if (on) "⏻ AI OFF" else "⏻ AI ON"
        panelPower?.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (on) "#455A64" else "#2E7D32")); cornerRadius = dp(8).toFloat()
        }
        // Disable Test combo button in the floating window whenever Player is on (v37.01)
        val comboAllowed = !AiRuntime.playerOn
        comboBtn?.isEnabled = comboAllowed
        comboBtn?.alpha = if (comboAllowed) 1f else 0.42f
        comboBtn?.text = if (comboAllowed) "⚔ COMBO" else "⚔ COMBO (off)"
        comboBtn?.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (comboAllowed) "#3949AB" else "#455A64")); cornerRadius = dp(6).toFloat()
        }
        if (panel?.visibility == View.VISIBLE) setLines(AiRuntime.panelText())
        pointer?.invalidate()
        // tell vision where our own windows are, so it ignores them
        val (rsw, rsh) = com.example.mlbbresearch.core.ScreenGeom.size(ctx)
        val sw = rsw.toFloat()
        val sh = rsh.toFloat()
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

    // Minimap icon calibration: a see-through layer over the game; tap an icon and its colour
    // is sampled from the next clean frame (the layer is gone by then). done(null) = no frame.
    fun pickColor(label: String, done: (IntArray?) -> Unit) {
        stopCalibration()
        if (icon == null) show()
        val root = FrameLayout(ctx)
        val hint = TextView(ctx).apply {
            text = "Tap: $label"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#E615171C")); cornerRadius = dp(8).toFloat() }
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) })
        root.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val (rw, rh) = com.example.mlbbresearch.core.ScreenGeom.size(ctx)
                val fx = e.rawX / rw
                val fy = e.rawY / rh
                stopCalibration()
                val at = System.currentTimeMillis()
                // wait for a frame without our layer, then average a 3x3 spot
                ui.postDelayed(object : Runnable {
                    var tries = 0
                    override fun run() {
                        if (FrameHub.lastFrameAt < at + 150 && tries++ < 20) { ui.postDelayed(this, 100); return }
                        val snap = FrameHub.snapshot(-1L, null)
                        if (snap == null) { done(null); return }
                        val cx = (fx * snap.width).toInt(); val cy = (fy * snap.height).toInt()
                        var r = 0; var g = 0; var b = 0; var n = 0
                        for (yy in cy - 1..cy + 1) for (xx in cx - 1..cx + 1) {
                            if (xx !in 0 until snap.width || yy !in 0 until snap.height) continue
                            val p = snap.pixels[yy * snap.width + xx]
                            r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
                        }
                        done(if (n == 0) null else intArrayOf(r / n, g / n, b / n))
                    }
                }, 200)
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            addOverlay(root, lp)
            calib = root
        } catch (e: Exception) {
            Diag.error("Pick layer failed", e)
            done(null)
        }
    }

    private fun toggleAi() {
        val msg = AiRuntime.toggleFromOverlay()
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        refresh()
    }

    // ---------------- calibration on the game ----------------
    // A full-screen layer over MLBB: drag each yellow marker onto its button, or tap
    // Auto-detect to let the app find them. Save writes them; Cancel throws them away.

    private val groups = listOf(
        "skills2" to "2-skill buttons",
        "skills3" to "3-skill buttons",
        "skills4" to "4-skill buttons",
        "game" to "In-game HUD (joystick / attack / spell)",
        "large" to "In-game buttons (large HUD)",
        "shop" to "Shop screen",
        "select" to "Hero select"
    )

    fun startCalibrationGroup(groupKey: String) {
        val idx = groups.indexOfFirst { it.first == groupKey }.takeIf { it >= 0 } ?: 0
        startCalibration(idx)
    }

    fun startCalibration(groupIndex: Int = 0) {
        stopCalibration()
        if (icon == null) show()
        val gi = groupIndex.coerceIn(0, groups.size - 1)
        val (group, groupLabel) = groups[gi]
        val controls = Layout.defaults.filter { it.group == group }
        val pos = LinkedHashMap<String, FloatArray>()
        for (c in controls) { val (x, y) = Layout.point(c.key); pos[c.key] = floatArrayOf(x, y) }

        val root = FrameLayout(ctx)
        val view = CalibView(ctx, controls, pos)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply { setColor(Color.parseColor("#E615171C")); cornerRadius = dp(10).toFloat() }
        }
        val info = TextView(ctx).apply {
            text = "$groupLabel: drag markers"
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(6), 0, dp(8), 0)
        }
        bar.addView(info)
        fun barBtn(t: String, color: String, onClick: () -> Unit) = bar.addView(TextView(ctx).apply {
            text = t
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(8).toFloat() }
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        barBtn("Auto-detect", "#1565C0") { autoDetect(root, view, pos, info) }
        barBtn("Next screen", "#455A64") {
            Layout.saveGroup(group, pos)
            startCalibration((gi + 1) % groups.size)
        }
        barBtn("Save $groupLabel", "#2E7D32") {
            Layout.saveGroup(group, pos)
            Toast.makeText(ctx, "Saved $groupLabel", Toast.LENGTH_SHORT).show()
            stopCalibration()
        }
        barBtn("Cancel", "#C62828") { stopCalibration() }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            addOverlay(root, lp)
            calib = root
        } catch (e: Exception) {
            Diag.error("Calibration layer failed", e)
            Toast.makeText(ctx, "Could not show the calibration layer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopCalibration() {
        val c = calib ?: return
        try { wm.removeView(c) } catch (e: Exception) { }
        calib = null
    }

    val calibrating: Boolean get() = calib != null

    // Hide our layer, wait for a clean frame of the game, find the buttons, show the result
    private fun autoDetect(root: View, view: CalibView, pos: MutableMap<String, FloatArray>, info: TextView) {
        root.visibility = View.INVISIBLE
        val wasMarkers = markersOn
        markersOn = false
        pointer?.invalidate()
        val hiddenAt = System.currentTimeMillis()
        info.text = "Looking..."
        fun finish(msg: String) {
            markersOn = wasMarkers
            root.visibility = View.VISIBLE
            info.text = msg.lineSequence().first()
            view.invalidate()
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                if (FrameHub.lastFrameAt < hiddenAt + 350) {
                    if (tries == 3) AccessibilityController.instance?.grabScreenshot()
                    if (tries > 30) { finish("No screen frame: start screen capture first (Home page)"); return }
                    ui.postDelayed(this, 150)
                    return
                }
                val snap = FrameHub.snapshot(-1L, null)
                if (snap == null) { finish("No screen frame yet"); return }
                Thread {
                    val r = try {
                        val hint = ScreenReader.classify(snap.pixels, snap.width, snap.height)
                        ButtonDetector.detect(snap.pixels, snap.width, snap.height, hint)
                    } catch (e: Exception) {
                        Diag.error("Auto-detect failed", e); null
                    }
                    ui.post {
                        if (r == null) { finish("Auto-detect failed (see Logs)"); return@post }
                        var n = 0
                        for ((k, p) in r.found) if (pos.containsKey(k)) { pos[k] = floatArrayOf(p.first, p.second); n++ }
                        Diag.info("Auto-detect: " + r.report.replace("\n", " | "))
                        finish(if (n == 0 && r.found.isNotEmpty()) "Found buttons of another screen. Use 'Next screen'.\n" + r.report
                               else "Moved $n markers. Check them, then Save.\n" + r.report)
                    }
                }.start()
            }
        }
        ui.postDelayed(poll, 300)
    }

    private inner class CalibView(c: Context, val controls: List<Layout.Control>, val pos: MutableMap<String, FloatArray>) : View(c) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#FFD600") }
        private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD600") }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 12f * density; setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private val shade = Paint().apply { color = Color.parseColor("#33000000") }
        private var dragging: String? = null
        private var offX = 0f
        private var offY = 0f

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            // positions are fractions of the WHOLE screen; this view may start beside the cut-out
            val (rw, rh) = com.example.mlbbresearch.core.ScreenGeom.size(context)
            val w = rw.toFloat()
            val h = rh.toFloat()
            val o = IntArray(2).also { getLocationOnScreen(it) }
            c.save()
            c.translate(-o[0].toFloat(), -o[1].toFloat())
            for (ctl in controls) {
                val p = pos[ctl.key] ?: continue
                val x = p[0] * w
                val y = p[1] * h
                c.drawCircle(x, y, 18f * density, if (ctl.key == dragging) active else ring)
                c.drawCircle(x, y, 3f * density, dot)
                c.drawText(ctl.label, x + 21f * density, y - 6f * density, label)
            }
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val (rw, rh) = com.example.mlbbresearch.core.ScreenGeom.size(context)
            val w = rw.toFloat().coerceAtLeast(1f)
            val h = rh.toFloat().coerceAtLeast(1f)
            val ex = e.rawX
            val ey = e.rawY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    var best: String? = null
                    var bestD = 48f * density
                    for ((k, p) in pos) {
                        val d = kotlin.math.hypot(p[0] * w - ex, p[1] * h - ey)
                        if (d < bestD) { bestD = d; best = k }
                    }
                    dragging = best
                    best?.let { val p = pos[it]!!; offX = p[0] * w - ex; offY = p[1] * h - ey }
                }
                MotionEvent.ACTION_MOVE -> dragging?.let {
                    pos[it] = floatArrayOf(((ex + offX) / w).coerceIn(0f, 1f), ((ey + offY) / h).coerceIn(0f, 1f))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
            }
            invalidate()
            return true
        }
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

        // where this view starts on the screen (beside the cut-out on some phones)
        fun screenOffset(): IntArray = IntArray(2).also { getLocationOnScreen(it) }

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
            // vision and calibration use fractions of the WHOLE screen (2712 x 1220 on a POCO X6 Pro,
            // camera cut-out included); draw in those coordinates, shifted by where this view starts
            val (rw, rh) = com.example.mlbbresearch.core.ScreenGeom.size(context)
            val w = rw.toFloat()
            val h = rh.toFloat()
            val now = System.currentTimeMillis()
            if (cleanNow()) return          // a clean picture is being taken: draw nothing
            val o = screenOffset()
            c.save()
            c.translate(-o[0].toFloat(), -o[1].toFloat())

            // what the AI sees
            val v = FrameProcessor.latest
            if (Cfg.showDetections && AiRuntime.anyOn() && v != null && now - v.ts < 1000) {
                for (s in v.all) {
                    boxPaint.color = when (s.team) {
                        Team.SELF -> Color.WHITE
                        Team.ALLY -> Color.parseColor("#B0BEC5")
                        Team.ENEMY -> if (s.hero) Color.parseColor("#FFD600") else Color.parseColor("#FFAB00")
                        Team.NEUTRAL -> Color.parseColor("#CE93D8")
                    }
                    // Screen capture also records this overlay, so nothing is drawn ON the HP bars
                    // (that hid your own bar from the AI): the box starts under the bar, the label
                    // sits under the box.
                    if (s.team == Team.SELF) continue
                    val r = RectF(s.barLeft * w - 4f, s.barBottom * h + 0.02f * h, s.barRight * w + 4f, s.y * h + h * 0.04f)
                    c.drawRect(r, boxPaint)
                    if (s.hero) c.drawText("${(s.hp * 100).toInt()}%", r.left, r.bottom + 14f * density, textPaint)
                }
            }

            // where the AI thinks you are on the minimap, and the jungle camps it can walk to
            val gs = com.example.mlbbresearch.game.GameStateTracker.state
            if (Cfg.showDetections && AiRuntime.anyOn() && now - gs.ts < 1000) {
                val (ml, mt) = Layout.get("mapTL")
                val (mr, mb) = Layout.get("mapBR")
                fun mx(f: Float) = (ml + f * (mr - ml)) * w
                fun my(f: Float) = (mt + f * (mb - mt)) * h
                boxPaint.color = Color.parseColor("#CE93D8")
                for (cp in gs.camps) c.drawCircle(mx(cp.x), my(cp.y), if (cp.buff) 7f * density else 4f * density, boxPaint)
                // enemy towers with their range (yellow = bonus tower)
                val rr = com.example.mlbbresearch.game.TowerLogic.range() * (mr - ml) * w
                for (tw in gs.towers) {
                    boxPaint.color = Color.parseColor(if (tw.bonus) "#FFD600" else "#FF7043")
                    c.drawCircle(mx(tw.x), my(tw.y), rr, boxPaint)
                }
                gs.minimapSelf?.let {
                    boxPaint.color = Color.WHITE
                    c.drawCircle(mx(it.first), my(it.second), 9f * density, boxPaint)
                }
            }

            // calibration markers
            if (markersOn) {
                boxPaint.color = Color.parseColor("#FFD600")
                for (ctl in Layout.defaults) {
                    val (fx, fy) = Layout.point(ctl.key)
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
            c.restore()
        }
    }
}
