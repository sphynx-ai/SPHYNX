package com.sphynx.app

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
import com.sphynx.R
import com.sphynx.ai.AiRuntime
import com.sphynx.ai.ComboFetcher
import com.sphynx.ai.ComboRunner
import com.sphynx.ai.TacticalAdvisor
import com.sphynx.ai.StrategicAdvisor
import com.sphynx.capture.FrameHub
import com.sphynx.capture.ScreenCaptureService
import com.sphynx.controller.AccessibilityController
import com.sphynx.controller.ActionExecutor
import com.sphynx.controller.QuickBuySwap
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.core.Layout
import com.sphynx.game.BuildBook
import com.sphynx.game.ComboBook
import com.sphynx.game.LaneLogic
import com.sphynx.game.SpellDb
import com.sphynx.game.GameStateTracker
import com.sphynx.game.HeroDb
import com.sphynx.game.Role
import com.sphynx.learning.Brain
import com.sphynx.learning.DatasetManager
import com.sphynx.learning.DemoRecorder
import com.sphynx.learning.ImitationPolicy
import com.sphynx.learning.ExperienceRecorder
import com.sphynx.learning.PerformanceEvaluator
import com.sphynx.models.ModelManager
import com.sphynx.vision.FrameProcessor
import com.sphynx.vision.ObjectDetector
import com.sphynx.vision.IconMemory
import kotlin.math.abs

