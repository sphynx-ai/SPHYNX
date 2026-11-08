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
    private var groqWasLimited = false
    private val leaveHooks = ArrayList<() -> Unit>()
    private var defaultText: ColorStateList? = null
    private var page = "home"
    private var swipeBlocked = false
    private var pendingStage: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private var headerStatus: TextView? = null
    private var navScroll: HorizontalScrollView? = null

    private val pages = listOf("home", "news", "player", "draft", "build", "meta", "library", "model", "learn", "speed", "logs", "setup")
    private val labels = mapOf(
        "home" to "Home", "news" to "What's new", "player" to "Player", "draft" to "Draft", "build" to "Build", "meta" to "Meta", "library" to "Library",
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
                // Groq resting after its rate limit: a red countdown in the header, on every page
                val gq = com.example.mlbbresearch.ai.GroqClient
                if (gq.limited) {
                    val sp = android.text.SpannableString("  ·  Groq in " + gq.waitText())
                    sp.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(T.RED)), 0, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    h.append(sp)
                }
                if (groqWasLimited && !gq.limited) toast("Groq is available again")
                groqWasLimited = gq.limited
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
            text = getString(R.string.app_name)
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
        com.example.mlbbresearch.learning.OnlineBackup.flushLearned()   // learned data into the folder now
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
    // Groq status for the indicators: colour + text
    private fun groqIndicator(): Pair<String, String> {
        val gq = com.example.mlbbresearch.ai.GroqClient
        return when {
            !gq.anyKey -> T.MUTED to "Online AI: no key"
            gq.limited -> T.RED to "All online models resting after their rate limits · available again in ${gq.waitText()} (${gq.availableAtText()})"
            gq.groqLimited -> T.GREEN to "Online AI available (Groq resting; the other models answer)"
            else -> T.GREEN to "Online AI available"
        }
    }

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
            "news" -> newsPage()
            "player" -> playerPage()
            "draft" -> draftPage()
            "build" -> buildPage()
            "meta" -> metaPage()
            "library" -> libraryPage()
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
            toast("Turn ON 'Sphynx Auto Pilot' in Accessibility, then come back")
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

    // WHAT'S NEW (core.WhatsNew): fixed bugs and new features, newest first
    private fun whatsNewRow(cd: LinearLayout, e: com.example.mlbbresearch.core.WhatsNew.Entry, details: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        row.addView(chip(e.kind.label, e.kind.color))
        row.addView(TextView(this).apply {
            text = e.title; textSize = 14f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cd.addView(row)
        if (details) cardNote(cd, e.details + "\n" + com.example.mlbbresearch.core.WhatsNew.ref(e) +
            (if (e.needsDeviceCheck) " · not yet checked on a phone" else ""))
    }

    private fun whatsNewPreview() {
        val cd = card()
        cardHeader(cd, "What's new", "v${com.example.mlbbresearch.core.AppVersion.VERSION}")
        for (e in com.example.mlbbresearch.core.WhatsNew.latest(3)) whatsNewRow(cd, e, false)
        val more = Button(this).apply {
            text = "See all changes"; isAllCaps = false; textSize = 13f; stateListAnimator = null
            background = ripple(T.SURF2, 12, T.STROKE); setTextColor(c(T.TEXT))
            setOnClickListener { go("news") }
        }
        cd.addView(more, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) })
    }

    private fun newsPage() {
        val wn = com.example.mlbbresearch.core.WhatsNew
        title("WHAT'S NEW")
        val n = wn.counts()
        text("Sphynx Auto Pilot v${com.example.mlbbresearch.core.AppVersion.VERSION} · " +
            com.example.mlbbresearch.core.WhatsNew.Kind.values().joinToString(" · ") { "${n[it] ?: 0} ${it.label.lowercase()}" }, 12f, color = T.MUTED)
        for ((date, entries) in wn.byDate()) {
            val cd = card()
            cardHeader(cd, date)
            for (e in entries) whatsNewRow(cd, e, true)
        }
        text("Changes still waiting to be merged are not listed. \"Not yet checked on a phone\" means the change is built " +
            "and unit tested but has not been confirmed in a real match yet.", 12f, color = T.MUTED)
    }

    private fun homePage() {
        // the app version (and the installed build, if the package says something else)
        val installed = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        text("Sphynx Auto Pilot v${com.example.mlbbresearch.core.AppVersion.VERSION}" +
            (if (installed != null && installed != com.example.mlbbresearch.core.AppVersion.VERSION) " · build $installed" else ""), 12f, color = T.MUTED)
        whatsNewPreview()
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

        // VISION TUNING (moved here from the Player page - v36.93)
        title("VISION TUNING")
        switch("Show what the AI sees (boxes on the game)", Cfg.showDetections) { Cfg.showDetections = it }
        switch("Show where the AI touches", Cfg.showTouches) { Cfg.showTouches = it }

        title("RUN")
        stageSwitch("Player (plays the hero)", "player") { AiRuntime.playerOn }
        stageSwitch("Drafter (ban & pick)", "draft") { AiRuntime.drafterOn }
        stageSwitch("Builder (items)", "build") { AiRuntime.builderOn }
        stageSwitch("Research mode (practice vs Computer only; arbiter picks fight option, records moments)", "research") { com.example.mlbbresearch.research.ResearchMode.on }
        val resInfo = text("", 12f, color = T.MUTED)
        upd { resInfo.text = com.example.mlbbresearch.research.ResearchMode.momentsText() + "\n" + com.example.mlbbresearch.research.ReadinessRun.status() +
            "\n" + com.example.mlbbresearch.research.ResearchMode.abText() }
        btn("Test the small model on the practice moments") {
            val rr = com.example.mlbbresearch.research.ReadinessRun
            if (rr.running) { rr.stop(); toast("Stopping the test") }
            else rr.start { msg -> runOnUiThread { toast(msg.take(150)) } }
        }
        btn("Clear the arbiter vs normal comparison") {
            android.app.AlertDialog.Builder(this).setMessage("Clear the arbiter vs normal comparison?")
                .setPositiveButton("Clear") { _, _ -> com.example.mlbbresearch.research.ResearchMode.clearAb(); toast("Cleared") }
                .setNegativeButton("Cancel", null).show()
        }
        btn("Delete the recorded practice moments") {
            android.app.AlertDialog.Builder(this).setMessage("Delete all recorded practice moments?")
                .setPositiveButton("Delete") { _, _ -> com.example.mlbbresearch.research.ResearchMode.clearMoments(); toast("Deleted") }
                .setNegativeButton("Cancel", null).show()
        }
        switch("Autonomous Drafter + Builder", Cfg.autonomous) { Cfg.autonomous = it }
        switch("Dry run: only show where it would tap", Cfg.dryRun) { Cfg.dryRun = it }
        val mode = text("", 12f, color = T.MUTED)
        upd { mode.text = "Match: ${if (Cfg.ranked) "Ranked" else "Standard"} (detected by itself) · ⏻ on the floating icon toggles the AI in game" }

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
                if (s.selfHp >= 0) {
                    append("HP ").append(((if (s.selfHpRaw >= 0) s.selfHpRaw else s.selfHp) * 100).toInt()).append("%")
                    if (s.selfExtra > 0.01f) append(" + ").append(when (com.example.mlbbresearch.game.HeroKit.barModel(s.ownHero)) {
                        com.example.mlbbresearch.game.HeroKit.BarModel.ARMOR -> "armour"
                        com.example.mlbbresearch.game.HeroKit.BarModel.STORED -> "stored energy"
                        else -> "shield" }).append(" ").append((s.selfExtra * 100).toInt()).append("%")
                    append("  ·  ")
                }
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
        text("Type your hero and press ENTER: an entered hero is ABSOLUTE (beats every detection) and the app learns how it looks from it. Enter an empty box to go back to detection: skill set > hero icon / model > portrait.", 12f, color = "#9AA0A6")
        fun saveHero(v: String) {
            val m = HeroDb.match(v)
            Cfg.manualHero = if (v.isBlank()) "" else m ?: v.trim()
            if (v.isBlank()) toast("Detection: skill set > hero icon / model > portrait")
            else if (m == null) toast("Entered \"${v.trim()}\" (not in the hero list: role defaults until its skills are learned online)")
            else { if (m != Cfg.myHero) Cfg.myHero = m; toast("Entered: $m (absolute)") }
        }
        // (typing alone changes nothing: only Enter - the button or the keyboard's Done - sets it)
        val heroBox = field("My hero (Enter = absolute; empty + Enter = detect)", Cfg.manualHero, false) { }
        heroBox.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        heroBox.setOnEditorActionListener { v, _, _ -> saveHero(v.text.toString().trim()); false }
        btn("Enter") { saveHero(heroBox.text.toString().trim()) }
        // your in-game name: finds YOUR card on the loading screen and the scoreboard (v36.84)
        val nameBox = field("My player name (in-game name, as written in the game)", com.example.mlbbresearch.game.PlayerName.get(), false) { }
        nameBox.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        fun saveName(v: String) { com.example.mlbbresearch.game.PlayerName.set(v); toast(if (v.isBlank()) "Player name cleared: your card = the first card" else "Player name: $v") }
        nameBox.setOnEditorActionListener { v, _, _ -> saveName(v.text.toString().trim()); false }
        btn("Enter name") { saveName(nameBox.text.toString().trim()) }
        val heroInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            val p = HeroDb.profile(h)
            heroInfo.text = "Using: ${h ?: "unknown"} (${GameStateTracker.ownHeroSource()})\n" +
                "Role ${p.role.name.lowercase()}, ${if (HeroDb.isRanged(h)) "ranged" else "melee"}, dash ${HeroDb.dashSlot(h) ?: "none"}, ultimate: ${p.ult.name.lowercase()}" +
                (com.example.mlbbresearch.game.CooldownTracker.ultCooldown(h)?.let { " (cooldown ${it.toInt()} s)" } ?: "") + "\n" +
                "Learned aggression adjust: ${"%+.2f".format(Cfg.learnedBias(h ?: "unknown"))}\n" +
                "Heroes whose looks are learned: ${IconMemory.heroCount()} (portrait), ${com.example.mlbbresearch.vision.HeroVerifier.learnedCount()} (skill icons)\n" +
                "Hero check: ${com.example.mlbbresearch.vision.HeroVerifier.status}"
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
            spellInfo.text = "Using: ${GameStateTracker.spellDisplay() ?: GameStateTracker.spell() ?: "not known yet"} (${GameStateTracker.spellSource()}). " +
                "Spells whose icon is learned: ${IconMemory.spellCount()}"
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
                "${st.camps.count { !it.enemy }} of your camps up (${st.camps.count { it.buff && !it.enemy }} buffs), ${st.camps.count { it.enemy }} enemy camps seen\nJungle path: ${com.example.mlbbresearch.game.JungleNav.status}\n${com.example.mlbbresearch.ai.TacticalEngine.invadeStatus}"
        }
        val laneInfo = text("", 13f)
        val rotModeBtn = btn("") {
            val lbl = com.example.mlbbresearch.game.RoleProfile.cycleMode()
            toast("Rotations & Pathing: $lbl")
        }
        val rotMetaInfo = text("", 12.5f, color = "#9AA0A6")
        upd {
            val (lane, why) = GameStateTracker.lane()
            laneInfo.text = "Playing: ${lane.label} ($why)"
            rotModeBtn.text = "Rotations & Pathing: ${com.example.mlbbresearch.game.RoleProfile.modeLabel()}  (tap to change)"
            rotMetaInfo.text = com.example.mlbbresearch.game.RoleProfile.activeRotationSummary(GameStateTracker.state)
        }
        val tuneInfo = text("", 12f, color = "#9AA0A6")
        upd { tuneInfo.text = "Learned by itself: base to lane ${Cfg.laneTravelSec} s, enemy tower range ${Cfg.towerRangePct}% of the minimap" }

        title("ENEMY TEAM")
        val enemyInfo = text("", 13f)
        upd { enemyInfo.text = com.example.mlbbresearch.game.EnemyIntel.text() }

        title("SKILL COMBOS (MANUAL OR BUILT-IN)")
        text("Uses your Manual Combo if set for the selected hero; if Manual Combo is empty, uses the Built-In Optimized Multi-Combo Library (online combo overrides are disabled).", 12f, color = "#9AA0A6")
        switch("Automatic situational combo selection (Burst / Chase / Poke / Pre-Lv4 / Retreat)", Cfg.autoSituationalCombo) {
            Cfg.autoSituationalCombo = it
        }
        fun selectedComboHero(): String? =
            Cfg.manualHero.trim().takeIf { it.isNotEmpty() }?.let { HeroDb.match(it) ?: it }
                ?: GameStateTracker.ownHero()
                ?: HeroDb.match(Cfg.myHero)

        val comboCard = card()
        val comboInfo = cardText(comboCard, 13f, true)
        val comboBuiltInList = cardNote(comboCard)
        val manualComboBox = field("Manual skill combo for selected hero (empty = use Built-In, e.g. s1 > s2 > a > ult > a)", Cfg.manualCombo(selectedComboHero()).orEmpty(), false) { }
        manualComboBox.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        var lastComboHeroLoaded: String? = selectedComboHero()
        var variantIdx = 0

        fun saveManualComboForHero(rawText: String) {
            val h = selectedComboHero()
            if (h == null) {
                toast("Select or enter a hero under YOUR HERO first")
                return
            }
            val cleaned = rawText.trim()
            if (cleaned.isEmpty()) {
                Cfg.setManualCombo(h, null)
                toast("$h: Manual combo cleared — using Built-In combos")
            } else {
                val parsed = ComboBook.parse(cleaned)
                if (parsed.isEmpty()) {
                    toast("Use s1, s2, s3, ult, a (basic attack), or spell separated by >")
                    return
                }
                val formatted = ComboBook.format(parsed)
                Cfg.setManualCombo(h, formatted)
                manualComboBox.setText(formatted)
                toast("$h: Saved Manual Combo ($formatted)")
            }
        }
        manualComboBox.setOnEditorActionListener { v, _, _ -> saveManualComboForHero(v.text.toString()); false }

        fun appendComboToken(tok: String) {
            val cur = manualComboBox.text.toString().trim()
            val next = if (cur.isEmpty()) tok else "$cur > $tok"
            manualComboBox.setText(next)
            manualComboBox.setSelection(next.length)
        }
        val quickRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        for ((lbl, tok) in listOf("+ S1" to "s1", "+ S2" to "s2", "+ S3" to "s3", "+ Ult" to "ult", "+ BA (a)" to "a", "+ Spell" to "spell")) {
            quickRow1.addView(Button(this).apply {
                text = lbl; isAllCaps = false; textSize = 11.5f; styleButton(this)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { appendComboToken(tok) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        content.addView(quickRow1)

        btn("Save manual combo for this hero") { saveManualComboForHero(manualComboBox.text.toString()) }
        btn("Clear manual combo (use Built-In)") {
            val h = selectedComboHero()
            if (h != null) Cfg.setManualCombo(h, null)
            manualComboBox.setText("")
            toast("${h ?: "Hero"}: using Built-In optimized combos")
        }
        btn("Copy next Built-In combo variant into box") {
            val h = selectedComboHero()
            val vars = ComboBook.allVariants(h)
            if (vars.isNotEmpty()) {
                val (vName, vSeq) = vars[variantIdx % vars.size]
                variantIdx++
                manualComboBox.setText(vSeq)
                toast("${h ?: "Hero"} ($vName): $vSeq")
            }
        }
        btn("Test active combo now") {
            toast(ActionExecutor.testCombo())
        }
        upd {
            val h = selectedComboHero()
            if (h != lastComboHeroLoaded) {
                lastComboHeroLoaded = h
                variantIdx = 0
                if (!manualComboBox.hasFocus()) {
                    manualComboBox.setText(Cfg.manualCombo(h).orEmpty())
                }
            }
            val (combo, from) = ComboBook.text(h)
            val vars = ComboBook.allVariants(h)
            comboInfo.text = "Hero: ${h ?: "unknown"}  ·  Active: $combo\nSource: $from\nRunner: ${ComboRunner.status}"
            comboBuiltInList.text = "Situational Built-In Combos for ${h ?: "hero"}:\n" +
                ComboBook.describeAllCombos(h) +
                (if (vars.isNotEmpty()) "\n\nAll Built-In Variants (${vars.size}):\n" + vars.joinToString("\n") { "• ${it.first}: ${it.second}" } else "")
        }

        title("IN-GAME SCENERY & MINIMAP SYNCHRONIZATION")
        text("Synchronizes 3D screen scenery (towers, monster pits, bushes, walls) with the 2D minimap on every frame. Saved persistently on your phone (mlbb-scenery-sync.json in /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage, or Download/MLBB-Research without all files access) across app uninstall/reinstall — only deleted when you press Clear saved map scenery or Reset minimap sync offsets.", 12f, color = "#9AA0A6")
        switch("Auto-sync 3D screen scenery with minimap landmarks", Cfg.sceneryAutoSync) { Cfg.sceneryAutoSync = it }
        val sceneryInfo = text("", 13f)
        val camPresetBtn = btn("") {
            val lbl = com.example.mlbbresearch.game.SceneryMap.cycleCameraPreset()
            toast("Camera preset: $lbl")
        }
        btn("Sync 3D Scenery & Minimap Now") {
            val msg = com.example.mlbbresearch.game.SceneryMap.syncNow(GameStateTracker.state)
            toast(msg)
        }
        val roamBtn = btn("") {
            val msg = com.example.mlbbresearch.game.SceneryMap.toggleRoam()
            toast(msg)
        }
        btn("Reset scenery-minimap sync offsets") {
            toast(com.example.mlbbresearch.game.SceneryMap.resetSyncCalibration())
        }
        btn("Clear saved map scenery") {
            com.example.mlbbresearch.game.SceneryMap.clear()
            toast("Scenery map cleared")
        }
        upd {
            val active = com.example.mlbbresearch.game.SceneryMap.roamActive
            camPresetBtn.text = "Camera Height: ${com.example.mlbbresearch.game.SceneryMap.cameraPresetLabel()}  (tap to change)"
            roamBtn.text = if (active) "Stop scenery calibration roam" else "Start scenery calibration roam (Practice Mode)"
            sceneryInfo.text = "Memorized: ${com.example.mlbbresearch.game.SceneryMap.summary()}\n" +
                "Roam status: ${com.example.mlbbresearch.game.SceneryMap.status}\n" +
                com.example.mlbbresearch.game.SceneryMap.syncStatus()
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
        text("Retribution: Turtle / Lord at ${Cfg.retriBossPct}%, buffs at ${Cfg.retriCreepPct}% (from your rulebook).", 12f, color = "#9AA0A6")
        val objInfo = text("", 13f)
        upd {
            val st = GameStateTracker.state
            val b = st.boss
            objInfo.text = "Monsters on screen: ${st.monsters.size}" +
                (if (b != null) " | ${LaneLogic.objectiveName(st.matchSec)} HP ${(b.hp * 100).toInt()}%" else "") +
                (if (st.matchSec >= 0) " | match time ${st.matchSec / 60}:${"%02d".format(st.matchSec % 60)}" else "")
        }

        title("BEHAVIOUR")
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
        val towerInfo = text("", 13f)
        upd {
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
        text("Regen below ${Cfg.regenHpPct}% HP, Recall below ${Cfg.recallHpPct}% (from your rulebook); a nearby enemy cancels them.", 12f, color = "#9AA0A6")
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
                !com.example.mlbbresearch.ai.GroqClient.anyKey -> toast("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Auto-Pilot-Storage folder")
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
        val learnInfo = text("", 13f)
        DemoRecorder.refreshTotal()
        upd {
            learnInfo.text = "Recorded moments: ${DemoRecorder.total} (${DemoRecorder.recorded} of you this session)\n" +
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
        switch("Send a screenshot with plan questions (slower)", Cfg.strategyWithImage) { Cfg.strategyWithImage = it }

        // (Vision tuning moved to the Home page - v36.93)
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
        title("MATCH TYPE")
        val phase = text("", 13f, color = "#9AA0A6")
        upd { phase.text = "Detected by itself on the hero-select screen (a ban stage = ranked): now ${if (Cfg.ranked) "RANKED" else "STANDARD"}" +
            (if (!GemmaEngine.ready) " (needs the Gemma model to tell them apart)" else "") + "\nScreen now: ${GameStateTracker.state.phase.label}" }

        title("STANDARD MATCH")
        text("Picks your hero (and spell / emblem), then confirms. Needs Drafter + Autonomous.", 13f)
        switch("Tap my hero", Cfg.stdPickHero) { Cfg.stdPickHero = it }
        switch("Change battle spell", Cfg.stdChangeSpell) { Cfg.stdChangeSpell = it }
        switch("Change emblem set", Cfg.stdChangeEmblem) { Cfg.stdChangeEmblem = it }
        btn("Test the standard pick now") {
            if (AccessibilityController.instance == null) toast("Turn on the accessibility service first")
            else ActionExecutor.standardSelect(GameStateTracker.ownHero())
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
        // the defense to build once the enemy team's damage types are known (v36.73)
        title("Enemy damage")
        val defT = text("", 13f)
        upd {
            val en = GameStateTracker.enemyTeam
            defT.text = if (en.isEmpty()) "Shown once the enemy heroes are known." else
                en.joinToString { "$it (${HeroDb.damageText(it).substringBefore(" (")})" } + "\n" + (com.example.mlbbresearch.game.ItemClass.defenseAgainst(en) ?: "")
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
        // ---- experimental builds (BuildLab) ----
        run {
            val lab = com.example.mlbbresearch.ai.BuildLab
            val hero = GameStateTracker.ownHero() ?: HeroDb.match(Cfg.manualHero) ?: HeroDb.match(Cfg.myHero)
            val xc = card()
            cardHeader(xc, "Experimental builds", hero ?: "no hero", T.GOLD)
            cardText(xc, 12f).text = "Builds that try something different from the meta, made from ${hero ?: "your hero"}'s real skills and passive, its kit, " +
                "how it plays, the original server's patch and meta, and what the Library knows about each item. Real items and spells only."
            val xs = lab.builds(hero)
            if (xs.isEmpty()) cardText(xc, 12.5f).text = if (hero == null) "Choose your hero on the Player page first." else "None made yet."
            else cardText(xc, 11.5f).text = "Made for patch ${lab.madeFor(hero).ifBlank { "?" }}" + (lab.chosen(hero)?.let { " · in use: ${it.name}" } ?: "")
            for ((i, b) in xs.withIndex()) {
                xc.addView(TextView(this).apply { text = "${i + 1}. ${b.name}"; textSize = 14.5f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, dp(2)) })
                IconGrid(xc, hero = false, sizeDp = 40).set(b.items)
                cardText(xc, 12f).text = buildString {
                    append(b.idea)
                    if (b.spell != null) append("\nSpell: ").append(b.spell)
                    if (b.emblem.isNotBlank()) append(if (b.spell != null) "  ·  " else "\n").append("Emblem: ").append(b.emblem)
                    if (b.whenToUse.isNotBlank()) append("\nWhen: ").append(b.whenToUse)
                    if (b.risk.isNotBlank()) append("\nRisk: ").append(b.risk)
                }
                val inUse = lab.chosen(hero)?.name == b.name
                xc.addView(Button(this).apply {
                    text = if (inUse) "In use - back to the meta build" else "Use this build"; isAllCaps = false; textSize = 13f
                    setOnClickListener { if (hero != null) { lab.choose(hero, if (inUse) null else i); show("build") } }
                })
            }
            val gen = Button(this).apply {
                text = if (lab.busy.isNotEmpty()) "Working on ${lab.busy}…" else if (xs.isEmpty()) "Make experimental builds" else "Make new experimental builds"
                isAllCaps = false
                setOnClickListener {
                    if (hero == null) { toast("Choose your hero on the Player page first"); return@setOnClickListener }
                    toast("Asking the online model (uses tokens)…")
                    lab.generate(hero) { m -> runOnUiThread { toast(m); show("build") } }
                }
            }
            styleButton(gen, primary = true)
            xc.addView(gen)
        }
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
            val xb = com.example.mlbbresearch.ai.BuildLab.chosen(hero)
            // Advanced equipment only, filtered by hero damage type & tank defensive rule (v37.01)
            val items = BuildBook.forHero(hero, xb?.items ?: meta?.build?.takeIf { it.isNotEmpty() } ?: BuildBook.primary(role))
            bHead.text = (hero ?: "Your hero") + " build"
            bChip.text = if (xb != null) "experimental" else if (meta != null && meta.build.isNotEmpty()) "online meta" else role.name.lowercase()
            buildGrid.set(items)
            bNote.text = buildString {
                if (xb != null) {
                    append(xb.name)
                    if (xb.spell != null) append("  ·  Spell: ").append(xb.spell)
                    if (xb.emblem.isNotBlank()) append("  ·  Emblem: ").append(xb.emblem)
                } else if (meta != null) {
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
            } + (com.example.mlbbresearch.game.ItemClass.defenseAgainst(enemies)?.let { "\n$it" } ?: "")
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
        text("Swaps under ${Cfg.qbHpPercent}% HP with ${Cfg.qbMinGold}+ gold: Immortality › Winter Crown › Rose Gold Meteor › Guardian Helmet.", 12f, color = T.MUTED)
        val live = text("", 13f, color = T.MUTED)
        upd {
            val s = GameStateTracker.state
            live.text = "HP ${if (s.selfHp < 0) "?" else "${(s.selfHp * 100).toInt()}%"}  ·  items ${if (s.items < 0) "?" else s.items}/6  ·  " +
                "gold ${if (s.gold < 0) "?" else s.gold}  ·  ${QuickBuySwap.status}"
        }
        btn("Test the swap now (buys and sells for real)") {
            if (AccessibilityController.instance == null) toast("Turn on the accessibility service first") else QuickBuySwap.start()
        }

        title("Builds per role")
        text("Used when the online meta has no build for your hero yet (the hero's own build from the meta comes first).", 12f, color = T.MUTED)
        for (r in Role.values()) {
            if (r == Role.UNKNOWN) continue
            text(r.name.lowercase().replaceFirstChar { it.uppercase() }, 13f, bold = true)
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(holder)
            val grid = IconGrid(holder, hero = false, sizeDp = 32)
            val items = Cfg.roleBuild(r.name, BuildBook.defaults[r] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            grid.set(com.example.mlbbresearch.game.ItemClass.advancedOnly(items))
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
    //  LIBRARY: everything the app knows - heroes, battle spells, equipment - with icons
    // =====================================================================

    private var libTab = "heroes"
    private var libFilter = ""

    private fun libraryPage() {
        val ll = com.example.mlbbresearch.learning.LibraryLearner
        val lc = card()
        cardHeader(lc, "What the app knows")
        val lt = cardText(lc, 13f)
        upd {
            lt.text = ll.counts() + "\n" + (if (ll.running) "Learning: " else "Last run: ") + ll.progress +
                com.example.mlbbresearch.app.IconCache.loadAllStatus.takeIf { it != "-" }?.let { "\nIcons: $it" }.orEmpty() +
                com.example.mlbbresearch.app.IconCache.lastProblem.takeIf { it != "-" }?.let { "\nPictures: $it (tried again every 2 min)" }.orEmpty()
        }
        val lb = btn("") {
            if (ll.running) { ll.stop(); toast("Stopping after the current question") }
            else ll.start { m -> runOnUiThread { toast(m) } }
        }
        upd { lb.text = if (ll.running) "Stop learning" else "Learn the whole database online" }
        styleButton(lb, primary = true)
        val loadIconsBtn = btn("") {
            if (com.example.mlbbresearch.app.IconCache.loadingAll) {
                toast("Already loading icons: ${com.example.mlbbresearch.app.IconCache.loadAllStatus}")
            } else {
                toast("Force-loading all Library icons (heroes, skills, spells & equipment)…")
                com.example.mlbbresearch.app.IconCache.forceLoadAll(this) { msg ->
                    runOnUiThread { toast(msg); show("library") }
                }
            }
        }
        upd {
            loadIconsBtn.text = if (com.example.mlbbresearch.app.IconCache.loadingAll) "Loading all icons… (${com.example.mlbbresearch.app.IconCache.loadAllStatus})" else "Load all icons"
            styleButton(loadIconsBtn, primary = !com.example.mlbbresearch.app.IconCache.loadingAll)
        }
        text("Skills come from each hero's wiki page (real skills and passive only; the online model just copies them and every name is checked against the page). Every hero's skills and online guide, then every item - skipping what is known, waiting out Groq's limit by itself (all of it takes a few days of the app being open).", 12f, color = T.MUTED)

        // tabs
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, label) in listOf("heroes" to "Heroes", "spells" to "Spells", "items" to "Equipment", "terms" to "Terms")) {
            tabs.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f
                background = ripple(if (k == libTab) T.ACCENT else T.SURF2, 16)
                setTextColor(Color.parseColor(if (k == libTab) "#FFFFFF" else T.TEXT))
                setOnClickListener { libTab = k; show("library") }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(8), dp(3), dp(4)) })
        }
        content.addView(tabs)
        val search = EditText(this).apply {
            hint = "Search"; setText(libFilter); setTextColor(c(T.TEXT)); setHintTextColor(c(T.MUTED))
            background = shape(T.SURF2, 10, T.STROKE); setPadding(dp(12), dp(8), dp(12), dp(8)); isSingleLine = true
        }
        content.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        fun row(name: String, hero: Boolean, status: String, ok: Boolean, onLong: (() -> Unit)? = null,
                icons: (() -> List<Pair<String, String>>)? = null, file: String? = null, detail: () -> String) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = shape(T.SURF, 12, T.STROKE); setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val px = dp(40)
            top.addView(com.example.mlbbresearch.app.IconCache.view(this, name, hero, px, file), LinearLayout.LayoutParams(px, px))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            col.addView(TextView(this).apply { text = name; textSize = 15f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD) })
            col.addView(TextView(this).apply { text = (if (ok) "● " else "○ ") + status; textSize = 12f; setTextColor(c(if (ok) T.GREEN else T.MUTED)) })
            top.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(top)
            // the hero's passive and skills as icons (the wiki's skill pictures), shown with the details
            val strip = android.widget.HorizontalScrollView(this).apply { visibility = View.GONE; isHorizontalScrollBarEnabled = false }
            val stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            strip.addView(stripRow)
            box.addView(strip)
            val det = TextView(this).apply { textSize = 12.5f; setTextColor(c(T.TEXT)); setLineSpacing(0f, 1.15f); setPadding(0, dp(8), 0, 0); visibility = View.GONE }
            box.addView(det)
            box.setOnClickListener {
                if (det.visibility == View.GONE) {
                    det.text = detail(); det.visibility = View.VISIBLE
                    fun fill() {
                        val ic = icons?.invoke().orEmpty()
                        stripRow.removeAllViews()
                        for ((skill, label) in ic) {
                            val cell = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 0, dp(10), 0) }
                            val sz = dp(46)
                            // "file:<name>" = the real picture read from the wiki page
                            val file = skill.removePrefix("file:").takeIf { skill.startsWith("file:") }
                            cell.addView(com.example.mlbbresearch.app.IconCache.view(this@MainActivity, if (file != null) label else skill, false, sz, file), LinearLayout.LayoutParams(sz, sz))
                            cell.addView(TextView(this@MainActivity).apply { text = label; textSize = 10.5f; setTextColor(c(T.MUTED)); gravity = Gravity.CENTER; maxWidth = dp(70) })
                            stripRow.addView(cell)
                        }
                        strip.visibility = if (ic.isEmpty()) View.GONE else View.VISIBLE
                    }
                    fill()
                    // the real skill pictures are read from the hero's wiki page the first time (no tokens)
                    if (hero && !com.example.mlbbresearch.game.WikiSkills.iconsKnown(name)) Thread {
                        val m = try { com.example.mlbbresearch.game.WikiSkills.fetchIcons(name) } catch (e: Exception) { null }
                        if (m != null) runOnUiThread { if (strip.visibility == View.VISIBLE) fill() }
                    }.start()
                } else { det.visibility = View.GONE; strip.visibility = View.GONE }
            }
            if (onLong != null) box.setOnLongClickListener { onLong(); true }
            list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        // the passive's and every skill's picture on the wiki: "<Skill name>.png" (like Fighting_Spirit.png,
        // The_Power_of_Axe.png on Aulus's page) - only real names learned from the wiki
        fun heroSkillIcons(h: String): List<Pair<String, String>> {
            val kit = com.example.mlbbresearch.game.SkillBook.get(h)
            val out = ArrayList<Pair<String, String>>()
            // the real pictures from the wiki page (passive + every skill), when read
            val real = com.example.mlbbresearch.game.WikiSkills.icons(h)
            if (real.isNotEmpty()) {
                val hints = com.example.mlbbresearch.game.HeroKit.buttonHints[h]
                real["passive"]?.let { out.add("file:$it" to "Passive") }
                // every button of the hero's kit: the wiki picture, else (v36.68) the picture named after
                // the skill - unless that name is also an item / spell (Layla, Arlott)
                for (sl in listOf("s1", "s2", "s3", "ult").filter { it in real || it in com.example.mlbbresearch.game.HeroKit.slots(h) }) {
                    val lb = hints?.get(sl) ?: when (sl) { "s1" -> "Skill 1"; "s2" -> "Skill 2"; "s3" -> "Skill 3"; else -> "Ultimate" }
                    val f = real[sl]
                    if (f != null) out.add("file:$f" to lb)
                    else kit[sl]?.name?.takeIf { n -> n.isNotBlank() && n != sl && com.example.mlbbresearch.game.ItemDb.canonical(n) == null &&
                        com.example.mlbbresearch.game.SpellDb.match(n) == null }?.let { n -> out.add(n to lb) }
                }
                return out
            }
            kit.passiveText.substringBefore(" - ").trim().takeIf { it.isNotBlank() && it.length < 40 && kit.passiveText.contains(" - ") }?.let { out.add(it to "Passive") }
            val hints = com.example.mlbbresearch.game.HeroKit.buttonHints[h]
            for (sl in com.example.mlbbresearch.game.HeroKit.slots(h)) kit[sl]?.let { k ->
                if (k.name.isNotBlank() && k.name != sl) out.add(k.name to (hints?.get(sl) ?: when (sl) { "s1" -> "Skill 1"; "s2" -> "Skill 2"; "s3" -> "Skill 3"; else -> "Ultimate" }))
            }
            return out
        }

        fun heroDetail(h: String): String = buildString {
            ll.lastError(h, "skills")?.takeIf { !com.example.mlbbresearch.game.SkillBook.known(h) }?.let { append("⚠ Skills could not be learned: ").append(it).append('\n') }
            ll.lastError(h, "guide")?.takeIf { !com.example.mlbbresearch.game.MetaStore.has(h) }?.let { append("⚠ Guide could not be learned: ").append(it).append('\n') }
            append("(Long-press this hero to learn its skills and guide now.)\n")
            com.example.mlbbresearch.game.WikiSkills.iconsDebug(h)?.let { d ->
                val got = com.example.mlbbresearch.game.WikiSkills.icons(h).keys
                if (com.example.mlbbresearch.game.HeroKit.slots(h).any { it !in got }) append("⚠ Skill pictures on the wiki page: ").append(d).append('\n')
            }
            val kit = com.example.mlbbresearch.game.SkillBook.get(h)
            val pb = com.example.mlbbresearch.game.HeroPlaybook.of(h)
            append(HeroDb.role(h).name.lowercase()).append(if (HeroDb.isRanged(h)) ", ranged" else ", melee")
            append(" · dash ").append(HeroDb.dashSlot(h) ?: "none")
            com.example.mlbbresearch.game.CooldownTracker.ultCooldown(h)?.let { append(" · ultimate cooldown ").append(it.toInt()).append(" s") }
            val dmgT = HeroDb.damageText(h)
            append("\nDamage type: ").append(dmgT).append(" · its equipment: ")
                .append(if (dmgT.startsWith("Physical + Magic")) "Attack or Magic (Adaptive Attack fits both)" else if (dmgT.startsWith("Magic")) "Magic (Magic Power) - Adaptive Attack fits too" else "Attack (Physical Attack) - Adaptive Attack fits too")
            append("\nPassive: ").append(kit.passiveText.ifBlank { "not learned yet (every hero has its own: learn it from the wiki)" }.take(320))
            append("\nSkills (").append(kit.source).append("):")
            append("\nKit: ").append(com.example.mlbbresearch.game.HeroKit.describe(h))
            for (sl in com.example.mlbbresearch.game.HeroKit.slots(h)) kit[sl]?.let { k ->
                append("\n• ").append(sl).append(if (k.name.isNotBlank() && k.name != sl) " ${k.name}" else "").append(": ")
                append(k.kind.name.lowercase()).append(", range ").append((k.range * 100).toInt()).append("%")
                if (k.hardCc) append(", hard CC") else if (k.slow) append(", slow")
                if (k.mobility) append(", dash"); if (k.heal) append(", heal"); if (k.shield) append(", shield")
                append(", ").append(k.mode.name.lowercase())
                if (k.cooldownSec > 0f) append(", ").append(k.cooldownSec.toInt()).append(" s")
                com.example.mlbbresearch.game.GameTerms.ccIn(k.description).takeIf { it.isNotEmpty() }?.let { append("\n   crowd control: ").append(it.joinToString()) }
                if (k.description.isNotBlank() && k.description != k.name) append("\n   ").append(k.description.take(400))
            }
            append("\nHP back from hitting: ").append(com.example.mlbbresearch.game.Sustain.describe(h))
            append("\nPlays as: ").append(pb.style).append(" · combo ").append(pb.combo)
            val m = com.example.mlbbresearch.game.MetaStore.hero(h)
            if (m != null) {
                append("\nGuide (").append(m.patch).append("): build ").append(m.build.joinToString())
                if (m.emblem.isNotBlank()) append("\nEmblem: ").append(m.emblem)
                m.spell?.let { append(" · spell ").append(it) }
                if (m.playstyle.isNotBlank()) append("\nHow to play: ").append(m.playstyle)
                if (m.counters.isNotEmpty()) append("\nCountered by: ").append(m.counters.joinToString())
            } else append("\nGuide: not learned yet")
        }

        val spellUse = mapOf(
            "Retribution" to "AI: buffs at ${Cfg.retriCreepPct}%, Turtle / Lord at ${Cfg.retriBossPct}%, close hero fights; never minions or small camps",
            "Execute" to "AI: only to finish a target under the execute line", "Flicker" to "AI: escape, catch a fleeing kill, pass a wall",
            "Flameshot" to "AI: finish under 30% or push away a chaser", "Petrify" to "AI: 2+ enemies close, or one at under 60%, or running",
            "Inspire" to "AI: in a fight with the target in reach", "Sprint" to "AI: running, or chasing a low target just out of reach",
            "Aegis" to "AI: under 45% HP with enemies near", "Revitalize" to "AI: under 50% HP with enemies near",
            "Purify" to "AI: just hit, enemy close, under 45%", "Vengeance" to "AI: in a fight, just hit, under 75%",
            "Arrival" to "AI: not used by itself")
        val q = libFilter.trim().lowercase()
        fun ok(n: String) = q.isEmpty() || q in n.lowercase()
        when (libTab) {
            "heroes" -> for (h in HeroDb.allNames.filter { ok(it) }) {
                val kit = com.example.mlbbresearch.game.SkillBook.get(h)
                val skills = com.example.mlbbresearch.game.SkillBook.known(h)
                val guide = com.example.mlbbresearch.game.MetaStore.has(h)
                val skillsSt = when {
                    skills -> if (kit.source.startsWith("wiki")) "from the wiki" else kit.source
                    com.example.mlbbresearch.game.SkillBook.unverified(h) -> "AI-written, not trusted (re-learn)"
                    !kit.source.startsWith("role defaults") -> "built in, unchecked"
                    else -> "not learned"
                }
                val errS = if (!skills) ll.lastError(h, "skills") else null
                val errG = if (!guide) ll.lastError(h, "guide") else null
                val st = (if (errS != null || errG != null) "⚠ last try failed · " else "") + HeroDb.role(h).name.lowercase() + " · skills " + skillsSt +
                    " · guide " + (if (guide) "learned" else "not yet")
                row(h, true, st, skills && guide, onLong = {
                    toast("Learning $h now (wiki skills + guide)…")
                    ll.learnOne(h) { m -> runOnUiThread { toast(m); show("library") } }
                }, icons = { heroSkillIcons(h) }) { heroDetail(h) }
            }
            "spells" -> {
                for (sp in SpellDb.names.filter { ok(it) }) {
                    val cd = com.example.mlbbresearch.game.SpellInfo.cooldown[sp]
                    row(sp, false, "cooldown ${cd?.toInt() ?: "?"} s · built in", true) {
                        (com.example.mlbbresearch.game.SpellInfo.effect[sp] ?: "-") + "\n" + (spellUse[sp] ?: "") +
                            "\nOn an enemy: " + com.example.mlbbresearch.game.EnemyIntel.spellNote(sp)
                    }
                }
                // the Retribution kinds (from the jungle item; seen only in a match)
                // (your guide - v36.76 - and the wiki's exact effect text; never an AI's)
                val ic = com.example.mlbbresearch.game.ItemClass
                val rs = com.example.mlbbresearch.game.ItemDb.retributions.filter { ok(it) }
                if (rs.isNotEmpty()) {
                    list.addView(TextView(this).apply { text = "Blessed Retribution"; textSize = 15f; setTextColor(c(T.ACCENT)); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(14), 0, dp(2)) })
                    list.addView(TextView(this).apply { text = ic.RETRI_INTRO; textSize = 12f; setTextColor(c(T.MUTED)); setPadding(dp(6), 0, 0, dp(2)) })
                }
                for (sp in rs) {
                    val wiki = ic.retributionText(sp)
                    val guide = ic.retributionGuide[sp]
                    row(sp, false, "Blessed Retribution · best for: " + (guide?.second?.joinToString(", ") ?: "-"), true) {
                        (guide?.first?.joinToString("\n") { "• $it" } ?: "") +
                            "\nBest for: " + (guide?.second?.joinToString(", ") ?: "-") + "\n" +
                            (if (wiki == null) "Exact effect (wiki): read when the Library learns (\"Learn the whole database online\")."
                            else "Exact effect (wiki): $wiki") +
                            "\nAI: used like Retribution (buffs at ${Cfg.retriCreepPct}%, Turtle / Lord at ${Cfg.retriBossPct}%, close hero fights)"
                    }
                }
            }
            "terms" -> {
                // the game's own Tutorial > Terms: attributes, states (crowd control by level, buffs), common terms
                val gt = com.example.mlbbresearch.game.GameTerms
                for ((title, items) in listOf("Attribute Guide" to gt.attributes, "State Guide (crowd control and buffs)" to gt.states, "Common Terms" to gt.common)) {
                    val shown = items.filter { ok(it.name) || ok(it.text) }
                    if (shown.isEmpty()) continue
                    list.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(c(T.ACCENT)); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(14), 0, dp(2)) })
                    var group = ""
                    for (t in shown) {
                        if (t.group.isNotEmpty() && t.group != group) {
                            group = t.group
                            list.addView(TextView(this).apply { text = group; textSize = 12f; setTextColor(c(T.MUTED)); setPadding(dp(6), dp(8), 0, 0) })
                        }
                        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(T.SURF, 12, T.STROKE); setPadding(dp(10), dp(8), dp(10), dp(8)) }
                        box.addView(TextView(this).apply { text = t.name; textSize = 14f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD) })
                        box.addView(TextView(this).apply { text = t.text; textSize = 12.5f; setTextColor(c(T.TEXT)); setLineSpacing(0f, 1.15f) })
                        list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
                    }
                }
            }
            else -> {
                // v36.73: Advanced (with its Active-skill items) / Intermediate / Basic, each item with its
                // stat types; then the roaming boots' blessings (not equipment: no cost, nothing to learn)
                val idb = com.example.mlbbresearch.game.ItemDb
                val ic = com.example.mlbbresearch.game.ItemClass
                fun head(t: String, sub: Boolean = false) = list.addView(TextView(this).apply {
                    text = t; textSize = if (sub) 13f else 15f; setTextColor(c(if (sub) T.MUTED else T.ACCENT)); setTypeface(null, Typeface.BOLD)
                    setPadding(dp(if (sub) 6 else 4), dp(if (sub) 8 else 14), 0, dp(2)) })
                fun itemRow(n: String) {
                    val inf = ic.info(n)
                    val lib = com.example.mlbbresearch.game.ItemBook.get(n)
                    val price = inf.price.takeIf { it >= 0 } ?: lib?.price?.takeIf { it >= 0 }
                    row(n, false, ic.kindsText(n) + (price?.let { if (it == 0) " · no cost" else " · $it gold" } ?: ""), inf.tier != null) {
                        "Tier: ${inf.tier?.label ?: "not known yet"} · types: ${ic.kindsText(n)} (from ${inf.source})" +
                            (ic.wikiStats(n)?.let { "\nStats (wiki): $it" } ?: lib?.let { "\nStats (learned): ${it.stats.ifBlank { "-" }}" } ?: "") +
                            (idb.actives[n]?.let { "\nActive: $it" } ?: "") +
                            (lib?.effect?.takeIf { it.isNotBlank() }?.let { "\nEffect: $it" } ?: "") +
                            (com.example.mlbbresearch.game.Sustain.itemKind(n)?.let { k -> "\nHP back from hitting: $k" } ?: "")
                    }
                }
                val all = idb.current.filter { ok(it) && !ic.isBlessing(it) }
                val byTier = all.groupBy { ic.tier(it) }
                val advanced = byTier[com.example.mlbbresearch.game.ItemClass.Tier.ADVANCED].orEmpty()
                if (advanced.isNotEmpty()) {
                    head("Advanced equipment (used in builds)")
                    val acts = advanced.filter { idb.isActive(it) }
                    if (acts.isNotEmpty()) { head("Active skills (items you press in a match)", true); acts.forEach { itemRow(it) } }
                    for (k in com.example.mlbbresearch.game.ItemClass.Kind.values()) {
                        val inK = advanced.filter { !idb.isActive(it) && ic.info(it).kinds.firstOrNull() == k }
                        if (inK.isNotEmpty() || k == com.example.mlbbresearch.game.ItemClass.Kind.ATTACK || k == com.example.mlbbresearch.game.ItemClass.Kind.MAGIC) {
                            head(k.label, true)
                            inK.forEach { itemRow(it) }
                            // Under Physical Attack: Physical Penetration & Physical Attack Speed classifications (v37.01)
                            if (k == com.example.mlbbresearch.game.ItemClass.Kind.ATTACK) {
                                val physPen = all.filter { ic.isPhysicalPenetration(it) }
                                if (physPen.isNotEmpty()) {
                                    head("↳ Physical Penetration (under Physical Attack)", true)
                                    physPen.forEach { itemRow(it) }
                                }
                                val physAspd = all.filter { ic.isPhysicalAttackSpeed(it) }
                                if (physAspd.isNotEmpty()) {
                                    head("↳ Attack Speed - Physical (best for Marksmen / basic-attack heroes)", true)
                                    physAspd.forEach { itemRow(it) }
                                }
                            }
                            // Under Magic Power: Magic Penetration & Magic Attack Speed classifications (v37.01)
                            if (k == com.example.mlbbresearch.game.ItemClass.Kind.MAGIC) {
                                val magPen = all.filter { ic.isMagicPenetration(it) }
                                if (magPen.isNotEmpty()) {
                                    head("↳ Magic Penetration (under Magic Power)", true)
                                    magPen.forEach { itemRow(it) }
                                }
                                val magAspd = all.filter { ic.isMagicAttackSpeed(it) }
                                if (magAspd.isNotEmpty()) {
                                    head("↳ Attack Speed - Magic (best for Magic basic-attack heroes)", true)
                                    magAspd.forEach { itemRow(it) }
                                }
                            }
                        }
                    }
                    advanced.filter { !idb.isActive(it) && ic.info(it).kinds.isEmpty() }.takeIf { it.isNotEmpty() }?.let { head("Other", true); it.forEach { n -> itemRow(n) } }
                }
                for ((t, label) in listOf(com.example.mlbbresearch.game.ItemClass.Tier.INTERMEDIATE to "Intermediate equipment",
                        com.example.mlbbresearch.game.ItemClass.Tier.BASIC to "Basic equipment", com.example.mlbbresearch.game.ItemClass.Tier.CONSUMABLE to "Potions")) {
                    val l = byTier[t].orEmpty()
                    if (l.isNotEmpty()) { head(label); l.forEach { itemRow(it) } }
                }
                byTier[null].orEmpty().takeIf { it.isNotEmpty() }?.let { head("Tier not known yet (read from the wiki when the Library learns)"); it.forEach { n -> itemRow(n) } }
                // the roaming boots' blessings: not equipment
                val bl = ic.blessings.filter { ok(it.name) || ok("blessing") || ok("roam") }
                if (bl.isNotEmpty()) {
                    head("Roaming boots blessings (not equipment)")
                    list.addView(TextView(this).apply { text = "No cost: you get one when you buy boots with a roaming blessing as a roamer."; textSize = 12f; setTextColor(c(T.MUTED)); setPadding(dp(6), 0, 0, dp(2)) })
                    for (b0 in bl) row(b0.name, false, b0.kind, true, file = b0.file) { b0.text }
                }
            }
        }
        search.setOnEditorActionListener { v, _, _ -> libFilter = v.text.toString(); show("library"); true }
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
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
        // off = only the patch meta searches the web (one search can use a whole day of the free Groq tier)
        switch("Web search for each hero (uses many tokens)", Cfg.heroWebSearch) { Cfg.heroWebSearch = it }
        // OFF by default: nothing is asked online unless you press a button
        switch("Automatic online updates (uses tokens)", Cfg.autoOnline) { Cfg.autoOnline = it }
        text("Off: online questions are asked only when you press a button. On: your hero's meta and skills, the enemy heroes' skills, the weekly patch meta and the online draft update by themselves.", 12f, color = T.MUTED)
        val prov = text("", 12f, color = T.MUTED)
        upd { prov.text = "Online models: " + com.example.mlbbresearch.ai.GroqClient.providersText() +
            "\nKeys in the folder: groq_key.txt (two Groq models), gemini_key.txt (Gemini 3.8 Flash, 3.6 Flash, 3.5 Flash-Lite), cerebras_key.txt" +
            (if (com.example.mlbbresearch.ai.GroqClient.lastProvider != "-") "\nLast answer from: ${com.example.mlbbresearch.ai.GroqClient.lastProvider}" else "") }
        val gq = com.example.mlbbresearch.ai.GroqClient
        // rate-limit indicator: green = ready, red = resting after the limit (with a countdown)
        val lim = text("", 14f, bold = true)
        upd {
            val (col, t) = groqIndicator()
            lim.text = "● " + t; lim.setTextColor(c(col))
        }
        val st = text("", 13f, color = T.MUTED)
        upd {
            st.text = (if (!com.example.mlbbresearch.ai.GroqClient.anyKey) "No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Auto-Pilot-Storage folder." else "${gq.calls} answers this session" +
                (if (gq.tokensLeft >= 0) " · ${gq.tokensLeft} tokens left this minute" else "")) +
                (if (ma.busy.isNotEmpty()) "\nWorking: ${ma.busy}…" else "") +
                (if (ma.lastMessage.isNotEmpty()) "\n${ma.lastMessage}" else "")
        }
        val upd1 = btn("Update the patch meta") {
            leaveHooks.forEach { it() }
            if (!com.example.mlbbresearch.ai.GroqClient.anyKey) toast("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Auto-Pilot-Storage folder") else {
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
                !com.example.mlbbresearch.ai.GroqClient.anyKey -> toast("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Auto-Pilot-Storage folder")
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
    //  SPEED: every engine and model, live, with the last 5 minutes
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

        // ---- the phone: CPU, GPU, RAM, ROM (DeviceStats) ----
        val ds = com.example.mlbbresearch.core.DeviceStats
        val pc = card()
        cardHeader(pc, "Phone", "every 2 s", T.ACCENT)
        fun metric(label: String): Triple<TextView, com.example.mlbbresearch.app.BudgetBar, TextView> {
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
            top.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(c(T.TEXT)); setTypeface(null, Typeface.BOLD) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(this).apply { textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(c(T.TEXT)) }
            top.addView(v); pc.addView(top)
            val bar = com.example.mlbbresearch.app.BudgetBar(this)
            pc.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(5) })
            val note = TextView(this).apply { textSize = 12f; setTextColor(c(T.MUTED)); setPadding(0, dp(3), 0, 0) }
            pc.addView(note)
            return Triple(v, bar, note)
        }
        val (cpuV, cpuBar, cpuNote) = metric("CPU (this app)")
        val cpuSpark = com.example.mlbbresearch.app.Sparkline(this)
        pc.addView(cpuSpark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4) })
        val (gpuV, gpuBar, gpuNote) = metric("GPU")
        val (ramV, ramBar, ramNote) = metric("RAM (phone in use)")
        val ramSpark = com.example.mlbbresearch.app.Sparkline(this)
        pc.addView(ramSpark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4) })
        val (romV, romBar, romNote) = metric("ROM (storage in use)")
        fun level(f: Float) = c(when { f < 0.6f -> T.GREEN; f < 0.85f -> T.GOLD; else -> T.RED })

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
            ds.sampleAsync(this)
            ds.last?.let { p ->
                cpuV.text = "%.0f%%".format(p.appCpuPct); cpuV.setTextColor(level(p.appCpuPct / 100f))
                cpuBar.set(p.appCpuPct / 100f, level(p.appCpuPct / 100f))
                cpuNote.text = "of all ${p.cores} cores" + (if (p.coreLoadPct >= 0f) " · cores at %.0f%% of their top speed".format(p.coreLoadPct) else "") +
                    (if (p.coreGhz.isNotEmpty()) " · " + p.coreGhz.joinToString(" ") { "%.1f".format(it) } + " GHz" else "") +
                    "\n(the whole phone's CPU use is closed to apps since Android 8)"
                cpuSpark.set(ds.history(ds.cpuHistory), 50f, c(T.ACCENT))
                val gl = p.gpuLoadPct
                gpuV.text = when { gl != null -> "%.0f%%".format(gl); p.gpuMhz != null -> "%.0f MHz".format(p.gpuMhz); else -> "not readable" }
                gpuV.setTextColor(if (gl != null) level(gl / 100f) else c(T.MUTED))
                gpuBar.set((gl ?: 0f) / 100f, if (gl != null) level(gl / 100f) else c(T.STROKE))
                gpuNote.text = (if (gl != null || p.gpuMhz != null) "whole phone (${p.gpuSource})" + (p.gpuMhz?.takeIf { gl != null }?.let { " · %.0f MHz".format(it) } ?: "") else p.gpuSource) +
                    "\nRuns on: strategic model " + com.example.mlbbresearch.ai.GemmaEngine.backendName.let { if (it == "-") "not loaded" else it } +
                    ", small model " + com.example.mlbbresearch.ai.TacticalLlmEngine.backendName.let { if (it == "-") "not loaded" else it } + " · detectors and networks: CPU"
                val usedRam = (p.ramTotalMb - p.ramAvailMb).toFloat() / p.ramTotalMb.coerceAtLeast(1)
                ramV.text = "%.1f / %.1f GB".format((p.ramTotalMb - p.ramAvailMb) / 1024f, p.ramTotalMb / 1024f); ramV.setTextColor(level(usedRam))
                ramBar.set(usedRam, level(usedRam))
                ramNote.text = "this app: ${p.appRamMb} MB (Java ${p.appJavaMb} MB, native ${p.appNativeMb} MB + models / screen buffers) · free %.1f GB".format(p.ramAvailMb / 1024f) +
                    (if (p.lowMemory) " · LOW MEMORY: Android may close apps" else "")
                ramSpark.set(ds.history(ds.ramHistory), (p.ramTotalMb / 4f), c(T.GOLD))
                val usedRom = (p.romTotalGb - p.romFreeGb) / p.romTotalGb.coerceAtLeast(0.1f)
                romV.text = "%.0f / %.0f GB".format(p.romTotalGb - p.romFreeGb, p.romTotalGb); romV.setTextColor(level(usedRom))
                romBar.set(usedRom, level(usedRom))
                romNote.text = "free %.1f GB · this app's files (models, icons, cache): ${p.appFilesMb} MB".format(p.romFreeGb)
            } ?: run { cpuV.text = "…"; gpuV.text = "…"; ramV.text = "…"; romV.text = "…" }
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
                "5 min: average ${fmt(total.avg(), "ms")}, worst ${fmt(total.max(), "ms")}, target under ${fmt(total.budget, "ms")}"
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
        text("Put files in the Sphynx-Auto-Pilot-Storage folder (top of the phone's storage, next to Download); the app loads them by itself:\n• Gemma 4 E2B (.litertlm) – strategy\n• Gemma 3 270M (.litertlm) – fight advice (optional)\n• mlbb_policies.zip – tactical networks\n• detectors (.tflite + labels) – minimap and screen\n• groq_key.txt – online meta", 13f)
        val mcard = card()
        val st = cardText(mcard, 14f, true)
        upd {
            val af = com.example.mlbbresearch.models.AutoFolder
            val pn = com.example.mlbbresearch.learning.PolicyNet
            // a coloured dot per part: green = loaded / running, yellow = loading or loaded but switched off,
            // red = missing / not loaded
            val sb = android.text.SpannableStringBuilder()
            fun line(state: Int, t: String) {
                if (sb.isNotEmpty()) sb.append("\n")
                val st0 = sb.length
                sb.append("●  ")
                sb.setSpan(android.text.style.ForegroundColorSpan(c(when (state) { 2 -> T.GREEN; 1 -> T.GOLD; else -> T.RED })),
                    st0, st0 + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.RelativeSizeSpan(1.25f), st0, st0 + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(t)
            }
            fun sub(t: String) { sb.append("\n     ").append(t) }
            line(if (af.treeUri != null) 2 else 0, "Folder: " + (af.treeUri?.let { Uri.decode(it.lastPathSegment ?: "chosen").substringAfter(':') } ?: "not chosen yet"))
            sub(af.status)
            sb.append("\n")
            line(when { GemmaEngine.ready -> 2; GemmaEngine.loading -> 1; else -> 0 },
                "Strategic model: ${ModelManager.status(this)}, ${GemmaEngine.state}" +
                (if (GemmaEngine.calls > 0) " (last answer ${GemmaEngine.lastLatencyMs} ms)" else ""))
            val smallFile = ModelManager.tacticalFile(this).exists()
            line(when { TacticalLlmEngine.ready && Cfg.tacticalLlmOn -> 2; TacticalLlmEngine.loading -> 1; smallFile -> 1; else -> 0 },
                "Small tactical model: ${ModelManager.tacticalStatus(this)}, " + (if (Cfg.tacticalLlmOn) TacticalLlmEngine.state else "switched off"))
            if (Cfg.tacticalLlmOn && TacticalLlmEngine.ready) sub(TacticalAdvisor.text().substringAfter(": "))
            val sd = com.example.mlbbresearch.vision.ScreenDetector
            line(when { ObjectDetector.ready && Cfg.detectorOn -> 2; ObjectDetector.ready -> 1; else -> 0 },
                "Minimap detector: ${ObjectDetector.state}" + (if (ObjectDetector.ready) ", ${ObjectDetector.lastMs} ms per run" else "") +
                (if (ObjectDetector.ready && !Cfg.detectorOn) " (switched off)" else ""))
            line(when { sd.ready && Cfg.detectorOn -> 2; sd.ready -> 1; else -> 0 },
                "Screen detector: ${sd.state}" + (if (sd.ready) ", ${sd.lastMs} ms per run" else "") +
                (if (sd.ready && !Cfg.detectorOn) " (switched off)" else ""))
            line(when { pn.ready && Cfg.policyOn -> 2; pn.ready -> 1; else -> 0 },
                "Tactical network: ${pn.state}" + (if (pn.ready && !Cfg.policyOn) " (switched off)" else "") +
                (if (pn.ready && pn.agreeRate >= 0f) ", agrees with the rules ${(pn.agreeRate * 100).toInt()}%" else ""))
            pn.available(this).let { if (it.isNotEmpty()) sub("on the phone: " + it.joinToString()) }
            if (pn.ready) sub(pn.qualityText)
            line(when { !com.example.mlbbresearch.ai.GroqClient.anyKey -> 0; com.example.mlbbresearch.ai.GroqClient.limited -> 1; else -> 2 },
                "Online AI: " + (if (!com.example.mlbbresearch.ai.GroqClient.anyKey) "no key (groq_key.txt, gemini_key.txt, cerebras_key.txt)" else groqIndicator().second))
            line(if (com.example.mlbbresearch.learning.CloudTrain.ready()) 2 else 0, "Kaggle key: " +
                (if (com.example.mlbbresearch.learning.CloudTrain.ready()) "set (${Cfg.kaggleUser})" else "not found (kaggle.json)"))
            sb.append("\n\nMinimap: " + com.example.mlbbresearch.game.MapIntel.status)
            st.text = sb
        }
        // v37.19: the app's folder /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage (imports and exports)
        val storageInfo = text("", 12f, color = T.MUTED)
        val allowBtn = btn("") {
            if (com.example.mlbbresearch.core.Storage.allFilesAccess) com.example.mlbbresearch.models.AutoFolder.scan(this) { msg -> runOnUiThread { toast(msg.take(150)) } }
            else try { startActivity(com.example.mlbbresearch.core.Storage.requestIntent(this)) }
                catch (e: Exception) { toast("Open Settings > Apps > Special app access > All files access > Sphynx Auto Pilot") }
        }
        upd {
            val on = com.example.mlbbresearch.core.Storage.allFilesAccess
            storageInfo.text = com.example.mlbbresearch.core.Storage.status()
            storageInfo.setTextColor(c(if (on) T.MUTED else T.GOLD))
            allowBtn.text = if (on) "All files access is on - scan the folder" else "Allow all files access (for /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage)"
        }
        val folderInfo = text("", 12f, color = T.MUTED)
        upd {
            val af = com.example.mlbbresearch.models.AutoFolder
            folderInfo.text = af.folderHint
            folderInfo.setTextColor(c(if (af.treeUri == null || af.legacyChosen()) T.GOLD else T.MUTED))
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
        // the fight-choice network: the fast numeric adviser in the same slot
        switch("Fight-choice network in fights (fast)", Cfg.fightNetOn) { on ->
            Cfg.fightNetOn = on
            if (on && !com.example.mlbbresearch.learning.FightNet.ready) com.example.mlbbresearch.learning.FightNet.load(this)?.let { toast(it) }
        }
        val fnInfo = text("", 12f, color = T.MUTED)
        upd { fnInfo.text = "Network: " + com.example.mlbbresearch.learning.FightNet.info +
            (if (com.example.mlbbresearch.learning.FightNet.asked > 0) " · last advice: ${com.example.mlbbresearch.learning.FightNet.lastAdvice}" else "") +
            "\nIt comes in mlbb_policies.zip (Train the tactical networks). When both are on, the network advises first." }
        // does it help? the AI's matches with each adviser vs none, from your own games
        title("Fight advisers: on vs off")
        val ab = card()
        val abText = cardText(ab, 13f)
        upd { abText.text = com.example.mlbbresearch.learning.ABCompare.summary() }
        text("Counts matches the AI plays with the small model on (loaded) or off for the WHOLE match. Same hero and lane both ways gives the fairest numbers.", 12f, color = T.MUTED)
        btn("Clear the comparison") { com.example.mlbbresearch.learning.ABCompare.clear(); toast("Cleared") }
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
        // ---- one-tap training in the cloud (Kaggle) ----
        title("Train in the cloud")
        val ctc = card()
        val ctHead = cardHeader(ctc, "Kaggle (free GPU)", "", T.GREEN)
        val ctChip = (ctHead.parent as LinearLayout).getChildAt(1) as TextView
        val ctNote = cardNote(ctc)
        val ct = com.example.mlbbresearch.learning.CloudTrain
        upd {
            ctChip.text = if (ct.ready()) "key set" else "no key"
            ctChip.setTextColor(c(if (ct.ready()) T.GREEN else T.RED))
            ctNote.text = if (ct.ready()) "Tap a training: inputs upload from Download/MLBB-Research, it trains on Kaggle, results download back and load by themselves."
                else "Put kaggle.json in the Sphynx-Auto-Pilot-Storage folder (kaggle.com > Settings > API > Create New Token), then Scan the folder on the Model page."
        }
        for (j in com.example.mlbbresearch.learning.CloudTrain.Job.values()) {
            val jc = card()
            val jHead = cardHeader(jc, j.label, "", T.ACCENT)
            val jChip = (jHead.parent as LinearLayout).getChildAt(1) as TextView
            val jIn = cardNote(jc)
            val jSt = cardText(jc, 13f)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            jc.addView(row)
            val go = Button(this).apply { text = "Train"; styleButton(this, primary = true) }
            val open = Button(this).apply { text = "Open on Kaggle"; styleButton(this) }
            row.addView(go, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            row.addView(open, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            go.setOnClickListener { ct.start(this, j) { m -> runOnUiThread { toast(m.take(140)) } } }
            val ready0 = cardNote(jc)
            open.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ct.kernelUrl(j)))) }
            var inputsAt = 0L
            upd {
                val run = ct.running(j)
                jChip.text = if (run) "training" else if (j.gpu) "GPU" else "CPU"
                jChip.setTextColor(c(if (run) T.GOLD else T.MUTED))
                val st = ct.status(j)
                jSt.text = st
                jSt.setTextColor(c(when { st.startsWith("done") -> T.GREEN; st.startsWith("failed") -> T.RED; else -> T.TEXT }))
                // the counts are refreshed every 10 s (database counts and the folder listing are slower)
                if (System.currentTimeMillis() - inputsAt > 10_000) {
                    inputsAt = System.currentTimeMillis()
                    ct.refreshCounts()
                    jIn.text = try { ct.inputsText(this, j) } catch (e: Exception) { "-" }
                }
                // green = enough to train on (tap to zip / export, upload and train); red = not yet (cannot be tapped)
                val rd = ct.readiness(this, j)
                val can = !run && ct.ready() && rd.ok
                go.isEnabled = can
                go.text = when { run -> "Training…"; !ct.ready() -> "Needs kaggle.json"; rd.ok -> "Train"; else -> "Not enough yet" }
                go.background = ripple(when { run -> T.GOLD; can -> T.GREEN; else -> T.RED }, 12)
                go.setTextColor(Color.WHITE)
                go.alpha = if (can || run) 1f else 0.75f
                ready0.text = rd.text
            }
        }

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
        ExperienceRecorder.refreshFights()
        val fightInfo = text("", 12f, color = "#9AA0A6")
        upd { fightInfo.text = "Fight moments for the small tactical model: " + (if (ExperienceRecorder.fights < 0) "counting..." else "${ExperienceRecorder.fights}") +
            " (each fight choice with how it went 4 s later; used by Train)" }
        btn("Delete all fight moments") {
            AlertDialog.Builder(this).setTitle("Delete all fight moments?")
                .setMessage("Every saved fight choice and how it went (used to train the small tactical model). Cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> ExperienceRecorder.clearFights { runOnUiThread { toast("Fight moments deleted") } } }
                .setNegativeButton("Cancel", null).show()
        }
        btn("Delete all recorded moments") {
            AlertDialog.Builder(this).setTitle("Delete ALL recorded moments?")
                .setMessage("Everything recorded in your matches: the AI's saved decisions, your recorded play (Coach / play like me) and the fight moments. Live-match moments are kept (their own button below). Cannot be undone.")
                .setPositiveButton("Delete all") { _, _ -> ExperienceRecorder.clearAllMoments { n -> runOnUiThread { toast("Deleted $n recorded moments") } } }
                .setNegativeButton("Cancel", null).show()
        }

        title("Live matches")
        text("Watch someone's live match with learning on: their play is recorded like yours and used by Train.", 13f)
        switch("Learn from live matches", Cfg.liveLearn) { Cfg.liveLearn = it }
        val liveWho = text("", 13f)
        upd { liveWho.text = "Watching: ${Cfg.liveHero.ifBlank { "read from the screen when a live match starts" }}" +
            (if (Cfg.liveHero.isNotBlank()) " · role ${Cfg.liveLane.label}" else "") + " (found by itself)\nSkill icons: " +
            com.example.mlbbresearch.vision.HeroVerifier.liveStatus + " · looks learned: ${com.example.mlbbresearch.vision.HeroVerifier.learnedLiveCount()}" }
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
        text("Saves labelled pictures while you play (minimap 300, screen 300 per batch). Train zips them by itself; at capacity they are zipped and the switch turns off.", 13f)
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
        // SESSION RECORDER (data file + video, for bug reports)
        title("SESSION RECORDER - for bug reports")
        text("Records what the AI saw and decided (about 10 times a second, every aimed skill, the log) into a data file, " +
            "and a video of the AI's view with its targets and aim drawn on top. Start it before a match, stop it after; " +
            "send both files (in Sphynx-Auto-Pilot-Storage/recordings). Screen capture must be on for the video.", 12f, color = T.MUTED)
        val recInfo = text("", 12f)
        val recBtn = btn("") {
            val r = com.example.mlbbresearch.core.SessionRecorder
            if (r.on) { toast("Saving…"); r.stop(this) { msg -> runOnUiThread { toast(msg.take(200)) } } }
            else { r.start(this); toast(if (com.example.mlbbresearch.core.VideoRecorder.on) "Recording data + video" else "Recording data (start screen capture for video)") }
        }
        upd {
            val r = com.example.mlbbresearch.core.SessionRecorder
            recInfo.text = r.status()
            recInfo.setTextColor(c(if (r.on) T.RED else T.MUTED))
            recBtn.text = if (r.on) "■ Stop and save the recording" else "● Start recording (data + video)"
        }

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
        // (screen capture: started on the Home page only - v36.86)
        text("POCO/Xiaomi: also allow 'pop-up windows while running in the background' and turn off 'Manage app if unused'.", 12f, color = "#9AA0A6")

        title("SCREEN")
        val scr = text("", 12f, color = "#9AA0A6")
        upd {
            scr.text = "Screen: " + com.example.mlbbresearch.core.ScreenGeom.describe(this) +
                "\nVision, touches and drawings all use the whole screen (a POCO X6 Pro is 2712 x 1220), so positions match MLBB exactly."
        }

        title("CALIBRATE BUTTON POSITIONS")
        text("Open a match, come back, tap Calibrate: drag the yellow markers onto the buttons (or Auto-detect), then Save. 2-skill, 3-skill, and 4-skill buttons have separate calibration and separate saves.", 12f, color = "#9AA0A6")
        fun calibrateGroupLater(groupKey: String) {
            val o = AccessibilityController.instance?.overlay
            if (o == null) { toast("Turn on the accessibility service first"); return }
            if (!ScreenCaptureService.running) toast("Tip: start screen capture first so Auto-detect can see the game")
            toast("Switch to MLBB now: calibration starts in 5 seconds")
            ui.postDelayed({ o.startCalibrationGroup(groupKey) }, 5000)
        }
        val autoCal = text("", 12f, color = "#9AA0A6")
        upd { autoCal.text = "Automatic: the first match and hero-select screen place the buttons by themselves (" +
            com.example.mlbbresearch.game.AutoTune.calStatus + ").\nSaved skill layouts: " +
            "2-skill ${if (Layout.isGroupSaved("skills2")) "✓ saved" else "default"} · " +
            "3-skill ${if (Layout.isGroupSaved("skills3")) "✓ saved" else "default"} · " +
            "4-skill ${if (Layout.isGroupSaved("skills4")) "✓ saved" else "default"}" }
        btn("Calibrate 2-skill buttons on the game") { calibrateGroupLater("skills2") }
        btn("Calibrate 3-skill buttons on the game") { calibrateGroupLater("skills3") }
        btn("Calibrate 4-skill buttons on the game") { calibrateGroupLater("skills4") }
        btn("Calibrate in-game HUD (joystick / attack / spell)") { calibrateGroupLater("game") }
        btn("Calibrate shop buttons on the game") { calibrateGroupLater("shop") }
        btn("Calibrate hero-select buttons on the game") { calibrateGroupLater("select") }

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

        title("SCENERY & MINIMAP SYNCHRONIZATION")
        text("Aligns the 3D camera ground view with the minimap using visible towers, jungle pits, and bushes.", 12f, color = "#9AA0A6")
        switch("Auto-sync 3D scenery with minimap", Cfg.sceneryAutoSync) { Cfg.sceneryAutoSync = it }
        val setupSyncInfo = text("", 12.5f)
        val setupCamBtn = btn("") { toast(com.example.mlbbresearch.game.SceneryMap.cycleCameraPreset()) }
        btn("Sync Scenery & Minimap Now") { toast(com.example.mlbbresearch.game.SceneryMap.syncNow(GameStateTracker.state)) }
        btn("Start / Stop Practice Mode Scenery Roam") { toast(com.example.mlbbresearch.game.SceneryMap.toggleRoam()) }
        upd {
            setupCamBtn.text = "Camera Height: ${com.example.mlbbresearch.game.SceneryMap.cameraPresetLabel()}  (tap to change)"
            setupSyncInfo.text = com.example.mlbbresearch.game.SceneryMap.syncStatus() + "\nMemorized: " + com.example.mlbbresearch.game.SceneryMap.summary()
        }

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
        fun groupTitle(g: String) = when (g) {
            "skills2" -> "2-skill buttons"
            "skills3" -> "3-skill buttons"
            "skills4" -> "4-skill buttons"
            "large" -> "In-game buttons (large HUD)"
            "shop" -> "Shop screen"
            "select" -> "Hero select"
            else -> "In-game HUD (joystick / attack / spell)"
        }
        val groupOrder = listOf("skills2", "skills3", "skills4", "game", "large", "shop", "select")
        for (grp in groupOrder) {
            val ctls = Layout.defaults.filter { it.group == grp }
            if (ctls.isEmpty()) continue
            text(groupTitle(grp), 13f, true)
            for (ctl in ctls) {
                field(ctl.label, Layout.text(ctl.key), false) { v -> if (v != Layout.text(ctl.key)) Layout.set(ctl.key, v) }
            }
            if (grp in listOf("skills2", "skills3", "skills4")) {
                val label = groupTitle(grp)
                btn("Save $label") {
                    Layout.saveGroup(grp)
                    toast("Saved $label")
                }
            }
        }
        btn("Reset all positions to defaults") {
            for (ctl in Layout.defaults) Layout.reset(ctl.key)
            show("setup")
        }
    }
}
