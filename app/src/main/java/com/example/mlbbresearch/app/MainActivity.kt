package com.example.mlbbresearch.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.mlbbresearch.R
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.ai.ComboFetcher
import com.example.mlbbresearch.ai.ComboRunner
import com.example.mlbbresearch.ai.GemmaEngine
import com.example.mlbbresearch.ai.TacticalAdvisor
import com.example.mlbbresearch.ai.TacticalLlmEngine
import com.example.mlbbresearch.ai.StrategicAdvisor
import com.example.mlbbresearch.capture.FrameHub
import com.example.mlbbresearch.capture.ScreenCaptureService
import com.example.mlbbresearch.controller.AccessibilityController
import com.example.mlbbresearch.controller.ActionExecutor
import com.example.mlbbresearch.controller.QuickBuySwap
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.game.BuildBook
import com.example.mlbbresearch.game.ComboBook
import com.example.mlbbresearch.game.LaneLogic
import com.example.mlbbresearch.game.SpellDb
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.Role
import com.example.mlbbresearch.learning.Brain
import com.example.mlbbresearch.learning.DatasetManager
import com.example.mlbbresearch.learning.DemoRecorder
import com.example.mlbbresearch.learning.ImitationPolicy
import com.example.mlbbresearch.learning.ExperienceRecorder
import com.example.mlbbresearch.learning.PerformanceEvaluator
import com.example.mlbbresearch.models.ModelManager
import com.example.mlbbresearch.vision.FrameProcessor
import com.example.mlbbresearch.vision.ObjectDetector
import com.example.mlbbresearch.vision.IconMemory
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private val navButtons = LinkedHashMap<String, Button>()
    private val updaters = ArrayList<() -> Unit>()
    private val leaveHooks = ArrayList<() -> Unit>()
    private var defaultText: ColorStateList? = null
    private var page = "home"
    private var swipeBlocked = false
    private var pendingStage: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private var headerStatus: TextView? = null
    private var navScroll: HorizontalScrollView? = null

    private val pages = listOf("home", "player", "draft", "build", "meta", "model", "learn", "speed", "logs", "setup")
    private val labels = mapOf(
        "home" to "Home", "player" to "Player", "draft" to "Draft", "build" to "Build", "meta" to "Meta",
        "model" to "Model", "learn" to "Learn", "speed" to "Speed", "logs" to "Logs", "setup" to "Setup"
    )

    private val reqCapture = 42
    private val reqNotify = 43
    private val reqFolder = 46

    private val refresher = object : Runnable {
        override fun run() {
            headerStatus?.let { h ->
                val on = AiRuntime.anyOn()
                h.text = if (on) "● " + AiRuntime.statusLine() else "○ AI off"
                h.setTextColor(Color.parseColor(if (on) T.GREEN else T.MUTED))
            }
            for (u in updaters.toList()) u()
            ui.postDelayed(this, 500)
        }
    }

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(this)
        Diag.init(this)
        actionBar?.hide()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(T.BG))
        }

        // header with the app logo
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        header.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_mlbb) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        names.addView(TextView(this).apply {
            text = "MLBB Research"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        headerStatus = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor(T.MUTED)) }
        names.addView(headerStatus)
        header.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // the one emergency stop in the app (also: the floating icon and the notification)
        val stopTop = Button(this).apply {
            text = "■  STOP"
            isAllCaps = false
            textSize = 14f
            stateListAnimator = null
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = ripple(T.RED, 22)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { AiRuntime.emergencyStop("app header") }
        }
        header.addView(stopTop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))
        root.addView(header)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // page tabs (scroll sideways if they do not fit)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(10)) }
        for (k in pages) {
            val b = Button(this).apply {
                text = labels[k]
                textSize = 13f
                isAllCaps = false
                stateListAnimator = null
                minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { go(k) }
            }
            navButtons[k] = b
            nav.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) })
        }
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor(T.STROKE)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(nav); setBackgroundColor(Color.parseColor(T.SURF)) })
        navScroll = root.getChildAt(root.childCount - 1) as HorizontalScrollView

        // keep everything clear of the status bar and the phone's own Home/Back/Recent buttons
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
        defaultText = navButtons["home"]?.textColors

        show("home")
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
        val st = pendingStage
        if (st != null && AccessibilityController.instance != null) {
            pendingStage = null
            startStage(st)
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
        for (h in leaveHooks.toList()) h()
    }

    // rotation is handled here instead of restarting the app (see android:configChanges)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.requestApplyInsets()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            reqFolder -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                com.example.mlbbresearch.models.AutoFolder.onPicked(this, uri) { msg -> runOnUiThread { toast(msg.take(150)) } }
            }
            reqCapture -> {
                if (resultCode == RESULT_OK && data != null) {
                    val i = Intent(this, ScreenCaptureService::class.java)
                        .putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                        .putExtra(ScreenCaptureService.EXTRA_DATA, data)
                    startForegroundService(i)
                    toast("Screen capture started")
                } else {
                    toast("Screen capture not allowed - the AI will use the slower 1 fps fallback")
                }
            }
        }
    }

    // Swipe left / right anywhere to change pages (up / down still scrolls)
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeBlocked = onInputField(window.decorView, ev.rawX.toInt(), ev.rawY.toInt())
                downX = ev.rawX
                downY = ev.rawY
                downAt = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val ms = System.currentTimeMillis() - downAt
                if (!swipeBlocked && ms < 600 && abs(dx) > dp(90) && abs(dx) > abs(dy) * 2) {
                    val i = pages.indexOf(page)
                    val n = if (dx < 0) i + 1 else i - 1
                    if (n in pages.indices) {
                        // cancel the touch so the button under the finger is not pressed
                        super.dispatchTouchEvent(MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL })
                        show(pages[n], if (dx < 0) 1 else -1)
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // Switches and text boxes use sideways drags themselves, so they never change the page
    private fun onInputField(v: View, x: Int, y: Int): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (x < loc[0] || x > loc[0] + v.width || y < loc[1] || y > loc[1] + v.height) return false
        if (v is Switch || v is EditText || v is HorizontalScrollView) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (onInputField(v.getChildAt(i), x, y)) return true
        return false
    }

    // =====================================================================
    //  UI helpers
    // =====================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun upd(f: () -> Unit) { updaters.add(f); f() }

    // ---- look: one dark theme, used by every page ----
    private object T {
        const val BG = "#0B0E14"; const val SURF = "#151A24"; const val SURF2 = "#1E2533"; const val STROKE = "#283247"
        const val TEXT = "#E9ECF2"; const val MUTED = "#8D96AA"; const val ACCENT = "#6D8BFF"; const val GOLD = "#F4C25B"
        const val GREEN = "#3DD68C"; const val RED = "#FF5A6A"
    }
    private fun c(hex: String) = Color.parseColor(hex)
    private fun shape(fill: String, radiusDp: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(c(fill)); cornerRadius = dp(radiusDp).toFloat(); if (stroke != null) setStroke(dp(1), c(stroke))
    }
    private fun ripple(fill: String, radiusDp: Int, stroke: String? = null) =
        android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(c("#33FFFFFF")), shape(fill, radiusDp, stroke), shape("#FFFFFF", radiusDp))
    // older colour names used on the pages map onto the theme
    private fun themed(hex: String) = when (hex.uppercase()) {
        "#E6E6E6", "#C8C8C8" -> T.TEXT; "#9AA0A6" -> T.MUTED; "#8AB4F8" -> T.ACCENT
        "#EF9A9A", "#F28B82" -> T.RED; "#66BB6A" -> T.GREEN; else -> hex
    }

    private fun text(t: String, size: Float = 14f, bold: Boolean = false, color: String = T.TEXT): TextView {
        val v = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(c(themed(color)))
            if (bold) setTypeface(null, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(6), 0, dp(2))
        }
        content.addView(v)
        return v
    }

    // a section header: accent bar + small capitals
    private fun title(t: String): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(this).apply { background = shape(T.ACCENT, 2) }, LinearLayout.LayoutParams(dp(3), dp(14)))
        val v = TextView(this).apply {
            text = t.uppercase(); textSize = 12f; letterSpacing = 0.08f
            setTextColor(c(T.MUTED)); setTypeface(null, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
        }
        row.addView(v)
        content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22); bottomMargin = dp(4)
        })
        return v
    }

    private fun styleButton(b: Button, primary: Boolean = false) {
        b.isAllCaps = false
        b.textSize = 14f
        b.stateListAnimator = null
        b.minHeight = dp(46)
        b.setTextColor(c(if (primary) "#FFFFFF" else T.TEXT))
        b.background = if (primary) ripple(T.ACCENT, 12) else ripple(T.SURF2, 12, T.STROKE)
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { text = t; setOnClickListener { onClick() } }
        styleButton(b)
        content.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        if (defaultText == null) defaultText = b.textColors
        return b
    }

    private fun switch(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(c(T.TEXT))
            isChecked = initial
            thumbTintList = ColorStateList(states, intArrayOf(c("#FFFFFF"), c("#AEB6C6")))
            trackTintList = ColorStateList(states, intArrayOf(c(T.ACCENT), c("#3A4357")))
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        content.addView(sw)
        return sw
    }

    private fun field(hint: String, value: String, numeric: Boolean, save: (String) -> Unit): EditText {
        text(hint, 12f, color = T.MUTED)
        val e = EditText(this).apply {
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            setText(value)
            setTextColor(c(T.TEXT))
            setHintTextColor(c(T.MUTED))
            background = shape(T.SURF2, 10, T.STROKE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        leaveHooks.add { save(e.text.toString().trim()) }
        return e
    }

    private fun card(): LinearLayout {
        val cd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = shape(T.SURF, 16, T.STROKE)
        }
        content.addView(cd, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        return cd
    }

    private fun cardText(cd: LinearLayout, size: Float = 14f, bold: Boolean = false): TextView {
        val v = TextView(this).apply {
            textSize = size
            setTextColor(c(T.TEXT))
            if (bold) setTypeface(null, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(3), 0, dp(3))
        }
        cd.addView(v)
        return v
    }

    private fun mark(b: Button, on: Boolean, color: String = T.GREEN) {
        if (on) {
            b.background = ripple(color, 12)
            b.setTextColor(Color.WHITE)
        } else {
            b.background = ripple(T.SURF2, 12, T.STROKE)
            b.setTextColor(c(T.TEXT))
        }
    }

    // ---- small building blocks for the richer pages ----

    // a small rounded label
    private fun chip(t: String, color: String = T.ACCENT): TextView = TextView(this).apply {
        text = t; textSize = 11f; setTextColor(c(color)); setTypeface(null, Typeface.BOLD)
        background = shape("#1A" + color.removePrefix("#"), 20, null)
        setPadding(dp(9), dp(3), dp(9), dp(3))
    }

    // a heading inside a card (with an optional chip on the right)
    private fun cardHeader(cd: LinearLayout, t: String, chipText: String? = null, chipColor: String = T.ACCENT): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tv = TextView(this).apply { text = t; textSize = 15f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD) }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (chipText != null) row.addView(chip(chipText, chipColor))
        cd.addView(row)
        return tv
    }

    // A wrapping grid of hero (round) or item (rounded square) pictures with their names.
    // set() rebuilds it only when the list changed (pages refresh twice a second).
    private inner class IconGrid(val parent: LinearLayout, val hero: Boolean, val sizeDp: Int = 46) {
        private var shown: List<String>? = null
        private val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        init { parent.addView(box) }
        fun set(names: List<String>, captions: List<String>? = null) {
            if (names == shown) return
            shown = names
            box.removeAllViews()
            if (names.isEmpty()) return
            val cellW = dp(sizeDp + 22)
            val avail = (resources.displayMetrics.widthPixels - dp(64)).coerceAtLeast(cellW)
            val perRow = (avail / cellW).coerceIn(1, 8)
            var row: LinearLayout? = null
            names.forEachIndexed { i, n ->
                if (i % perRow == 0) {
                    row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    box.addView(row)
                }
                val cell = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
                val px = dp(sizeDp)
                val iv = com.example.mlbbresearch.app.IconCache.view(this@MainActivity, n, hero, px)
                cell.addView(iv, LinearLayout.LayoutParams(px, px))
                cell.addView(TextView(this@MainActivity).apply {
                    text = captions?.getOrNull(i) ?: n; textSize = 10f; setTextColor(c(T.MUTED)); gravity = Gravity.CENTER
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(3), dp(2), 0)
                }, LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT))
                row!!.addView(cell, LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    // a small muted line inside a card
    private fun cardNote(cd: LinearLayout, t: String = ""): TextView = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(c(T.MUTED)); setLineSpacing(0f, 1.1f); setPadding(0, dp(4), 0, dp(2))
        cd.addView(this)
    }

    private fun go(k: String) {
        val d = pages.indexOf(k) - pages.indexOf(page)
        show(k, if (d > 0) 1 else if (d < 0) -1 else 0)
    }

    private fun show(p: String, dir: Int = 0) {
        for (h in leaveHooks.toList()) h()
        leaveHooks.clear()
        updaters.clear()
        content.removeAllViews()
        page = p
        when (p) {
            "home" -> homePage()
            "player" -> playerPage()
            "draft" -> draftPage()
            "build" -> buildPage()
            "meta" -> metaPage()
            "model" -> modelPage()
            "learn" -> learnPage()
            "speed" -> speedPage()
            "logs" -> logsPage()
            "setup" -> setupPage()
        }
        for ((k, b) in navButtons) {
            val on = k == p
            b.background = if (on) ripple(T.ACCENT, 19) else ripple(T.SURF2, 19)
            b.setTextColor(Color.parseColor(if (on) "#FFFFFF" else T.MUTED))
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            if (on) b.post { navScroll?.smoothScrollTo((b.left - dp(40)).coerceAtLeast(0), 0) }
        }
        scroll.scrollTo(0, 0)
        if (dir != 0) {
            content.translationX = dir * dp(60).toFloat()
            content.alpha = 0.3f
            content.animate().translationX(0f).alpha(1f).setDuration(180).start()
        }
    }

    // =====================================================================
    //  Starting things (jumps to whatever is missing)
    // =====================================================================

    private fun startStage(stage: String) {
        if (AccessibilityController.instance == null) {
            pendingStage = stage
            toast("Turn ON 'MLBB Research' in Accessibility, then come back")
            openAccessibility()
            return
        }
        if (AiRuntime.needsGemma(stage) && !GemmaEngine.ready) {
            toast("Load the Gemma model first")
            show("model")
            return
        }
        val err = AiRuntime.start(stage)
        if (err != null) { toast(err); return }
        if (!ScreenCaptureService.running) askScreenCapture()
        val ov = AccessibilityController.instance?.overlay
        if (ov != null && !ov.shown && !Settings.canDrawOverlays(this)) {
            toast("Allow 'Display over other apps' so the floating icon can show")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun askScreenCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotify)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    // A switch that mirrors a running stage (and turns it on/off)
    private fun stageSwitch(label: String, stage: String, isOn: () -> Boolean): Switch {
        var syncing = false
        val sw = switch(label, isOn()) { on ->
            if (!syncing) {
                if (on) startStage(stage) else AiRuntime.stop(stage)
            }
        }
        upd {
            if (sw.isChecked != isOn()) {
                syncing = true
                sw.isChecked = isOn()
                syncing = false
            }
        }
        return sw
    }

    // Several switches where exactly one is on (e.g. the lanes)
    private fun radioSwitches(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        var syncing = false
        val all = ArrayList<Switch>()
        for ((key, label) in options) {
            lateinit var sw: Switch
            sw = switch(label, key == current) { on ->
                if (!syncing) {
                    syncing = true
                    if (on) {
                        onPick(key)
                        for (o in all) if (o !== sw) o.isChecked = false
                    } else sw.isChecked = true
                    syncing = false
                }
            }
            all.add(sw)
        }
    }

    // Two switches where exactly one is on (e.g. Standard / Ranked)
    private fun exclusivePair(a: String, b: String, aOn: Boolean, onChange: (Boolean) -> Unit) {
        var syncing = false
        lateinit var swA: Switch
        lateinit var swB: Switch
        swA = switch(a, aOn) { on ->
            if (!syncing) {
                syncing = true
                if (on) { onChange(true); swB.isChecked = false } else swA.isChecked = true
                syncing = false
            }
        }
        swB = switch(b, !aOn) { on ->
            if (!syncing) {
                syncing = true
                if (on) { onChange(false); swA.isChecked = false } else swB.isChecked = true
                syncing = false
            }
        }
    }

    // =====================================================================
    //  HOME: dashboard
    // =====================================================================

    private fun homePage() {
        val c1 = card()
        val status = cardHeader(c1, "", "", T.GREEN)
        val sChip = (status.parent as LinearLayout).getChildAt(1) as TextView
        val gemma = cardNote(c1)
        val capture = cardNote(c1)
        upd {
            val on = AiRuntime.anyOn()
            status.text = if (on) AiRuntime.statusLine() else "AI is off"
            sChip.text = if (on) "running" else "stopped"
            sChip.setTextColor(c(if (on) T.GREEN else T.MUTED))
            gemma.text = "Strategy: " + ModelManager.strategicName() + " – " + GemmaEngine.state +
                (if (GemmaEngine.calls > 0) " (${GemmaEngine.lastLatencyMs / 100 / 10.0} s)" else "") +
                (if (Cfg.tacticalLlmOn) "\nFights: " + ModelManager.tacticalName() + " – " + TacticalLlmEngine.state +
                    (if (TacticalLlmEngine.calls > 0) " (${TacticalLlmEngine.lastLatencyMs} ms)" else "") else "")
            capture.text = "Screen: " + (if (FrameHub.isLive()) FrameHub.source else "not captured") +
                " · ${"%.0f".format(FrameProcessor.processedFps)} fps · frame→touch ${"%.0f".format(com.example.mlbbresearch.core.Speed.total())} ms"
        }
        val capBtn = btn("") {
            if (ScreenCaptureService.running) {
                startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
            } else askScreenCapture()
        }
        upd {
            capBtn.text = if (ScreenCaptureService.running) "Stop screen capture" else "Start screen capture"
            styleButton(capBtn, primary = !ScreenCaptureService.running)
        }

        title("RUN")
        stageSwitch("Player (plays the hero)", "player") { AiRuntime.playerOn }
        stageSwitch("Drafter (ban & pick)", "draft") { AiRuntime.drafterOn }
        stageSwitch("Builder (items)", "build") { AiRuntime.builderOn }
        switch("Autonomous Drafter + Builder", Cfg.autonomous) { Cfg.autonomous = it }
        switch("Dry run: only show where it would tap", Cfg.dryRun) { Cfg.dryRun = it }
        val mode = text("", 12f, color = T.MUTED)
        upd { mode.text = "Match: ${if (Cfg.ranked) "Ranked" else "Standard"} (Draft page) · ⏻ on the floating icon toggles the AI in game" }

        title("WHAT THE AI SEES")
        val preview = PreviewView(this)
        content.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        upd { preview.refresh() }

        val c2 = card()
        val meRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        c2.addView(meRow)
        var meIconFor: String? = "?"
        val decision = cardText(c2, 14f, true)
        val detected = cardNote(c2)
        val target = cardNote(c2)
        upd {
            val h = GameStateTracker.ownHero()
            if (h != meIconFor) {
                meIconFor = h
                meRow.removeAllViews()
                meRow.addView(com.example.mlbbresearch.app.IconCache.view(this, h ?: "?", true, dp(44)), LinearLayout.LayoutParams(dp(44), dp(44)))
                meRow.addView(TextView(this).apply { text = h ?: "Hero not known yet"; textSize = 17f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(12), 0, 0, 0) })
            }
        }
        upd {
            val s = GameStateTracker.state
            val hero = GameStateTracker.ownHero()
            detected.text = buildString {
                if (s.selfHp >= 0) append("HP ").append((s.selfHp * 100).toInt()).append("%  ·  ")
                if (s.self != null) append("Level ").append(s.level).append(if (s.enemyLevel > 0) " (enemy ${s.enemyLevel})" else "").append("  ·  ")
                append(s.lane.label).append("  ·  Spell ").append(s.spell ?: "?")
                if (s.monsters.isNotEmpty()) append("   Monsters: ").append(s.monsters.size)
                append("\nPhase: ").append(s.phase.label)
                if (s.hud.upgradeReady.isNotEmpty()) append("   (can upgrade ").append(s.hud.upgradeReady.joinToString()).append(")")
                if (s.hud.quickBuyVisible) append("   (quick-buy item showing)")
                append("\nEnemy heroes: ")
                append(if (s.enemies.isEmpty()) "none visible" else s.enemies.joinToString { "#${it.id} ${(it.hp * 100).toInt()}%" })
                append("\nEnemy minions: ").append(s.enemyMinions.size).append("   Allies: ").append(s.allies.size)
                append("   On minimap: ").append(s.minimapEnemies)
                if (s.enemyTeam.isNotEmpty()) append("\nEnemy team: ").append(s.enemyTeam.joinToString())
            }
            decision.text = "▶ " + ActionExecutor.lastDecision
            target.text = "Target: " + ActionExecutor.lastTarget +
                "  ·  Plan: ${StrategicAdvisor.plan.plan} (aggression ${"%.1f".format(StrategicAdvisor.plan.aggression)})"
        }

        title("Recent actions")
        val hc = card()
        val hist = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(c(T.TEXT)); setLineSpacing(0f, 1.15f) }
        hc.addView(hist)
        upd {
            val h = ActionExecutor.history(12)
            hist.text = if (h.isEmpty()) "(nothing yet)" else h.joinToString("\n")
        }

        val learn = text("", 12f, color = T.MUTED)
        upd { learn.text = "Learning: ${ExperienceRecorder.count} examples (${PerformanceEvaluator.evaluated} scored this session)" }


        // the X on the floating icon only hides it; bring it back here
        val iconStatus = text("", 12f, color = "#9AA0A6")
        upd {
            val o = AccessibilityController.instance?.overlay
            iconStatus.text = when {
                o == null -> "Floating icon: needs accessibility"
                o.shown -> "Floating icon: showing"
                o.userHidden -> "Floating icon: hidden (AI still runs)"
                else -> "Floating icon: shows when the AI starts"
            }
        }
        btn("Show the floating icon") {
            val o = AccessibilityController.instance?.overlay
            if (o == null) toast("Turn on the accessibility service first")
            else if (o.showByUser()) toast("Floating icon is back") else toast("Allow 'Display over other apps' so the floating icon can show")
        }
    }

    // =====================================================================
    //  PLAYER
    // =====================================================================

    private fun playerPage() {
        stageSwitch("Player running", "player") { AiRuntime.playerOn }
        val info = text("", 13f)
        upd {
            info.text = if (GemmaEngine.ready) "Fast rules play; Gemma adjusts the plan every ${Cfg.strategyEverySec}s or when a fight starts."
                        else "Gemma not loaded: the Player still works with the fast rules only."
        }


        title("YOUR HERO")
        text("Your hero is recognised from the minimap portrait, the pick screen, or the name below.", 12f, color = "#9AA0A6")
        val heroBox = field("Hero name (empty = detect)", Cfg.myHero, false) { Cfg.myHero = it }
        val heroInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            val p = HeroDb.profile(h)
            heroInfo.text = "Using: ${h ?: "unknown"} (${GameStateTracker.ownHeroSource()})\n" +
                "Role ${p.role.name.lowercase()}, ultimate: ${p.ult.name.lowercase()}, retreat below ${(p.retreatHp * 100).toInt()}% HP\n" +
                "Learned aggression adjust: ${"%+.2f".format(Cfg.learnedBias(h ?: "unknown"))}\n" +
                "Heroes whose icons are learned: ${IconMemory.heroCount()}"
        }
        btn("This is my hero now (learn its look)") {
            val h = HeroDb.match(heroBox.text.toString())
            when {
                h == null -> toast("Type the hero's name first")
                GameStateTracker.teachHero(h) -> toast("Learned how $h looks")
                else -> toast("No game frame yet: start the Player or Learn from me during a match, then try again")
            }
        }

        title("BATTLE SPELL")
        val spells = listOf("auto") + SpellDb.names
        val spellBtn = btn("") {
            val i = spells.indexOf(Cfg.mySpell).coerceAtLeast(0)
            Cfg.mySpell = spells[(i + 1) % spells.size]
        }
        val spellInfo = text("", 13f)
        upd {
            spellBtn.text = "My battle spell: ${if (Cfg.mySpell == "auto") "detect it" else Cfg.mySpell}  (tap to change)"
            spellInfo.text = "Detected: ${GameStateTracker.spell() ?: "unknown"} (${GameStateTracker.spellSource()}). " +
                "Spells whose icon is learned: ${IconMemory.spellCount()}"
        }
        btn("This is my spell now (learn its icon)") {
            val sp = SpellDb.match(Cfg.mySpell)
            when {
                sp == null -> toast("Choose the spell with the button above first")
                GameStateTracker.teachSpell(sp) -> toast("Learned the $sp icon")
                else -> toast("No game frame yet: start the Player during a match, then try again")
            }
        }

        title("LANE (only one can be on)")
        radioSwitches(listOf(
            "auto" to "Auto (Retribution = jungle, else from the hero's role)",
            "exp" to "EXP lane (top)", "gold" to "Gold lane (bottom)", "mid" to "Mid lane",
            "roam" to "Roam (stay with the team)", "jungle" to "Jungle"
        ), Cfg.lane) { Cfg.lane = it }
        val jungleInfo = text("", 13f)
        upd {
            val st = GameStateTracker.state
            jungleInfo.text = "Minimap: you ${if (st.minimapSelf != null) "found" else "not found"}, " +
                "${st.camps.size} of your camps up (${st.camps.count { it.buff }} buffs)\nJungle path: ${com.example.mlbbresearch.game.JungleNav.status}"
        }
        val laneInfo = text("", 13f)
        upd {
            val (lane, why) = GameStateTracker.lane()
            laneInfo.text = "Playing: ${lane.label} ($why)"
        }
        field("Seconds from base to your lane", Cfg.laneTravelSec.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.laneTravelSec = v.coerceIn(0, 60) }
        }

        title("SKILL COMBOS")
        val comboInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            val (combo, from) = ComboBook.text(h)
            comboInfo.text = "Combo for ${h ?: "unknown hero"}: $combo ($from)\nNow: ${ComboRunner.status}"
        }
        text("Format: s1 > s2 > a > ult (a = basic attack). Pause in ms: s2(500) > s1.", 12f, color = "#9AA0A6")
        val comboBox = EditText(this).apply {
            setText(ComboBook.text(GameStateTracker.ownHero()).first)
            setTextColor(Color.WHITE)
        }
        content.addView(comboBox)
        fun heroForCombo(): String? = GameStateTracker.ownHero() ?: HeroDb.match(heroBox.text.toString())
        btn("Save this combo for my hero") {
            val h = heroForCombo()
            val steps = ComboBook.parse(comboBox.text.toString())
            when {
                h == null -> toast("Your hero is not known yet: type its name above")
                steps.size < 2 -> toast("Write at least 2 steps, like s1 > s2 > ult")
                else -> { Cfg.setHeroCombo(h, ComboBook.format(steps)); toast("Saved for $h") }
            }
        }
        btn("Back to the built-in combo") {
            heroForCombo()?.let { Cfg.setHeroCombo(it, null); comboBox.setText(ComboBook.text(it).first); toast("Reset") }
        }
        text("Downloads a guide's combo (or Gemma builds one from the skills). Online only when you tap.", 12f, color = "#9AA0A6")
        field("Guide address template ({hero} = hero name)", Cfg.comboUrl, false) { if (it.contains("http")) Cfg.comboUrl = it }
        val pageBox = field("Guide page address (optional)", "", false) { }
        val fetchBtn = btn("Get my hero's combo online") {
            val h = heroForCombo()
            if (h == null) toast("Your hero is not known yet: type its name above") else {
                toast("Downloading...")
                ComboFetcher.fetch(h, pageBox.text.toString().trim()) { msg ->
                    runOnUiThread { toast(msg); comboBox.setText(ComboBook.text(h).first) }
                }
            }
        }
        upd { fetchBtn.isEnabled = !ComboFetcher.busy }
        val pasteBox = EditText(this).apply {
            hint = "...or paste guide text here"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            minLines = 2
        }
        content.addView(pasteBox)
        btn("Find the combo in the pasted text") {
            val h = heroForCombo()
            if (h == null) toast("Your hero is not known yet: type its name above") else {
                ComboFetcher.fromPaste(h, pasteBox.text.toString()) { msg ->
                    runOnUiThread { toast(msg); comboBox.setText(ComboBook.text(h).first) }
                }
            }
        }

        title("JUNGLE, TURTLE & LORD")
        val monInfo = text("", 13f)
        upd { monInfo.text = "Only the Lord shows its red HP bar before it is attacked. Every other monster (Turtle, buffs, " +
            "small camps) shows its bar only after it takes damage - from you, your teammates or the enemies. So: a monster " +
            "WITH a bar is found by that bar (no level badge next to it - enemy heroes have one) and named from its size, " +
            "colour and pit; at a camp or the Turtle pit with no bar yet, the AI starts with a basic attack so the bar " +
            "appears, then uses its skills. Monsters nobody has hit are only seen by the trained screen detector (if loaded)." }
        title("HERO LEVEL")
        text("Level is read from the badge by your HP bar. Before 4: farm. At 4, 8, 12 or ahead: fight more. 2+ behind: avoid even fights.", 13f)
        val lvInfo = text("", 13f)
        upd { lvInfo.text = "Badges remembered: ${com.example.mlbbresearch.vision.LevelReader.templatesKnown} of 15 (levels 1, 2, 9 and 12 are built in)" }
        btn("Forget the remembered level badges") { com.example.mlbbresearch.vision.LevelReader.forgetTemplates(); toast("Forgotten") }
        title("TURTLE & LORD")
        field("Retribution on turtle / lord below HP (%)", Cfg.retriBossPct.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.retriBossPct = v.coerceIn(1, 60) }
        }
        field("Retribution on jungle monsters below HP (%)", Cfg.retriCreepPct.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.retriCreepPct = v.coerceIn(1, 90) }
        }
        val objInfo = text("", 13f)
        upd {
            val st = GameStateTracker.state
            val b = st.boss
            objInfo.text = "Monsters on screen: ${st.monsters.size}" +
                (if (b != null) " | ${LaneLogic.objectiveName(st.matchSec)} HP ${(b.hp * 100).toInt()}%" else "") +
                (if (st.matchSec >= 0) " | match time ${st.matchSec / 60}:${"%02d".format(st.matchSec % 60)}" else "")
        }

        title("BEHAVIOUR")
        val dirs = listOf("up-right", "right", "down-right", "down", "down-left", "left", "up-left", "up")
        val dirBtn = btn("") {
            val i = dirs.indexOf(Cfg.pushDir)
            Cfg.pushDir = dirs[(i + 1) % dirs.size]
        }
        upd { dirBtn.text = "Push towards enemy base: ${Cfg.pushDir}  (tap to change)" }
        val moveInfo = text("", 13f)
        upd {
            val gs = GameStateTracker.state
            val wm = com.example.mlbbresearch.vision.WallMap
            moveInfo.text = "Wall map: ${if (wm.ready) "ready" else "building (needs a few seconds in a match)"}\n" +
                (if (wm.ready) "Minimap terrain (40x40 cells): walls ${wm.count(com.example.mlbbresearch.vision.Terrain.WALL)}, " +
                    "bushes ${wm.count(com.example.mlbbresearch.vision.Terrain.BUSH)}, lane paths ${wm.count(com.example.mlbbresearch.vision.Terrain.LANE)}, " +
                    "jungle ${wm.count(com.example.mlbbresearch.vision.Terrain.JUNGLE)}, river ${wm.count(com.example.mlbbresearch.vision.Terrain.RIVER)}\n" else "") +
                "K/D/A seen this match: ${GameStateTracker.kills} / ${GameStateTracker.deaths} / ${GameStateTracker.assists}" +
                "  (jungle camps killed: ${com.example.mlbbresearch.game.JungleNav.campsKilled})\n" +
                "Monsters on screen: ${gs.monsters.size}   Bushes on screen: ${gs.bushes.size}" + (if (gs.inBush) "   (you are in a bush)" else "") +
                "\nVision: ${"%.0f".format(FrameProcessor.processedFps)} frames/s\nSpeed: ${com.example.mlbbresearch.core.Speed.text()}" +
                "\n" + com.example.mlbbresearch.game.GameMemory.text(System.currentTimeMillis()) +
                "\n" + com.example.mlbbresearch.ai.Deliberation.text(gs.lane)
        }

        title("SKILLS & ITEMS")
        val upInfo = text("", 12f, color = "#9AA0A6")
        upd {
            val h = GameStateTracker.ownHero()
            upInfo.text = "Skill upgrades are automatic: " + com.example.mlbbresearch.game.SkillOrder.of(h).joinToString(" > ") +
                " for ${h ?: "your hero"} (${com.example.mlbbresearch.game.SkillOrder.source(h)})"
        }
        switch("Buy items (quick buy)", Cfg.playerQuickBuy) { Cfg.playerQuickBuy = it }

        title("KILLS, TOWERS & GOLD")
        text("Priority: low-HP enemies, then camps and minions, then towers (only with your minions). Defend your towers; destroy the base to win.", 12f, color = "#9AA0A6")
        field("Chase and finish enemy heroes below HP (%)", Cfg.executeHpPct.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.executeHpPct = v.coerceIn(5, 60) }
        }
        field("Enemy tower range (% of the minimap width)", Cfg.towerRangePct.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.towerRangePct = v.coerceIn(3, 20) }
        }
        val rangeBtn = btn("") {
            val h = GameStateTracker.ownHero() ?: HeroDb.match(heroBox.text.toString())
            if (h == null) toast("Your hero is not known yet") else {
                val opts = listOf("auto", "melee", "ranged")
                Cfg.setHeroRange(h, opts[(opts.indexOf(Cfg.heroRange(h)) + 1) % opts.size])
            }
        }
        upd {
            val h = GameStateTracker.ownHero()
            rangeBtn.text = "Range type of ${h ?: "my hero"}: ${if (HeroDb.isRanged(h)) "RANGED (pokes heroes, keeps distance)" else "MELEE (fights heroes up close)"} " +
                "(${HeroDb.rangeSource(h)}) - tap to change"
        }
        val dashBtn = btn("") {
            val h = GameStateTracker.ownHero() ?: HeroDb.match(heroBox.text.toString())
            if (h == null) toast("Your hero is not known yet") else {
                val opts = listOf("auto", "s1", "s2", "ult", "none")
                Cfg.setHeroDash(h, opts[(opts.indexOf(Cfg.heroDash(h)) + 1) % opts.size])
            }
        }
        val towerInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            dashBtn.text = "Dash skill for ${h ?: "my hero"}: ${HeroDb.dashSlot(h) ?: "none"} (${HeroDb.dashSource(h)}) - tap to change"
            val st = GameStateTracker.state
            val n = com.example.mlbbresearch.game.TowerLogic.nearest(st)
            towerInfo.text = "Minimap: ${st.towers.size} enemy towers (${st.towers.count { it.bonus }} bonus), " +
                "${st.allyTowers.size} of yours, enemy base ${if (st.enemyBase != null) "seen" else "not seen"}\n" +
                (if (n == null) "Your position on the minimap is not known" else
                    "Nearest enemy tower: ${(n.second * 100).toInt()}% of the map away" +
                        (if (n.second < com.example.mlbbresearch.game.TowerLogic.range()) " - INSIDE its range" else "") +
                        (if (com.example.mlbbresearch.game.TowerLogic.canPush(st)) " - safe to push" else ""))
        }

        title("REGEN & RECALL")
        field("Regen below HP (%)", Cfg.regenHpPct.toString(), true) { it.toIntOrNull()?.let { v -> Cfg.regenHpPct = v.coerceIn(10, 95) } }
        field("Recall below HP (%)", Cfg.recallHpPct.toString(), true) { it.toIntOrNull()?.let { v -> Cfg.recallHpPct = v.coerceIn(5, 80) } }
        text("Regen and Recall stand still; a nearby enemy cancels them.", 12f, color = "#9AA0A6")
        text("Buys only when no enemy is close. Calibrate the buttons on Setup.", 12f, color = "#9AA0A6")

        title("COACH: HELP THE AI, IT LEARNS FROM YOU")
        text("Take over any time: while your finger is on the screen the AI waits, then resumes 1.2 s after you let go. It learns your combos, aim and joystick.", 13f)
        switch("Coach mode (you can step in; it learns)", Cfg.coachMode) { on ->
            Cfg.coachMode = on
            if (on) ImitationPolicy.ensureLoaded(GameStateTracker.ownHero())
        }
        val coachInfo = text("", 13f)
        upd {
            coachInfo.text = (if (AiRuntime.learnOn) "Recording you: ON" + (if (AiRuntime.playerOn) " (while you help the AI)" else " (Player off: everything you do is recorded)")
                else "Recording you: starts with screen capture or the Player") +
                "\nLearned: " + com.example.mlbbresearch.learning.Coach.summary(GameStateTracker.ownHero())
        }
        btn("Forget what Coach learned for this hero") {
            com.example.mlbbresearch.learning.Coach.forget(GameStateTracker.ownHero()); toast("Forgotten")
        }

        title("Game knowledge")
        val know = text("", 12f)
        upd {
            val st = GameStateTracker.state
            val hero = GameStateTracker.ownHero()
            know.text = "Your hero ($hero) in the wiki's laning lists: ${com.example.mlbbresearch.game.WikiLanes.describe(hero)}\n" +
                "Timeline: " + com.example.mlbbresearch.game.MatchEvents.ALL.joinToString("; ") { "%d:%02d %s".format(it.sec / 60, it.sec % 60, it.what) } + "\n" +
                "Role tip now (${st.lane.label}): " + com.example.mlbbresearch.game.RoleTips.of(st.lane, st.matchSec.coerceAtLeast(0), st.ownHero) + "\n" +
                (st.spell?.let { sp -> "Battle spell $sp: ${com.example.mlbbresearch.game.SpellInfo.effect[sp] ?: "-"} (cooldown ${com.example.mlbbresearch.game.SpellInfo.cooldown[sp]?.toInt() ?: "?"} s)\n" } ?: "") +
                "Jungle:\n" + com.example.mlbbresearch.game.JungleDb.describe()
        }

        // ---- how to play the hero you are on (worked out from its skills + the online guide, and used in play) ----
        title("How to play your hero")
        val pc = card()
        val pHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pc.addView(pHead)
        val pPlan = cardText(pc, 13f)
        val pTips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(pTips)
        val pSrc = cardNote(pc)
        var pKey = ""
        upd {
            val h = GameStateTracker.ownHero()
            val pb = com.example.mlbbresearch.game.HeroPlaybook.of(h)
            val key = "$h|${pb.combo}|${pb.tips.size}|${pb.source}"
            if (key == pKey) return@upd
            pKey = key
            pHead.removeAllViews()
            pHead.addView(com.example.mlbbresearch.app.IconCache.view(this, h ?: "?", true, dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
            val nm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            nm.addView(TextView(this).apply { text = h ?: "No hero yet"; textSize = 17f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
            nm.addView(chip(pb.style, if (pb.learned) T.ACCENT else T.MUTED))
            pHead.addView(nm, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            pPlan.text = "Combo: " + com.example.mlbbresearch.game.ComboBook.text(h).first +
                "\nOpener ${pb.opener ?: "-"} · CC ${pb.cc ?: "-"} · finisher ${pb.finisher ?: "-"}" +
                "\nEscape ${pb.escape ?: "-"}${if (pb.keepEscape) " (kept)" else ""} · waves ${pb.waveClear ?: "-"}" +
                (pb.sustain?.let { " · heal/shield $it" } ?: "") + (pb.buff?.let { " · buff $it" } ?: "")
            pTips.removeAllViews()
            for (t in pb.tips) pTips.addView(TextView(this).apply {
                text = "•  $t"; textSize = 13f; setTextColor(c(T.TEXT)); setLineSpacing(0f, 1.1f); setPadding(0, dp(3), 0, dp(1))
            })
            pSrc.text = if (pb.learned) "Used in play: combo, escape, wave clear, ultimate timing. From ${pb.source}."
                else "Only role defaults so far: tap Learn this hero to get its real skills and guide."
        }
        btn("Learn this hero (skills + online guide)") {
            val h = GameStateTracker.ownHero()
            when {
                h == null -> toast("Pick a hero first")
                Cfg.groqKey.isBlank() -> toast("No Groq key: put groq_key.txt in the MLBB-Research folder")
                else -> {
                    toast("Learning $h (skills, then the guide)…")
                    com.example.mlbbresearch.game.SkillBook.fetch(h) { m1 ->
                        com.example.mlbbresearch.ai.MetaAdvisor.updateHero(h) { m2 -> runOnUiThread { toast("$h learned. " + m2.take(80)) } }
                        runOnUiThread { toast(m1.take(80)) }
                    }
                }
            }
        }

        title("Skills & passive")
        text("What each skill does (range, crowd control, dash, cast mode) is learned online. Skills that miss or fail are re-tried and re-aimed.", 13f)
        val skInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            skInfo.text = (if (h == null) "Pick a hero first." else com.example.mlbbresearch.game.SkillBook.describe(h)) +
                "\nLast cast check: ${ActionExecutor.lastFeedback}" +
                "\nAll heroes: ${com.example.mlbbresearch.game.SkillBook.learnAllProgress.ifEmpty { "-" }}"
        }
        btn("Learn this hero's skills online") {
            val h = GameStateTracker.ownHero()
            if (h == null) toast("Pick a hero first") else com.example.mlbbresearch.game.SkillBook.fetch(h) { m -> runOnUiThread { toast(m.take(120)) } }
        }
        btn("Learn all heroes' skills (background)") {
            com.example.mlbbresearch.game.SkillBook.learnAll { m -> runOnUiThread { toast(m) } }
            toast("Started")
        }
        btn("Stop learning all heroes") { com.example.mlbbresearch.game.SkillBook.stopLearnAll() }
        val paste = field("Or paste: passive: … / s1: … / s2: … / ult: …", "", false) { }
        btn("Use the pasted descriptions") {
            val h = GameStateTracker.ownHero()
            if (h == null) toast("Pick a hero first") else toast(com.example.mlbbresearch.game.SkillBook.fromText(h, paste.text.toString()).take(120))
        }
        field("Copy your style after this many moments", Cfg.imitationMinDemos.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.imitationMinDemos = v.coerceIn(50, 100_000) }
        }
        val learnInfo = text("", 13f)
        DemoRecorder.refreshTotal()
        upd {
            learnInfo.text = "Recorded moments: ${DemoRecorder.total} (${DemoRecorder.recorded} of you, " +
                "${DemoRecorder.watched} from heroes you watched this session)\n" +
                "Last seen from you: ${DemoRecorder.lastAction}\nYour moves: ${ImitationPolicy.status}" +
                (if (Cfg.coachMode && ImitationPolicy.size > 0) ", similarity now ${(ImitationPolicy.lastMatch * 100).toInt()}%" else "")
        }
        btn("Delete my recorded play") {
            AlertDialog.Builder(this).setTitle("Delete all recorded play?")
                .setPositiveButton("Delete") { _, _ -> DemoRecorder.clear { runOnUiThread { toast("Deleted") } } }
                .setNegativeButton("Cancel", null).show()
        }
        val cdInfo = text("", 12f, color = "#9AA0A6")
        upd { cdInfo.text = "Buttons now: " + com.example.mlbbresearch.game.CooldownTracker.screenState() }
        field("Ask Gemma for a new plan every (seconds)", Cfg.strategyEverySec.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.strategyEverySec = v.coerceIn(3, 120) }
        }
        switch("Send a screenshot with plan questions (slower)", Cfg.strategyWithImage) { Cfg.strategyWithImage = it }

        title("VISION TUNING")
        switch("Show what the AI sees (boxes on the game)", Cfg.showDetections) { Cfg.showDetections = it }
        switch("Show where the AI touches", Cfg.showTouches) { Cfg.showTouches = it }
        field("Hero HP bar min width (% of screen)",
            Cfg.heroBarMinWidthPct.toString(), true) { it.toFloatOrNull()?.let { v -> Cfg.heroBarMinWidthPct = v.coerceIn(1f, 20f) } }
        field("Hero body below its bar (% of height)", Cfg.bodyBelowBarPct.toString(), true) {
            it.toFloatOrNull()?.let { v -> Cfg.bodyBelowBarPct = v.coerceIn(0f, 30f) }
        }
        btn("Reset learned aggression for this hero") {
            Cfg.setLearnedBias(GameStateTracker.ownHero() ?: "unknown", 0f)
            toast("Reset")
        }
    }

    // =====================================================================
    //  DRAFT
    // =====================================================================

    private fun draftPage() {
        stageSwitch("Drafter running", "draft") { AiRuntime.drafterOn }
        title("MATCH TYPE (only one can be on)")
        exclusivePair("Standard match (pick hero, battle spell, emblem)", "Ranked match (ban & pick)", !Cfg.ranked) { std ->
            Cfg.matchMode = if (std) "standard" else "ranked"
            if (!std && AiRuntime.drafterOn && !GemmaEngine.ready) toast("Ranked drafting needs the Gemma model (Model page)")
        }
        val phase = text("", 13f, color = "#9AA0A6")
        upd { phase.text = "Screen now: ${GameStateTracker.state.phase.label}" }

        title("STANDARD MATCH")
        text("Picks your hero (and spell / emblem), then confirms. Needs Drafter + Autonomous.", 13f)
        field("Your hero (also used by the Player)", Cfg.myHero, false) { Cfg.myHero = it }
        switch("Tap my hero", Cfg.stdPickHero) { Cfg.stdPickHero = it }
        switch("Change battle spell", Cfg.stdChangeSpell) { Cfg.stdChangeSpell = it }
        switch("Change emblem set", Cfg.stdChangeEmblem) { Cfg.stdChangeEmblem = it }
        btn("Test the standard pick now") {
            if (AccessibilityController.instance == null) toast("Turn on the accessibility service first")
            else ActionExecutor.standardSelect(HeroDb.match(Cfg.myHero))
        }

        title("Ranked: team or solo")
        var syncing = false
        lateinit var swTeam: Switch
        lateinit var swSolo: Switch
        swTeam = switch("Team", !Cfg.draftSolo) { on ->
            if (!syncing) {
                syncing = true
                if (on) { Cfg.draftSolo = false; swSolo.isChecked = false } else swTeam.isChecked = true
                syncing = false
            }
        }
        swSolo = switch("Solo", Cfg.draftSolo) { on ->
            if (!syncing) {
                syncing = true
                if (on) { Cfg.draftSolo = true; swTeam.isChecked = false } else swSolo.isChecked = true
                syncing = false
            }
        }
        field("Check the draft screen every (seconds)", Cfg.draftEverySec.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.draftEverySec = v.coerceIn(5, 120) }
        }
        title("Ban & pick")
        val dcard = card()
        cardHeader(dcard, "Pick")
        val pickGrid = IconGrid(dcard, hero = true, sizeDp = 48)
        dcard.addView(TextView(this).apply { text = "Ban"; textSize = 15f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
        val banGrid = IconGrid(dcard, hero = true, sizeDp = 48)
        val t = cardNote(dcard)
        upd {
            val (picks, bans, src) = draftLists()
            pickGrid.set(picks); banGrid.set(bans)
            t.text = (if (picks.isEmpty() && bans.isEmpty()) "Waiting for a draft screen." else "From $src.") +
                StrategicAdvisor.draftText.takeIf { it.isNotBlank() }?.let { "\n" + it.lines().filter { l -> l.isNotBlank() }.take(3).joinToString("\n") }.orEmpty()
        }
        title("AUTONOMOUS RANKED DRAFT")
        text("Ranked: taps the recommended hero and locks in. Keep Dry run on until the marker is right.", 13f)
        val err = text("", 12f, color = "#EF9A9A")
        upd { err.text = StrategicAdvisor.lastError }
    }

    // =====================================================================
    //  BUILD
    // =====================================================================

    private fun buildPage() {
        stageSwitch("Builder running", "build") { AiRuntime.builderOn }

        // ---- your build ----
        val bc = card()
        val bHead = cardHeader(bc, "", "", T.GOLD)
        val bChip = (bHead.parent as LinearLayout).getChildAt(1) as TextView
        val buildGrid = IconGrid(bc, hero = false, sizeDp = 44)
        val bNote = cardNote(bc)
        // ---- counter items ----
        val cc = card()
        cardHeader(cc, "Counter items")
        val counterGrid = IconGrid(cc, hero = false, sizeDp = 40)
        val cNote = cardNote(cc)
        // ---- draft: picks and bans ----
        val dc = card()
        val dSrc = cardHeader(dc, "Recommended picks")
        val pickGrid = IconGrid(dc, hero = true, sizeDp = 46)
        dc.addView(TextView(this).apply { text = "Recommended bans"; textSize = 15f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
        val banGrid = IconGrid(dc, hero = true, sizeDp = 46)
        val dNote = cardNote(dc)
        upd {
            val hero = GameStateTracker.ownHero()
            val role = HeroDb.role(hero)
            val meta = if (Cfg.useOnlineMeta) com.example.mlbbresearch.game.MetaStore.hero(hero) else null
            val items = meta?.build?.takeIf { it.isNotEmpty() } ?: BuildBook.primary(role)
            bHead.text = (hero ?: "Your hero") + " build"
            bChip.text = if (meta != null && meta.build.isNotEmpty()) "online meta" else role.name.lowercase()
            buildGrid.set(items)
            bNote.text = buildString {
                if (meta != null) {
                    if (meta.emblem.isNotBlank()) append("Emblem: ").append(meta.emblem)
                    if (meta.spell != null) append(if (isEmpty()) "" else "  ·  ").append("Spell: ").append(meta.spell)
                }
                if (isEmpty()) append("Set the same build as your in-game preset (quick buy follows it).")
            }
            val enemies = GameStateTracker.enemyTeam
            val counters = com.example.mlbbresearch.game.CounterRules.counters(hero, enemies)
            counterGrid.set(counters.map { it.item }, counters.map { it.reason })
            cNote.text = when {
                enemies.isEmpty() -> "Waiting for the enemy line-up."
                counters.isEmpty() -> "None needed against " + enemies.joinToString() + "."
                else -> "Against " + enemies.joinToString() + "."
            }
            val (picks, bans, src) = draftLists()
            dSrc.text = "Recommended picks"
            pickGrid.set(picks)
            banGrid.set(bans)
            dNote.text = if (picks.isEmpty() && bans.isEmpty()) "No draft yet: update the patch meta (Meta page) or start the Drafter." else "From $src."
        }

        title("Auto quick buy (item swap)")
        switch("Auto quick buy when about to die", Cfg.qbOn) { Cfg.qbOn = it }
        switch("Only with a full build", Cfg.qbRequireFull) { Cfg.qbRequireFull = it }
        switch("Only with enough gold", Cfg.qbRequireGold) { Cfg.qbRequireGold = it }
        field("Minimum gold", Cfg.qbMinGold.toString(), true) { it.toIntOrNull()?.let { v -> Cfg.qbMinGold = v } }
        field("Swap when HP is below (%)", Cfg.qbHpPercent.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.qbHpPercent = v.coerceIn(5, 90) }
        }
        val live = text("", 13f, color = T.MUTED)
        upd {
            val s = GameStateTracker.state
            live.text = "HP ${if (s.selfHp < 0) "?" else "${(s.selfHp * 100).toInt()}%"}  ·  items ${if (s.items < 0) "?" else s.items}/6  ·  " +
                "gold ${if (s.gold < 0) "?" else s.gold}  ·  ${QuickBuySwap.status}"
        }
        btn("Test the swap now (buys and sells for real)") {
            if (AccessibilityController.instance == null) toast("Turn on the accessibility service first") else QuickBuySwap.start()
        }
        field("Read items & gold every (seconds)", Cfg.buildEverySec.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.buildEverySec = v.coerceIn(10, 300) }
        }

        title("Builds per role")
        text("Comma separated. Names are checked against the current item list.", 12f, color = T.MUTED)
        for (r in Role.values()) {
            if (r == Role.UNKNOWN) continue
            val box = field(r.name.lowercase().replaceFirstChar { it.uppercase() }, Cfg.roleBuild(r.name, BuildBook.defaults[r] ?: ""), false) { Cfg.setRoleBuild(r.name, it) }
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(holder)
            val grid = IconGrid(holder, hero = false, sizeDp = 32)
            val warn = text("", 12f, color = T.RED)
            upd {
                val typed = box.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                grid.set(typed.mapNotNull { com.example.mlbbresearch.game.ItemDb.canonical(it) })
                val bad = com.example.mlbbresearch.game.ItemDb.outdated(typed)
                warn.text = if (bad.isEmpty()) "" else "Not in the game now (left out): " + bad.joinToString()
                warn.visibility = if (bad.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    // picks / bans to show: the last draft screen read, else the patch meta for your role
    private fun draftLists(): Triple<List<String>, List<String>, String> {
        val d = StrategicAdvisor.draft
        if (d != null && (d.pick.isNotEmpty() || d.ban.isNotEmpty())) return Triple(d.pick.take(10), d.ban.take(10), "the draft screen")
        val g = com.example.mlbbresearch.game.MetaStore.general()
        val role = HeroDb.role(GameStateTracker.ownHero() ?: HeroDb.match(Cfg.myHero))
        if (g != null) {
            val picks = (g.picks[role] ?: emptyList()).ifEmpty { g.picks.values.flatten() }
            return Triple(picks.distinct().take(10), g.bans.distinct().take(10), "the patch meta (${g.patch})")
        }
        val fb = HeroDb.fallbackPicks[role] ?: emptyList()
        return Triple(fb, emptyList(), "the built-in list")
    }

    // =====================================================================
    //  META: online meta through the Groq API
    // =====================================================================

    private fun metaPage() {
        val mg = com.example.mlbbresearch.game.MetaStore
        val ma = com.example.mlbbresearch.ai.MetaAdvisor
        text("Live patch meta from the web (Groq). Saved on the phone; the Player, Builder and Drafter use it.", 13f, color = T.MUTED)
        switch("Use the online meta", Cfg.useOnlineMeta && Cfg.onlineAuto) {
            Cfg.useOnlineMeta = it; Cfg.onlineAuto = it; Cfg.onlineDraft = it
        }
        val st = text("", 13f, color = T.MUTED)
        upd {
            st.text = (if (Cfg.groqKey.isBlank()) "No Groq key: put groq_key.txt in the MLBB-Research folder." else "Groq ready · ${com.example.mlbbresearch.ai.GroqClient.calls} answers this session") +
                (if (ma.busy.isNotEmpty()) "\nWorking: ${ma.busy}…" else "") +
                (if (ma.lastMessage.isNotEmpty()) "\n${ma.lastMessage}" else "")
        }
        val upd1 = btn("Update the patch meta") {
            leaveHooks.forEach { it() }
            if (Cfg.groqKey.isBlank()) toast("No Groq key: put groq_key.txt in the MLBB-Research folder") else {
                toast("Asking Groq (10-30 s)…")
                ma.updateGeneral { msg -> runOnUiThread { toast(msg) } }
            }
        }
        styleButton(upd1, primary = true)
        val heroBox = field("Hero (empty = the hero you play)", "", false) { }
        btn("Update this hero") {
            leaveHooks.forEach { it() }
            val h = HeroDb.match(heroBox.text.toString()) ?: GameStateTracker.ownHero() ?: HeroDb.match(Cfg.myHero)
            when {
                Cfg.groqKey.isBlank() -> toast("No Groq key: put groq_key.txt in the MLBB-Research folder")
                h == null -> toast("Type a hero name")
                else -> { toast("Asking Groq about $h (10-30 s)…"); ma.updateHero(h) { msg -> runOnUiThread { toast(msg) } } }
            }
        }

        // the two cards are rebuilt only when their data changes
        val patchBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heroBox2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(patchBox); content.addView(heroBox2)
        var patchKey = ""; var heroKey = ""

        fun sub(parent: LinearLayout, t: String) = parent.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 11f; letterSpacing = 0.08f; setTextColor(c(T.MUTED)); setTypeface(null, Typeface.BOLD); setPadding(0, dp(12), 0, dp(2))
        })
        fun box(): LinearLayout {
            val cd = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(T.SURF, 16, T.STROKE) }
            return cd
        }
        fun bullets(parent: LinearLayout, items: List<String>) {
            for (i in items) parent.addView(TextView(this).apply {
                text = "•  $i"; textSize = 13f; setTextColor(c(T.TEXT)); setLineSpacing(0f, 1.1f); setPadding(0, dp(2), 0, dp(2))
            })
        }
        fun pair(parent: LinearLayout, k: String, v: String?) {
            if (v.isNullOrBlank()) return
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
            row.addView(TextView(this).apply { text = k; textSize = 13f; setTextColor(c(T.MUTED)) }, LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply { text = v; textSize = 13f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            parent.addView(row)
        }
        fun age(t: Long) = "%.0f days old".format(mg.ageDays(t))

        upd {
            // ---- patch meta ----
            val g = mg.general()
            val pk = g?.let { "${it.patch}${it.updatedAt}" } ?: "none"
            if (pk != patchKey) {
                patchKey = pk
                patchBox.removeAllViews()
                title("Patch meta").let { v -> (v.parent as View).let { r -> content.removeView(r); patchBox.addView(r) } }
                val cd = box()
                patchBox.addView(cd, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                if (g == null) {
                    cardNote(cd, "Not downloaded yet: tap Update the patch meta.")
                } else {
                    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    head.addView(TextView(this).apply { text = "Patch " + g.patch; textSize = 17f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    head.addView(chip(age(g.updatedAt), if (mg.ageDays(g.updatedAt) > 14) T.GOLD else T.GREEN))
                    cd.addView(head)
                    if (g.bans.isNotEmpty()) { sub(cd, "Top bans"); IconGrid(cd, true, 44).set(g.bans) }
                    for ((r, l) in g.picks) if (l.isNotEmpty()) {
                        sub(cd, "Best " + r.name.lowercase() + " picks")
                        IconGrid(cd, true, 44).set(l)
                    }
                    if (g.notes.isNotBlank()) cardNote(cd, g.notes)
                    if (g.sources.isNotEmpty()) cardNote(cd, "Sources: " + g.sources.joinToString())
                }
            }
            // ---- hero meta ----
            val h = HeroDb.match(heroBox.text.toString()) ?: GameStateTracker.ownHero() ?: HeroDb.match(Cfg.myHero)
            val m = mg.hero(h)
            val hk = "$h|${m?.updatedAt}"
            if (hk != heroKey) {
                heroKey = hk
                heroBox2.removeAllViews()
                title("Hero meta").let { v -> (v.parent as View).let { r -> content.removeView(r); heroBox2.addView(r) } }
                val cd = box()
                heroBox2.addView(cd, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                if (h != null) head.addView(com.example.mlbbresearch.app.IconCache.view(this, h, true, dp(58)), LinearLayout.LayoutParams(dp(58), dp(58)))
                val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                names.addView(TextView(this).apply { text = h ?: "No hero"; textSize = 19f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
                names.addView(TextView(this).apply {
                    text = if (m == null) "not downloaded yet" else "${HeroDb.role(h).name.lowercase()} · ${m.patch} · ${age(m.updatedAt)}"
                    textSize = 12f; setTextColor(c(T.MUTED))
                })
                head.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                cd.addView(head)
                if (m == null) {
                    cardNote(cd, "Tap Update this hero.")
                } else {
                    if (m.build.isNotEmpty()) { sub(cd, "Build"); IconGrid(cd, false, 42).set(m.build) }
                    sub(cd, "Setup")
                    pair(cd, "Emblem", m.emblem); pair(cd, "Spell", m.spell); pair(cd, "Lane", m.lane?.label)
                    pair(cd, "Combo", m.combo); pair(cd, "Dash", m.dash)
                    if (m.skillOrder.isNotEmpty()) pair(cd, "Skill order", m.skillOrder.joinToString(" › "))
                    if (m.laning.isNotEmpty()) { sub(cd, "Laning"); bullets(cd, m.laning) }
                    if (m.jungle.isNotEmpty()) { sub(cd, "Jungle"); bullets(cd, m.jungle) }
                    if (m.rotation.isNotEmpty()) { sub(cd, "Rotations"); bullets(cd, m.rotation) }
                    if (m.counters.isNotEmpty()) { sub(cd, "Countered by"); IconGrid(cd, true, 40).set(m.counters) }
                    if (m.changes.isNotBlank()) { sub(cd, "Recent changes"); cardNote(cd, m.changes) }
                    if (m.sources.isNotEmpty()) cardNote(cd, "Sources: " + m.sources.joinToString())
                }
            }
        }
        val del = btn("Delete all downloaded meta") {
            AlertDialog.Builder(this).setTitle("Delete the downloaded meta?")
                .setPositiveButton("Delete") { _, _ -> mg.clear(); toast("Deleted") }
                .setNegativeButton("Cancel", null).show()
        }
        // keep the delete button at the very bottom, under the cards
        content.removeView(del); content.addView(del)
        del.setTextColor(c(T.RED))
    }

    // =====================================================================
    //  SPEED: every engine and model, live, with the last 2 minutes
    // =====================================================================

    private fun speedPage() {
        val log = com.example.mlbbresearch.core.SpeedLog
        fun fmt(v: Float, unit: String) = when (unit) {
            "ms" -> if (v < 10f) "%.1f ms".format(v) else "%.0f ms".format(v)
            "s" -> "%.1f s".format(v)
            else -> "%.0f %s".format(v, unit)
        }
        fun gradeColor(g: Int) = c(when (g) { 0 -> T.GREEN; 1 -> T.GOLD; else -> T.RED })
        text("Live speed of every part of the AI. Dashed line = target.", 13f, color = T.MUTED)

        // ---- the headline: frame to touch, and what it is made of ----
        val total = log.series[0]
        val parts = log.series.subList(1, 5)
        val hc = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val big = TextView(this).apply { textSize = 40f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) }
        head.addView(big)
        val gradeChip = chip("")
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(gradeChip)
        hc.addView(TextView(this).apply { text = "FRAME → TOUCH"; textSize = 11f; letterSpacing = 0.08f; setTextColor(c(T.MUTED)); setTypeface(null, Typeface.BOLD) })
        hc.addView(head)
        val stack = com.example.mlbbresearch.app.StackBar(this)
        hc.addView(stack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hc.addView(legend)
        val legendRows = parts.map { sr ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
            row.addView(View(this).apply { background = shape(sr.color, 5) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            row.addView(TextView(this).apply { text = sr.name; textSize = 13f; setTextColor(c(T.TEXT)); setPadding(dp(8), 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(this).apply { textSize = 13f; setTextColor(c(T.MUTED)) }
            row.addView(v)
            legend.addView(row)
            v
        }
        val totalSpark = com.example.mlbbresearch.app.Sparkline(this)
        hc.addView(totalSpark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply { topMargin = dp(8) })
        val totalNote = cardNote(hc)

        // ---- every other measure, grouped ----
        class Row(val sr: com.example.mlbbresearch.core.SpeedLog.Series, val value: TextView, val bar: com.example.mlbbresearch.app.BudgetBar,
                  val spark: com.example.mlbbresearch.app.Sparkline, val note: TextView)
        val rows = ArrayList<Row>()
        var group = ""
        for (sr in log.series.drop(5)) {
            if (sr.group != group) { group = sr.group; title(group) }
            val cd = card()
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(View(this).apply { background = shape(sr.color, 5) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            top.addView(TextView(this).apply { text = sr.name; textSize = 14f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD); setPadding(dp(8), 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val value = TextView(this).apply { textSize = 18f; setTypeface(null, Typeface.BOLD) }
            top.addView(value)
            cd.addView(top)
            val bar = com.example.mlbbresearch.app.BudgetBar(this)
            cd.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
            val spark = com.example.mlbbresearch.app.Sparkline(this)
            cd.addView(spark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
            rows.add(Row(sr, value, bar, spark, cardNote(cd)))
        }

        upd {
            log.tick()
            val t = total.last()
            big.text = if (total.count == 0 || t <= 0f) "–" else fmt(t, "ms")
            big.setTextColor(if (t <= 0f) c(T.MUTED) else gradeColor(total.grade()))
            gradeChip.text = when { t <= 0f -> "waiting for a match"; total.grade() == 0 -> "fast"; total.grade() == 1 -> "ok"; else -> "slow" }
            gradeChip.setTextColor(if (t <= 0f) c(T.MUTED) else gradeColor(total.grade()))
            stack.set(parts.map { it.last() to c(it.color) })
            parts.forEachIndexed { i, sr -> legendRows[i].text = fmt(sr.last(), sr.unit) }
            totalSpark.set(total.history(), total.budget, c(total.color))
            totalNote.text = if (total.count == 0) "No samples yet: start the AI in a match." else
                "2 min: average ${fmt(total.avg(), "ms")}, worst ${fmt(total.max(), "ms")}, target under ${fmt(total.budget, "ms")}"
            for (r in rows) {
                val v = r.sr.last()
                val used = r.sr.history().any { it > 0f }
                r.value.text = if (!used) "not used" else fmt(v, r.sr.unit)
                r.value.setTextColor(if (!used) c(T.MUTED) else gradeColor(r.sr.grade()))
                r.bar.set(if (r.sr.higherIsBetter) v / r.sr.budget else v / r.sr.budget, if (!used) c(T.STROKE) else gradeColor(r.sr.grade()))
                r.spark.set(r.sr.history(), r.sr.budget, c(r.sr.color))
                r.note.text = if (!used) "No runs yet (loaded and used only when on)." else
                    "average ${fmt(r.sr.avg(), r.sr.unit)} · " + (if (r.sr.higherIsBetter) "target ${fmt(r.sr.budget, r.sr.unit)}+" else "target under ${fmt(r.sr.budget, r.sr.unit)}")
            }
        }
    }

    // =====================================================================
    //  MODEL
    // =====================================================================

    private fun modelPage() {
        title("FILES")
        text("Put files in Download/MLBB-Research; the app loads them by itself:\n• Gemma 4 E2B (.litertlm) – strategy\n• Gemma 3 270M (.litertlm) – fight advice (optional)\n• mlbb_policies.zip – tactical networks\n• detectors (.tflite + labels) – minimap and screen\n• groq_key.txt – online meta", 13f)
        val c = card()
        val st = cardText(c, 14f, true)
        upd {
            val af = com.example.mlbbresearch.models.AutoFolder
            val pn = com.example.mlbbresearch.learning.PolicyNet
            fun mark(ok: Boolean) = if (ok) "\u2713" else "\u2717"
            st.text = "Folder: " + (af.treeUri?.let { Uri.decode(it.lastPathSegment ?: "chosen").substringAfter(':') } ?: "not chosen yet") +
                "\n${af.status}" +
                "\n\n${mark(GemmaEngine.ready)} Strategic model: ${ModelManager.status(this)}, ${GemmaEngine.state}" +
                (if (GemmaEngine.calls > 0) " (last answer ${GemmaEngine.lastLatencyMs} ms)" else "") +
                "\n${mark(TacticalLlmEngine.ready)} Small tactical model: ${ModelManager.tacticalStatus(this)}, " +
                (if (Cfg.tacticalLlmOn) TacticalLlmEngine.state else "switched off") +
                (if (Cfg.tacticalLlmOn && TacticalLlmEngine.ready) "\n     " + TacticalAdvisor.text().substringAfter(": ") else "") +
                "\n${mark(ObjectDetector.ready)} Minimap detector: ${ObjectDetector.state}" +
                (if (ObjectDetector.ready) ", ${ObjectDetector.lastMs} ms per run" else "") +
                "\n${mark(com.example.mlbbresearch.vision.ScreenDetector.ready)} Screen detector: ${com.example.mlbbresearch.vision.ScreenDetector.state}" +
                (if (com.example.mlbbresearch.vision.ScreenDetector.ready) ", ${com.example.mlbbresearch.vision.ScreenDetector.lastMs} ms per run; " +
                    com.example.mlbbresearch.vision.ScreenIntel.added else "") +
                "\n${mark(pn.ready)} Tactical network: ${pn.state}" +
                pn.available(this).let { if (it.isEmpty()) "" else "\n   on the phone: " + it.joinToString() } +
                (if (pn.ready && pn.agreeRate >= 0f) ", agrees with the rules ${(pn.agreeRate * 100).toInt()}%" else "") +
                (if (pn.ready) "\n     ${pn.qualityText}" else "") +
                "\n${mark(Cfg.groqKey.isNotBlank())} Groq key: " + (if (Cfg.groqKey.isBlank()) "not found" else "set") +
                "\n\nMinimap: " + com.example.mlbbresearch.game.MapIntel.status
        }
        val pick = btn("") {
            @Suppress("DEPRECATION")
            startActivityForResult(com.example.mlbbresearch.models.AutoFolder.pickIntent(), reqFolder)
        }
        upd { pick.text = if (com.example.mlbbresearch.models.AutoFolder.treeUri == null) "Choose the folder" else "Change the folder" }
        btn("Scan the folder now") {
            com.example.mlbbresearch.models.AutoFolder.scan(this) { msg -> runOnUiThread { toast(msg.take(150)) } }
        }

        title("WHERE TO GET THEM")
        btn("Get Gemma 4 E2B (2.6 GB)") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.MODEL_PAGE))) }
        btn("Get Gemma 3 270M") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.SMALL_MODEL_PAGE))) }
        btn("Training notebooks (GitHub)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/georgenaval1-blip/Ai-App/tree/main/notebooks")))
        }
        btn("Groq API key (free)") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }

        title("SETTINGS")
        switch("Strategic model on the GPU (faster)", Cfg.useGpu) { Cfg.useGpu = it }
        switch("Small tactical model in fights", Cfg.tacticalLlmOn) { on ->
            Cfg.tacticalLlmOn = on
            if (on) ModelManager.loadTactical(this) { err -> runOnUiThread { toast(err ?: "Small tactical model loaded") } }
            else ModelManager.unloadTactical()
        }
        // the detector and the tactical network are used together: one switch
        switch("Use the trained models", Cfg.detectorOn && Cfg.policyOn) {
            Cfg.detectorOn = it; Cfg.policyOn = it
        }
    }


    // =====================================================================
    //  LEARN
    // =====================================================================

    private fun learnPage() {
        DemoRecorder.refreshTotal()
        com.example.mlbbresearch.learning.LiveRecorder.refreshTotal()
        title("Smart fights")
        text("Near an enemy the AI picks: all in, poke, back off, group or farm, scores the result 4 s later and learns what works.", 13f)
        switch("Smart fights (learning AI)", Cfg.smartFights) { Cfg.smartFights = it }
        val levels = listOf("Low (mostly uses what it knows)", "Normal", "High (tries new things more)")
        val explore = btn("") { Cfg.exploreLevel = (Cfg.exploreLevel + 1) % 3 }
        upd { explore.text = "Curiosity: ${levels[Cfg.exploreLevel.coerceIn(0, 2)]}  (tap to change)" }
        switch("Also learn from my fights", Cfg.brainLearnsFromYou) { Cfg.brainLearnsFromYou = it }

        val c = card()
        val head = cardText(c, 15f, true)
        val table = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(c(T.TEXT)) }
        c.addView(table)
        val now = cardText(c, 13f)
        upd {
            val hero = GameStateTracker.ownHero()
            val own = Brain.fights(hero)
            head.text = "${hero ?: "Unknown hero"}: $own fights learned" +
                (if (own < 40) " (uses the shared brain, ${Brain.sharedFights()} fights, until 40)" else "") +
                "\nThis session: ${Brain.learnedThisSession} lessons"
            val stats = Brain.stats(hero)
            val best = stats.filter { it.tries >= 3 }.maxByOrNull { it.avgReward }
            table.text = "choice                   tried  avg result\n" + stats.joinToString("\n") { st ->
                val bar = if (st.tries == 0) "" else (if (st.avgReward >= 0) "+".repeat((st.avgReward * 4).toInt().coerceIn(0, 8))
                          else "-".repeat((-st.avgReward * 4).toInt().coerceIn(0, 8)))
                "%-24s %5d  %+.2f %s%s".format(st.option.label.take(24), st.tries, st.avgReward, bar, if (st === best) "  <- best so far" else "")
            }
            now.text = "Now: ${Brain.lastChoice}"
        }

        title("RECENT LESSONS")
        val lessons = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(c(T.TEXT)) }
        content.addView(lessons)
        upd {
            val r = Brain.recent(12)
            lessons.text = if (r.isEmpty()) "(none yet: start the Player and get into a fight)" else r.joinToString("\n")
        }
        btn("Forget what it learned for this hero") {
            val hero = GameStateTracker.ownHero() ?: "unknown"
            AlertDialog.Builder(this).setTitle("Forget the fights learned with $hero?")
                .setPositiveButton("Forget") { _, _ -> Brain.reset(hero); toast("Forgotten") }
                .setNegativeButton("Cancel", null).show()
        }
        btn("Forget everything it learned (all heroes)") {
            AlertDialog.Builder(this).setTitle("Forget all learned fights?")
                .setPositiveButton("Forget") { _, _ -> Brain.reset(null); toast("Forgotten") }
                .setNegativeButton("Cancel", null).show()
        }

        title("OTHER LEARNING")
        val o = card()
        val t = cardText(o, 13f)
        upd {
            val hero = GameStateTracker.ownHero() ?: "unknown"
            t.text = "Decisions saved: ${ExperienceRecorder.count} (${PerformanceEvaluator.evaluated} scored this session)\n" +
                "Aggression adjust for $hero: ${"%+.2f".format(Cfg.learnedBias(hero))}\n" +
                "Your recorded play (Coach): ${DemoRecorder.total} moments - see the Player page"
        }
        btn("Export decisions to Downloads (CSV)") {
            DatasetManager.export(this) { msg -> runOnUiThread { toast(msg) } }
        }
        btn("Export my play (CSV)") {
            DatasetManager.exportDemos(this) { msg -> runOnUiThread { toast(msg) } }
        }
        ExperienceRecorder.refreshFights()
        val fightInfo = text("", 12f, color = "#9AA0A6")
        upd { fightInfo.text = "Fight moments for the small tactical model: " + (if (ExperienceRecorder.fights < 0) "counting..." else "${ExperienceRecorder.fights}") +
            " (each fight choice - the AI's, the small model's or yours - with how it went 4 s later)" }
        btn("Export fight moments (CSV)") {
            DatasetManager.exportFights(this) { msg -> runOnUiThread { toast(msg) } }
        }

        title("Live matches")
        text("Watch someone's live match with learning on: their play is recorded like yours. Export it for the notebook.", 13f)
        switch("Learn from live matches", Cfg.liveLearn) { Cfg.liveLearn = it }
        field("Hero you are watching (e.g. Balmond)", Cfg.liveHero, false) { v ->
            val m = com.example.mlbbresearch.game.HeroDb.match(v)
            Cfg.liveHero = m ?: v.trim()
            if (v.isNotBlank() && m == null) toast("Hero not recognised: saved as typed")
        }
        val lanes = listOf("exp", "gold", "mid", "roam", "jungle")
        val laneBtn = btn("") { Cfg.liveLaneName = lanes[(lanes.indexOf(Cfg.liveLaneName) + 1) % lanes.size] }
        upd { laneBtn.text = "Their role: ${Cfg.liveLane.label}  (tap to change)" }
        val liveInfo = text("", 12f, color = "#9AA0A6")
        upd {
            com.example.mlbbresearch.learning.LiveRecorder.let { r ->
                val st = GameStateTracker.state
                val watching = st.phase == com.example.mlbbresearch.game.Phase.LIVE
                liveInfo.text = (if (watching) "Watching a live match now" + (if (st.self == null) " (their hero is dead or not found on screen: not recording)" else ", their HP ${(st.selfHp * 100).toInt()}%") +
                    "\nMatch time: " + (if (st.matchSec >= 0) "%d:%02d".format(st.matchSec / 60, st.matchSec % 60) else "not read yet (needs Gemma)") +
                    "\nPanel: ${r.panel}\nLast seen: ${r.lastAction}\n" else "Not watching a live match\n") +
                    "Live moments: ${r.recorded} this session, ${r.total} saved; deaths seen: ${r.deaths} (the 6 s before each are marked)"
            }
        }
        btn("Export live matches (CSV)") {
            DatasetManager.exportLive(this) { msg -> runOnUiThread { toast(msg) } }
        }
        btn("Delete the live match moments") {
            AlertDialog.Builder(this).setTitle("Delete all live match moments?")
                .setPositiveButton("Delete") { _, _ -> com.example.mlbbresearch.learning.LiveRecorder.clear { runOnUiThread { toast("Deleted") } } }
                .setNegativeButton("Cancel", null).show()
        }

        title("HOW THE AI DOES PER HERO AND ROLE")
        text("Every match: hero, role, K/D/A, result.", 13f)
        val ml = text("", 12f)
        upd { ml.text = com.example.mlbbresearch.game.MatchLog.summary() }
        btn("Clear the match log") { com.example.mlbbresearch.game.MatchLog.clear(); toast("Cleared") }

        title("DETECTOR TRAINING PICTURES")
        text("Saves labelled pictures while you play (minimap 1500, screen 1000). At capacity it zips them in Download/MLBB-Research and turns the switch off.", 13f)
        val zk = com.example.mlbbresearch.learning.DatasetZipper
        val swMini = switch("Collect minimap training pictures", Cfg.collectDataset) { Cfg.collectDataset = it }
        val swScreen = switch("Collect whole-screen training pictures", Cfg.collectScreenDataset) { Cfg.collectScreenDataset = it }
        val dsInfo = text("", 12f, color = "#9AA0A6")
        upd {
            // the switches follow the app turning a full collector off
            if (swMini.isChecked != Cfg.collectDataset) swMini.isChecked = Cfg.collectDataset
            if (swScreen.isChecked != Cfg.collectScreenDataset) swScreen.isChecked = Cfg.collectScreenDataset
            fun line(k: com.example.mlbbresearch.learning.DatasetZipper.Kind) = "${zk.count(k)} of ${k.capacity} pictures since the last zip" +
                if (!zk.switchOn(k)) " - switched off" else ""
            dsInfo.text = "Minimap: " + line(com.example.mlbbresearch.learning.DatasetZipper.Kind.MINIMAP) +
                "\nScreen: " + line(com.example.mlbbresearch.learning.DatasetZipper.Kind.SCREEN) +
                "\nLast zip: " + zk.status
        }
        btn("Zip the minimap pictures now") {
            zk.zipNew(this, com.example.mlbbresearch.learning.DatasetZipper.Kind.MINIMAP, "zipped by hand") { m -> runOnUiThread { toast(m) } }
        }
        btn("Zip the screen pictures now") {
            zk.zipNew(this, com.example.mlbbresearch.learning.DatasetZipper.Kind.SCREEN, "zipped by hand") { m -> runOnUiThread { toast(m) } }
        }
        btn("Delete saved decisions") {
            AlertDialog.Builder(this).setTitle("Delete saved decisions?")
                .setPositiveButton("Delete") { _, _ -> DatasetManager.clear { runOnUiThread { toast("Deleted") } } }
                .setNegativeButton("Cancel", null).show()
        }
    }

    // =====================================================================
    //  LOGS
    // =====================================================================

    private fun logsPage() {
        title("DIAGNOSTICS - bugs and errors")
        val cur = text("")
        upd {
            val e = listOf(AiRuntime.lastError, StrategicAdvisor.lastError).filter { it.isNotEmpty() }
            cur.text = if (e.isEmpty()) "✓ No active errors" else e.joinToString("\n") { "✗ $it" }
            cur.setTextColor(Color.parseColor(if (e.isEmpty()) "#66BB6A" else "#EF5350"))
        }
        btn("Copy history") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MLBB logs", Diag.read()))
            toast("Copied")
        }
        btn("Delete history") {
            AlertDialog.Builder(this).setTitle("Delete diagnostic history?")
                .setPositiveButton("Delete") { _, _ -> Diag.clear() }
                .setNegativeButton("Cancel", null).show()
        }
        title("HISTORY (newest first)")
        val log = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true); setTextColor(c(T.TEXT))
        }
        content.addView(log)
        upd {
            val lines = Diag.read().lines().filter { it.isNotBlank() }
            val s = if (lines.isEmpty()) "(history is empty)" else lines.takeLast(300).reversed().joinToString("\n")
            if (log.text.toString() != s) log.text = s
        }
    }

    // =====================================================================
    //  SETUP: permissions + calibration
    // =====================================================================

    private fun setupPage() {
        title("PERMISSIONS")
        val acc = btn("1. Accessibility service (touches)") { openAccessibility() }
        upd { mark(acc, AccessibilityController.instance != null) }
        btn("Switch greyed out? Allow restricted settings") {
            toast("Tap ⋮ (top-right), then 'Allow restricted settings'")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val ov = btn("2. Display over other apps (floating icon)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        upd { mark(ov, Settings.canDrawOverlays(this)) }
        val nt = btn("3. Notifications") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotify)
        }
        upd {
            mark(nt, Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        }
        val cap = btn("4. Screen capture") { askScreenCapture() }
        upd { mark(cap, ScreenCaptureService.running) }
        text("POCO/Xiaomi: also allow 'pop-up windows while running in the background' and turn off 'Manage app if unused'.", 12f, color = "#9AA0A6")

        title("SCREEN")
        val scr = text("", 12f, color = "#9AA0A6")
        upd {
            scr.text = "Screen: " + com.example.mlbbresearch.core.ScreenGeom.describe(this) +
                "\nVision, touches and drawings all use the whole screen (a POCO X6 Pro is 2712 x 1220), so positions match MLBB exactly."
        }

        title("CALIBRATE BUTTON POSITIONS")
        text("Open a match, come back, tap Calibrate: drag the yellow markers onto the buttons (or Auto-detect), then Save.", 12f, color = "#9AA0A6")
        fun calibrateLater(group: Int) {
            val o = AccessibilityController.instance?.overlay
            if (o == null) { toast("Turn on the accessibility service first"); return }
            if (!ScreenCaptureService.running) toast("Tip: start screen capture first so Auto-detect can see the game")
            toast("Switch to MLBB now: calibration starts in 5 seconds")
            ui.postDelayed({ o.startCalibration(group) }, 5000)
        }
        btn("Calibrate in-game buttons on the game") { calibrateLater(0) }
        btn("Calibrate shop buttons on the game") { calibrateLater(1) }
        btn("Calibrate hero-select buttons on the game") { calibrateLater(2) }

        title("MINIMAP ICON CALIBRATION")
        text("Minimap misread? Pick an icon type, tap 'Pick it on the game', then tap that icon on the minimap within 5 s.", 12f, color = "#9AA0A6")
        val kinds = com.example.mlbbresearch.vision.MinimapColors.Kind.values()
        var kindIdx = 0
        val kindBtn = btn("") { kindIdx = (kindIdx + 1) % kinds.size }
        val kindInfo = text("", 13f)
        upd {
            val k = kinds[kindIdx]
            kindBtn.text = "Icon type: ${k.label}  (tap to change)"
            val ss = com.example.mlbbresearch.vision.MinimapColors.samples(k)
            kindInfo.text = "Samples: " + (if (ss.isEmpty()) "none (built-in colours only)" else ss.joinToString("  ") { "${it[0]},${it[1]},${it[2]}" })
        }
        btn("Pick it on the game (switch to MLBB, 5 s)") {
            val o = AccessibilityController.instance?.overlay
            if (o == null) { toast("Turn on the accessibility service first"); return@btn }
            if (!ScreenCaptureService.running) { toast("Start screen capture first (Home page)"); return@btn }
            val k = kinds[kindIdx]
            toast("Switch to MLBB now, then tap: ${k.label}")
            ui.postDelayed({
                o.pickColor(k.label) { rgb ->
                    if (rgb == null) toast("No screen frame: try again")
                    else { com.example.mlbbresearch.vision.MinimapColors.add(k, rgb); toast("Saved ${k.label}: ${rgb[0]},${rgb[1]},${rgb[2]}") }
                }
            }, 5000)
        }
        btn("Clear the samples of this icon type") { com.example.mlbbresearch.vision.MinimapColors.clear(kinds[kindIdx]); toast("Cleared") }

        title("Presets")
        val active = text("", 13f)
        upd { active.text = "Active preset: " + Cfg.activePreset.ifEmpty { "none (current positions)" } }
        val nameBox = EditText(this).apply {
            hint = "Preset name"; setTextColor(c(T.TEXT)); setHintTextColor(c(T.MUTED))
            background = shape(T.SURF2, 10, T.STROKE); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(nameBox)
        btn("Save current positions as a preset") {
            if (Layout.savePreset(nameBox.text.toString())) { toast("Preset saved"); show("setup") } else toast("Type a name first")
        }
        for (name in Layout.presetNames()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 14f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Load"; styleButton(this)
                setOnClickListener { if (Layout.loadPreset(name)) { toast("Loaded $name"); show("setup") } }
            })
            row.addView(Button(this).apply {
                text = "Delete"; styleButton(this); setTextColor(c(T.RED))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Delete preset $name?")
                        .setPositiveButton("Delete") { _, _ -> Layout.deletePreset(name); show("setup") }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            content.addView(row)
        }

        title("TYPE POSITIONS BY HAND")
        text("x,y in % of the screen. 'Show markers' draws them on the game.", 12f, color = "#9AA0A6")
        switch("Show markers on the game", AccessibilityController.instance?.overlay?.markersOn == true) { on ->
            val o = AccessibilityController.instance?.overlay
            if (o == null) toast("Turn on the accessibility service first") else {
                o.markersOn = on
                if (on) o.show()
            }
        }
        var group = ""
        for (ctl in Layout.defaults) {
            if (ctl.group != group) {
                group = ctl.group
                text(when (group) { "shop" -> "Shop screen"; "select" -> "Hero select"; else -> "In game" }, 13f, true)
            }
            field(ctl.label, Layout.text(ctl.key), false) { v -> if (v != Layout.text(ctl.key)) Layout.set(ctl.key, v) }
        }
        btn("Reset all positions to defaults") {
            for (ctl in Layout.defaults) Layout.reset(ctl.key)
            show("setup")
        }
    }
}