// the colours: app/Theme.kt (the pages write T.BG, T.TEXT ...)
private typealias T = Theme

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
    // help next to each control (owner, 2026-11-14): the descriptions on the current page, each behind the "?"
    // of the control it explains (HelpHold)
    private val descriptions = ArrayList<TextView>()
    private class HelpGroup(val icon: TextView, val lines: MutableList<TextView> = ArrayList())
    private val titleRows = HashSet<View>()                  // section headers (a "?" can sit at their end)
    private val groupSwitches = HashSet<View>()              // switches of a one-of-several group
    private val helpPadded = HashSet<View>()                 // buttons and fields with the "?" inside their end
    private val helpOf = HashMap<View, HelpGroup>()          // a description, or a control's row -> its "?"
    private var bubble: android.widget.PopupWindow? = null
    private var bubbleFor: HelpGroup? = null
    private var bubbleClosedAt = 0L

    // the five sections of the bottom bar and their pages (app/Sections.kt)
    private val pages = Sections.PAGES
    private val labels = Sections.PAGE_LABELS
    private val lastSeen = HashMap<String, String>()          // section -> the page last open in it
    private lateinit var subRow: LinearLayout                // the sub-pages of the open section, under the header

    private val reqCapture = 42
    private val reqNotify = 43
    private val reqFolder = 46
    private val reqGguf = 47

    private val refresher = object : Runnable {
        override fun run() {
            headerStatus?.let { h ->
                val on = AiRuntime.anyOn()
                h.text = if (on) "● " + AiRuntime.statusLine() else "○ AI off"
                h.setTextColor(Color.parseColor(if (on) T.GREEN else T.MUTED))
                // Groq resting after its rate limit: a red countdown in the header, on every page
                val gq = com.sphynx.ai.GroqClient
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
        // the chosen theme (Settings > Setup > Theme): its colours, the logo's colour, the system bars
        Theme.use(Cfg.theme)
        PanelTheme.apply()
        window.statusBarColor = c(T.BG)
        window.navigationBarColor = c(T.SURF)
        window.decorView.setBackgroundColor(c(T.BG))

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
        // the symbol, drawn: its diamond takes the theme's colour
        header.addView(View(this).apply {
            background = com.sphynx.controller.PanelIcons.on(shape(T.SURF, 10, T.STROKE),
                com.sphynx.controller.PanelIcons.Kind.MARK, c(T.STRONG), dp(7))
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        names.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 21f
            setTextColor(c(T.STRONG))
            typeface = display()
        })
        headerStatus = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor(T.MUTED)) }
        names.addView(headerStatus)
        header.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // (the help "?" is next to each control now - owner, 2026-11-14 - not in the header)
        // the one emergency stop in the app (also: the floating icon and the notification)
        val stopTop = Button(this).apply {
            text = "■  STOP"
            isAllCaps = false
            textSize = 14f
            stateListAnimator = null
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c(T.ON_STOP))
            background = ripple(T.STOP, 24)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { AiRuntime.emergencyStop("app header") }
        }
        header.addView(stopTop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(header)

        // the sub-pages of the open section (hidden when the section has one page)
        subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(T.SURF, 14, T.STROKE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            visibility = View.GONE
        }
        root.addView(subRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(4); bottomMargin = dp(4)
        })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // THE BOTTOM BAR: five sections, always visible (no sideways scrolling)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(Color.parseColor(T.SURF))
        }
        for (sec in Sections.ALL) {
            val b = Button(this).apply {
                text = sec.label
                typeface = display()
                isAllCaps = false
                stateListAnimator = null
                maxLines = 2
                gravity = Gravity.CENTER
                minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                maxLines = 1
                setPadding(dp(2), dp(7), dp(2), dp(4))
                compoundDrawablePadding = dp(2)
                // the label shrinks to fit its fifth of the bar instead of breaking inside a word
                setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                contentDescription = sec.label
                setOnClickListener { go(Sections.open(sec, lastSeen)) }
            }
            navButtons[sec.key] = b
            nav.addView(b, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor(T.STROKE)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(nav)

        // keep everything clear of the status bar and the phone's own Home/Back/Recent buttons
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
        defaultText = navButtons["play"]?.textColors

        show(if (Cfg.firstRunPending) "welcome" else "home")
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
        com.sphynx.learning.OnlineBackup.flushLearned()   // learned data into the folder now
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
            reqGguf -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                toast("Adding the model... (see Model page)")
                com.sphynx.models.GgufLibrary.add(this, uri) { msg ->
                    runOnUiThread { toast(msg.take(150)); if (page == "model") show("model") }
                }
            }
            reqFolder -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                com.sphynx.models.AutoFolder.onPicked(this, uri) { msg -> runOnUiThread { toast(msg.take(150)) } }
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
        val gq = com.sphynx.ai.GroqClient
        return when {
            !gq.anyKey -> T.MUTED to "Online AI: no key"
            gq.limited -> T.RED to "All online models resting after their rate limits · available again in ${gq.waitText()} (${gq.availableAtText()})"
            gq.groqLimited -> T.GREEN to "Online AI available (Groq resting; the other models answer)"
            else -> T.GREEN to "Online AI available"
        }
    }

    // ---- look: one dark theme, used by every page ----
    // (the colours are in app/Theme.kt - the brand palette, contrast-tested; the fonts in app/Fonts.kt)
    private fun display(): Typeface = Fonts.display(this)
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

    // describe = false: a line that is information, not a description (field labels, the app version)
    private fun text(t: String, size: Float = 14f, bold: Boolean = false, color: String = T.TEXT, describe: Boolean = true): TextView {
        val v = TextView(this).apply {
            text = t
            textSize = maxOf(size, 13f)
            setTextColor(c(themed(color)))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(6), 0, dp(2))
        }
        content.addView(v)
        if (HelpHold.isDescription(t, bold, describe)) asDescription(v) else if (describe && !bold) watchLong(v)
        return v
    }

    // a description: not on the page; it goes behind a "?" at the end of the control just before it (attached
    // once the page has placed it). If the page rewrites its text later it is a live line after all (a status),
    // so it is shown for good and leaves the "?".
    private fun asDescription(v: TextView) {
        val atCreation = v.text.toString()
        descriptions.add(v)
        v.visibility = View.GONE
        v.post { if (v in descriptions) attachHelp(v) }
        v.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // a rewritten line is live information: it comes back on the page unless it is long
                if (v in descriptions && !HelpHold.staysDescription(atCreation, s ?: "") && !HelpHold.isLongInfo(s)) {
                    descriptions.remove(v)
                    helpOf.remove(v)?.let { g -> g.lines.remove(v); refreshHelp(g) }
                    v.visibility = View.VISIBLE
                }
            }
        })
    }

    // a live line (status, counts, timeline): on the page while short; once long, behind the "?" of the row above
    // it (owner, 2026-11-15). The bubble shows its text at the moment of the tap, so it stays current.
    private val noAnchor = HashSet<View>()
    private fun watchLong(v: TextView) {
        fun check() {
            if (v in descriptions || v in noAnchor || !HelpHold.isLongInfo(v.text)) return
            descriptions.add(v)
            v.visibility = View.GONE
            v.post {
                if (v in descriptions && helpOf[v] == null) {
                    attachHelp(v)
                    if (v !in descriptions) noAnchor.add(v)     // nothing above it to hang the "?" on: it stays a line
                }
            }
        }
        v.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                check()
                helpOf[v]?.let { refreshHelp(it) }
            }
        })
        v.post { check() }
    }

    // puts description d behind the "?" of the control placed before it (several lines -> one bubble)
    private fun attachHelp(d: TextView) {
        val parent = d.parent as? LinearLayout
        if (parent == null) { descriptions.remove(d); d.visibility = View.VISIBLE; return }
        var i = parent.indexOfChild(d) - 1
        while (i >= 0 && parent.getChildAt(i).let { it in descriptions || it.visibility == View.GONE }) i--
        var prev = if (i >= 0) parent.getChildAt(i) else null
        if (prev == null) { descriptions.remove(d); d.visibility = View.VISIBLE; return }   // nothing to explain: keep the line
        // a line after a group of switches (theme, lane, font...) explains the whole group: its "?" goes on the
        // group's header, not on the last switch (owner, 2026-11-15)
        if (prev in groupSwitches) {
            var j = i
            while (j >= 0 && parent.getChildAt(j).let { it in groupSwitches || it in descriptions || it.visibility == View.GONE }) j--
            val head = if (j >= 0) parent.getChildAt(j) else null
            if (head != null && head in titleRows) { i = j; prev = head }
        }
        val ctl: View = prev
        val g = helpOf[ctl] ?: run {
            val lp: ViewGroup.LayoutParams = ctl.layoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            parent.removeViewAt(i)
            val grp = HelpGroup(helpIcon())
            grp.icon.setOnClickListener { toggleBubble(grp) }
            val holder = helpHolder(ctl, grp.icon, lp)
            if (ctl in titleRows) titleRows.add(holder)
            parent.addView(holder, i, lp)
            helpOf[holder] = grp
            grp
        }
        g.lines.add(d)
        helpOf[d] = g
        refreshHelp(g)
    }

    // Where the "?" goes, so a control with help has the same size and place as one without (owner, 2026-11-15):
    //  - a switch: label, then the "?", then the switch, which stays at the right edge like every other switch;
    //  - a button or field: full width as before, the "?" inside its right end (text kept clear of it);
    //  - anything else (a section header): a row with the "?" at its end.
    private fun helpHolder(prev: View, icon: TextView, lp: ViewGroup.LayoutParams): View {
        val wrapH = LinearLayout.LayoutParams.WRAP_CONTENT
        if (prev is Switch) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48)
            }
            val label = TextView(this).apply {
                text = prev.text; textSize = 16f; setTextColor(prev.textColors); typeface = prev.typeface
                setPadding(0, dp(10), dp(4), dp(10))
                setOnClickListener { if (prev.isEnabled) prev.toggle() }
            }
            prev.text = ""
            prev.contentDescription = label.text
            // a label the page rewrites later still shows next to the switch
            prev.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    if (!e.isNullOrEmpty()) { label.text = e.toString(); prev.contentDescription = label.text; prev.text = "" }
                }
            })
            row.addView(label, LinearLayout.LayoutParams(0, wrapH, 1f))
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
            row.addView(prev, LinearLayout.LayoutParams(wrapH, wrapH))
            return row
        }
        if (prev is TextView) {   // Button, EditText, chooser buttons
            val frame = android.widget.FrameLayout(this)
            val h = lp.height.takeIf { it > 0 } ?: wrapH
            val centred = prev is Button
            helpPadded.add(prev)
            prev.setPaddingRelative(if (centred) dp(46) else prev.paddingStart, prev.paddingTop, dp(46), prev.paddingBottom)
            frame.addView(prev, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, h))
            frame.addView(icon, android.widget.FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(2)
            })
            icon.elevation = dp(2).toFloat()
            return frame
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val h = lp.height.takeIf { it > 0 } ?: wrapH
        row.addView(prev, LinearLayout.LayoutParams(0, h, 1f))
        row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
        return row
    }

    // the small "?" circle: 22dp drawn, 44dp to tap
    private fun helpIcon(): TextView = TextView(this).apply {
        text = "?"
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(c(T.MUTED))
        background = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(c(T.SURF2)); setStroke(dp(1), c(T.MUTED))
        }, dp(11))
    }

    private fun refreshHelp(g: HelpGroup) {
        g.icon.visibility = if (g.lines.isEmpty()) View.GONE else View.VISIBLE
        g.icon.contentDescription = HelpHold.spokenLabel(g.lines.map { it.text })
    }

    // tap "?": the bubble next to it; tap anywhere else (or the same "?" again): closed
    private fun toggleBubble(g: HelpGroup) {
        val open = bubble
        if (open != null && open.isShowing) { open.dismiss(); if (bubbleFor === g) return }
        if (bubbleFor === g && System.currentTimeMillis() - bubbleClosedAt < 300) return   // this tap just closed it
        val text = HelpHold.bubbleText(g.lines.map { it.text })
        if (text.isEmpty()) return
        val maxW = (resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(320))
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(c(T.TEXT))
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = maxW
            background = shape("#F2" + T.BG.removePrefix("#"), 10, T.OUTLINE)
        }
        tv.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val pw = android.widget.PopupWindow(tv, tv.measuredWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(6).toFloat()
            setOnDismissListener { bubbleClosedAt = System.currentTimeMillis(); bubble = null }
        }
        bubble = pw; bubbleFor = g
        // to the left of the "?", just below it (Android keeps it on the screen)
        pw.showAsDropDown(g.icon, g.icon.width - tv.measuredWidth, -dp(6))
    }

    private fun closeBubble() { bubble?.dismiss(); bubble = null; bubbleFor = null }

    // a section header: accent bar + small capitals
    private fun title(t: String, capability: Capability? = null): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(this).apply { background = shape(T.ACCENT, 2) }, LinearLayout.LayoutParams(dp(3), dp(16)))
        val v = TextView(this).apply {
            text = t.uppercase(); textSize = 15f; letterSpacing = 0.08f
            setTextColor(c(T.STRONG)); typeface = display(); setPadding(dp(8), 0, 0, 0)
        }
        if (capability != null && capability.shown) {
            // the capability badge: a word in its colour at the end of the header (backlog item 7)
            row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(chip(capability.label, capability.color).apply { contentDescription = "Status: ${capability.label}" })
        } else row.addView(v)
        titleRows.add(row)
        content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22); bottomMargin = dp(4)
        })
        return v
    }

    private fun styleButton(b: Button, primary: Boolean = false) {
        b.isAllCaps = false
        b.textSize = 15f
        b.stateListAnimator = null
        b.minHeight = dp(48)
        b.setTextColor(c(if (primary) T.ON_PRIMARY else T.TEXT))
        b.setTypeface(Typeface.create(b.typeface, if (primary) 700 else 500, false))
        b.background = if (primary) ripple(T.PRIMARY, 12) else ripple(T.SURF2, 12, T.OUTLINE)
        val side = if (b in helpPadded) dp(46) else dp(14)   // a button with its "?" inside keeps room for it
        b.setPadding(side, dp(8), side, dp(8))
    }

    // asks before an action that deletes or resets something (Phase 3 backlog item 2)
    private fun confirm(question: String, action: String, run: () -> Unit) {
        AlertDialog.Builder(this).setMessage(question)
            .setPositiveButton(action) { _, _ -> run() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { text = t; setOnClickListener { onClick() } }
        styleButton(b)
        if (Destructive.isLabel(t)) {   // Delete / Clear / Forget / Reset / Remove: red text and outline
            b.setTextColor(c(T.RED))
            b.background = ripple(T.SURF2, 12, T.RED)
        }
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
            textSize = 16f
            setTextColor(c(T.TEXT))
            isChecked = initial
            thumbTintList = ColorStateList(states, intArrayOf(c(T.STRONG), c(T.MUTED)))
            trackTintList = ColorStateList(states, intArrayOf(c(T.TRACK), c(T.STROKE)))
            minHeight = dp(48)
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        content.addView(sw)
        return sw
    }

    private fun field(hint: String, value: String, numeric: Boolean, save: (String) -> Unit): EditText {
        text(hint, 12f, color = T.MUTED, describe = false)
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
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(3), 0, dp(3))
        }
        cd.addView(v)
        return v
    }

    // a permission (or other done / not done) button: the colour AND a word, never colour alone (backlog item 7)
    private fun markWord(b: Button, on: Boolean, onWord: String = StatusWords.GRANTED, offWord: String = StatusWords.MISSING) {
        mark(b, on)
        val t = StatusWords.withWord(StatusWords.base(b.text.toString()), if (on) onWord else offWord)
        if (b.text.toString() != t) b.text = t
    }

    private fun mark(b: Button, on: Boolean, color: String = T.GREEN) {
        if (on) {
            b.background = ripple(color, 12)
            b.setTextColor(c(T.ON_FILL))          // dark text on the coloured fill (white was about 2:1)
        } else {
            b.background = ripple(T.SURF2, 12, T.STROKE)
            b.setTextColor(c(T.TEXT))
        }
    }

    // ---- small building blocks for the richer pages ----

    // a small rounded label
    private fun chip(t: String, color: String = T.ACCENT): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(c(color)); setTypeface(typeface, Typeface.BOLD)
        background = shape("#1A" + color.removePrefix("#"), 20, null)
        setPadding(dp(9), dp(3), dp(9), dp(3))
    }

    // a heading inside a card (with an optional chip on the right)
    private fun cardHeader(cd: LinearLayout, t: String, chipText: String? = null, chipColor: String = T.ACCENT): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tv = TextView(this).apply { text = t; textSize = 17f; setTextColor(c(T.STRONG)); typeface = display() }
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
                val iv = com.sphynx.app.IconCache.view(this@MainActivity, n, hero, px)
                cell.addView(iv, LinearLayout.LayoutParams(px, px))
                cell.addView(TextView(this@MainActivity).apply {
                    text = captions?.getOrNull(i) ?: n; textSize = 11f; setTextColor(c(T.MUTED)); gravity = Gravity.CENTER
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(3), dp(2), 0)
                    fitWords(this, 11f, 8f)
                }, LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT))
                row!!.addView(cell, LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    // a small muted line inside a card
    // ---- standard states (Phase 3 state matrix; backlog item 8) ----

    // an empty state: a muted sentence, and the action that fills it when there is one
    private fun emptyState(parent: LinearLayout, sentence: String, action: String? = null, onAction: (() -> Unit)? = null): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        box.addView(TextView(this).apply { text = sentence; textSize = 14f; setTextColor(c(T.MUTED)) })
        if (action != null && onAction != null) box.addView(Button(this).apply {
            text = action; styleButton(this, primary = true); setOnClickListener { onAction() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        parent.addView(box)
        return box
    }

    // buttons that need the internet: disabled while the phone is offline, with the reason on a line under them
    private fun needsInternet(vararg buttons: Button) {
        val note = text("", 13f, color = T.INFO, describe = false)
        upd {
            val on = Online.available(this)
            for (b in buttons) { b.isEnabled = on; b.alpha = if (on) 1f else 0.45f }
            note.text = if (on) "" else "Offline: these need the internet. Everything else works without it."
            note.visibility = if (on) View.GONE else View.VISIBLE
        }
    }

    // a short label (grid caption, tab) never breaks inside a word (backlog item 10): once laid out, it takes the
    // largest size at which its longest word fits, or one line with "…" if none does (Labels)
    private fun fitWords(tv: TextView, maxSp: Float, minSp: Float) {
        var doneFor = -1
        tv.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w == doneFor || w <= 0) return@addOnLayoutChangeListener
            doneFor = w
            val t = v as TextView
            val p = android.text.TextPaint(t.paint).apply {
                textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
            }
            val longest = Labels.words(t.text).maxOfOrNull { p.measureText(it) } ?: 0f
            val avail = (w - t.totalPaddingLeft - t.totalPaddingRight).toFloat()
            val sp = Labels.fitSp(longest, avail, maxSp, minSp)
            t.post {
                if (sp != null) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
                else { t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, minSp); t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END }
            }
        }
    }

    private fun cardNote(cd: LinearLayout, t: String = ""): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(c(T.MUTED)); setLineSpacing(0f, 1.2f); setPadding(0, dp(4), 0, dp(2))
        cd.addView(this)
        watchLong(this)
    }

    private fun go(k: String) {
        val d = pages.indexOf(k) - pages.indexOf(page)
        show(k, if (d > 0) 1 else if (d < 0) -1 else 0)
    }

    private fun show(p: String, dir: Int = 0) {
        for (h in leaveHooks.toList()) h()
        leaveHooks.clear()
        updaters.clear()
        descriptions.clear()
        helpOf.clear()
        helpPadded.clear()
        titleRows.clear()
        groupSwitches.clear()
        noAnchor.clear()
        closeBubble()
        content.removeAllViews()
        page = p
        when (p) {
            "home" -> homePage()
            "news" -> newsPage()
            "player" -> playerPage()
            "style" -> stylePage()
            "draft" -> draftPage()
            "build" -> buildPage()
            "meta" -> metaPage()
            "library" -> libraryPage()
            "model" -> modelPage()
            "learn" -> learnPage()
            "practice" -> practicePage()
            "speed" -> speedPage()
            "logs" -> logsPage()
            "debug" -> debugPage()
            "setup" -> setupPage()
            "welcome" -> welcomePage()
        }
        // the bottom bar marks the page's section; the sub-page row lists that section's pages
        val sec = Sections.of(p)
        if (sec != null) lastSeen[sec.key] = p
        for ((k, b) in navButtons) {
            val on = k == sec?.key
            val ink = c(if (on) T.ACCENT else T.MUTED)
            b.background = if (on) ripple(T.TILE, 16) else ripple(T.SURF, 16)
            b.setTextColor(ink)
            b.setCompoundDrawables(null, NavIcons.icon(k, ink, dp(22)).apply { setBounds(0, 0, dp(22), dp(22)) }, null, null)
            b.isSelected = on
        }
        subRow.removeAllViews()
        subRow.visibility = if (sec != null && sec.pages.size > 1) View.VISIBLE else View.GONE
        if (sec != null && sec.pages.size > 1) for (k in sec.pages) {
            val on = k == p
            subRow.addView(Button(this).apply {
                text = labels[k]
                isAllCaps = false
                stateListAnimator = null
                maxLines = 1
                minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                setPadding(dp(2), 0, dp(2), 0)
                setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setTypeface(Typeface.create(typeface, if (on) 700 else 500, false))
                background = if (on) ripple(T.PRIMARY, 10) else ripple(T.SURF, 10)
                setTextColor(Color.parseColor(if (on) T.ON_PRIMARY else T.TEXT))
                isSelected = on
                setOnClickListener { go(k) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
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

    // starts a stage; the error when it could not start (null when it started, or when the user was sent to fix a
    // missing permission or model)
    private fun startStage(stage: String): String? {
        if (AccessibilityController.instance == null) {
            pendingStage = stage
            toast("Turn ON 'Sphynx' in Accessibility, then come back")
            openAccessibility()
            return null
        }
        if (AiRuntime.needsGemma(stage) && !com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC)) {
            toast("Load the Gemma model first")
            show("model")
            return null
        }
        val err = AiRuntime.start(stage)
        if (err != null) { toast(err); return err }
        if (!ScreenCaptureService.running) askScreenCapture()
        val ov = AccessibilityController.instance?.overlay
        if (ov != null && !ov.shown && !Settings.canDrawOverlays(this)) {
            toast("Allow 'Display over other apps' so the floating icon can show")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        return null
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

    // where a page used to have its own "running" switch: one line saying whether it runs, and where to turn it on
    private fun runningLine(name: String, isOn: () -> Boolean) {
        val v = text("", 14f, describe = false)
        upd {
            val on = isOn()
            v.text = "$name: ${if (on) "running" else "off"} · turn it on or off on Play › Running now"
            v.setTextColor(c(if (on) T.ACCENT else T.MUTED))
        }
    }

    // A segmented control (design tokens, components): one of a few choices, the selected one filled. Long lists
    // wrap into rows of perRow equal buttons. A description written after it explains the whole group, so its "?"
    // goes on the section header (like a group of switches).
    private fun segmented(options: List<Pair<String, String>>, current: String, perRow: Int = options.size,
                          onPick: (String) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(T.SURF2, 14, T.OUTLINE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val buttons = ArrayList<Pair<String, TextView>>()
        var selected = current
        fun paint() {
            for ((k, b) in buttons) {
                val on = k == selected
                b.background = if (on) shape(T.PRIMARY, 10) else null
                b.setTextColor(c(if (on) T.ON_PRIMARY else T.TEXT))
                b.setTypeface(Typeface.create(b.typeface, if (on) 700 else 500, false))
                b.isSelected = on
            }
        }
        for (rowOpts in options.chunked(perRow)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((k, label) in rowOpts) {
                val b = TextView(this).apply {
                    text = label; textSize = 15f; gravity = Gravity.CENTER; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = label
                    isClickable = true
                    setOnClickListener { if (selected != k) { selected = k; paint(); onPick(k) } }
                }
                buttons.add(k to b)
                row.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            // a short last row keeps the same button width
            repeat(perRow - rowOpts.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f)) }
            box.addView(row)
        }
        paint()
        content.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(4)
        })
        groupSwitches.add(box)
        return box
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
            groupSwitches.add(sw)
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
        groupSwitches.add(swA); groupSwitches.add(swB)
    }

    // =====================================================================
    //  HOME: dashboard
    // =====================================================================

    // WHAT'S NEW (core.WhatsNew): fixed bugs and new features, newest first
    private fun whatsNewRow(cd: LinearLayout, e: com.sphynx.core.WhatsNew.Entry, details: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        row.addView(chip(e.kind.label, e.kind.color))
        row.addView(TextView(this).apply {
            text = e.title; textSize = 14f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cd.addView(row)
        if (details) cardNote(cd, e.details + "\n" + com.sphynx.core.WhatsNew.ref(e) +
            (if (e.needsDeviceCheck) " · not yet checked on a phone" else ""))
    }

    // PRACTICE BENCHMARK: how the AI does against Computer, match by match
    private fun practicePage() {
        val rm = com.sphynx.research.ResearchMode
        title("PRACTICE BENCHMARK")
        text("One scorecard per practice match vs Computer that the Player played (verified: open the scoreboard once; " +
            "the Player must play at least a minute). Matches with a real player are never counted. " +
            "Goal: play as well as the game's own AI, or better.", 13f, color = T.MUTED)
        val cd = card()
        cardHeader(cd, "Scorecards")
        val body = text("", 12f)
        upd { body.text = rm.benchText() }
        text("Idle: the AI had nothing to do. Stuck: walking but the camera did not move. Death cause: what the AI was " +
            "doing in the 5 s before (fighting, farming, escaping, ...), and \"outnumbered\" when more enemy heroes than " +
            "allies were close.", 12f, color = T.MUTED)
        text("Export: on the Debug data page (all exports for debugging are there).", 12f, color = T.MUTED)
        btn("Clear the scorecards") {
            AlertDialog.Builder(this).setTitle("Delete all practice scorecards?")
                .setPositiveButton("Delete") { _, _ -> rm.clearBench(); toast("Deleted") }
                .setNegativeButton("Cancel", null).show()
        }
        // the research tests, moved here from Play (Phase 3 screen specs, Play: "The research test buttons ... move to
        // AI & models > Practice"; backlog item 11)
        title("RESEARCH TESTS")
        val resInfo = text("", 12f, color = T.MUTED)
        upd { resInfo.text = com.sphynx.research.ResearchMode.momentsText() + "\n" + com.sphynx.research.ReadinessRun.status() +
            "\n" + com.sphynx.research.ResearchMode.abText() }
        btn("Test the small model on the practice moments") {
            val rr = com.sphynx.research.ReadinessRun
            if (rr.running) { rr.stop(); toast("Stopping the test") }
            else rr.start { msg -> runOnUiThread { toast(msg.take(150)) } }
        }
        btn("Clear the arbiter vs normal comparison") {
            android.app.AlertDialog.Builder(this).setMessage("Clear the arbiter vs normal comparison?")
                .setPositiveButton("Clear") { _, _ -> com.sphynx.research.ResearchMode.clearAb(); toast("Cleared") }
                .setNegativeButton("Cancel", null).show()
        }
        btn("Delete the recorded practice moments") {
            android.app.AlertDialog.Builder(this).setMessage("Delete all recorded practice moments?")
                .setPositiveButton("Delete") { _, _ -> com.sphynx.research.ResearchMode.clearMoments(); toast("Deleted") }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun newsPage() {
        val wn = com.sphynx.core.WhatsNew
        title("WHAT'S NEW")
        val n = wn.counts()
        text("Sphynx v${com.sphynx.core.AppVersion.VERSION} · " +
            com.sphynx.core.WhatsNew.Kind.values().joinToString(" · ") { "${n[it] ?: 0} ${it.label.lowercase()}" }, 12f, color = T.MUTED, describe = false)
        for ((date, entries) in wn.byDate()) {
            val cd = card()
            cardHeader(cd, date)
            for (e in entries) whatsNewRow(cd, e, true)
        }
        text("Changes still waiting to be merged are not listed. \"Not yet checked on a phone\" means the change is built " +
            "and unit tested but has not been confirmed in a real match yet.", 12f, color = T.MUTED)
    }

    // ---- Play: "Running now" (one switch per engine) and "Ready check" (what is missing, with Fix) ----
    // Phase 3 screen specs, Play cards 1-2; backlog item 3. The per-page "running" switches are gone: this is the one place.

    // a row inside a card: name, a state word under it, a switch at the right edge
    private fun cardSwitchRow(cd: LinearLayout, name: String, initial: Boolean, onChange: (Boolean) -> Unit): Pair<Switch, TextView> {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = name; textSize = 16f; setTextColor(c(T.STRONG)) })
        val word = TextView(this).apply { textSize = 13f; setTextColor(c(T.MUTED)) }
        col.addView(word)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = initial
            thumbTintList = ColorStateList(states, intArrayOf(c(T.STRONG), c(T.MUTED)))
            trackTintList = ColorStateList(states, intArrayOf(c(T.TRACK), c(T.STROKE)))
            contentDescription = name
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw)
        col.setOnClickListener { sw.toggle() }
        cd.addView(row)
        return sw to word
    }

    private fun runningNowCard() {
        val cd = card()
        val head = cardHeader(cd, "Running now", "", T.ACCENT)
        val badge = (head.parent as LinearLayout).getChildAt(1) as TextView
        // name, stage, is it on, does it touch the screen
        val engines = listOf(
            Triple("Player", "player", { AiRuntime.playerOn }),
            Triple("Drafter", "draft", { AiRuntime.drafterOn }),
            Triple("Builder", "build", { AiRuntime.builderOn }),
            Triple("Learning", "learn", { AiRuntime.learnOn })
        )
        for ((name, stage, isOn) in engines) {
            var syncing = false
            // an engine that failed to start: a danger row with the reason and Try again (state matrix, Running now)
            val errRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
            val errText = TextView(this).apply { textSize = 13f; setTextColor(c(T.RED)) }
            fun start(): String? =
                if (stage == "learn") AiRuntime.start("learn").also { if (it == null && !ScreenCaptureService.running) askScreenCapture() }
                else startStage(stage)
            fun showError(err: String?) {
                errRow.visibility = if (err == null) View.GONE else View.VISIBLE
                errText.text = err?.let { "Could not start: $it" }.orEmpty()
            }
            val (sw, word) = cardSwitchRow(cd, name, isOn()) { on ->
                if (!syncing) {
                    if (!on) { AiRuntime.stop(stage); showError(null) } else showError(start())
                }
            }
            errRow.addView(errText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            errRow.addView(Button(this).apply {
                text = "Try again"; styleButton(this); contentDescription = "Try again: start $name"
                setOnClickListener { showError(start()) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
            cd.addView(errRow)
            upd {
                if (isOn() && errRow.visibility == View.VISIBLE) showError(null)
                val on = isOn()
                if (sw.isChecked != on) { syncing = true; sw.isChecked = on; syncing = false }
                word.text = PlayStatus.engineWord(on, Cfg.dryRun, touches = stage != "learn")
                word.setTextColor(c(if (on) T.ACCENT else T.MUTED))
            }
        }
        cardSwitchRow(cd, "Dry run: only show where it would tap", Cfg.dryRun) { Cfg.dryRun = it }.second.text = "A safety guard: on by default"
        cardSwitchRow(cd, "Autonomous Drafter + Builder", Cfg.autonomous) { Cfg.autonomous = it }.second.text = "Picks, bans and buys by itself"
        val mode = cardNote(cd)
        upd {
            val b = PlayStatus.runningBadge(AiRuntime.anyOn(), Cfg.dryRun)
            badge.text = b
            badge.setTextColor(c(when (b) { "Live" -> T.GREEN; "Dry run" -> T.GOLD; else -> T.MUTED }))
            mode.text = "Match: ${if (Cfg.ranked) "Ranked" else "Standard"} (detected by itself) · the power button on the floating icon turns the AI on or off in game"
        }
    }

    private fun readyCheckCard() {
        val cd = card()
        val head = cardHeader(cd, "Ready check", "", T.GREEN)
        val badge = (head.parent as LinearLayout).getChildAt(1) as TextView
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cd.addView(list)
        var shown: List<Pair<String, Boolean>>? = null
        fun items(): List<PlayStatus.Item> = listOf(
            PlayStatus.Item("access", "Accessibility service (touches)", AccessibilityController.instance != null, "Granted", "Missing"),
            PlayStatus.Item("overlay", "Display over other apps", Settings.canDrawOverlays(this), "Granted", "Missing"),
            PlayStatus.Item("notify", "Notifications", Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, "Granted", "Missing"),
            PlayStatus.Item("files", "All files access (the app's folder)", com.sphynx.core.Storage.allFilesAccess, "Granted", "Missing"),
            PlayStatus.Item("capture", "Screen capture", ScreenCaptureService.running, "Running", "Off", needed = false),
            batteryItem(),
            PlayStatus.Item("model", "Strategic model (Gemma)", com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC), "Loaded", if (com.sphynx.ai.Roles.loading(com.sphynx.ai.Roles.STRATEGIC)) "Loading" else "Not loaded", needed = false)
        )
        fun fix(key: String) {
            when (key) {
                "access" -> openAccessibility()
                "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                "notify" -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotify)
                "files" -> try { startActivity(com.sphynx.core.Storage.requestIntent(this)) }
                    catch (e: Exception) { toast("Open Settings > Apps > Special app access > All files access > Sphynx") }
                "capture" -> askScreenCapture()
                "battery" -> openBatterySettings()
                "model" -> show("model")
            }
        }
        upd {
            val all = items()
            val summary = PlayStatus.readySummary(all)
            badge.text = summary
            badge.setTextColor(c(if (PlayStatus.allNeededOk(all)) T.GREEN else T.GOLD))
            val key = all.map { it.key + it.word to it.ok } + ("loading" to com.sphynx.ai.Roles.loading(com.sphynx.ai.Roles.STRATEGIC))
            if (key == shown) return@upd
            shown = key
            list.removeAllViews()
            // only what is NOT ready is listed (screen specs, Play card 2); all ready: one line
            val missing = PlayStatus.notReady(all)
            if (missing.isEmpty()) list.addView(TextView(this).apply {
                text = "● Ready to play"; textSize = 15f; setTextColor(c(T.GREEN)); setPadding(0, dp(6), 0, dp(6))
            })
            if (com.sphynx.ai.Roles.loading(com.sphynx.ai.Roles.STRATEGIC)) {
                // the model is loading: a bar and its name (state matrix, Ready check)
                list.addView(TextView(this).apply { text = "Loading ${ModelManager.strategicName()}…"; textSize = 13f; setTextColor(c(T.TEXT)) })
                list.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true; contentDescription = "Loading the model"
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
            }
            for (item in missing) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply { text = item.label; textSize = 15f; setTextColor(c(T.TEXT)) })
                col.addView(TextView(this).apply {
                    // a word and a dot shape, never colour alone
                    text = (if (item.ok) "● " else "○ ") + item.word + (if (!item.needed && !item.ok) " (optional)" else "")
                    textSize = 13f; setTextColor(c(when { item.ok -> T.GREEN; item.needed -> T.RED; else -> T.GOLD }))
                })
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (item.needsFix) {
                    val k = item.key
                    row.addView(Button(this).apply {
                        text = if (k == "capture") "Start" else if (k == "model") "Open" else "Fix"
                        styleButton(this, primary = item.needed)
                        minWidth = dp(72)
                        contentDescription = "${text}: ${item.label}"
                        setOnClickListener { fix(k) }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
                }
                list.addView(row)
            }
        }
    }

    private fun homePage() {
        // the app version (and the installed build, if the package says something else)
        val installed = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        text("Sphynx v${com.sphynx.core.AppVersion.VERSION}" +
            (if (installed != null && installed != com.sphynx.core.AppVersion.VERSION) " · build $installed" else ""), 12f, color = T.MUTED, describe = false)
        val c1 = card()
        // the state banner, in the theme's colour
        c1.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c(T.BANNER), c(T.BANNER_END))).apply {
            cornerRadius = dp(18).toFloat(); setStroke(dp(1), c(T.ACCENT))
        }
        c1.setPadding(dp(16), dp(16), dp(16), dp(16))
        val status = cardHeader(c1, "", "", T.GREEN)
        status.setTextColor(c(T.ON_BANNER)); status.textSize = 22f
        val sChip = (status.parent as LinearLayout).getChildAt(1) as TextView
        sChip.background = shape("#33" + T.ON_BANNER.removePrefix("#"), 20, null)
        val gemma = cardNote(c1).apply { setTextColor(c(T.ON_BANNER)) }
        val capture = cardNote(c1).apply { setTextColor(c(T.ON_BANNER)) }
        upd {
            val on = AiRuntime.anyOn()
            status.text = if (on) AiRuntime.statusLine() else "AI is off"
            sChip.text = if (on) "running" else "stopped"
            sChip.setTextColor(c(T.ON_BANNER))
            gemma.text = "Strategy: " + ModelManager.strategicName() + " – " + com.sphynx.ai.Roles.state(com.sphynx.ai.Roles.STRATEGIC) +
                (if (com.sphynx.ai.Roles.calls(com.sphynx.ai.Roles.STRATEGIC) > 0) " (${com.sphynx.ai.Roles.lastLatencyMs(com.sphynx.ai.Roles.STRATEGIC) / 100 / 10.0} s)" else "") +
                (if (Cfg.tacticalLlmOn) "\nFights: " + ModelManager.tacticalName() + " – " + com.sphynx.ai.Roles.state(com.sphynx.ai.Roles.TACTICAL) +
                    (if (com.sphynx.ai.Roles.calls(com.sphynx.ai.Roles.TACTICAL) > 0) " (${com.sphynx.ai.Roles.lastLatencyMs(com.sphynx.ai.Roles.TACTICAL)} ms)" else "") else "")
            capture.text = "Screen: " + (if (FrameHub.isLive()) FrameHub.source else "not captured") +
                " · ${"%.0f".format(FrameProcessor.processedFps)} fps · frame→touch ${"%.0f".format(com.sphynx.core.Speed.total())} ms"
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

        runningNowCard()
        readyCheckCard()

        title("WHAT THE AI SEES")
        val noCapture = emptyState(content, "No screen capture: the AI sees nothing yet.", "Start screen capture") { askScreenCapture() }
        val preview = PreviewView(this)
        content.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        upd {
            val live = FrameHub.isLive()
            noCapture.visibility = if (live) View.GONE else View.VISIBLE
            preview.refresh()
        }

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
                meRow.addView(com.sphynx.app.IconCache.view(this, h ?: "?", true, dp(44)), LinearLayout.LayoutParams(dp(44), dp(44)))
                meRow.addView(TextView(this).apply { text = h ?: "Hero not known yet"; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(dp(12), 0, 0, 0) })
            }
        }
        upd {
            val s = GameStateTracker.state
            val hero = GameStateTracker.ownHero()
            detected.text = buildString {
                if (s.selfHp >= 0) {
                    append("HP ").append(((if (s.selfHpRaw >= 0) s.selfHpRaw else s.selfHp) * 100).toInt()).append("%")
                    if (s.selfExtra > 0.01f) append(" + ").append(when (com.sphynx.game.HeroKit.barModel(s.ownHero)) {
                        com.sphynx.game.HeroKit.BarModel.ARMOR -> "armour"
                        com.sphynx.game.HeroKit.BarModel.STORED -> "stored energy"
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
        val hist = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(c(T.TEXT)); setLineSpacing(0f, 1.15f) }
        hc.addView(hist)
        upd {
            val h = ActionExecutor.history(12)
            hist.text = if (h.isEmpty()) "Nothing yet: the actions appear here once the Player plays." else h.joinToString("\n")
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
        runningLine("Player") { AiRuntime.playerOn }
        val info = text("", 13f)
        upd {
            info.text = if (com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC)) "Fast rules play; Gemma adjusts the plan every ${Cfg.strategyEverySec}s or when a fight starts."
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
        val nameBox = field("My player name (in-game name, as written in the game)", com.sphynx.game.PlayerName.get(), false) { }
        nameBox.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        fun saveName(v: String) { com.sphynx.game.PlayerName.set(v); toast(if (v.isBlank()) "Player name cleared: your card = the first card" else "Player name: $v") }
        nameBox.setOnEditorActionListener { v, _, _ -> saveName(v.text.toString().trim()); false }
        btn("Enter name") { saveName(nameBox.text.toString().trim()) }
        val heroInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            val p = HeroDb.profile(h)
            heroInfo.text = "Using: ${h ?: "unknown"} (${GameStateTracker.ownHeroSource()})\n" +
                "Role ${p.role.name.lowercase()}, ${if (HeroDb.isRanged(h)) "ranged" else "melee"}, dash ${HeroDb.dashSlot(h) ?: "none"}, ultimate: ${p.ult.name.lowercase()}" +
                (com.sphynx.game.CooldownTracker.ultCooldown(h)?.let { " (cooldown ${it.toInt()} s)" } ?: "") + "\n" +
                "Learned aggression adjust: ${"%+.2f".format(Cfg.learnedBias(h ?: "unknown"))}\n" +
                "Heroes whose looks are learned: ${IconMemory.heroCount()} (portrait), ${com.sphynx.vision.HeroVerifier.learnedCount()} (skill icons)\n" +
                "Hero check: ${com.sphynx.vision.HeroVerifier.status}"
        }

        title("BATTLE SPELL")
        // one tap picks it (screen specs 2.1, card 2): Detect, or one of the battle spells
        segmented(listOf("auto" to "Detect") + SpellDb.names.map { it to it },
            if (Cfg.mySpell in SpellDb.names) Cfg.mySpell else "auto", perRow = 3) { Cfg.mySpell = it }
        val spellInfo = text("", 13f)
        upd {
            spellInfo.text = "Using: ${GameStateTracker.spellDisplay() ?: GameStateTracker.spell() ?: "not known yet"} (${GameStateTracker.spellSource()}). " +
                "Spells whose icon is learned: ${IconMemory.spellCount()}"
        }

        title("LANE")
        segmented(listOf("auto" to "Auto", "exp" to "EXP", "gold" to "Gold", "mid" to "Mid", "roam" to "Roam", "jungle" to "Jungle"),
            Cfg.lane, perRow = 3) { Cfg.lane = it }
        text("Auto: Retribution = jungle, else the lane comes from the hero's role. EXP is the top lane, Gold the bottom " +
            "lane; Roam stays with the team.")
        val jungleInfo = text("", 13f)
        upd {
            val st = GameStateTracker.state
            jungleInfo.text = "Minimap: you ${if (st.minimapSelf != null) "found" else "not found"}, " +
                "${st.camps.count { !it.enemy }} of your camps up (${st.camps.count { it.buff && !it.enemy }} buffs), ${st.camps.count { it.enemy }} enemy camps seen\nJungle path: ${com.sphynx.game.JungleNav.status}\n${com.sphynx.ai.TacticalEngine.invadeStatus}"
        }
        val laneInfo = text("", 13f)
        val rotModeBtn = btn("") {
            val lbl = com.sphynx.game.RoleProfile.cycleMode()
            toast("Rotations & Pathing: $lbl")
        }
        val rotMetaInfo = text("", 12.5f, color = "#9AA0A6")
        upd {
            val (lane, why) = GameStateTracker.lane()
            laneInfo.text = "Playing: ${lane.label} ($why)"
            rotModeBtn.text = "Rotations & Pathing: ${com.sphynx.game.RoleProfile.modeLabel()}  (tap to change)"
            rotMetaInfo.text = com.sphynx.game.RoleProfile.activeRotationSummary(GameStateTracker.state)
        }
        val tuneInfo = text("", 12f, color = "#9AA0A6")
        upd { tuneInfo.text = "Learned by itself: base to lane ${Cfg.laneTravelSec} s, enemy tower range ${Cfg.towerRangePct}% of the minimap" }

        title("ENEMY TEAM")
        // five slots: a portrait once a hero is read, "?" until then (screen specs 2.1, card 4)
        val slots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(slots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        var slotsFor: List<String>? = null
        upd {
            val team = GameStateTracker.enemyTeam.take(5)
            if (team == slotsFor) return@upd
            slotsFor = team
            slots.removeAllViews()
            for (i in 0 until 5) {
                val name = team.getOrNull(i)
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val px = dp(48)
                col.addView(com.sphynx.app.IconCache.view(this, name ?: "?", true, px).apply {
                    contentDescription = (name ?: "Enemy ${i + 1}: not read yet") + ". Tap to choose the hero"
                }, LinearLayout.LayoutParams(px, px))
                // tap a slot to set the hero by hand (screen specs 2.1, card 4: "filled by detection or by tap")
                val slot = i
                col.isClickable = true
                col.setOnClickListener { pickEnemy(slot) }
                col.addView(TextView(this).apply {
                    text = name ?: "?"; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(c(if (name != null) T.TEXT else T.MUTED))
                })
                slots.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val enemyInfo = text("", 13f)
        upd { enemyInfo.text = com.sphynx.game.EnemyIntel.text() }

        title("Game knowledge")
        val know = text("", 12f)
        upd {
            val st = GameStateTracker.state
            val hero = GameStateTracker.ownHero()
            know.text = "Your hero ($hero) in the wiki's laning lists: ${com.sphynx.game.WikiLanes.describe(hero)}\n" +
                "Timeline: " + com.sphynx.game.MatchEvents.ALL.joinToString("; ") { "%d:%02d %s".format(it.sec / 60, it.sec % 60, it.what) } + "\n" +
                "Role tip now (${st.lane.label}): " + com.sphynx.game.RoleTips.of(st.lane, st.matchSec.coerceAtLeast(0), st.ownHero) + "\n" +
                (st.spell?.let { sp -> "Battle spell $sp: ${com.sphynx.game.SpellInfo.effect[sp] ?: "-"} (cooldown ${com.sphynx.game.SpellInfo.cooldown[sp]?.toInt() ?: "?"} s)\n" } ?: "") +
                "Jungle:\n" + com.sphynx.game.JungleDb.describe()
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
            val pb = com.sphynx.game.HeroPlaybook.of(h)
            val key = "$h|${pb.combo}|${pb.tips.size}|${pb.source}"
            if (key == pKey) return@upd
            pKey = key
            pHead.removeAllViews()
            pHead.addView(com.sphynx.app.IconCache.view(this, h ?: "?", true, dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
            val nm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            nm.addView(TextView(this).apply { text = h ?: "No hero yet"; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
            nm.addView(chip(pb.style, if (pb.learned) T.ACCENT else T.MUTED))
            pHead.addView(nm, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            pPlan.text = "Combo: " + com.sphynx.game.ComboBook.text(h).first +
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
                !com.sphynx.ai.GroqClient.anyKey -> toast("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Storage/Input folder")
                else -> {
                    toast("Learning $h (skills, then the guide)…")
                    com.sphynx.game.SkillBook.fetch(h) { m1 ->
                        com.sphynx.ai.MetaAdvisor.updateHero(h) { m2 -> runOnUiThread { toast("$h learned. " + m2.take(80)) } }
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
            skInfo.text = (if (h == null) "Pick a hero first." else com.sphynx.game.SkillBook.describe(h)) +
                "\nLast cast check: ${ActionExecutor.lastFeedback}" +
                "\nAll heroes: ${com.sphynx.game.SkillBook.learnAllProgress.ifEmpty { "-" }}"
        }
        val learnOne = btn("Learn this hero's skills online") {
            val h = GameStateTracker.ownHero()
            if (h == null) toast("Pick a hero first") else com.sphynx.game.SkillBook.fetch(h) { m -> runOnUiThread { toast(m.take(120)) } }
        }
        needsInternet(learnOne)
        btn("Learn all heroes' skills (background)") {
            com.sphynx.game.SkillBook.learnAll { m -> runOnUiThread { toast(m) } }
            toast("Started")
        }
        btn("Stop learning all heroes") { com.sphynx.game.SkillBook.stopLearnAll() }
    }

    // Hero & match › Play style (Phase 3 screen specs 2.2, backlog item 4): how the AI plays your hero,
    // split off the old Player page, which keeps the hero itself (now "Hero")
    // choose an enemy hero for one of the five slots (a list with a search box); "Clear" empties the slot
    private fun pickEnemy(slot: Int) {
        val all = HeroDb.allNames.sorted()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val q = EditText(this).apply { hint = "Search a hero"; isSingleLine = true }
        val lv = android.widget.ListView(this)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, all.toMutableList())
        lv.adapter = adapter
        box.addView(q)
        box.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString()?.trim()?.lowercase().orEmpty()
                adapter.clear(); adapter.addAll(all.filter { t.isEmpty() || t in it.lowercase() })
            }
        })
        fun set(hero: String?) {
            val team = GameStateTracker.enemyTeam.toMutableList()
            while (team.size <= slot) team.add("")
            team[slot] = hero.orEmpty()
            GameStateTracker.enemyTeam = team.filter { it.isNotEmpty() }.distinct().take(5)
        }
        val dlg = AlertDialog.Builder(this).setTitle("Enemy hero ${slot + 1}").setView(box)
            .setNeutralButton("Clear") { _, _ -> set(null) }
            .setNegativeButton("Cancel", null).create()
        lv.setOnItemClickListener { _, _, pos, _ -> set(adapter.getItem(pos)); dlg.dismiss() }
        dlg.show()
    }

    private fun stylePage() {
        title("SKILL COMBOS (MANUAL OR BUILT-IN)")
        text("Uses your Manual Combo if set for the selected hero; if Manual Combo is empty, uses the Built-In Optimized Multi-Combo Library (online combo overrides are disabled).", 12f, color = "#9AA0A6")
        switch("Automatic situational combo selection (Burst / Chase / Poke / Pre-Lv4 / Retreat)", Cfg.autoSituationalCombo) {
            Cfg.autoSituationalCombo = it
        }
        fun selectedComboHero(): String? =
            Cfg.manualHero.trim().takeIf { it.isNotEmpty() }?.let { HeroDb.match(it) ?: it }
                ?: GameStateTracker.ownHero()
                ?: HeroDb.match(Cfg.myHero)

        // RECOMMENDED LANES for the selected hero (owner, 2026-11-11): your setting comes first (game.HeroLanes)
        val lanesBox = field("Recommended lanes for selected hero: primary, secondary, \"flex\" if it fits any lane " +
            "(e.g. exp, jungle   or   exp, jungle, flex; empty = automatic: online guide + wiki)", Cfg.manualLanes(selectedComboHero()).orEmpty(), false) { }
        val lanesInfo = text("", 12f, color = "#9AA0A6")
        fun showLanes() {
            val h = selectedComboHero()
            lanesInfo.text = if (h == null) "Select a hero under YOUR HERO first" else
                "$h: " + com.sphynx.game.HeroLanes.of(h).let { "${it.text()}  [${it.source}]" }
        }
        showLanes()
        btn("Save recommended lanes for this hero") {
            val h = selectedComboHero()
            val raw = lanesBox.text.toString()
            when {
                h == null -> toast("Select or enter a hero under YOUR HERO first")
                raw.isNotBlank() && com.sphynx.game.HeroLanes.parse(raw) == null ->
                    toast("Use exp, gold, mid, roam, jungle (primary first), and flex if it fits any lane")
                else -> {
                    Cfg.setManualLanes(h, raw.ifBlank { null })
                    toast(if (raw.isBlank()) "$h: lanes back to automatic (online guide + wiki)" else "$h: lanes saved")
                    showLanes()
                }
            }
        }

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
        // the combo pieces: two rows of three equal buttons, each label on one line (owner, 2026-11-15)
        val pieces = listOf("+ S1" to "s1", "+ S2" to "s2", "+ S3" to "s3", "+ Ult" to "ult", "+ Basic (a)" to "a", "+ Spell" to "spell")
        for (rowPieces in pieces.chunked(3)) {
            val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((n, piece) in rowPieces.withIndex()) {
                val (lbl, tok) = piece
                quickRow.addView(Button(this).apply {
                    text = lbl; styleButton(this)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnClickListener { appendComboToken(tok) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = if (n == 0) 0 else dp(4); marginEnd = if (n == rowPieces.lastIndex) 0 else dp(4)
                })
            }
            content.addView(quickRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }

        btn("Save manual combo for this hero") { saveManualComboForHero(manualComboBox.text.toString()) }
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
        upd { lvInfo.text = "Badges remembered: ${com.sphynx.vision.LevelReader.templatesKnown} of 15 (levels 1, 2, 9 and 12 are built in)" }
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
            val wm = com.sphynx.vision.WallMap
            moveInfo.text = "Wall map: ${if (wm.ready) "ready" else "building (needs a few seconds in a match)"}\n" +
                (if (wm.ready) "Minimap terrain (40x40 cells): walls ${wm.count(com.sphynx.vision.Terrain.WALL)}, " +
                    "bushes ${wm.count(com.sphynx.vision.Terrain.BUSH)}, lane paths ${wm.count(com.sphynx.vision.Terrain.LANE)}, " +
                    "jungle ${wm.count(com.sphynx.vision.Terrain.JUNGLE)}, river ${wm.count(com.sphynx.vision.Terrain.RIVER)}\n" else "") +
                "K/D/A seen this match: ${GameStateTracker.kills} / ${GameStateTracker.deaths} / ${GameStateTracker.assists}" +
                "  (jungle camps killed: ${com.sphynx.game.JungleNav.campsKilled})\n" +
                "Monsters on screen: ${gs.monsters.size}   Bushes on screen: ${gs.bushes.size}" + (if (gs.inBush) "   (you are in a bush)" else "") +
                "\nVision: ${"%.0f".format(FrameProcessor.processedFps)} frames/s\nSpeed: ${com.sphynx.core.Speed.text()}" +
                "\n" + com.sphynx.game.GameMemory.text(System.currentTimeMillis()) +
                "\n" + com.sphynx.ai.Deliberation.text(gs.lane)
        }

        title("SKILLS & ITEMS")
        val upInfo = text("", 12f, color = "#9AA0A6")
        upd {
            val h = GameStateTracker.ownHero()
            upInfo.text = "Skill upgrades are automatic: " + com.sphynx.game.SkillOrder.of(h).joinToString(" > ") +
                " for ${h ?: "your hero"} (${com.sphynx.game.SkillOrder.source(h)})"
        }
        switch("Buy items (quick buy)", Cfg.playerQuickBuy) { Cfg.playerQuickBuy = it }

        title("KILLS, TOWERS & GOLD")
        text("Priority: low-HP enemies, then camps and minions, then towers (only with your minions). Defend your towers; destroy the base to win.", 12f, color = "#9AA0A6")
        val towerInfo = text("", 13f)
        upd {
            val st = GameStateTracker.state
            val n = com.sphynx.game.TowerLogic.nearest(st)
            towerInfo.text = "Minimap: ${st.towers.size} enemy towers (${st.towers.count { it.bonus }} bonus), " +
                "${st.allyTowers.size} of yours, enemy base ${if (st.enemyBase != null) "seen" else "not seen"}\n" +
                (if (n == null) "Your position on the minimap is not known" else
                    "Nearest enemy tower: ${(n.second * 100).toInt()}% of the map away" +
                        (if (n.second < com.sphynx.game.TowerLogic.range()) " - INSIDE its range" else "") +
                        (if (com.sphynx.game.TowerLogic.canPush(st)) " - safe to push" else ""))
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
                "\nLearned: " + com.sphynx.learning.Coach.summary(GameStateTracker.ownHero())
        }

        val learnInfo = text("", 13f)
        DemoRecorder.refreshTotal()
        upd {
            learnInfo.text = "Recorded moments: ${DemoRecorder.total} (${DemoRecorder.recorded} of you this session)\n" +
                "Last seen from you: ${DemoRecorder.lastAction}\nYour moves: ${ImitationPolicy.status}" +
                (if (Cfg.coachMode && ImitationPolicy.size > 0) ", similarity now ${(ImitationPolicy.lastMatch * 100).toInt()}%" else "")
        }
        val cdInfo = text("", 12f, color = "#9AA0A6")
        upd { cdInfo.text = "Buttons now: " + com.sphynx.game.CooldownTracker.screenState() }
        switch("Send a screenshot with plan questions (slower)", Cfg.strategyWithImage) { Cfg.strategyWithImage = it }

        // (Vision tuning moved to the Home page - v36.93)
        // everything on this page that deletes or resets, together at the end (Phase 3 screen specs, "Data & resets")
        title("DATA & RESETS")
        btn("Clear manual combo (use Built-In)") {
            val h = selectedComboHero()
            confirm("Clear the manual combo of ${h ?: "this hero"}? The built-in combo is used instead.", "Clear") {
                if (h != null) Cfg.setManualCombo(h, null)
                manualComboBox.setText("")
                toast("${h ?: "Hero"}: using Built-In optimized combos")
            }
        }
        btn("Forget the remembered level badges") {
            confirm("Forget the remembered level badges? They are learned again in the next matches.", "Forget") {
                com.sphynx.vision.LevelReader.forgetTemplates(); toast("Forgotten")
            }
        }
        btn("Forget what Coach learned for this hero") {
            val hero = GameStateTracker.ownHero()
            confirm("Forget what Coach learned for ${hero ?: "this hero"}?", "Forget") {
                com.sphynx.learning.Coach.forget(hero); toast("Forgotten")
            }
        }
        btn("Delete my recorded play") {
            AlertDialog.Builder(this).setTitle("Delete all recorded play?")
                .setPositiveButton("Delete") { _, _ -> DemoRecorder.clear { runOnUiThread { toast("Deleted") } } }
                .setNegativeButton("Cancel", null).show()
        }
        btn("Reset learned aggression for this hero") {
            val hero = GameStateTracker.ownHero() ?: "unknown"
            confirm("Reset the learned aggression for $hero to neutral?", "Reset") {
                Cfg.setLearnedBias(hero, 0f)
                toast("Reset")
            }
        }
    }

    // =====================================================================
    //  DRAFT
    // =====================================================================

    private fun draftPage() {
        runningLine("Drafter") { AiRuntime.drafterOn }
        title("MATCH TYPE")
        val phase = text("", 13f, color = "#9AA0A6")
        upd { phase.text = "Detected by itself on the hero-select screen (a ban stage = ranked): now ${if (Cfg.ranked) "RANKED" else "STANDARD"}" +
            (if (!com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC)) " (needs the Gemma model to tell them apart)" else "") + "\nScreen now: ${GameStateTracker.state.phase.label}" }

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
        segmented(listOf("team" to "Team", "solo" to "Solo"), if (Cfg.draftSolo) "solo" else "team") { Cfg.draftSolo = it == "solo" }
        title("Ban & pick")
        val dcard = card()
        cardHeader(dcard, "Pick")
        val pickGrid = IconGrid(dcard, hero = true, sizeDp = 48)
        dcard.addView(TextView(this).apply { text = "Ban"; textSize = 15f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
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
                en.joinToString { "$it (${HeroDb.damageText(it).substringBefore(" (")})" } + "\n" + (com.sphynx.game.ItemClass.defenseAgainst(en) ?: "")
        }
        title("AUTONOMOUS RANKED DRAFT", Capability.EXPERIMENTAL)
        text("Ranked: taps the recommended hero and locks in. Keep Dry run on until the marker is right.", 13f)
        val err = text("", 12f, color = "#EF9A9A")
        upd { err.text = StrategicAdvisor.lastError }
    }

    // =====================================================================
    //  BUILD
    // =====================================================================

    private fun buildPage() {
        runningLine("Builder") { AiRuntime.builderOn }

        // ---- your build ----
        val bc = card()
        val bHead = cardHeader(bc, "", "", T.GOLD)
        val bChip = (bHead.parent as LinearLayout).getChildAt(1) as TextView
        val buildGrid = IconGrid(bc, hero = false, sizeDp = 44)
        val bNote = cardNote(bc)
        // ---- experimental builds (BuildLab) ----
        run {
            val lab = com.sphynx.ai.BuildLab
            val hero = GameStateTracker.ownHero() ?: HeroDb.match(Cfg.manualHero) ?: HeroDb.match(Cfg.myHero)
            val xc = card()
            cardHeader(xc, "Experimental builds", hero ?: "no hero", T.GOLD)
            asDescription(cardText(xc, 12f).apply { text = "Builds that try something different from the meta, made from ${hero ?: "your hero"}'s real skills and passive, its kit, " +
                "how it plays, the original server's patch and meta, and what the Library knows about each item. Real items and spells only." })
            val xs = lab.builds(hero)
            if (xs.isEmpty()) cardText(xc, 12.5f).text = if (hero == null) "Choose your hero on Hero & match › Hero first." else "None made yet."
            else cardText(xc, 11.5f).text = "Made for patch ${lab.madeFor(hero).ifBlank { "?" }}" + (lab.chosen(hero)?.let { " · in use: ${it.name}" } ?: "")
            for ((i, b) in xs.withIndex()) {
                xc.addView(TextView(this).apply { text = "${i + 1}. ${b.name}"; textSize = 14.5f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(2)) })
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
                    if (hero == null) { toast("Choose your hero on Hero & match › Hero first"); return@setOnClickListener }
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
        dc.addView(TextView(this).apply { text = "Recommended bans"; textSize = 15f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
        val banGrid = IconGrid(dc, hero = true, sizeDp = 46)
        val dNote = cardNote(dc)
        upd {
            val hero = GameStateTracker.ownHero()
            val role = HeroDb.role(hero)
            val meta = if (Cfg.useOnlineMeta) com.sphynx.game.MetaStore.hero(hero) else null
            val xb = com.sphynx.ai.BuildLab.chosen(hero)
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
            val counters = com.sphynx.game.CounterRules.counters(hero, enemies)
            counterGrid.set(counters.map { it.item }, counters.map { it.reason })
            cNote.text = when {
                enemies.isEmpty() -> "Waiting for the enemy line-up."
                counters.isEmpty() -> "None needed against " + enemies.joinToString() + "."
                else -> "Against " + enemies.joinToString() + "."
            } + (com.sphynx.game.ItemClass.defenseAgainst(enemies)?.let { "\n$it" } ?: "")
            val (picks, bans, src) = draftLists()
            dSrc.text = "Recommended picks"
            pickGrid.set(picks)
            banGrid.set(bans)
            dNote.text = if (picks.isEmpty() && bans.isEmpty()) "No draft yet: update the patch meta (Meta page) or start the Drafter." else "From $src."
        }

        title("Item actives")
        switch("Press item actives (Frozen, Wind Chant, Conceal)", Cfg.builderActives) { Cfg.builderActives = it }
        text("In every match the Player does not play (Ranked, Classic). Frozen and Wind Chant when you are low on HP with enemies on you; Conceal only when the Player switch is on (it needs the engage / retreat advice).", 12f, color = T.MUTED)

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
            grid.set(com.sphynx.game.ItemClass.advancedOnly(items))
        }
    }

    // picks / bans to show: the last draft screen read, else the patch meta for your role
    private fun draftLists(): Triple<List<String>, List<String>, String> {
        val d = StrategicAdvisor.draft
        if (d != null && (d.pick.isNotEmpty() || d.ban.isNotEmpty())) return Triple(d.pick.take(10), d.ban.take(10), "the draft screen")
        val g = com.sphynx.game.MetaStore.general()
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
        val ll = com.sphynx.learning.LibraryLearner
        val lc = card()
        cardHeader(lc, "What the app knows")
        val lt = cardText(lc, 13f)
        // how fresh the library is and where it came from (backlog item 9)
        val lFresh = cardNote(lc)
        upd {
            val at = ll.lastRunAt()
            lFresh.text = if (at > 0) Freshness.line(at, listOf("the Mobile Legends wiki", "the online guides"))
                else "Never learned as a whole yet (single heroes may be)."
        }
        upd {
            lt.text = ll.counts() + "\n" + (if (ll.running) "Learning: " else "Last run: ") + ll.progress +
                com.sphynx.app.IconCache.loadAllStatus.takeIf { it != "-" }?.let { "\nIcons: $it" }.orEmpty() +
                com.sphynx.app.IconCache.lastProblem.takeIf { it != "-" }?.let { "\nPictures: $it (tried again every 2 min)" }.orEmpty()
        }
        val lb = btn("") {
            if (ll.running) { ll.stop(); toast("Stopping after the current question") }
            else ll.start { m -> runOnUiThread { toast(m) } }
        }
        upd { lb.text = if (ll.running) "Stop learning" else "Learn the whole database online" }
        styleButton(lb, primary = true)
        // learning: an indeterminate bar while it runs (the button above is its Stop)
        val lbar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; contentDescription = "Learning the database"
        }
        content.addView(lbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
        upd { lbar.visibility = if (ll.running) View.VISIBLE else View.GONE }
        needsInternet(lb)
        val loadIconsBtn = btn("") {
            if (com.sphynx.app.IconCache.loadingAll) {
                toast("Already loading icons: ${com.sphynx.app.IconCache.loadAllStatus}")
            } else {
                toast("Force-loading all Library icons (heroes, skills, spells & equipment)…")
                com.sphynx.app.IconCache.forceLoadAll(this) { msg ->
                    runOnUiThread { toast(msg); show("library") }
                }
            }
        }
        upd {
            loadIconsBtn.text = if (com.sphynx.app.IconCache.loadingAll) "Loading all icons… (${com.sphynx.app.IconCache.loadAllStatus})" else "Load all icons"
            styleButton(loadIconsBtn, primary = !com.sphynx.app.IconCache.loadingAll)
        }
        text("Skills come from each hero's wiki page (real skills and passive only; the online model just copies them and every name is checked against the page). Every hero's skills and online guide, then every item - skipping what is known, waiting out Groq's limit by itself (all of it takes a few days of the app being open).", 12f, color = T.MUTED)

        // tabs
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, label) in listOf("heroes" to "Heroes", "spells" to "Spells", "items" to "Equipment", "terms" to "Terms")) {
            tabs.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f; maxLines = 1
                setPadding(dp(4), 0, dp(4), 0); minWidth = 0; minimumWidth = 0
                fitWords(this, 13f, 9f)        // "Equipment" used to break as "Equipmen/t" (audit finding 23)
                contentDescription = "$label tab"
                background = ripple(if (k == libTab) T.PRIMARY else T.SURF2, 16)
                setTextColor(Color.parseColor(if (k == libTab) T.ON_PRIMARY else T.TEXT))
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
            top.addView(com.sphynx.app.IconCache.view(this, name, hero, px, file), LinearLayout.LayoutParams(px, px))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            col.addView(TextView(this).apply { text = name; textSize = 15f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD) })
            col.addView(TextView(this).apply { text = (if (ok) "● " else "○ ") + status; textSize = 13f; setTextColor(c(if (ok) T.GREEN else T.MUTED)) })
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
                            cell.addView(com.sphynx.app.IconCache.view(this@MainActivity, if (file != null) label else skill, false, sz, file), LinearLayout.LayoutParams(sz, sz))
                            cell.addView(TextView(this@MainActivity).apply { text = label; textSize = 10.5f; setTextColor(c(T.MUTED)); gravity = Gravity.CENTER; maxWidth = dp(70) })
                            stripRow.addView(cell)
                        }
                        strip.visibility = if (ic.isEmpty()) View.GONE else View.VISIBLE
                    }
                    fill()
                    // the real skill pictures are read from the hero's wiki page the first time (no tokens)
                    if (hero && !com.sphynx.game.WikiSkills.iconsKnown(name)) Thread {
                        val m = try { com.sphynx.game.WikiSkills.fetchIcons(name) } catch (e: Exception) { null }
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
            val kit = com.sphynx.game.SkillBook.get(h)
            val out = ArrayList<Pair<String, String>>()
            // the real pictures from the wiki page (passive + every skill), when read
            val real = com.sphynx.game.WikiSkills.icons(h)
            if (real.isNotEmpty()) {
                val hints = com.sphynx.game.HeroKit.buttonHints[h]
                real["passive"]?.let { out.add("file:$it" to "Passive") }
                // every button of the hero's kit: the wiki picture, else (v36.68) the picture named after
                // the skill - unless that name is also an item / spell (Layla, Arlott)
                for (sl in listOf("s1", "s2", "s3", "ult").filter { it in real || it in com.sphynx.game.HeroKit.slots(h) }) {
                    val lb = hints?.get(sl) ?: when (sl) { "s1" -> "Skill 1"; "s2" -> "Skill 2"; "s3" -> "Skill 3"; else -> "Ultimate" }
                    val f = real[sl]
                    if (f != null) out.add("file:$f" to lb)
                    else kit[sl]?.name?.takeIf { n -> n.isNotBlank() && n != sl && com.sphynx.game.ItemDb.canonical(n) == null &&
                        com.sphynx.game.SpellDb.match(n) == null }?.let { n -> out.add(n to lb) }
                }
                return out
            }
            kit.passiveText.substringBefore(" - ").trim().takeIf { it.isNotBlank() && it.length < 40 && kit.passiveText.contains(" - ") }?.let { out.add(it to "Passive") }
            val hints = com.sphynx.game.HeroKit.buttonHints[h]
            for (sl in com.sphynx.game.HeroKit.slots(h)) kit[sl]?.let { k ->
                if (k.name.isNotBlank() && k.name != sl) out.add(k.name to (hints?.get(sl) ?: when (sl) { "s1" -> "Skill 1"; "s2" -> "Skill 2"; "s3" -> "Skill 3"; else -> "Ultimate" }))
            }
            return out
        }

        fun heroDetail(h: String): String = buildString {
            ll.lastError(h, "skills")?.takeIf { !com.sphynx.game.SkillBook.known(h) }?.let { append("⚠ Skills could not be learned: ").append(it).append('\n') }
            ll.lastError(h, "guide")?.takeIf { !com.sphynx.game.MetaStore.has(h) }?.let { append("⚠ Guide could not be learned: ").append(it).append('\n') }
            append("(Long-press this hero to learn its skills and guide now.)\n")
            com.sphynx.game.WikiSkills.iconsDebug(h)?.let { d ->
                val got = com.sphynx.game.WikiSkills.icons(h).keys
                if (com.sphynx.game.HeroKit.slots(h).any { it !in got }) append("⚠ Skill pictures on the wiki page: ").append(d).append('\n')
            }
            val kit = com.sphynx.game.SkillBook.get(h)
            val pb = com.sphynx.game.HeroPlaybook.of(h)
            append(HeroDb.role(h).name.lowercase()).append(if (HeroDb.isRanged(h)) ", ranged" else ", melee")
            append(" · dash ").append(HeroDb.dashSlot(h) ?: "none")
            // recommended lanes (online guide + wiki): what the automatic lane uses
            com.sphynx.game.HeroLanes.of(h).let { r ->
                append("\nRecommended lanes: ").append(r.primary.label)
                r.secondary?.let { append(" (primary) · ").append(it.label).append(" (secondary)") }
                append(if (r.flex) " · FLEX PICK: can be played in almost any lane" else "")
                append("  [").append(r.source).append("]")
                val wiki = com.sphynx.game.WikiLanes.lanes(h)
                if (wiki.isNotEmpty()) append("\nWiki lists it for: ").append(wiki.joinToString { it.label })
            }
            com.sphynx.game.CooldownTracker.ultCooldown(h)?.let { append(" · ultimate cooldown ").append(it.toInt()).append(" s") }
            val dmgT = HeroDb.damageText(h)
            append("\nDamage type: ").append(dmgT).append(" · its equipment: ")
                .append(if (dmgT.startsWith("Physical + Magic")) "Attack or Magic (Adaptive Attack fits both)" else if (dmgT.startsWith("Magic")) "Magic (Magic Power) - Adaptive Attack fits too" else "Attack (Physical Attack) - Adaptive Attack fits too")
            append("\nPassive: ").append(kit.passiveText.ifBlank { "not learned yet (every hero has its own: learn it from the wiki)" }.take(320))
            append("\nSkills (").append(kit.source).append("):")
            append("\nKit: ").append(com.sphynx.game.HeroKit.describe(h))
            for (sl in com.sphynx.game.HeroKit.slots(h)) kit[sl]?.let { k ->
                append("\n• ").append(sl).append(if (k.name.isNotBlank() && k.name != sl) " ${k.name}" else "").append(": ")
                append(k.kind.name.lowercase()).append(", range ").append((k.range * 100).toInt()).append("%")
                if (k.hardCc) append(", hard CC") else if (k.slow) append(", slow")
                if (k.mobility) append(", dash"); if (k.heal) append(", heal"); if (k.shield) append(", shield")
                append(", ").append(k.mode.name.lowercase())
                if (k.cooldownSec > 0f) append(", ").append(k.cooldownSec.toInt()).append(" s")
                com.sphynx.game.GameTerms.ccIn(k.description).takeIf { it.isNotEmpty() }?.let { append("\n   crowd control: ").append(it.joinToString()) }
                if (k.description.isNotBlank() && k.description != k.name) append("\n   ").append(k.description.take(400))
            }
            append("\nHP back from hitting: ").append(com.sphynx.game.Sustain.describe(h))
            append("\nPlays as: ").append(pb.style).append(" · combo ").append(pb.combo)
            val m = com.sphynx.game.MetaStore.hero(h)
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
                val kit = com.sphynx.game.SkillBook.get(h)
                val skills = com.sphynx.game.SkillBook.known(h)
                val guide = com.sphynx.game.MetaStore.has(h)
                val skillsSt = when {
                    skills -> if (kit.source.startsWith("wiki")) "from the wiki" else kit.source
                    com.sphynx.game.SkillBook.unverified(h) -> "AI-written, not trusted (re-learn)"
                    !kit.source.startsWith("role defaults") -> "built in, unchecked"
                    else -> "not learned"
                }
                val errS = if (!skills) ll.lastError(h, "skills") else null
                val errG = if (!guide) ll.lastError(h, "guide") else null
                val st = (if (errS != null || errG != null) "⚠ last try failed · " else "") + HeroDb.role(h).name.lowercase() +
                    " · " + com.sphynx.game.HeroLanes.of(h).text() + " · skills " + skillsSt +
                    " · guide " + (if (guide) "learned" else "not yet")
                row(h, true, st, skills && guide, onLong = {
                    toast("Learning $h now (wiki skills + guide)…")
                    ll.learnOne(h) { m -> runOnUiThread { toast(m); show("library") } }
                }, icons = { heroSkillIcons(h) }) { heroDetail(h) }
            }
            "spells" -> {
                for (sp in SpellDb.names.filter { ok(it) }) {
                    val cd = com.sphynx.game.SpellInfo.cooldown[sp]
                    row(sp, false, "cooldown ${cd?.toInt() ?: "?"} s · built in", true) {
                        (com.sphynx.game.SpellInfo.effect[sp] ?: "-") + "\n" + (spellUse[sp] ?: "") +
                            "\nOn an enemy: " + com.sphynx.game.EnemyIntel.spellNote(sp)
                    }
                }
                // the Retribution kinds (from the jungle item; seen only in a match)
                // (your guide - v36.76 - and the wiki's exact effect text; never an AI's)
                val ic = com.sphynx.game.ItemClass
                val rs = com.sphynx.game.ItemDb.retributions.filter { ok(it) }
                if (rs.isNotEmpty()) {
                    list.addView(TextView(this).apply { text = "Blessed Retribution"; textSize = 15f; setTextColor(c(T.ACCENT)); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), dp(14), 0, dp(2)) })
                    list.addView(TextView(this).apply { text = ic.RETRI_INTRO; textSize = 13f; setTextColor(c(T.MUTED)); setPadding(dp(6), 0, 0, dp(2)) })
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
                val gt = com.sphynx.game.GameTerms
                for ((title, items) in listOf("Attribute Guide" to gt.attributes, "State Guide (crowd control and buffs)" to gt.states, "Common Terms" to gt.common)) {
                    val shown = items.filter { ok(it.name) || ok(it.text) }
                    if (shown.isEmpty()) continue
                    list.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(c(T.ACCENT)); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), dp(14), 0, dp(2)) })
                    var group = ""
                    for (t in shown) {
                        if (t.group.isNotEmpty() && t.group != group) {
                            group = t.group
                            list.addView(TextView(this).apply { text = group; textSize = 13f; setTextColor(c(T.MUTED)); setPadding(dp(6), dp(8), 0, 0) })
                        }
                        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(T.SURF, 12, T.STROKE); setPadding(dp(10), dp(8), dp(10), dp(8)) }
                        box.addView(TextView(this).apply { text = t.name; textSize = 14f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD) })
                        box.addView(TextView(this).apply { text = t.text; textSize = 12.5f; setTextColor(c(T.TEXT)); setLineSpacing(0f, 1.15f) })
                        list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
                    }
                }
            }
            else -> {
                // v36.73: Advanced (with its Active-skill items) / Intermediate / Basic, each item with its
                // stat types; then the roaming boots' blessings (not equipment: no cost, nothing to learn)
                val idb = com.sphynx.game.ItemDb
                val ic = com.sphynx.game.ItemClass
                fun head(t: String, sub: Boolean = false) = list.addView(TextView(this).apply {
                    text = t; textSize = if (sub) 13f else 15f; setTextColor(c(if (sub) T.MUTED else T.ACCENT)); setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(if (sub) 6 else 4), dp(if (sub) 8 else 14), 0, dp(2)) })
                fun itemRow(n: String) {
                    val inf = ic.info(n)
                    val lib = com.sphynx.game.ItemBook.get(n)
                    val price = inf.price.takeIf { it >= 0 } ?: lib?.price?.takeIf { it >= 0 }
                    row(n, false, ic.kindsText(n) + (price?.let { if (it == 0) " · no cost" else " · $it gold" } ?: ""), inf.tier != null) {
                        "Tier: ${inf.tier?.label ?: "not known yet"} · types: ${ic.kindsText(n)} (from ${inf.source})" +
                            (ic.wikiStats(n)?.let { "\nStats (wiki): $it" } ?: lib?.let { "\nStats (learned): ${it.stats.ifBlank { "-" }}" } ?: "") +
                            (idb.actives[n]?.let { "\nActive: $it" } ?: "") +
                            (lib?.effect?.takeIf { it.isNotBlank() }?.let { "\nEffect: $it" } ?: "") +
                            (com.sphynx.game.Sustain.itemKind(n)?.let { k -> "\nHP back from hitting: $k" } ?: "")
                    }
                }
                val all = idb.current.filter { ok(it) && !ic.isBlessing(it) }
                val byTier = all.groupBy { ic.tier(it) }
                val advanced = byTier[com.sphynx.game.ItemClass.Tier.ADVANCED].orEmpty()
                if (advanced.isNotEmpty()) {
                    head("Advanced equipment (used in builds)")
                    val acts = advanced.filter { idb.isActive(it) }
                    if (acts.isNotEmpty()) { head("Active skills (items you press in a match)", true); acts.forEach { itemRow(it) } }
                    for (k in com.sphynx.game.ItemClass.Kind.values()) {
                        val inK = advanced.filter { !idb.isActive(it) && ic.info(it).kinds.firstOrNull() == k }
                        if (inK.isNotEmpty() || k == com.sphynx.game.ItemClass.Kind.ATTACK || k == com.sphynx.game.ItemClass.Kind.MAGIC) {
                            head(k.label, true)
                            inK.forEach { itemRow(it) }
                            // Under Physical Attack: Physical Penetration & Physical Attack Speed classifications (v37.01)
                            if (k == com.sphynx.game.ItemClass.Kind.ATTACK) {
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
                            if (k == com.sphynx.game.ItemClass.Kind.MAGIC) {
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
                for ((t, label) in listOf(com.sphynx.game.ItemClass.Tier.INTERMEDIATE to "Intermediate equipment",
                        com.sphynx.game.ItemClass.Tier.BASIC to "Basic equipment", com.sphynx.game.ItemClass.Tier.CONSUMABLE to "Potions")) {
                    val l = byTier[t].orEmpty()
                    if (l.isNotEmpty()) { head(label); l.forEach { itemRow(it) } }
                }
                byTier[null].orEmpty().takeIf { it.isNotEmpty() }?.let { head("Tier not known yet (read from the wiki when the Library learns)"); it.forEach { n -> itemRow(n) } }
                // the roaming boots' blessings: not equipment
                val bl = ic.blessings.filter { ok(it.name) || ok("blessing") || ok("roam") }
                if (bl.isNotEmpty()) {
                    head("Roaming boots blessings (not equipment)")
                    list.addView(TextView(this).apply { text = "No cost: you get one when you buy boots with a roaming blessing as a roamer."; textSize = 13f; setTextColor(c(T.MUTED)); setPadding(dp(6), 0, 0, dp(2)) })
                    for (b0 in bl) row(b0.name, false, b0.kind, true, file = b0.file) { b0.text }
                }
            }
        }
        if (list.childCount == 0) emptyState(list, if (q.isNotEmpty()) "Nothing named \"${libFilter.trim()}\" here." else "Nothing here yet.")
        search.setOnEditorActionListener { v, _, _ -> libFilter = v.text.toString(); show("library"); true }
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }

    // =====================================================================
    //  META: online meta through the Groq API
    // =====================================================================

    private fun metaPage() {
        val mg = com.sphynx.game.MetaStore
        val ma = com.sphynx.ai.MetaAdvisor
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
        upd { prov.text = "Online models: " + com.sphynx.ai.GroqClient.providersText() +
            "\nKeys in the folder: groq_key.txt (two Groq models), gemini_key.txt (Gemini 3.8 Flash, 3.6 Flash, 3.5 Flash-Lite), cerebras_key.txt" +
            (if (com.sphynx.ai.GroqClient.lastProvider != "-") "\nLast answer from: ${com.sphynx.ai.GroqClient.lastProvider}" else "") }
        val gq = com.sphynx.ai.GroqClient
        // rate-limit indicator: green = ready, red = resting after the limit (with a countdown)
        val lim = text("", 14f, bold = true)
        upd {
            val (col, t) = groqIndicator()
            lim.text = "● " + t; lim.setTextColor(c(col))
        }
        val st = text("", 13f, color = T.MUTED)
        upd {
            st.text = (if (!com.sphynx.ai.GroqClient.anyKey) "No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Storage/Input folder." else "${gq.calls} answers this session" +
                (if (gq.tokensLeft >= 0) " · ${gq.tokensLeft} tokens left this minute" else "")) +
                (if (ma.busy.isNotEmpty()) "\nWorking: ${ma.busy}…" else "") +
                (if (ma.lastMessage.isNotEmpty()) "\n${ma.lastMessage}" else "")
        }
        val upd1 = btn("Update the patch meta") {
            leaveHooks.forEach { it() }
            if (!com.sphynx.ai.GroqClient.anyKey) toast("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Storage/Input folder") else {
                toast("Asking Groq (10-30 s)…")
                ma.updateGeneral { msg -> runOnUiThread { toast(msg) } }
            }
        }
        styleButton(upd1, primary = true)
        needsInternet(upd1)
        val heroBox = field("Hero (empty = the hero you play)", "", false) { }
        val updHero = btn("Update this hero") {
            leaveHooks.forEach { it() }
            val h = HeroDb.match(heroBox.text.toString()) ?: GameStateTracker.ownHero() ?: HeroDb.match(Cfg.myHero)
            when {
                !com.sphynx.ai.GroqClient.anyKey -> toast("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Storage/Input folder")
                h == null -> toast("Type a hero name")
                else -> { toast("Asking Groq about $h (10-30 s)…"); ma.updateHero(h) { msg -> runOnUiThread { toast(msg) } } }
            }
        }
        needsInternet(updHero)

        // the two cards are rebuilt only when their data changes
        val patchBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heroBox2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(patchBox); content.addView(heroBox2)
        var patchKey = ""; var heroKey = ""

        fun sub(parent: LinearLayout, t: String) = parent.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 13f; letterSpacing = 0.08f; setTextColor(c(T.MUTED)); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(2))
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
            row.addView(TextView(this).apply { text = v; textSize = 13f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD) },
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
                    head.addView(TextView(this).apply { text = "Patch " + g.patch; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    head.addView(chip(age(g.updatedAt), if (mg.ageDays(g.updatedAt) > 14) T.GOLD else T.GREEN))
                    cd.addView(head)
                    // how fresh it is and where it came from (backlog item 9, audit finding 8)
                    cardNote(cd, Freshness.line(g.updatedAt, g.sources))
                    if (g.bans.isNotEmpty()) { sub(cd, "Top bans"); IconGrid(cd, true, 44).set(g.bans) }
                    for ((r, l) in g.picks) if (l.isNotEmpty()) {
                        sub(cd, "Best " + r.name.lowercase() + " picks")
                        IconGrid(cd, true, 44).set(l)
                    }
                    if (g.notes.isNotBlank()) cardNote(cd, g.notes)
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
                if (h != null) head.addView(com.sphynx.app.IconCache.view(this, h, true, dp(58)), LinearLayout.LayoutParams(dp(58), dp(58)))
                val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                names.addView(TextView(this).apply { text = h ?: "No hero"; textSize = 19f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
                names.addView(TextView(this).apply {
                    text = if (m == null) "not downloaded yet" else "${HeroDb.role(h).name.lowercase()} · ${m.patch} · ${age(m.updatedAt)}"
                    textSize = 13f; setTextColor(c(T.MUTED))
                })
                head.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                cd.addView(head)
                if (m == null) {
                    cardNote(cd, "Tap Update this hero.")
                } else {
                    cardNote(cd, Freshness.line(m.updatedAt, m.sources))
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
    //  PERFORMANCE (owner, 2026-11-11: was "Speed"; laid out after the owner's phone-performance screenshots)
    //  1. this phone: one card per topic, a big headline number, label / value rows, a few clear charts
    //  2. the AI's speed: frame to touch and every engine
    //  3. the speed recorder (export)
    // =====================================================================
    private fun speedPage() {
        val log = com.sphynx.core.SpeedLog
        val ds = com.sphynx.core.DeviceStats
        val di = com.sphynx.core.DeviceInfo
        Thread { di.readGpu(); di.readAccelerators() }.start()
        fun fmt(v: Float, unit: String) = when (unit) {
            "ms" -> if (v < 10f) "%.1f ms".format(v) else "%.0f ms".format(v)
            "s" -> "%.1f s".format(v)
            else -> "%.0f %s".format(v, unit)
        }
        fun gradeColor(g: Int) = c(when (g) { 0 -> T.GREEN; 1 -> T.GOLD; else -> T.RED })
        fun level(f: Float) = c(when { f < 0.6f -> T.GREEN; f < 0.85f -> T.GOLD; else -> T.RED })
        fun gb(mb: Long) = if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"
        val MATCH = LinearLayout.LayoutParams.MATCH_PARENT; val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // ---- building blocks: a card, a big headline, label / value rows ----
        fun pcard(name: String, fill: String = T.SURF): LinearLayout {
            val cd = card(); cd.background = shape(fill, 24, if (fill == T.SURF) T.STROKE else null)
            cd.setPadding(dp(18), dp(16), dp(18), dp(16))
            cd.addView(TextView(this).apply { text = name; textSize = 17f; setTextColor(c(T.TEXT)) })
            return cd
        }
        fun headline(cd: LinearLayout, size: Float = 26f): TextView = TextView(this).apply {
            textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(4)); cd.addView(this)
        }
        fun kv(cd: LinearLayout, label: String): TextView {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
            row.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(c("#B8C0D4")) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val v = TextView(this).apply { textSize = 14f; setTextColor(c(T.TEXT)) }
            row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
            cd.addView(row)
            return v
        }
        fun note(cd: LinearLayout, t: String = ""): TextView = cardNote(cd, t)
        fun bar(cd: LinearLayout, hDp: Int = 6, top: Int = 8): com.sphynx.app.BudgetBar =
            com.sphynx.app.BudgetBar(this).also { cd.addView(it, LinearLayout.LayoutParams(MATCH, dp(hDp)).apply { topMargin = dp(top) }) }

        // ---- header ----
        content.addView(TextView(this).apply { text = di.phoneName(); textSize = 26f; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, 0) })
        content.addView(TextView(this).apply { text = "Live · updates every 2 seconds"; textSize = 14f; setTextColor(c(T.ACCENT)) })

        // ---- SUPPORT (Phase 5, P3): this phone against the app's requirements (read once at start) ----
        val sup = pcard("What this phone supports")
        val prof = com.sphynx.core.DeviceProfile.last
        if (prof == null) note(sup, "Still reading this phone's details; open the page again in a moment.")
        else for (ck in com.sphynx.core.DeviceProfile.checks(prof)) {
            val v = kv(sup, ck.name)
            v.text = (when (ck.ok) { true -> "Yes · "; false -> "No · "; null -> "" }) + ck.detail
            if (ck.ok == false) v.setTextColor(c(T.RED))
        }
        if (prof != null) note(sup, "Minimum 6 GB of memory, 8 to 12 GB recommended. A GPU or an NPU driver being present does not prove a model can run on it: the Model page shows where a model really loaded.")

        // ---- MEMORY: ring (in use), available / total, available over 5 min, unused, this app ----
        val mem = pcard("Memory", "#2E4374")
        val memRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        val ring = com.sphynx.app.RingGauge(this)
        memRow.addView(ring, LinearLayout.LayoutParams(dp(112), dp(112)))
        val memCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, 0, 0) }
        val memBig = TextView(this).apply { textSize = 36f; setTextColor(Color.WHITE) }
        val memSub = TextView(this).apply { text = "RAM available"; textSize = 16f; setTextColor(c("#DDE4F7")) }
        val memTot = TextView(this).apply { textSize = 13f; setTextColor(c("#C8D2F0")) }
        memCol.addView(memBig); memCol.addView(memSub); memCol.addView(memTot)
        memRow.addView(memCol)
        mem.addView(memRow)
        mem.addView(TextView(this).apply { text = "Available RAM · last 5 minutes"; textSize = 13f; setTextColor(c("#C8D2F0")); setPadding(0, dp(8), 0, 0) })
        val memSpark = com.sphynx.app.Sparkline(this)
        mem.addView(memSpark, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(4) })
        note(mem, "Available RAM includes memory Android can reclaim.").setTextColor(c("#C8D2F0"))
        val memUnused = kv(mem, "Unused RAM")
        val memApp = kv(mem, "App process RAM")

        // ---- STORAGE ----
        val rom = pcard("Internal storage")
        val romBig = headline(rom, 28f)
        val romBar = bar(rom, 6, 6)
        val romTotal = kv(rom, "Total")
        val romApp = kv(rom, "This app's files (models)")
        note(rom, "Storage figures describe the internal data partition available to apps.")

        // ---- CPU & CORES: chip, cores, this app's share (with its line), one tile per core ----
        val cpu = pcard("CPU & cores")
        headline(cpu, 26f).text = di.chip()
        val cpuCores = kv(cpu, "Reported CPU cores")
        val cpuOnline = kv(cpu, "Online cores")
        kv(cpu, "System CPU load").text = "Not reported"
        val cpuApp = kv(cpu, "App CPU share")
        val cpuSpark = com.sphynx.app.Sparkline(this)
        cpu.addView(cpuSpark, LinearLayout.LayoutParams(MATCH, dp(36)).apply { topMargin = dp(2) })
        note(cpu, "Core tiles show clock speed, not use. App CPU share is spread over all cores. Android closes the whole phone's CPU load to apps.")
        val nCores = Runtime.getRuntime().availableProcessors()
        class Tile(val ghz: TextView, val max: TextView, val bar: com.sphynx.app.BudgetBar)
        val tiles = ArrayList<Tile>()
        var tileRow: LinearLayout? = null
        for (i in 0 until nCores) {
            if (i % 2 == 0) { tileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; cpu.addView(tileRow) }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(T.SURF2, 18); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            t.addView(TextView(this).apply { text = "Core $i"; textSize = 14f; setTextColor(c(T.TEXT)) })
            val g = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(2)) }
            val mx = TextView(this).apply { textSize = 13f; setTextColor(c(T.MUTED)) }
            t.addView(g); t.addView(mx)
            val b = com.sphynx.app.BudgetBar(this)
            t.addView(b, LinearLayout.LayoutParams(MATCH, dp(5)).apply { topMargin = dp(8) })
            tileRow!!.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(if (i % 2 == 0) 0 else dp(5), dp(10), if (i % 2 == 0) dp(5) else 0, 0) })
            tiles.add(Tile(g, mx, b))
        }
        if (nCores % 2 == 1) tileRow?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) })

        // ---- GRAPHICS PROCESSOR ----
        val gpu = pcard("Graphics processor")
        val gpuName = headline(gpu, 26f)
        val gpuMaker = kv(gpu, "Manufacturer")
        val gpuGl = note(gpu)
        val gpuLoad = kv(gpu, "GPU load")
        val gpuUse = kv(gpu, "Runs on the GPU")

        // ---- NPU & ACCELERATOR DRIVERS ----
        val npu = pcard("NPU & accelerator drivers")
        val npuList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npu.addView(npuList)
        note(npu, "Names found in the phone's vendor folders. They show an NPU / DSP is there - not that a model can run on it. Its use is not readable.")
        var npuShown: List<String>? = null

        // ---- TEMPERATURE & BATTERY ----
        val pow = pcard("Temperature & battery")
        val powThermal = kv(pow, "Thermal status")
        val powHead = kv(pow, "Thermal headroom")
        val powHeadBar = bar(pow, 5, 2)
        val powTemp = kv(pow, "Battery temperature")
        val powLevel = kv(pow, "Battery level")
        val powLevelBar = bar(pow, 5, 2)

        // ---- LOCAL MODEL ACTIVITY ----
        val llm = pcard("Local model activity")
        val llmBig = headline(llm, 24f)
        val llmSub = note(llm)
        val llmTac = kv(llm, "Small tactical model")
        val llmLast = kv(llm, "Last answer")

        // ---- THE AI'S SPEED: frame to touch, and what it is made of ----
        title("AI SPEED")
        val total = log.series[0]
        val parts = log.series.subList(1, 5)
        val hc = pcard("Frame → touch")
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val big = TextView(this).apply { textSize = 36f; setTextColor(Color.WHITE) }
        head.addView(big)
        val gradeChip = chip("")
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(gradeChip)
        hc.addView(head)
        val stack = com.sphynx.app.StackBar(this)
        hc.addView(stack, LinearLayout.LayoutParams(MATCH, dp(12)).apply { topMargin = dp(10); bottomMargin = dp(6) })
        val legendRows = parts.map { sr ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(View(this).apply { background = shape(sr.color, 5) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            row.addView(TextView(this).apply { text = sr.name; textSize = 13f; setTextColor(c("#B8C0D4")); setPadding(dp(10), 0, 0, 0) },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            val v = TextView(this).apply { textSize = 13f; setTextColor(c(T.TEXT)) }
            row.addView(v)
            hc.addView(row)
            v
        }
        val totalSpark = com.sphynx.app.Sparkline(this)
        hc.addView(totalSpark, LinearLayout.LayoutParams(MATCH, dp(64)).apply { topMargin = dp(8) })
        val totalNote = note(hc)

        // every other engine: one compact row each (name, value, budget bar, 5-minute line), grouped in one card per group
        class Row(val sr: com.sphynx.core.SpeedLog.Series, val value: TextView, val bar: com.sphynx.app.BudgetBar,
                  val spark: com.sphynx.app.Sparkline, val note: TextView)
        val rows = ArrayList<Row>()
        var groupCard: LinearLayout? = null
        var group = ""
        for (sr in log.series.drop(5)) {
            if (sr.group != group || groupCard == null) { group = sr.group; groupCard = pcard(group) }
            val cd = groupCard!!
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            top.addView(View(this).apply { background = shape(sr.color, 5) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            top.addView(TextView(this).apply { text = sr.name; textSize = 14f; setTextColor(c(T.TEXT)); setPadding(dp(10), 0, 0, 0) },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            val value = TextView(this).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
            top.addView(value)
            cd.addView(top)
            val b = bar(cd, 5, 6)
            val spark = com.sphynx.app.Sparkline(this)
            cd.addView(spark, LinearLayout.LayoutParams(MATCH, dp(32)).apply { topMargin = dp(4) })
            rows.add(Row(sr, value, b, spark, note(cd)))
        }

        // ---- SPEED RECORDER (to send) ----
        title("SPEED RECORDER (to send)")
        val rc = pcard("Language models")
        val mText = cardText(rc, 12f)
        val rc2 = pcard("Screen to touch")
        val tText = cardText(rc2, 12f)
        val testInfo = text("", 12f, color = T.MUTED)
        switch("Detailed model figures (tokens per second, time to first token); applies when the models next load", Cfg.modelSpeedDetailed) {
            Cfg.modelSpeedDetailed = it
            toast(if (it) "On: restart the app (or reload the models) to apply" else "Off: applies when the models next load")
        }
        btn("Run the speed test (both models, same questions every time)") {
            com.sphynx.ai.ModelSpeed.runTest { msg -> runOnUiThread { toast(msg.take(150)) } }
        }
        text("Export: on the Debug data page (all exports for debugging are there).", 12f, color = T.MUTED)
        btn("Clear the speed data") {
            AlertDialog.Builder(this).setTitle("Delete the recorded model speed and screen-to-touch data?")
                .setPositiveButton("Delete") { _, _ ->
                    com.sphynx.ai.ModelSpeed.clear(); com.sphynx.core.TouchSpeed.clear(); toast("Deleted")
                }
                .setNegativeButton("Cancel", null).show()
        }

        upd {
            // ---- this phone ----
            ds.sampleAsync(this)
            val p = ds.last
            if (p != null) {
                val usedF = (p.ramTotalMb - p.ramAvailMb).toFloat() / p.ramTotalMb.coerceAtLeast(1)
                ring.set(usedF, "${(usedF * 100).toInt()}%", "In use", c(if (usedF < 0.85f) "#AFC4FF" else T.RED))
                memBig.text = gb(p.ramAvailMb)
                memTot.text = gb(p.ramTotalMb) + " total" + if (p.lowMemory) " · LOW MEMORY" else ""
                memSpark.set(ds.history(ds.availHistory), 0f, c("#AFC4FF"))
                memUnused.text = di.unusedRamMb()?.let { gb(it) } ?: "Not reported"
                memApp.text = gb(p.appRamMb)
                val romUsed = (p.romTotalGb - p.romFreeGb) / p.romTotalGb.coerceAtLeast(0.1f)
                romBig.text = "%.1f GB available".format(p.romFreeGb)
                romBar.set(romUsed, level(romUsed))
                romTotal.text = "%.1f GB".format(p.romTotalGb)
                romApp.text = gb(p.appFilesMb)
                cpuCores.text = "${p.cores}"
                cpuOnline.text = di.onlineCores()?.let { "$it / ${p.cores}" } ?: "Not reported"
                cpuApp.text = "%.1f%%".format(p.appCpuPct)
                cpuSpark.set(ds.history(ds.cpuHistory), 0f, c(T.ACCENT))
                tiles.forEachIndexed { i, t ->
                    val g = p.coreGhz.getOrNull(i) ?: -1f
                    val mx = di.coreMaxGhz(i)
                    t.ghz.text = if (g < 0f) "offline" else "%.2f GHz".format(g)
                    t.max.text = mx?.let { "Max %.2f GHz".format(it) } ?: "Max not reported"
                    t.bar.set(if (g > 0f && mx != null && mx > 0f) g / mx else 0f, c("#AFC4FF"))
                }
                gpuLoad.text = p.gpuLoadPct?.let { "%.0f%%".format(it) + (p.gpuMhz?.let { m -> " · %.0f MHz".format(m) } ?: "") } ?: "Not readable"
            }
            di.gpu.let { g ->
                gpuName.text = g?.first ?: "reading..."
                gpuMaker.text = g?.second ?: "-"
                gpuGl.text = g?.third ?: ""
            }
            gpuUse.text = "Strategist " + com.sphynx.ai.Roles.where(com.sphynx.ai.Roles.STRATEGIC).let { if (it == "-") "not loaded" else it } +
                " · small model " + com.sphynx.ai.Roles.where(com.sphynx.ai.Roles.TACTICAL).let { if (it == "-") "not loaded" else it }
            val acc = di.accelerators
            if (acc != null && acc != npuShown) {
                npuShown = acc
                npuList.removeAllViews()
                if (acc.isEmpty()) npuList.addView(TextView(this).apply { text = "None visible to apps on this phone"; textSize = 15f; setTextColor(c(T.TEXT)); setPadding(0, dp(8), 0, dp(4)) })
                for (a in acc) {
                    val service = a.startsWith("service: ")
                    npuList.addView(TextView(this).apply { text = a.removePrefix("service: "); textSize = if (service) 14f else 18f; setTextColor(Color.WHITE); setPadding(0, dp(10), 0, 0) })
                    npuList.addView(TextView(this).apply { text = if (service) "Neural-network service" else "Accelerator driver"; textSize = 13f; setTextColor(c(T.MUTED)) })
                }
            }
            val pw = di.power(this)
            powThermal.text = pw.thermal
            powHead.text = pw.headroom?.let { "${(it * 100).toInt()}% of the throttling point" } ?: "Not reported"
            powHeadBar.set(pw.headroom ?: 0f, level(pw.headroom ?: 0f))
            powTemp.text = pw.batteryC?.let { "%.1f °C".format(it) } ?: "Not reported"
            powLevel.text = (pw.batteryPct?.let { "$it%" } ?: "Not reported") + if (pw.charging == true) " · charging" else ""
            powLevelBar.set((pw.batteryPct ?: 0) / 100f, c(when { (pw.batteryPct ?: 100) > 30 -> T.GREEN; (pw.batteryPct ?: 0) > 15 -> T.GOLD; else -> T.RED }))
            val gs = com.sphynx.ai.Roles.state(com.sphynx.ai.Roles.STRATEGIC)
            llmBig.text = if (gs.startsWith("ready", true)) "Loaded" else gs.replaceFirstChar { it.uppercase() }
            llmSub.text = "Strategist: ${ModelManager.strategicName()} · $gs"
            llmTac.text = if (Cfg.tacticalLlmOn) "${ModelManager.tacticalName()} · ${com.sphynx.ai.Roles.state(com.sphynx.ai.Roles.TACTICAL)}" else "Off"
            llmLast.text = if (com.sphynx.ai.Roles.calls(com.sphynx.ai.Roles.STRATEGIC) > 0) "%.1f s (${com.sphynx.ai.Roles.calls(com.sphynx.ai.Roles.STRATEGIC)} answers)".format(com.sphynx.ai.Roles.lastLatencyMs(com.sphynx.ai.Roles.STRATEGIC) / 1000f) else "-"

            // ---- the AI's speed ----
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
                "5 min: average ${fmt(total.avg(), "ms")}, worst ${fmt(total.max(), "ms")}, target under ${fmt(total.budget, "ms")} (dashed line)"
            for (r in rows) {
                val v = r.sr.last()
                val used = r.sr.history().any { it > 0f }
                r.value.text = if (!used) "not used" else fmt(v, r.sr.unit)
                r.value.setTextColor(if (!used) c(T.MUTED) else gradeColor(r.sr.grade()))
                r.bar.set(v / r.sr.budget, if (!used) c(T.STROKE) else gradeColor(r.sr.grade()))
                r.spark.set(if (used) r.sr.history() else FloatArray(0), r.sr.budget, c(r.sr.color))
                r.note.text = if (!used) "No runs yet (loaded and used only when on)." else
                    "average ${fmt(r.sr.avg(), r.sr.unit)} · " + (if (r.sr.higherIsBetter) "target ${fmt(r.sr.budget, r.sr.unit)}+" else "target under ${fmt(r.sr.budget, r.sr.unit)}")
            }

            // ---- recorder ----
            mText.text = com.sphynx.ai.ModelSpeed.text()
            tText.text = com.sphynx.core.TouchSpeed.text()
            val ms = com.sphynx.ai.ModelSpeed
            testInfo.text = "Speed test: " + ms.testStatus + (if (ms.testRunning) " (running)" else "")
        }
    }

    // =====================================================================
    //  MODEL
    // =====================================================================

    // =====================================================================
    //  PHASE 9 M3: THE MODEL LAB - every model the app holds, its details, its job, its settings
    // =====================================================================
    private fun modelLab() {
        title("MODEL LAB")
        val recs = try { com.sphynx.models.ModelRegistry.refresh(this) } catch (t: Throwable) { com.sphynx.models.ModelRegistry.records }
        labStorage(recs)       // Phase 9 M5
        if (recs.isEmpty()) {
            text("No model yet. Put a .litertlm or .gguf file in Sphynx-Storage/Input, or add a GGUF model below.", 13f, color = T.MUTED, describe = false)
            return
        }
        val roles = listOf(com.sphynx.ai.Roles.STRATEGIC, com.sphynx.ai.Roles.TACTICAL)
        fun jobOf(r: com.sphynx.models.ModelRecord): String? = roles.firstOrNull { role ->
            val f = ModelManager.fileFor(this, role); f.name == r.fileName && f.length() == r.sizeBytes
        }?.name?.lowercase()
        for (r in recs.sortedWith(compareBy({ jobOf(it) == null }, { it.format.ordinal }, { it.fileName }))) {
            val cd = card()
            val job = jobOf(r)
            cardHeader(cd, r.fileName, job ?: r.format.label, if (job != null) T.GOLD else T.ACCENT)
            val loaded = job?.let { j -> val role = roles.first { it.name.lowercase() == j }
                if (com.sphynx.ai.Roles.ready(role)) "loaded on " + com.sphynx.ai.Roles.where(role) else "not loaded" }
            cardNote(cd, listOfNotNull(
                r.summary(),
                r.contextLength?.let { "trained for $it tokens" },
                r.projectorFor?.let { "picture part of $it" },
                "from: " + r.source.label + (r.originalName?.let { " ($it)" } ?: ""),
                "fingerprint: " + (r.sha256?.take(16)?.plus("…") ?: "not yet (copied before v38.35)"),
                job?.let { "job: $it, $loaded" },
                r.settings.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { "${it.key} ${it.value}" }?.let { "own settings: $it" },
            ).joinToString("\n"))
            if (r.format != com.sphynx.models.ModelRecord.Format.MMPROJ) {
                inCard(cd, "Give it a job…") { labJob(r) }
                inCard(cd, "Settings…") { labSettings(r) }
            }
            if (job == null && labDeletable(r)) inCard(cd, "Delete the app's copy") {
                confirm("Delete the app's copy of ${r.fileName} (${r.sizeBytes / 1048576} MB)? A file in Sphynx-Storage/Input stays " +
                    "there and is not copied again unless it changes.", "Delete") { labDelete(r) }
            }
        }
    }

    // Phase 9 M5: the room the models take, the room left, and leftover files offered for deletion
    private fun labStorage(recs: List<com.sphynx.models.ModelRecord>) {
        val ms = com.sphynx.models.ModelStorage
        val free = try { filesDir.usableSpace } catch (e: Exception) { -1L }
        val dirs = listOf(filesDir, com.sphynx.models.AutoFolder.liteLibrary(this), com.sphynx.models.GgufLibrary.dir(this))
        val files = dirs.flatMap { d -> (d.listFiles()?.filter { it.isFile } ?: emptyList()).map { f ->
            com.sphynx.models.ModelStorage.FileInfo(f.absolutePath, f.name, f.length(), f.lastModified()) } }
        val left = ms.leftovers(files, recs.map { it.fileName }.toSet(), System.currentTimeMillis()) {
            java.io.File(it.path).parentFile == filesDir }
        val cd = card()
        val warn = ms.roomWarning(free)
        cardHeader(cd, "STORAGE", if (warn != null) "low" else "ok", if (warn != null) T.RED else T.GREEN)
        cardNote(cd, listOfNotNull(ms.summary(recs, free), warn,
            if (left.isEmpty()) "No leftover files" else "Leftover files: " + left.joinToString(", ") { "${it.name} (${ms.mb(it.size)} MB)" },
        ).joinToString("\n"))
        if (left.isNotEmpty()) inCard(cd, "Delete ${left.size} leftover file" + (if (left.size > 1) "s" else "") + " (${ms.mb(left.sumOf { it.size })} MB)") {
            confirm("Delete these files? No model record points to them:\n" + left.joinToString("\n") { it.name }, "Delete") {
                val gone = left.count { java.io.File(it.path).delete() }
                Diag.info("Model Lab: deleted $gone of ${left.size} leftover files")
                toast("Deleted $gone of ${left.size}"); show("model")
            }
        }
    }

    // the job's own two files come back from Input at every scan, so only library copies and GGUF files are deleted here
    private fun labDeletable(r: com.sphynx.models.ModelRecord): Boolean =
        r.fileName != ModelManager.modelFile(this).name && r.fileName != ModelManager.tacticalFile(this).name

    private fun labFile(r: com.sphynx.models.ModelRecord): java.io.File? = listOf(
        java.io.File(filesDir, r.fileName), java.io.File(com.sphynx.models.AutoFolder.liteLibrary(this), r.fileName),
        java.io.File(com.sphynx.models.GgufLibrary.dir(this), r.fileName)).firstOrNull { it.isFile && it.length() == r.sizeBytes }

    private fun labJob(r: com.sphynx.models.ModelRecord) {
        val rc = com.sphynx.inference.roles.RoleChoice
        val f = labFile(r) ?: run { toast("The file is gone"); return }
        val value = when {
            r.format == com.sphynx.models.ModelRecord.Format.GGUF -> rc.gguf(r.fileName)
            f.parentFile?.name == "litertlm" -> rc.lite(r.fileName)
            else -> rc.LITERT                                   // a job's own file
        }
        val roles = listOf(com.sphynx.ai.Roles.STRATEGIC, com.sphynx.ai.Roles.TACTICAL)
        AlertDialog.Builder(this).setTitle("Which job for ${r.fileName}?")
            .setItems(arrayOf("Strategic job", "Tactical job")) { _, i ->
                val role = roles[i]
                rc.problem(role, value, ModelManager.choice(rc.other(role)))?.let { toast(it); return@setItems }
                if (role == com.sphynx.ai.Roles.STRATEGIC && r.pictures == false)
                    toast("Note: ${r.fileName} reads text only, so the draft and screen reading are off")
                val before = ModelManager.choice(role)
                val ownMb = if (com.sphynx.ai.Roles.ready(role)) ModelManager.fileFor(this, role).length() / 1_048_576L else 0L
                Cfg.setRaw(rc.settingKey(role), value)
                afterFitCheck(role, ownMb, cancelled = { Cfg.setRaw(rc.settingKey(role), before) }) { Thread {
                    com.sphynx.ai.Roles.unload(role)
                    com.sphynx.ai.Roles.loadByOwner()
                    val done: (String?) -> Unit = { err -> runOnUiThread { toast(err ?: ("Loaded: " + ModelManager.choiceLabel(this, role))); show("model") } }
                    if (role == com.sphynx.ai.Roles.STRATEGIC) ModelManager.load(this, done)
                    else if (Cfg.tacticalLlmOn) ModelManager.loadTactical(this, done)
                    else runOnUiThread { toast("Saved. Turn on the small tactical model to load it") }
                }.start() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun labSettings(r: com.sphynx.models.ModelRecord) {
        val ms = com.sphynx.models.ModelSettings
        val names = mapOf(ms.CONTEXT to "Context length (tokens)", ms.TEMPERATURE to "Temperature (0 to 1.5)", ms.THREADS to "Threads (1 to 8)",
            ms.KV8 to "8-bit cache (true / false)", ms.PROCESSORS to "Processors in order (e.g. GPU,CPU)", ms.PICTURES to "Picture part (true / false)")
        val keys = ms.ALL.filter { k -> ms.problem(k, when (k) { ms.KV8, ms.PICTURES -> "true"; ms.PROCESSORS -> "CPU"; ms.TEMPERATURE -> "0.2"; ms.THREADS -> "4"; else -> "4096" }, r.format) == null }
        val labels = keys.map { k -> names[k] + ": " + (r.settings[k] ?: "default") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Settings of ${r.fileName}")
            .setItems(labels) { _, i ->
                val k = keys[i]
                val input = android.widget.EditText(this).apply { setText(r.settings[k] ?: ""); hint = "empty = default"; setSingleLine() }
                AlertDialog.Builder(this).setTitle(names[k]).setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val why = com.sphynx.models.ModelRegistry.setSetting(this, r.id, k, input.text.toString())
                        toast(why ?: "Saved: it is used the next time this model loads"); if (why == null) show("model")
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun labDelete(r: com.sphynx.models.ModelRecord) {
        val f = labFile(r) ?: run { toast("The file is gone"); return }
        val ok = if (r.format == com.sphynx.models.ModelRecord.Format.LITERT_LM) {
            // remember the Input file it came from, so the next scan does not copy it back (owner: only the app's copy)
            Cfg.raw("auto_sig_lite_" + f.name)?.let { Cfg.setRaw("lab_deleted_" + f.name, it) }
            f.delete()
        } else com.sphynx.models.GgufLibrary.remove(this, f)
        Cfg.setRaw(com.sphynx.core.FileHash.key(r.fileName, r.sizeBytes), null)
        try { com.sphynx.models.ModelRegistry.refresh(this) } catch (t: Throwable) { }
        Diag.info("Model Lab: deleted the app's copy of ${r.fileName}: $ok")
        toast(if (ok) "Deleted ${r.fileName}" else "Could not delete ${r.fileName}")
        show("model")
    }

    private fun modelPage() {
        modelLab()      // Phase 9 M3
        title("FILES")
        text("Put files in Sphynx-Storage/Input (Sphynx-Storage is at the top of the phone's storage, next to Download; the app saves what it makes in Output and Recordings); the app loads them by itself:\n• Gemma 4 E2B (.litertlm) – strategy\n• Gemma 3 270M (.litertlm) – fight advice (optional)\n• mlbb_policies.zip – tactical networks\n• detectors (.tflite + labels) – minimap and screen\n• groq_key.txt – online meta", 13f)
        val mcard = card()
        val st = cardText(mcard, 14f, true)
        upd {
            val af = com.sphynx.models.AutoFolder
            val pn = com.sphynx.learning.PolicyNet
            // a coloured dot per part: green = loaded / running, yellow = loading or loaded but switched off,
            // red = missing / not loaded
            val sb = android.text.SpannableStringBuilder()
            fun line(state: Int, t: String) {
                if (sb.isNotEmpty()) sb.append("\n")
                val st0 = sb.length
                // a dot whose shape and word say the state too, not only its colour (backlog item 7)
                val badge = StatusWords.levelDot(state) + " " + StatusWords.levelWord(state)
                sb.append(badge).append("  ")
                val col = c(when (state) { 2 -> T.GREEN; 1 -> T.GOLD; else -> T.RED })
                sb.setSpan(android.text.style.ForegroundColorSpan(col), st0, st0 + badge.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st0, st0 + badge.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(t)
            }
            fun sub(t: String) { sb.append("\n     ").append(t) }
            line(if (af.treeUri != null) 2 else 0, "Folder: " + (af.treeUri?.let { Uri.decode(it.lastPathSegment ?: "chosen").substringAfter(':') } ?: "not chosen yet"))
            sub(af.status)
            sb.append("\n")
            line(when { com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC) -> 2; com.sphynx.ai.Roles.loading(com.sphynx.ai.Roles.STRATEGIC) -> 1; else -> 0 },
                "Strategic model: ${ModelManager.status(this)}, ${com.sphynx.ai.Roles.state(com.sphynx.ai.Roles.STRATEGIC)}" +
                (if (com.sphynx.ai.Roles.calls(com.sphynx.ai.Roles.STRATEGIC) > 0) " (last answer ${com.sphynx.ai.Roles.lastLatencyMs(com.sphynx.ai.Roles.STRATEGIC)} ms)" else ""))
            val smallFile = ModelManager.tacticalFile(this).exists()
            line(when { com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.TACTICAL) && Cfg.tacticalLlmOn -> 2; com.sphynx.ai.Roles.loading(com.sphynx.ai.Roles.TACTICAL) -> 1; smallFile -> 1; else -> 0 },
                "Small tactical model: ${ModelManager.tacticalStatus(this)}, " + (if (Cfg.tacticalLlmOn) com.sphynx.ai.Roles.state(com.sphynx.ai.Roles.TACTICAL) else "switched off"))
            if (Cfg.tacticalLlmOn && com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.TACTICAL)) sub(TacticalAdvisor.text().substringAfter(": "))
            val sd = com.sphynx.vision.ScreenDetector
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
            line(when { !com.sphynx.ai.GroqClient.anyKey -> 0; com.sphynx.ai.GroqClient.limited -> 1; else -> 2 },
                "Online AI: " + (if (!com.sphynx.ai.GroqClient.anyKey) "no key (groq_key.txt, gemini_key.txt, cerebras_key.txt)" else groqIndicator().second))
            line(if (com.sphynx.learning.CloudTrain.ready()) 2 else 0, "Kaggle key: " +
                (if (com.sphynx.learning.CloudTrain.ready()) "set" else "not found (kaggle.json)"))
            sb.append("\n\nMinimap: " + com.sphynx.game.MapIntel.status)
            st.text = sb
        }
        // v37.19: the app's folder /CodeOnTheGoProjects/Sphynx-Storage (imports and exports)
        val storageInfo = text("", 12f, color = T.MUTED)
        val allowBtn = btn("") {
            if (com.sphynx.core.Storage.allFilesAccess) com.sphynx.models.AutoFolder.scan(this) { msg -> runOnUiThread { toast(msg.take(150)) } }
            else try { startActivity(com.sphynx.core.Storage.requestIntent(this)) }
                catch (e: Exception) { toast("Open Settings > Apps > Special app access > All files access > Sphynx") }
        }
        upd {
            val on = com.sphynx.core.Storage.allFilesAccess
            storageInfo.text = com.sphynx.core.Storage.status()
            storageInfo.setTextColor(c(if (on) T.MUTED else T.GOLD))
            allowBtn.text = if (on) "All files access is on - scan the folder" else "Allow all files access (for /CodeOnTheGoProjects/Sphynx-Storage)"
        }
        val folderInfo = text("", 12f, color = T.MUTED)
        upd {
            val af = com.sphynx.models.AutoFolder
            folderInfo.text = af.folderHint
            folderInfo.setTextColor(c(if (af.treeUri == null || af.legacyChosen()) T.GOLD else T.MUTED))
        }
        val pick = btn("") {
            @Suppress("DEPRECATION")
            startActivityForResult(com.sphynx.models.AutoFolder.pickIntent(), reqFolder)
        }
        upd { pick.text = if (com.sphynx.models.AutoFolder.treeUri == null) "Choose the folder" else "Change the folder" }
        btn("Scan the folder now") {
            com.sphynx.models.AutoFolder.scan(this) { msg -> runOnUiThread { toast(msg.take(150)) } }
        }

        title("WHERE TO GET THEM")
        btn("Get Gemma 4 E2B (2.6 GB)") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.MODEL_PAGE))) }
        btn("Get Gemma 3 270M") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.SMALL_MODEL_PAGE))) }
        btn("Training notebooks (GitHub)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sphynx-ai/SPHYNX/tree/main/notebooks")))
        }
        btn("Groq API key (free)") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }

        // Phase 6 S7 (owner, 2026-11-22): which model does which job; the LiteRT-LM files stay the default
        title("WHICH MODEL DOES WHICH JOB")
        for (role in listOf(com.sphynx.ai.Roles.STRATEGIC, com.sphynx.ai.Roles.TACTICAL)) {
            val b = btn("") { chooseRoleModel(role) }
            upd { b.text = (if (role == com.sphynx.ai.Roles.STRATEGIC) "Strategic job: " else "Tactical job: ") + ModelManager.choiceLabel(this, role) }
        }
        text("Tap a job to choose its model: its LiteRT-LM file (as before) or one of your GGUF models. Only one GGUF model " +
            "runs at a time. A GGUF strategic model reads pictures (draft, loading cards, end screen) when its mmproj file is " +
            "in the GGUF library too (add it like a model, e.g. mmproj-gemma-3-4b-it-f16.gguf); without it, it reads text only.")

        // Phase 7 (L1): the LiteRT-LM model tested on the owner's phone (docs/PHASE_7_LITERTLM_COMPAT.md)
        text("Supported LiteRT-LM model: Gemma 4 E2B (tested on this phone, GPU). Other .litertlm files may work but are not tested yet.",
            12f, color = T.MUTED, describe = false)
        // Phase 7 (L9): which processor the strategic model tries first on this phone, and why
        ModelManager.fileFor(this, com.sphynx.ai.Roles.STRATEGIC).takeIf { it.isFile && it.name.endsWith(".litertlm", true) }?.let { f ->
            text("Processor for ${ModelManager.strategicName()}: " + com.sphynx.ai.Roles.processorLine(com.sphynx.ai.Roles.STRATEGIC, f),
                12f, color = T.MUTED, describe = false)
        }

        title("SETTINGS")
        switch("Strategic model on the GPU (faster)", Cfg.useGpu) { Cfg.useGpu = it; reloadGemmaForGpu() }
        // Phase 7 L8 (owner, 2026-11-23): experimental, off by default; NPU, then GPU, then CPU
        switch("Use the NPU (experimental)", Cfg.useNpu) { Cfg.useNpu = it; reloadGemmaForGpu() }
        text("Tries the strategic model on the phone's NPU first, then the GPU, then the CPU. The phone compiles Gemma for " +
            "its NPU when it loads (about 12 s the first time on your phone). It frees the GPU for the game, but on your phone " +
            "the GPU answered faster (11-23 test). If it fails, the GPU or CPU is used. " + com.sphynx.core.NpuCheck.line(this),
            12f, color = T.MUTED)
        // owner, 2026-11-23: the detectors have their own NPU switch, so Gemma can stay on the GPU while they try the NPU
        switch("Detectors on the NPU (experimental)", Cfg.detectorsNpu) {
            Cfg.detectorsNpu = it
            Thread {
                for (d in listOf(ObjectDetector, com.sphynx.vision.ScreenDetector)) if (d.ready) {
                    d.forgetNpuFailure()
                    d.load(this)
                    Diag.info("${d.javaClass.simpleName} now on ${d.where}")
                }
            }.start()
            toast(if (it) "Detectors: trying the NPU…" else "Detectors: back on the CPU")
        }
        text("Runs the minimap and screen detectors on the phone's NPU (through Android's NNAPI), else on the CPU. Each " +
            "is checked with one run when it loads; after a failure it goes back to the CPU until the next start. Their " +
            "status lines in the FILES card show where they run and their time per run, so you can compare with this " +
            "switch off. It needs the detector files (mlbb_detector.tflite, mlbb_screen_detector.tflite) in the Input folder.",
            12f, color = T.MUTED)
        switch("Small tactical model in fights", Cfg.tacticalLlmOn) { on ->
            Cfg.tacticalLlmOn = on
            com.sphynx.ai.Roles.loadByOwner()   // a button: a model that crashed may be tried again (Phase 6 S6)
            if (on) afterFitCheck(com.sphynx.ai.Roles.TACTICAL) {
                ModelManager.loadTactical(this) { err -> runOnUiThread { toast(err ?: "Small tactical model loaded") } }
            }
            else ModelManager.unloadTactical()
        }
        // Phase 8 G11 (owner, 2026-11-23): the tactical model also reads the minimap picture now and then
        switch("Tactical model reads the minimap (pictures)", Cfg.tacticalSightOn) { on ->
            Cfg.tacticalSightOn = on
            // the model loads again with (or without) its picture part
            if (Cfg.tacticalLlmOn) Thread {
                com.sphynx.ai.Roles.unload(com.sphynx.ai.Roles.TACTICAL)
                com.sphynx.ai.Roles.loadByOwner()
                ModelManager.loadTactical(this) { err -> runOnUiThread {
                    toast(err ?: if (com.sphynx.ai.Roles.images(com.sphynx.ai.Roles.TACTICAL)) "Tactical model: reads pictures"
                                 else if (on) "Tactical model: text only (this model has no picture part)" else "Tactical model: text only") } }
            }.start()
        }
        text("Outside fights, every few seconds, the small tactical model also looks at the minimap picture and writes one " +
            "line (where the enemies are, whether they are grouped, turtle or lord). That line is added to the next fight " +
            "questions; fight advice never waits for a picture. It needs a tactical model that reads pictures: a .litertlm " +
            "with vision, or a GGUF with its mmproj. Each reading is saved next to what the detectors saw (Debug data).",
            12f, color = T.MUTED)
        // the fight-choice network: the fast numeric adviser in the same slot
        switch("Fight-choice network in fights (fast)", Cfg.fightNetOn) { on ->
            Cfg.fightNetOn = on
            if (on && !com.sphynx.learning.FightNet.ready) com.sphynx.learning.FightNet.load(this)?.let { toast(it) }
        }
        val fnInfo = text("", 12f, color = T.MUTED)
        upd { fnInfo.text = "Network: " + com.sphynx.learning.FightNet.info +
            (if (com.sphynx.learning.FightNet.asked > 0) " · last advice: ${com.sphynx.learning.FightNet.lastAdvice}" else "") +
            "\nIt comes in mlbb_policies.zip (Train the tactical networks). When both are on, the network advises first." }
        // does it help? the AI's matches with each adviser vs none, from your own games
        title("Fight advisers: on vs off")
        val ab = card()
        val abText = cardText(ab, 13f)
        upd { abText.text = com.sphynx.learning.ABCompare.summary() }
        text("Counts matches the AI plays with the small model on (loaded) or off for the WHOLE match. Same hero and lane both ways gives the fairest numbers.", 12f, color = T.MUTED)
        btn("Clear the comparison") {
            confirm("Clear the trained vs built-in comparison?", "Clear") { com.sphynx.learning.ABCompare.clear(); toast("Cleared") }
        }
        // the detector and the tactical network are used together: one switch
        switch("Use the trained models", Cfg.detectorOn && Cfg.policyOn) {
            Cfg.detectorOn = it; Cfg.policyOn = it
        }

        // PHASE 8: GGUF models run by llama.cpp (LlamaCppBackend, the shared inference contract)
        ggufSection()
    }

    private fun ggufSection() {
        val lib = com.sphynx.models.GgufLibrary
        val be = lib.backend(this)
        title("GGUF MODELS (LLAMA.CPP)", Capability.EXPERIMENTAL)
        text("Add a .gguf model: it is copied into the app. Load it, then ask the test question to see how fast this " +
            "phone answers. Runs on the CPU. Not used by the AI yet: the Strategic and Tactical engines still use " +
            "their LiteRT models.", 13f, color = T.MUTED)
        val gc = card()
        val gEngine = cardText(gc, 12f)
        val gStatus = cardText(gc, 13f)
        gEngine.text = com.sphynx.inference.LlamaJni.info()
        upd {
            val st = be.status()
            val info = be.modelInfo
            gStatus.text = st.describe() +
                (if (st.ready && info != null) "\n" + info.optString("model") + " · context ${info.optInt("nCtx")} tokens · ${info.optInt("threads")} threads" else "") +
                (if (lib.status.isNotBlank()) "\n" + lib.status else "")
        }
        btn("Add a .gguf model") {
            if (lib.busy) { toast(lib.status); return@btn }
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), reqGguf)
        }

        val models = lib.models(this)
        if (models.isEmpty()) text("No GGUF model added yet.", 13f, describe = false)
        for (f in models) {
            val mc = card()
            cardHeader(mc, f.name, "${f.length() / (1024 * 1024)} MB")
            val loadBtn = btn("") {
                if (lib.loadedFile(this) == f) {
                    Thread { lib.unload(this) }.start()
                } else {
                    toast("Loading ${f.name}...")
                    lib.load(this, f) { msg -> runOnUiThread { toast(msg.lineSequence().first().take(150)) } }
                }
            }
            upd { loadBtn.text = if (lib.loadedFile(this) == f) "Unload ${f.name}" else "Load ${f.name}" }
            btn("Remove ${f.name}") { confirm("Remove ${f.name} from the phone? You would have to download or copy it again.", "Remove") {
                Thread {
                    val ok = lib.remove(this, f)
                    runOnUiThread { toast(if (ok) "Removed ${f.name}" else "Could not remove ${f.name}"); if (page == "model") show("model") }
                }.start()
            } }
        }

        val tc = card()
        cardHeader(tc, "Test question")
        cardNote(tc, lib.TEST_PROMPT)
        val tAnswer = cardText(tc, 14f)
        val tLine = cardNote(tc)
        upd {
            val t = lib.test
            tAnswer.text = t?.partial()?.trim()?.ifEmpty { if (t.done) "(empty answer)" else "..." } ?: "Not asked yet."
            tLine.text = lib.testLine
        }
        btn("Ask the test question") { lib.askTest(this)?.let { toast(it) } }
        btn("Stop the answer") { lib.stopTest(this) }

        // Phase 8 G5/G6: the repeatable GGUF benchmark (threads, 8-bit cache), saved to the Debug data
        val bc = card()
        cardHeader(bc, "GGUF benchmark")
        cardNote(bc, "Three fixed questions on one GGUF model with 4, 2 and 6 threads, then 4 threads with the 8-bit cache. " +
            "Each setting loads fresh. It takes a few minutes; keep the app open. The fastest thread count is kept for " +
            "that model. Every answer is saved for the Debug data upload.")
        val bLine = cardNote(bc)
        val sLine = cardNote(bc)
        upd {
            bLine.text = com.sphynx.models.GgufBenchmark.line.ifEmpty { "Not run yet." }
            sLine.text = com.sphynx.models.GgufStability.line
        }
        // Phase 8 G9: load and unload 20 times; the memory must come back
        btn("Load and unload 20 times (stability)") {
            val models = lib.models(this)
            if (models.isEmpty()) { toast("Add a GGUF model first"); return@btn }
            val rolesUse = listOf(com.sphynx.ai.Roles.STRATEGIC, com.sphynx.ai.Roles.TACTICAL)
                .any { com.sphynx.ai.Roles.backend(it) == com.sphynx.inference.BackendType.LLAMA_CPP }
            AlertDialog.Builder(this).setTitle("Test which model?")
                .setItems(models.map { it.name }.toTypedArray()) { _, i ->
                    com.sphynx.models.GgufStability.startCycles(this, models[i], rolesUse) { msg -> runOnUiThread { toast(msg.take(120)) } }
                        ?.let { toast(it) }
                }.setNegativeButton("Cancel", null).show()
        }
        btn("Run the GGUF benchmark") {
            val models = lib.models(this)
            if (models.isEmpty()) { toast("Add a GGUF model first"); return@btn }
            val rolesUse = listOf(com.sphynx.ai.Roles.STRATEGIC, com.sphynx.ai.Roles.TACTICAL)
                .any { com.sphynx.ai.Roles.backend(it) == com.sphynx.inference.BackendType.LLAMA_CPP }
            AlertDialog.Builder(this).setTitle("Benchmark which model?")
                .setItems(models.map { it.name }.toTypedArray()) { _, i ->
                    com.sphynx.models.GgufBenchmark.start(this, models[i], rolesUse) { msg -> runOnUiThread { toast(msg.take(120)) } }
                        ?.let { toast(it) }
                }.setNegativeButton("Cancel", null).show()
        }
    }


    // =====================================================================
    //  LEARN
    // =====================================================================

    private fun learnPage() {
        DemoRecorder.refreshTotal()
        com.sphynx.learning.LiveRecorder.refreshTotal()
        // ---- how much better than the last training (owner, 2026-11-12) ----
        title("Training progress")
        val tr = com.sphynx.learning.TrainingResults
        Thread { try { tr.refresh(this) } catch (e: Exception) { } }.start()
        val trc = card()
        asDescription(cardNote(trc, "Each circle: how much better (green) or worse (red) the last training is than the one before it."))
        for (k in com.sphynx.learning.TrainingResults.Kind.values()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            val ring = ProgressRing(this)
            row.addView(ring, LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(this).apply { text = k.label; textSize = 14f; setTextColor(c(T.TEXT)); setTypeface(typeface, Typeface.BOLD) }
            val detail = TextView(this).apply { textSize = 13f; setTextColor(c(T.MUTED)); setLineSpacing(0f, 1.1f) }
            texts.addView(name); texts.addView(detail)
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            trc.addView(row)
            var shownSig = "?"
            upd {
                val h = tr.history(k)
                val now = h.lastOrNull(); val prev = if (h.size >= 2) h[h.size - 2] else null
                val sig = (now?.sig ?: "") + "|" + (prev?.sig ?: "")
                if (sig == shownSig) return@upd
                shownSig = sig
                ring.set(tr.improvementPct(prev?.score, now?.score), tr.circleText(prev, now).replace("no result yet", "-").replace("first result", "new"))
                val when0 = now?.let { java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(it.at)) }
                detail.text = when {
                    now == null -> if (k == com.sphynx.learning.TrainingResults.Kind.ENGINE || k == com.sphynx.learning.TrainingResults.Kind.YOU) "No result yet: a 5v5 match with ${com.sphynx.learning.TrainingResults.MIN_FIGHTS}+ smart fights records one."
                        else "No result yet: train it (below) to get one."
                    prev == null -> "%.1f%% %s (%s). The next training is compared with this one.".format(now.score * 100, k.measure, when0)
                    else -> "%.1f%% %s now, %.1f%% last time (%s)%s".format(now.score * 100, k.measure, prev.score * 100, when0,
                        if (now.n > 0) " · ${now.n} checked" else "")
                }
            }
        }

        // ---- one-tap training in the cloud (Kaggle) ----
        title("Train in the cloud", Capability.NEEDS_INTERNET)
        val ctc = card()
        val ctHead = cardHeader(ctc, "Kaggle (free GPU)", "", T.GREEN)
        val ctChip = (ctHead.parent as LinearLayout).getChildAt(1) as TextView
        val ctNote = cardNote(ctc)
        val ct = com.sphynx.learning.CloudTrain
        upd {
            ctChip.text = if (ct.ready()) "key set" else "no key"
            ctChip.setTextColor(c(if (ct.ready()) T.GREEN else T.RED))
            ctNote.text = if (ct.ready()) "Tap a training: inputs upload from Sphynx-Storage, it trains on Kaggle, results download back and load by themselves."
                else "Put kaggle.json in the Sphynx-Storage/Input folder (kaggle.com > Settings > API > Create New Token), then Scan the folder on the Model page."
        }
        for (j in com.sphynx.learning.CloudTrain.Job.values()) {
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
                go.setTextColor(c(T.ON_FILL))
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
        val table = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(c(T.TEXT)) }
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
        val lessons = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(c(T.TEXT)) }
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
                "Your recorded play (Coach): ${DemoRecorder.total} moments - see Hero & match › Play style"
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
            (if (Cfg.liveHero.isNotBlank()) " · role ${com.sphynx.game.Lane.live().label}" else "") + " (found by itself)\nSkill icons: " +
            com.sphynx.vision.HeroVerifier.liveStatus + " · looks learned: ${com.sphynx.vision.HeroVerifier.learnedLiveCount()}" }
        val liveInfo = text("", 12f, color = "#9AA0A6")
        upd {
            com.sphynx.learning.LiveRecorder.let { r ->
                val st = GameStateTracker.state
                val watching = st.phase == com.sphynx.game.Phase.LIVE
                liveInfo.text = (if (watching) "Watching a live match now" + (if (st.self == null) " (their hero is dead or not found on screen: not recording)" else ", their HP ${(st.selfHp * 100).toInt()}%") +
                    "\nMatch time: " + (if (st.matchSec >= 0) "%d:%02d".format(st.matchSec / 60, st.matchSec % 60) else "not read yet (needs Gemma)") +
                    "\nPanel: ${r.panel}\nLast seen: ${r.lastAction}\n" else "Not watching a live match\n") +
                    "Live moments: ${r.recorded} this session, ${r.total} saved; deaths seen: ${r.deaths} (the 6 s before each are marked)"
            }
        }
        btn("Delete the live match moments") {
            AlertDialog.Builder(this).setTitle("Delete all live match moments?")
                .setPositiveButton("Delete") { _, _ -> com.sphynx.learning.LiveRecorder.clear { runOnUiThread { toast("Deleted") } } }
                .setNegativeButton("Cancel", null).show()
        }

        title("HOW THE AI DOES PER HERO AND ROLE")
        text("Every match: hero, role, K/D/A, result.", 13f)
        val ml = text("", 12f)
        upd { ml.text = com.sphynx.game.MatchLog.summary() }
        btn("Clear the match log") {
            confirm("Clear the match log?", "Clear") { com.sphynx.game.MatchLog.clear(); toast("Cleared") }
        }

        title("DETECTOR TRAINING PICTURES")
        text("Saves labelled pictures while you play (minimap 300, screen 300 per batch). Train zips them by itself; at capacity they are zipped and the switch turns off.", 13f)
        val zk = com.sphynx.learning.DatasetZipper
        val swMini = switch("Collect minimap training pictures", Cfg.collectDataset) { Cfg.collectDataset = it }
        val swScreen = switch("Collect whole-screen training pictures", Cfg.collectScreenDataset) { Cfg.collectScreenDataset = it }
        val dsInfo = text("", 12f, color = "#9AA0A6")
        upd {
            // the switches follow the app turning a full collector off
            if (swMini.isChecked != Cfg.collectDataset) swMini.isChecked = Cfg.collectDataset
            if (swScreen.isChecked != Cfg.collectScreenDataset) swScreen.isChecked = Cfg.collectScreenDataset
            fun line(k: com.sphynx.learning.DatasetZipper.Kind) = "${zk.count(k)} of ${k.capacity} pictures since the last zip" +
                if (!zk.switchOn(k)) " - switched off" else ""
            dsInfo.text = "Minimap: " + line(com.sphynx.learning.DatasetZipper.Kind.MINIMAP) +
                "\nScreen: " + line(com.sphynx.learning.DatasetZipper.Kind.SCREEN) +
                "\nLast zip: " + zk.status
        }
        // delete every picture of one kind (owner, 2026-11-12): the pictures, their zips, the parts waiting for upload
        for (k in com.sphynx.learning.DatasetZipper.Kind.values()) {
            btn("Delete all ${k.label} pictures") {
                AlertDialog.Builder(this).setTitle("Delete all ${k.label} pictures?")
                    .setMessage("Deletes every ${k.label} training picture on the phone, its zips and the parts waiting for the " +
                        "GitHub upload. Pictures already on Kaggle or GitHub stay there. This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> zk.deleteAll(this, k) { m -> runOnUiThread { toast(m.take(200)) } } }
                    .setNegativeButton("Cancel", null).show()
            }
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

    // THE DEBUG DATA PAGE (owner, 2026-11-12): everything needed for debugging on ONE page - the session recorder,
    // the exports, and the upload to GitHub (branch recordings-for-debugging). The training data for the notebooks
    // (Learn page: CSV exports, picture zips) is not here and is never uploaded.
    private fun debugPage() {
        // SESSION RECORDER (data file + video, for bug reports)
        title("SESSION RECORDER - for bug reports")
        text("Records what the AI saw and decided (about 10 times a second, every aimed skill, the log) into a data file, " +
            "and a video of the AI's view with its targets and aim drawn on top. Start it before a match, stop it after; " +
            "everything is saved in Sphynx-Storage/Recordings and uploaded from this page. Screen capture must be on for the video.", 12f, color = T.MUTED)
        val recInfo = text("", 12f)
        val recBtn = btn("") {
            val r = com.sphynx.core.SessionRecorder
            if (r.on) { toast("Saving…"); r.stop(this) { msg -> runOnUiThread { toast(msg.take(200)) } } }
            else { r.start(this); toast(if (com.sphynx.core.VideoRecorder.on) "Recording data + video" else "Recording data (start screen capture for video)") }
        }
        switch("Start recording by itself at the start of every match (stops and saves at the match end)", Cfg.autoRecord) {
            Cfg.autoRecord = it
        }
        val limBtn = btn("") {
            val v = com.sphynx.core.VideoRecorder
            val ch = v.SIZE_CHOICES_MB
            v.limitMb = ch[(ch.indexOf(v.limitMb) + 1) % ch.size]
            toast("Video part size: ${v.limitMb} MB")
        }
        upd {
            val v = com.sphynx.core.VideoRecorder
            limBtn.text = "New file every ${v.limitMb} MB of video (about ${v.partSeconds(v.limitMb) / 60} min ${v.partSeconds(v.limitMb) % 60} s) - tap to change"
        }
        upd {
            val r = com.sphynx.core.SessionRecorder
            recInfo.text = r.status()
            recInfo.setTextColor(c(if (r.on) T.RED else T.MUTED))
            recBtn.text = if (r.on) "■ Stop and save the recording" else "● Start recording (data + video)"
        }


        title("EXPORTS FOR DEBUGGING")
        text("Every recording already saves the speed data, the scorecards, the settings, the full log and the scenery picture " +
            "once, with its last part. These buttons save them now, without a recording (the same files, no second copy in CSV).", 12f, color = T.MUTED)
        btn("Export the speed data and the scorecards now") {
            Thread {
                val msg = try { com.sphynx.core.SessionRecorder.exportResearchNow(this) } catch (e: Exception) { "Export failed: ${e.message}" }
                runOnUiThread { toast(msg.take(200)) }
            }.start()
        }
        btn("Save the full log now (with crashes)") {
            Thread {
                val msg = try {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    val f = java.io.File(cacheDir, "diagnostics-$stamp.log")
                    f.writeText(Diag.read())
                    com.sphynx.core.SessionRecorder.exportFile(this, f, "text/plain").also { f.delete() }
                } catch (e: Exception) { "Export failed: ${e.message}" }
                runOnUiThread { toast(msg.take(200)) }
            }.start()
        }

        // Phase 9 M7: the model registry (no folder names), into Recordings, uploaded with the debug data
        btn("Export the model registry now (every model, its settings and what was learnt)") {
            Thread {
                val msg = try {
                    val recs = com.sphynx.models.ModelRegistry.refresh(this)
                    val roles = listOf(com.sphynx.ai.Roles.STRATEGIC, com.sphynx.ai.Roles.TACTICAL)
                    val jobs = roles.associate { it.name.lowercase() to ModelManager.choiceLabel(this, it) }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    val f = java.io.File(cacheDir, com.sphynx.models.ModelRegistryExport.fileName(stamp))
                    f.writeText(com.sphynx.models.ModelRegistryExport.text(recs, com.sphynx.core.AppVersion.VERSION, jobs,
                        com.sphynx.models.ModelStorage.summary(recs, try { filesDir.usableSpace } catch (e: Exception) { -1L }),
                        System.currentTimeMillis()))
                    "${recs.size} models. " + com.sphynx.core.SessionRecorder.exportFile(this, f, "application/json").also { f.delete() }
                } catch (e: Exception) { "Export failed: ${e.message}" }
                runOnUiThread { toast(msg.take(200)) }
            }.start()
        }

        // Phase 5: resource baselines (idle, capture on, Gemma loaded, GGUF loaded) - one snapshot per tap
        btn("Save a resource snapshot now (memory, CPU, heat, what is loaded)") {
            Thread {
                val msg = try {
                    val now = System.currentTimeMillis()
                    val st = com.sphynx.core.DeviceStats.sample(this)
                        ?: throw IllegalStateException("no reading yet, try again in two seconds")
                    val pw = com.sphynx.core.DeviceInfo.power(this)
                    val pm = getSystemService(android.os.PowerManager::class.java)
                    val r = com.sphynx.core.ResourceBaseline.Reading(now, ScreenCaptureService.running, com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC),
                        com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.TACTICAL), com.sphynx.models.GgufLibrary.loadedFile(this) != null,
                        st.ramTotalMb, st.ramAvailMb, st.lowMemory, st.appRamMb, st.appJavaMb, st.appNativeMb, st.appCpuPct,
                        pw.thermal + (try { " (status ${pm?.currentThermalStatus})" } catch (e: Exception) { "" }),
                        pw.headroom, pw.batteryC, pw.batteryPct, pw.charging)
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now))
                    val f = java.io.File(cacheDir, com.sphynx.core.ResourceBaseline.fileName(stamp))
                    f.writeText(com.sphynx.core.ResourceBaseline.toJson(r,
                        com.sphynx.core.DeviceProfile.last?.let { com.sphynx.core.DeviceProfile.summary(it) },
                        com.sphynx.core.AppVersion.VERSION).toString(2))
                    Diag.info("Resource baseline: " + com.sphynx.core.ResourceBaseline.summary(r))
                    com.sphynx.core.ResourceBaseline.summary(r) + "\n" + com.sphynx.core.SessionRecorder.exportFile(this, f, "application/json").also { f.delete() }
                } catch (e: Exception) { "Snapshot failed: ${e.message}" }
                runOnUiThread { toast(msg.take(240)) }
            }.start()
        }
        text("For Phase 5's resource baselines: tap once in each state (nothing loaded; screen capture on; the strategic " +
            "model loaded; a GGUF model loaded). Each snapshot is saved in Recordings and uploaded with the debug data.")

        title("UPLOAD TO GITHUB", Capability.NEEDS_INTERNET)
        val gh = com.sphynx.core.GitHubUpload
        text("Uploads everything in the recordings folder that is not on GitHub yet to the branch " + gh.BRANCH + ": all files " +
            "that are not videos in one commit, each video in a commit of its own. It also sends the training results " +
            "(every training's score and how much it improved) and the minimap and screen pictures taken since the last upload, " +
            "zipped in parts of up to 95 MB (each part in a commit of its own). Every file is deleted from the phone once it is " +
            "on GitHub. The picture zips used for training stay until a training with them finishes successfully. " +
            "Needs a fine-grained GitHub token (this " +
            "repository only, Contents: read and write) in the file " + gh.TOKEN_FILE + " in the app's folder.", 12f, color = T.MUTED)
        val ghInfo = text("", 12f)
        var ghState = "checking…"
        fun refreshGh() {
            Thread {
                val line = try {
                    (if (gh.token(this) != null) "✓ token found" else "✗ no token (" + gh.TOKEN_FILE + ")") + " · " + gh.pending(this) + " file(s) waiting"
                } catch (e: Exception) { "could not check: ${e.message}" }
                ghState = line
            }.start()
        }
        refreshGh()
        val upNow = btn("Upload the debug data to GitHub now") {
            toast("Uploading…")
            gh.uploadAll(this) { msg -> refreshGh(); runOnUiThread { toast(msg.take(200)) } }
        }
        needsInternet(upNow)
        switch("Upload by itself after each recording", Cfg.autoUpload) { Cfg.autoUpload = it }
        upd { ghInfo.text = ghState + "\n" + (if (gh.busy) "Uploading: " else "Last upload: ") + gh.status }
    }

    private fun logsPage() {
        text("The session recorder, every export for debugging and the GitHub upload are on the Debug data page.", 12f, color = T.MUTED)

        title("DIAGNOSTICS - bugs and errors")
        val cur = text("")
        upd {
            val e = listOf(AiRuntime.lastError, StrategicAdvisor.lastError).filter { it.isNotEmpty() }
            cur.text = if (e.isEmpty()) "✓ No active errors" else e.joinToString("\n") { "✗ $it" }
            cur.setTextColor(Color.parseColor(if (e.isEmpty()) "#66BB6A" else "#EF5350"))
        }
        // Phase 5 (P2): how Android says the app ended last time (crash, native crash, not responding, low memory)
        val lastExit = text("", 12f, color = T.MUTED, describe = false)
        upd {
            val s = "Last time the app closed: " + (com.sphynx.core.ExitHistory.last ?: "checking…")
            if (lastExit.text.toString() != s) lastExit.text = s
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
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextIsSelectable(true); setTextColor(c(T.TEXT))
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

    // Phase 5 (P6): battery optimisation and background restriction (optional item of the Ready check)
    private fun batteryItem(): PlayStatus.Item {
        val ignoring = try { getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true } catch (e: Exception) { true }
        val restricted = try { getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true } catch (e: Exception) { false }
        return PlayStatus.Item("battery", "Battery: not restricted in the background", PlayStatus.batteryOk(ignoring, restricted),
            PlayStatus.batteryWord(ignoring, restricted), PlayStatus.batteryWord(ignoring, restricted), needed = false)
    }

    // the system's own page (no extra permission): restricted -> the app's page; optimised -> the battery optimisation list
    private fun openBatterySettings() {
        val restricted = try { getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true } catch (e: Exception) { false }
        val intents = listOfNotNull(
            if (restricted) null else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (i in intents) { try { startActivity(i); toast("Choose Sphynx, then No restrictions / Don't optimise"); return } catch (e: Exception) { } }
        toast("Open Settings > Apps > Sphynx > Battery > No restrictions")
    }

    // Phase 6 S7: pick the model of a job (its LiteRT-LM file or a GGUF model) and load it
    private fun chooseRoleModel(role: com.sphynx.inference.ModelRole) {
        val rc = com.sphynx.inference.roles.RoleChoice
        val lite = if (role == com.sphynx.ai.Roles.STRATEGIC) ModelManager.strategicName() else ModelManager.tacticalName()
        val ggufs = com.sphynx.models.GgufLibrary.models(this)
        val values = listOf(rc.LITERT) + ggufs.map { rc.gguf(it.name) }
        // Phase 8 G11: a LiteRT-LM file known (from an earlier load) to have no picture part is marked text only
        val liteFile = if (role == com.sphynx.ai.Roles.STRATEGIC) ModelManager.modelFile(this) else ModelManager.tacticalFile(this)
        val liteNote = when (com.sphynx.models.ModelRegistry.fact(liteFile.name, if (liteFile.isFile) liteFile.length() else -1L, com.sphynx.models.ModelRecord.Fact.VISION)) { "0" -> ", text only: it has no picture part"; "1" -> ", reads pictures"; else -> "" }
        val labels = listOf("$lite (LiteRT-LM file$liteNote)") + ggufs.map {
            "${it.name} (GGUF, ${it.length() / 1048576} MB)\n" + com.sphynx.models.GgufLibrary.describe(it) +   // Phase 8 G2
                // Phase 8 G11: a model without its mmproj reads text only (allowed, owner 2026-11-23)
                (if (com.sphynx.models.GgufLibrary.projectorFor(it) == null) "\ntext only: add its mmproj file to read pictures"
                 else "\nreads pictures (mmproj found)")
        }
        AlertDialog.Builder(this).setTitle(if (role == com.sphynx.ai.Roles.STRATEGIC) "Strategic job" else "Tactical job")
            .setItems(labels.toTypedArray()) { _, i ->
                val v = values[i]
                val why = rc.problem(role, v, ModelManager.choice(rc.other(role)))
                if (why != null) { toast(why) } else {
                    val before = ModelManager.choice(role)
                    val ownMb = if (com.sphynx.ai.Roles.ready(role)) ModelManager.fileFor(this, role).length() / 1_048_576L else 0L
                    Cfg.setRaw(rc.settingKey(role), v)
                    afterFitCheck(role, ownMb, cancelled = { Cfg.setRaw(rc.settingKey(role), before) }) { Thread {
                        com.sphynx.ai.Roles.unload(role)
                        com.sphynx.ai.Roles.loadByOwner()
                        val done: (String?) -> Unit = { err -> runOnUiThread { toast(err ?: ("Loaded: " + ModelManager.choiceLabel(this, role))) } }
                        if (role == com.sphynx.ai.Roles.STRATEGIC) ModelManager.load(this, done)
                        else if (Cfg.tacticalLlmOn) ModelManager.loadTactical(this, done)
                        else runOnUiThread { toast("Saved. Turn on the small tactical model to load it") }
                    }.start() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // Phase 7 L3 (owner, 2026-11-23: "warn, let me load"): before a load the owner asked for, a model that will probably
    // not fit next to the game asks first (core/MemoryFit). [ownMb] is what the job's current model gives back.
    private fun afterFitCheck(role: com.sphynx.inference.ModelRole, ownMb: Long = 0L, cancelled: () -> Unit = {}, go: () -> Unit) {
        val w = ModelManager.fitWarning(this, role, ownMb) ?: return go()
        com.sphynx.core.Diag.log("W", "Memory check (${role.name.lowercase()}), asked: $w")
        AlertDialog.Builder(this).setTitle("Memory may run short")
            .setMessage("$w\n\nLoad it anyway?")
            .setPositiveButton("Load anyway") { _, _ -> com.sphynx.core.Diag.log("I", "Memory check: loaded anyway"); go() }
            .setNegativeButton("Cancel") { _, _ -> cancelled() }
            .setOnCancelListener { cancelled() }
            .show()
    }

    // the GPU switch changed while the model is loaded: load it again on the chosen processor (owner, 2026-11-13:
    // turning the switch on did nothing until the next start, so Gemma stayed on the slow CPU)
    private fun reloadGemmaForGpu() {
        if (!com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC)) return
        toast("Reloading the model on the " + (if (Cfg.useNpu) "NPU (compiling, can take over a minute)" else if (Cfg.useGpu) "GPU" else "CPU") + "…")
        com.sphynx.ai.Roles.loadByOwner()   // a button: a model that crashed may be tried again (Phase 6 S6)
        ModelManager.load(this) { err -> runOnUiThread { toast(err ?: ("Model loaded: " + com.sphynx.ai.Roles.state(com.sphynx.ai.Roles.STRATEGIC))) } }
    }

    // switch to MLBB and start placing one group of buttons there in 5 s
    private fun calibrateLater(groupKey: String) {
        val o = AccessibilityController.instance?.overlay
        if (o == null) { toast("Turn on the accessibility service first"); return }
        if (!ScreenCaptureService.running) toast("Tip: start screen capture first so Auto-detect can see the game")
        toast("Switch to MLBB now: calibration starts in 5 seconds")
        ui.postDelayed({ o.startCalibrationGroup(groupKey) }, 5000)
    }

    // FIRST-RUN SETUP (owner, 2026-11-12): a new install opens here - permissions, the folder, the models,
    // the main switches (all off on a new install), scenery calibration and skill button calibration.
    // It can be opened again from the Setup page.
    // a full-width button inside a card
    private fun inCard(cd: LinearLayout, t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; styleButton(this); setOnClickListener { onClick() }
        cd.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    // THE PERMISSIONS CARD: one card, used by the first-run guide and by Setup (backlog item 11: no duplicated setup
    // content). Each permission says Granted / Missing; the chip counts them.
    private fun permissionsCard(heading: String) {
        val p1 = card()
        val p1Head = cardHeader(p1, heading, "", T.GOLD)
        val p1Chip = (p1Head.parent as LinearLayout).getChildAt(1) as TextView
        val acc = inCard(p1, "Accessibility service (touches and the floating icon)") { openAccessibility() }
        inCard(p1, "Switch greyed out? Allow restricted settings") {
            toast("Tap ⋮ (top-right), then 'Allow restricted settings'")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val ov = inCard(p1, "Display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val nt = inCard(p1, "Notifications") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotify)
        }
        val fa = inCard(p1, "All files access (the app's folder)") {
            try { startActivity(com.sphynx.core.Storage.requestIntent(this)) }
            catch (e: Exception) { toast("Open Settings > Apps > Special app access > All files access > Sphynx") }
        }
        cardNote(p1, "POCO / Xiaomi: also allow 'pop-up windows while running in the background' and turn off 'Manage app if unused'.")
        upd {
            val a = AccessibilityController.instance != null
            val o = Settings.canDrawOverlays(this)
            val n = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val f = com.sphynx.core.Storage.allFilesAccess
            markWord(acc, a); markWord(ov, o); markWord(nt, n); markWord(fa, f)
            val ok = listOf(a, o, n, f).count { it }
            p1Chip.text = if (ok == 4) "done" else "$ok of 4"; p1Chip.setTextColor(c(if (ok == 4) T.GREEN else T.GOLD))
        }
    }

    private fun welcomePage() {
        title("WELCOME TO SPHYNX")
        text("Six short steps get the app ready. Each turns green when it is done. Every switch starts OFF: turn on only " +
            "what you want. You can do any step later from the Model, Learn and Setup pages.", 13f, color = T.MUTED)

        // 1. permissions: the same card as on Setup (backlog item 11: the guide reuses the Setup cards)
        permissionsCard("1. Permissions")

        // 2. folder
        val p2 = card()
        val p2Head = cardHeader(p2, "2. The app's folder", "", T.GOLD)
        val p2Chip = (p2Head.parent as LinearLayout).getChildAt(1) as TextView
        val p2Note = cardNote(p2)
        val pick = inCard(p2, "Choose the folder") {
            @Suppress("DEPRECATION")
            startActivityForResult(com.sphynx.models.AutoFolder.pickIntent(), reqFolder)
        }
        inCard(p2, "Scan the folder now (models, detectors, networks, keys)") {
            com.sphynx.models.AutoFolder.scan(this) { msg -> runOnUiThread { toast(msg.take(150)) } }
        }
        upd {
            val af = com.sphynx.models.AutoFolder
            val ok = com.sphynx.core.Storage.allFilesAccess || af.treeUri != null
            p2Note.text = com.sphynx.core.Storage.status() + "\n" + af.folderHint + "\nLast scan: " + af.status
            pick.text = if (af.treeUri == null) "Choose the folder" else "Change the folder"
            mark(pick, af.treeUri != null)
            p2Chip.text = if (ok) "done" else "to do"; p2Chip.setTextColor(c(if (ok) T.GREEN else T.GOLD))
        }

        // 3. models
        val p3 = card()
        val p3Head = cardHeader(p3, "3. Load the models", "", T.GOLD)
        val p3Chip = (p3Head.parent as LinearLayout).getChildAt(1) as TextView
        val p3Note = cardNote(p3)
        val load = inCard(p3, "Load the strategic model (Gemma)") {
            com.sphynx.ai.Roles.loadByOwner()   // a button: a model that crashed may be tried again (Phase 6 S6)
            val ownMb = if (com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC))
                ModelManager.fileFor(this, com.sphynx.ai.Roles.STRATEGIC).length() / 1_048_576L else 0L
            afterFitCheck(com.sphynx.ai.Roles.STRATEGIC, ownMb) {
                toast("Loading…")
                ModelManager.load(this) { err -> runOnUiThread { toast(err ?: "Model loaded") } }
            }
        }
        inCard(p3, "Get Gemma 4 E2B (2.6 GB)") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.MODEL_PAGE))) }
        cardNote(p3, "Put the .litertlm file in the app's folder, then Scan the folder (step 2): it is copied in. The trained " +
            "detectors and networks load by themselves from the same folder. More on the Model page.")
        upd {
            val ready = com.sphynx.ai.Roles.ready(com.sphynx.ai.Roles.STRATEGIC)
            p3Note.text = "Strategic model: " + ModelManager.status(this) + (if (ready) " - loaded" else if (com.sphynx.ai.Roles.loading(com.sphynx.ai.Roles.STRATEGIC)) " - loading…" else "") +
                "\nSmall tactical model: " + ModelManager.tacticalStatus(this)
            markWord(load, ready, "Loaded", if (com.sphynx.ai.Roles.loading(com.sphynx.ai.Roles.STRATEGIC)) "Loading" else "Not loaded")
            p3Chip.text = if (ready) "done" else "to do"; p3Chip.setTextColor(c(if (ready) T.GREEN else T.GOLD))
        }

        // 4. initial setup: the main switches (all off on a new install)
        val p4 = card()
        cardHeader(p4, "4. Choose what runs", "your choice", T.ACCENT)
        cardNote(p4, "Everything starts off. Dry run stays ON until you are ready (the AI then shows its moves but does not touch).")
        fun sw4(label: String, get: Boolean, set: (Boolean) -> Unit) {
            val s = switch(label, get, set)
            (s.parent as? android.view.ViewGroup)?.removeView(s)
            p4.addView(s)
        }
        sw4("Dry run (no touches)", Cfg.dryRun) { Cfg.dryRun = it }
        sw4("Record each match by itself", Cfg.autoRecord) { Cfg.autoRecord = it }
        sw4("Upload the debug data after each recording", Cfg.autoUpload) { Cfg.autoUpload = it }
        sw4("Collect minimap pictures (training)", Cfg.collectDataset) { Cfg.collectDataset = it }
        sw4("Collect screen pictures (training)", Cfg.collectScreenDataset) { Cfg.collectScreenDataset = it }
        sw4("Smart fights (learning AI)", Cfg.smartFights) { Cfg.smartFights = it }
        sw4("Use the trained models", Cfg.detectorOn && Cfg.policyOn) { Cfg.detectorOn = it; Cfg.policyOn = it }
        sw4("Strategic model on the GPU (faster)", Cfg.useGpu) { Cfg.useGpu = it; reloadGemmaForGpu() }
        sw4("Show the AI's touches", Cfg.showTouches) { Cfg.showTouches = it }

        // 5. skill button calibration (before the scenery: the roam needs the buttons placed - owner, 2026-11-13)
        val p6 = card()
        val p6Head = cardHeader(p6, "5. Skill button calibration", "", T.GOLD)
        val p6Chip = (p6Head.parent as LinearLayout).getChildAt(1) as TextView
        cardNote(p6, "Open a match, come back, tap the layout of your hero: drag the yellow markers onto the buttons (or " +
            "Auto-detect), then Save. The first match also places them by itself.")
        inCard(p6, "Calibrate 2-skill buttons") { calibrateLater("skills2") }
        inCard(p6, "Calibrate 3-skill buttons") { calibrateLater("skills3") }
        inCard(p6, "Calibrate 4-skill buttons") { calibrateLater("skills4") }
        inCard(p6, "Calibrate joystick / attack / spell") { calibrateLater("game") }
        upd {
            val n = listOf("skills2", "skills3", "skills4").count { Layout.isGroupSaved(it) }
            p6Chip.text = if (n > 0) "$n saved" else "to do"; p6Chip.setTextColor(c(if (n > 0) T.GREEN else T.GOLD))
        }

        // 6. scenery calibration
        val p5 = card()
        val p5Head = cardHeader(p5, "6. Scenery calibration", "", T.GOLD)
        val p5Chip = (p5Head.parent as LinearLayout).getChildAt(1) as TextView
        cardNote(p5, "Teaches the app where towers, pits, bushes and walls are on the screen compared to the minimap. Open a " +
            "Solo practice match vs Computer, start screen capture, then start the roam: the hero walks the map by itself. Come back " +
            "and stop it. Nothing is learned or recorded in Solo practice: it is for calibration only.")
        inCard(p5, "Start screen capture") { if (!ScreenCaptureService.running) askScreenCapture() else toast("Screen capture is already on") }
        val cam = inCard(p5, "") { toast(com.sphynx.game.SceneryMap.cycleCameraPreset()) }
        val roam = inCard(p5, "") { toast(com.sphynx.game.SceneryMap.toggleRoam()) }
        val p5Note = cardNote(p5)
        upd {
            val sm = com.sphynx.game.SceneryMap
            cam.text = "Camera height: ${sm.cameraPresetLabel()}  (tap to change)"
            roam.text = if (sm.roamActive) "Stop the scenery calibration roam" else "Start the scenery calibration roam (Practice match)"
            mark(roam, sm.roamActive, T.GOLD)
            p5Note.text = "Memorized: ${sm.summary()}\n${sm.status}"
            val done = Cfg.raw("first_run_scenery") == "1"
            if (sm.roamActive) Cfg.setRaw("first_run_scenery", "1")
            p5Chip.text = if (done) "done" else "to do"; p5Chip.setTextColor(c(if (done) T.GREEN else T.GOLD))
        }

        val fin = btn("Finish setup") { Cfg.firstRunPending = false; toast("Setup finished: you can change everything later"); show("home") }
        mark(fin, true)
        text("You can open this setup again from the Setup page.", 12f, color = T.MUTED)
    }

    private fun setupPage() {
        btn("Open the first-run setup") { show("welcome") }
        // the colour theme (owner, 2026-11-15): three to choose from, Signal by default
        title("THEME")
        radioSwitches(Theme.ALL.map { it.key to it.label }, Theme.byKey(Cfg.theme).key) { pick ->
            Cfg.theme = pick
            content.post { recreate() }       // every colour, the logo and the system bars take the new theme
        }
        text("The app's colours: what is selected or switched on, the main buttons, the banner on Play and the logo's " +
            "diamond. Text sizes and the red STOP are the same in every theme.")
        // the heading font: the owner picks between the two pixel fonts (design tokens, Phase 4)
        title("HEADING FONT")
        radioSwitches(Fonts.CHOICES, Fonts.choice(Cfg.headFont)) { pick ->
            Cfg.headFont = pick
            content.post { recreate() }       // the header, the tabs and every heading take the new font
        }
        text("The font of the headings, the tabs and the app name. Text and buttons use Inter. Pick the one you like; " +
            "the app redraws at once.")
        // Phase 5 (P5, owner 2026-11-21): what the app does by itself when the phone is low on memory or hot
        title("LOW MEMORY AND HEAT")
        radioSwitches(com.sphynx.core.PressurePolicy.Mode.values().map { it.key to it.label },
            com.sphynx.core.PressurePolicy.Mode.of(Cfg.pressureMode).key) { pick -> Cfg.pressureMode = pick }
        text("Only warn me: a line on the floating panel and in the log; nothing is unloaded. Step down gently: when the " +
            "phone is warm or low on memory the tactical model is unloaded first; when it is hot or very low on memory the " +
            "GGUF model too, and the strategic model only when memory is critical. They come back by themselves after five " +
            "quiet minutes, never during a fight. Play goes on with the rules either way.")
        title("PERMISSIONS")
        permissionsCard("Permissions")      // the same card as in the first-run guide (backlog item 11)

        title("SCREEN")
        val scr = text("", 12f, color = "#9AA0A6")
        upd {
            scr.text = "Screen: " + com.sphynx.core.ScreenGeom.describe(this) +
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
            com.sphynx.game.AutoTune.calStatus + ").\nSaved skill layouts: " +
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
        val kinds = com.sphynx.vision.MinimapColors.Kind.values()
        var kindIdx = 0
        val kindBtn = btn("") { kindIdx = (kindIdx + 1) % kinds.size }
        val kindInfo = text("", 13f)
        upd {
            val k = kinds[kindIdx]
            kindBtn.text = "Icon type: ${k.label}  (tap to change)"
            val ss = com.sphynx.vision.MinimapColors.samples(k)
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
                    else { com.sphynx.vision.MinimapColors.add(k, rgb); toast("Saved ${k.label}: ${rgb[0]},${rgb[1]},${rgb[2]}") }
                }
            }, 5000)
        }
        btn("Clear the samples of this icon type") {
            val kind = kinds[kindIdx]
            confirm("Clear the saved samples of this icon type? It has to be calibrated again.", "Clear") {
                com.sphynx.vision.MinimapColors.clear(kind); toast("Cleared")
            }
        }

        title("SCENERY CALIBRATION & MINIMAP SYNCHRONIZATION")
        text("Synchronizes 3D screen scenery (towers, monster pits, bushes, walls) with the 2D minimap on every frame. Saved persistently on your phone (mlbb-scenery-sync.json in /CodeOnTheGoProjects/Sphynx-Storage, or Download/Sphynx-Storage without all files access) across app uninstall/reinstall — only deleted when you press Clear saved map scenery or Reset minimap sync offsets.", 12f, color = "#9AA0A6")
        switch("Auto-sync 3D screen scenery with minimap landmarks", Cfg.sceneryAutoSync) { Cfg.sceneryAutoSync = it }
        val sceneryInfo = text("", 13f)
        val camPresetBtn = btn("") {
            val lbl = com.sphynx.game.SceneryMap.cycleCameraPreset()
            toast("Camera preset: $lbl")
        }
        btn("Sync 3D Scenery & Minimap Now") {
            val msg = com.sphynx.game.SceneryMap.syncNow(GameStateTracker.state)
            toast(msg)
        }
        val roamBtn = btn("") {
            val msg = com.sphynx.game.SceneryMap.toggleRoam()
            toast(msg)
        }
        btn("Reset scenery-minimap sync offsets") {
            confirm("Reset the scenery-minimap sync offsets?", "Reset") { toast(com.sphynx.game.SceneryMap.resetSyncCalibration()) }
        }
        btn("Clear saved map scenery") {
            confirm("Clear the saved map scenery? It is learned again while you play.", "Clear") {
                com.sphynx.game.SceneryMap.clear()
                toast("Scenery map cleared")
            }
        }
        // the places the hero got stuck at, remembered across matches (owner, 2026-11-13)
        val stuckInfo = text("", 12f, color = T.MUTED)
        upd { stuckInfo.text = "Stuck places (walls the map did not show): " + com.sphynx.vision.WallMap.stuckSummary() +
            ". Stuck twice at a place = a wall in every later match; a place the hero later stands on is forgotten." }
        btn("Forget the remembered stuck places") {
            AlertDialog.Builder(this).setTitle("Forget every remembered stuck place?")
                .setPositiveButton("Forget") { _, _ -> com.sphynx.vision.WallMap.clearStuck(); toast("Forgotten") }
                .setNegativeButton("Cancel", null).show()
        }
        upd {
            val active = com.sphynx.game.SceneryMap.roamActive
            camPresetBtn.text = "Camera Height: ${com.sphynx.game.SceneryMap.cameraPresetLabel()}  (tap to change)"
            roamBtn.text = if (active) "Stop scenery calibration roam" else "Start scenery calibration roam (Practice Mode)"
            sceneryInfo.text = "Memorized: ${com.sphynx.game.SceneryMap.summary()}\n" +
                "Roam status: ${com.sphynx.game.SceneryMap.status}\n" +
                com.sphynx.game.SceneryMap.syncStatus()
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

        // typing positions by hand was removed (owner, 2026-11-15): the in-game calibration moves the buttons
        btn("Reset all positions to defaults") {
            confirm("Reset every button position to the defaults? Your calibrated positions are lost.", "Reset") {
                for (ctl in Layout.defaults) Layout.reset(ctl.key)
                show("setup")
            }
        }
    }
}
