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

    private val pages = listOf("home", "player", "draft", "build", "meta", "model", "learn", "logs", "setup")
    private val labels = mapOf(
        "home" to "Home", "player" to "Player", "draft" to "Draft", "build" to "Build", "meta" to "Meta",
        "model" to "Model", "learn" to "Learn", "logs" to "Logs", "setup" to "Setup"
    )

    private val reqCapture = 42
    private val reqNotify = 43
    private val reqFolder = 46

    private val refresher = object : Runnable {
        override fun run() {
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
            setBackgroundColor(Color.parseColor("#0F1115"))
        }

        // header with the app logo
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        header.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_mlbb) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(this).apply {
            text = "MLBB Research"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val stopTop = Button(this).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
            setOnClickListener { AiRuntime.emergencyStop("app header") }
        }
        header.addView(stopTop)
        root.addView(header)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // page tabs (scroll sideways if they do not fit)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(10)) }
        for (k in pages) {
            val b = Button(this).apply {
                text = labels[k]
                textSize = 12f
                isAllCaps = false
                setOnClickListener { go(k) }
            }
            navButtons[k] = b
            nav.addView(b, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(nav) })

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

    private fun text(t: String, size: Float = 14f, bold: Boolean = false, color: String = "#E6E6E6"): TextView {
        val v = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        content.addView(v)
        return v
    }

    private fun title(t: String) = text(t, 13f, true, "#8AB4F8")

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }
        content.addView(b)
        if (defaultText == null) defaultText = b.textColors
        return b
    }

    private fun switch(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#E6E6E6"))
            isChecked = initial
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        content.addView(sw)
        return sw
    }

    private fun field(hint: String, value: String, numeric: Boolean, save: (String) -> Unit): EditText {
        text(hint, 12f, color = "#9AA0A6")
        val e = EditText(this).apply {
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            setText(value)
            setTextColor(Color.WHITE)
        }
        content.addView(e)
        leaveHooks.add { save(e.text.toString().trim()) }
        return e
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1D23")); cornerRadius = dp(12).toFloat() }
        }
        content.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        return c
    }

    private fun cardText(c: LinearLayout, size: Float = 14f, bold: Boolean = false): TextView {
        val v = TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor("#E6E6E6"))
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
        c.addView(v)
        return v
    }

    private fun mark(b: Button, on: Boolean, color: String = "#2E7D32") {
        if (on) {
            b.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
            b.setTextColor(Color.WHITE)
        } else {
            b.backgroundTintList = null
            defaultText?.let { b.setTextColor(it) }
        }
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
            "logs" -> logsPage()
            "setup" -> setupPage()
        }
        for ((k, b) in navButtons) mark(b, k == p, "#1565C0")
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
        val status = cardText(c1, 18f, true)
        val gemma = cardText(c1)
        val capture = cardText(c1)
        upd {
            status.text = "AI status: " + AiRuntime.statusLine()
            status.setTextColor(Color.parseColor(if (AiRuntime.anyOn()) "#66BB6A" else "#9AA0A6"))
            gemma.text = "Gemma 3n E2B: " + GemmaEngine.state +
                (if (GemmaEngine.calls > 0) "  (last answer ${GemmaEngine.lastLatencyMs / 100 / 10.0}s)" else "")
            capture.text = "Screen capture: " + (if (FrameHub.isLive()) FrameHub.source else "off") +
                "  |  vision ${"%.0f".format(FrameProcessor.processedFps)} fps"
        }
        val capBtn = btn("") {
            if (ScreenCaptureService.running) {
                startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
            } else askScreenCapture()
        }
        upd { capBtn.text = if (ScreenCaptureService.running) "Stop screen capture" else "Start screen capture (10-15 fps)" }

        title("RUN")
        stageSwitch("Player (plays the hero)", "player") { AiRuntime.playerOn }
        stageSwitch("Drafter (ban & pick)", "draft") { AiRuntime.drafterOn }
        stageSwitch("Builder (items)", "build") { AiRuntime.builderOn }
        switch("Autonomous: Drafter + Builder act on their own", Cfg.autonomous) { Cfg.autonomous = it }
        switch("Dry run: only show where it would tap", Cfg.dryRun) { Cfg.dryRun = it }
        val mode = text("", 13f, color = "#9AA0A6")
        upd {
            mode.text = "Match type: ${if (Cfg.ranked) "Ranked (ban & pick)" else "Standard (hero, spell, emblem)"} - change it on the Draft page.\n" +
                "Tip: the ⏻ button on the floating icon turns the AI on and off from inside the game."
        }

        title("WHAT THE AI SEES")
        val preview = PreviewView(this)
        content.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        upd { preview.refresh() }

        val c2 = card()
        val detected = cardText(c2)
        val decision = cardText(c2, 14f, true)
        val target = cardText(c2)
        upd {
            val s = GameStateTracker.state
            val hero = GameStateTracker.ownHero()
            detected.text = buildString {
                append("My hero: ").append(hero ?: "unknown")
                if (s.selfHp >= 0) append("  (HP ").append((s.selfHp * 100).toInt()).append("%)")
                if (s.self != null) append("  Level ").append(s.level).append(if (s.enemyLevel > 0) " (nearest enemy ${s.enemyLevel})" else "")
                append("  [${GameStateTracker.ownHeroSource()}]")
                append("\nLane: ").append(s.lane.label).append("   Spell: ").append(s.spell ?: "unknown")
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
            decision.text = "Current decision: " + ActionExecutor.lastDecision
            target.text = "Current target: " + ActionExecutor.lastTarget +
                "\nGemma plan: ${StrategicAdvisor.plan.plan} (aggression ${"%.1f".format(StrategicAdvisor.plan.aggression)})"
        }

        title("ACTION HISTORY")
        val hist = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#C8C8C8")) }
        content.addView(hist)
        upd {
            val h = ActionExecutor.history(12)
            hist.text = if (h.isEmpty()) "(nothing yet)" else h.joinToString("\n")
        }

        val learn = text("", 14f, true, "#8AB4F8")
        upd { learn.text = "Learning data: ${ExperienceRecorder.count} examples (${PerformanceEvaluator.evaluated} scored this session)" }

        val stop = Button(this).apply {
            text = "■  EMERGENCY STOP"
            textSize = 18f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
            setOnClickListener { AiRuntime.emergencyStop("home button") }
        }
        content.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(16) })
        text("Also: STOP ALL in the floating status window, the ⏻ button on the floating icon, and STOP AI in the notification.", 12f, color = "#9AA0A6")

        // the X on the floating icon only hides it; bring it back here
        val iconStatus = text("", 12f, color = "#9AA0A6")
        upd {
            val o = AccessibilityController.instance?.overlay
            iconStatus.text = when {
                o == null -> "Floating icon: needs the accessibility service"
                o.shown -> "Floating icon: showing (its X hides it; the AI keeps running)"
                o.userHidden -> "Floating icon: hidden (the AI keeps running in the background)"
                else -> "Floating icon: shows when you start the AI"
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
        text("The app finds the hero you really play: from its portrait on the minimap and its in-game model (learned once per hero), from the pick " +
            "(the AI's own standard pick, or Gemma reading the hero select / loading screen), then from the name below.", 12f, color = "#9AA0A6")
        val heroBox = field("Hero name (used for the standard pick; leave empty to detect)", Cfg.myHero, false) { Cfg.myHero = it }
        val heroInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            val p = HeroDb.profile(h)
            heroInfo.text = "Using: ${h ?: "unknown"} (${GameStateTracker.ownHeroSource()})\n" +
                "Role ${p.role.name.lowercase()}, ultimate: ${p.ult.name.lowercase()}, retreat below ${(p.retreatHp * 100).toInt()}% HP\n" +
                "Learned aggression adjust: ${"%+.2f".format(Cfg.learnedBias(h ?: "unknown"))}\n" +
                "Heroes whose icons are learned: ${IconMemory.heroCount()}"
        }
        btn("This is my hero now (learn its portrait and model from the game)") {
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
        btn("This is my spell now (learn its icon from the game)") {
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
        switch("Retribution equipped = always jungle", Cfg.retriMeansJungle) { Cfg.retriMeansJungle = it }
        switch("Jungler walks the jungle path (camp to camp, from the minimap)", Cfg.jungleRoute) { Cfg.jungleRoute = it }
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
        field("Seconds to walk from base to the lane (EXP: up, Gold: right, then along the lane)", Cfg.laneTravelSec.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.laneTravelSec = v.coerceIn(0, 60) }
        }

        title("SKILL COMBOS")
        switch("Use the hero's combo when fighting", Cfg.useCombos) { Cfg.useCombos = it }
        switch("Aim skills at the target (drag the skill button)", Cfg.aimSkills) { Cfg.aimSkills = it }
        val comboInfo = text("", 13f)
        upd {
            val h = GameStateTracker.ownHero()
            val (combo, from) = ComboBook.text(h)
            comboInfo.text = "Combo for ${h ?: "unknown hero"}: $combo ($from)\nNow: ${ComboRunner.status}"
        }
        text("Write it as s1 > s2 > a > ult (a = basic attack). A number in brackets is the pause after that step in ms, " +
            "e.g. s2(500) > s1 > ult.", 12f, color = "#9AA0A6")
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
        text("Get it online: downloads a guide page and copies the combo written there. If the page only describes the " +
            "skills, Gemma works a combo out of them. Uses the internet only when you tap.", 12f, color = "#9AA0A6")
        field("Guide address template ({hero} = hero name)", Cfg.comboUrl, false) { if (it.contains("http")) Cfg.comboUrl = it }
        val pageBox = field("Or a guide page address for this hero (optional)", "", false) { }
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
        switch("Neutral monster detector (jungle monsters, turtle, lord)", Cfg.detectMonsters) { Cfg.detectMonsters = it }
        val monInfo = text("", 13f)
        upd { monInfo.text = "Only the Lord shows its red HP bar before it is attacked. Every other monster (Turtle, buffs, " +
            "small camps) shows its bar only after it takes damage - from you, your teammates or the enemies. So: a monster " +
            "WITH a bar is found by that bar (no level badge next to it - enemy heroes have one) and named from its size, " +
            "colour and pit; at a camp or the Turtle pit with no bar yet, the AI starts with a basic attack so the bar " +
            "appears, then uses its skills. Monsters nobody has hit are only seen by the trained screen detector (if loaded)." }
        title("HERO LEVEL")
        text("Your level (1-15) is read from the badge left of your HP bar: it starts at 1, goes up when the badge " +
            "changes or a new skill point (+) shows, and every badge seen at a known level is remembered, so later " +
            "matches read it directly (also for enemy heroes). Before level 4 the AI farms first; at levels 4, 8 and 12 " +
            "and when ahead in level it fights more; 2+ levels behind it avoids even fights; at 15 it stops farming " +
            "small camps and pushes. It does not recall while a level-up is moments away.", 13f)
        val lvInfo = text("", 13f)
        upd { lvInfo.text = "Badges remembered: ${com.example.mlbbresearch.vision.LevelReader.templatesKnown} of 15 (levels 1, 2, 9 and 12 are built in)" }
        btn("Forget the remembered level badges") { com.example.mlbbresearch.vision.LevelReader.forgetTemplates(); toast("Forgotten") }
        title("TURTLE & LORD")
        switch("Help the team take turtle and lord", Cfg.takeObjectives) { Cfg.takeObjectives = it }
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
        switch("Battle spell is defensive (use it at very low HP)", Cfg.spellIsDefensive) { Cfg.spellIsDefensive = it }
        switch("Smooth joystick (keeps the finger down and turns gradually)", Cfg.smoothJoystick) { Cfg.smoothJoystick = it }
        switch("Natural movement (human turning speed, small thumb variation)", Cfg.naturalMove) { Cfg.naturalMove = it }
        switch("Walk around walls (minimap paths, and step aside when stuck)", Cfg.wallAvoid) { Cfg.wallAvoid = it }
        switch("Jump thin walls with the dash skill (Flicker only to escape or chase)", Cfg.wallHop) { Cfg.wallHop = it }
        switch("Use bushes: hide when hurt, ambush enemies walking in", Cfg.useBushes) { Cfg.useBushes = it }
        switch("Fastest mode (30 fps capture, decide on every frame, quick combos; more battery)", Cfg.fastMode) { Cfg.fastMode = it }
        val moveInfo = text("", 13f)
        upd {
            val gs = GameStateTracker.state
            moveInfo.text = "Wall map: ${if (com.example.mlbbresearch.vision.WallMap.ready) "ready" else "building (needs a few seconds in a match)"}\n" +
                "K/D/A seen this match: ${GameStateTracker.kills} / ${GameStateTracker.deaths} / ${GameStateTracker.assists}" +
                "  (jungle camps killed: ${com.example.mlbbresearch.game.JungleNav.campsKilled})\n" +
                "Monsters on screen: ${gs.monsters.size}   Bushes on screen: ${gs.bushes.size}" + (if (gs.inBush) "   (you are in a bush)" else "") +
                "\nVision: ${"%.0f".format(FrameProcessor.processedFps)} frames/s"
        }

        title("SKILLS & ITEMS")
        switch("Upgrade skills (taps the golden + when it shows)", Cfg.autoUpgradeSkills) { Cfg.autoUpgradeSkills = it }
        switch("Reserve the ultimate for heroes (never on minions, jungle monsters, turtle, lord)", Cfg.noUltOnCreeps) { Cfg.noUltOnCreeps = it }
        switch("Cast Skill 1 and 2 as soon as ready when fighting creeps or heroes", Cfg.skillsWhenReady) { Cfg.skillsWhenReady = it }
        field("Upgrade order when several + show (ult,s1,s2)", Cfg.upgradeOrder, false) { v ->
            val parts = v.split(",").map { it.trim().lowercase() }.filter { it in setOf("s1", "s2", "ult") }.distinct()
            if (parts.isNotEmpty()) Cfg.upgradeOrder = parts.joinToString(",")
        }
        switch("Buy items (taps the quick-buy item top right when it shows)", Cfg.playerQuickBuy) { Cfg.playerQuickBuy = it }

        title("KILLS, TOWERS & GOLD")
        text("Priority: finish low-HP enemy heroes (never under their tower), kill jungle camps and minions until they " +
            "die, push enemy towers only with your minions in range and no enemy hero, defend your towers, and walk your " +
            "lane to the next enemy tower (yellow bonus towers first). The match is won by destroying the enemy base.", 12f, color = "#9AA0A6")
        field("Chase and finish enemy heroes below HP (%)", Cfg.executeHpPct.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.executeHpPct = v.coerceIn(5, 60) }
        }
        switch("Never enter enemy tower range without my minions", Cfg.towerGuard) { Cfg.towerGuard = it }
        field("Enemy tower range (% of the minimap width)", Cfg.towerRangePct.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.towerRangePct = v.coerceIn(3, 20) }
        }
        switch("Laners also take jungle camps next to them between waves", Cfg.lanersTakeCamps) { Cfg.lanersTakeCamps = it }
        switch("Use the dash skill to chase and to escape", Cfg.useDash) { Cfg.useDash = it }
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
        switch("Use Regen when hurt and no enemy is near", Cfg.useRegen) { Cfg.useRegen = it }
        field("Regen below HP (%)", Cfg.regenHpPct.toString(), true) { it.toIntOrNull()?.let { v -> Cfg.regenHpPct = v.coerceIn(10, 95) } }
        switch("Recall to base when low and no enemy is near", Cfg.useRecall) { Cfg.useRecall = it }
        field("Recall below HP (%)", Cfg.recallHpPct.toString(), true) { it.toIntOrNull()?.let { v -> Cfg.recallHpPct = v.coerceIn(5, 80) } }
        text("The hero stands still while Regen / Recall channel; an enemy coming close cancels them. " +
            "Calibrate the Regen and Recall buttons on the Setup page.", 12f, color = "#9AA0A6")
        text("Calibrate 'Skill upgrade (+)' and 'Quick-buy item' on the Setup page. Buying waits until no enemy is close.", 12f, color = "#9AA0A6")

        title("COACH: HELP THE AI, IT LEARNS FROM YOU")
        text("The AI plays; take the joystick or press a skill whenever you want. While your finger is on the screen the " +
            "AI touches nothing, and it takes over 1.2 s after you let go. It remembers what you did: your skill COMBO " +
            "(the order you chain skills in fights; seen twice = used), your AIM / timing (how close the enemy was when " +
            "you cast each skill, and which skill you use on waves and camps), and your JOYSTICK (how long you hold one " +
            "direction). Every moment you play is saved and your moves are mixed into the AI's when a moment is like one " +
            "you played. It also learns from the ally you watch while dead and from enemy heroes on screen. " +
            "(This replaces Assist, Learn from me and Play like me.)", 13f)
        switch("Coach mode (I can help; the AI learns from me)", Cfg.coachMode) { on ->
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

        title("GAME KNOWLEDGE (from the Mobile Legends wiki)")
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

        title("SKILLS & PASSIVE (what each skill does, how to cast it)")
        text("Each skill's range, target type, crowd control, mobility, shield / heal, damage and cast mode (tap, aim, or " +
            "hold to charge and release), plus the passive turned into play rules (e.g. a basic attack after every skill " +
            "when the passive powers it up). Learned online from the skill descriptions (Groq key from the MLBB-Research folder) - the " +
            "hero you play is learned by itself. In play it checks every cast: a skill that did not go off twice is tried " +
            "with the next cast mode, and a missed aimed skill changes how far ahead it aims.", 13f)
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
        btn("Learn ALL heroes' skills online (slow, runs in the background)") {
            com.example.mlbbresearch.game.SkillBook.learnAll { m -> runOnUiThread { toast(m) } }
            toast("Started")
        }
        btn("Stop learning all heroes") { com.example.mlbbresearch.game.SkillBook.stopLearnAll() }
        val paste = field("Or paste descriptions, one per line: passive: ... / s1: ... / s2: ... / ult: ...", "", false) { }
        btn("Use the pasted descriptions") {
            val h = GameStateTracker.ownHero()
            if (h == null) toast("Pick a hero first") else toast(com.example.mlbbresearch.game.SkillBook.fromText(h, paste.text.toString()).take(120))
        }
        field("Start copying after this many recorded moments (5 per second of play)", Cfg.imitationMinDemos.toString(), true) {
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
        switch("Read skill cooldowns from the screen (dark button = cooling down)", Cfg.readCooldownsFromScreen) { Cfg.readCooldownsFromScreen = it }
        val cdInfo = text("", 12f, color = "#9AA0A6")
        upd { cdInfo.text = "Buttons now: " + com.example.mlbbresearch.game.CooldownTracker.screenState() }
        field("Ask Gemma for a new plan every (seconds)", Cfg.strategyEverySec.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.strategyEverySec = v.coerceIn(3, 120) }
        }
        switch("Send a screenshot with the plan question (slower, smarter)", Cfg.strategyWithImage) { Cfg.strategyWithImage = it }

        title("VISION TUNING")
        switch("Show what the AI sees (boxes on the game)", Cfg.showDetections) { Cfg.showDetections = it }
        switch("Show where the AI touches", Cfg.showTouches) { Cfg.showTouches = it }
        field("Hero HP bar minimum width (% of screen width). Lower it if heroes show as minions.",
            Cfg.heroBarMinWidthPct.toString(), true) { it.toFloatOrNull()?.let { v -> Cfg.heroBarMinWidthPct = v.coerceIn(1f, 20f) } }
        field("Hero body below its HP bar (% of screen height)", Cfg.bodyBelowBarPct.toString(), true) {
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
        text("No bans: when the hero-select screen shows, the AI taps your hero, optionally changes the battle spell and " +
            "emblem, then taps Confirm. Works without Gemma. Needs Drafter + Autonomous on. Calibrate the 'Hero select' " +
            "markers on the Setup page (put your hero first in MLBB's list or in favourites so its spot never changes).", 13f)
        field("Your hero (also used by the Player)", Cfg.myHero, false) { Cfg.myHero = it }
        switch("Tap my hero", Cfg.stdPickHero) { Cfg.stdPickHero = it }
        switch("Change battle spell", Cfg.stdChangeSpell) { Cfg.stdChangeSpell = it }
        switch("Change emblem set", Cfg.stdChangeEmblem) { Cfg.stdChangeEmblem = it }
        btn("Test the standard select now (follows Dry run)") {
            if (AccessibilityController.instance == null) toast("Turn on the accessibility service first")
            else ActionExecutor.standardSelect(HeroDb.match(Cfg.myHero))
        }

        title("RANKED: TEAM OR SOLO (only one can be on)")
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
        title("BAN & PICK RECOMMENDATION")
        val c = card()
        val t = cardText(c)
        upd { t.text = StrategicAdvisor.draftText.ifEmpty { "(waiting for a draft screen)" } }
        title("AUTONOMOUS RANKED DRAFT")
        text("Ranked only. With Drafter + Builder + Autonomous on, the AI taps the recommended hero when it is your turn and then taps lock-in. " +
            "Keep Dry run on until the yellow marker lands on the right portrait. Calibrate 'Draft: confirm / lock-in' on the Setup page.", 13f)
        val err = text("", 12f, color = "#EF9A9A")
        upd { err.text = StrategicAdvisor.lastError }
    }

    // =====================================================================
    //  BUILD
    // =====================================================================

    private fun buildPage() {
        stageSwitch("Builder running", "build") { AiRuntime.builderOn }
        val c = card()
        val t = cardText(c)
        upd { t.text = StrategicAdvisor.buildSummary() }
        field("Read items & gold every (seconds)", Cfg.buildEverySec.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.buildEverySec = v.coerceIn(10, 300) }
        }
        text("Autonomous build taps MLBB's quick-buy item on the left, which follows your in-game build preset. " +
            "Set that preset to the PRIMARY build above.", 13f)

        title("AUTO QUICK BUY (item swap when about to die)")
        switch("Auto quick buy", Cfg.qbOn) { Cfg.qbOn = it }
        switch("Only if the build is already full", Cfg.qbRequireFull) { Cfg.qbRequireFull = it }
        switch("Only if there is enough gold", Cfg.qbRequireGold) { Cfg.qbRequireGold = it }
        field("Minimum gold", Cfg.qbMinGold.toString(), true) { it.toIntOrNull()?.let { v -> Cfg.qbMinGold = v } }
        field("Trigger when HP is below (%)", Cfg.qbHpPercent.toString(), true) {
            it.toIntOrNull()?.let { v -> Cfg.qbHpPercent = v.coerceIn(5, 90) }
        }
        val live = text("", 13f)
        upd {
            val s = GameStateTracker.state
            live.text = "HP ${if (s.selfHp < 0) "?" else "${(s.selfHp * 100).toInt()}%"} | items ${if (s.items < 0) "?" else s.items}/6 | " +
                "gold ${if (s.gold < 0) "?" else s.gold}\nStatus: ${QuickBuySwap.status}"
        }
        btn("Run the swap now (test - buys and sells for real)") {
            if (AccessibilityController.instance == null) toast("Turn on the accessibility service first") else QuickBuySwap.start()
        }

        title("EDIT PRIMARY BUILDS PER ROLE (comma separated)")
        text("Item names are checked against the wiki's current equipment list (${com.example.mlbbresearch.game.ItemDb.current.size} items): " +
            "names are corrected, and items no longer in the game are left out of the build.", 12f, color = "#9AA0A6")
        for (r in Role.values()) {
            if (r == Role.UNKNOWN) continue
            field(r.name.lowercase(), Cfg.roleBuild(r.name, BuildBook.defaults[r] ?: ""), false) { Cfg.setRoleBuild(r.name, it) }
            val warn = text("", 12f, color = "#F28B82")
            upd { warn.text = BuildBook.outdated(r).let { if (it.isEmpty()) "" else "Not in the current item list (left out): " + it.joinToString() } }
        }
    }

    // =====================================================================
    //  META: online meta through the Groq API
    // =====================================================================

    private fun metaPage() {
        text("Online meta: Groq (model openai/gpt-oss-120b with browser search) searches the web (patch notes, hero adjustments, tier lists, guides) and returns the " +
            "current build, emblem, battle spell, lane, combo, dash skill, laning / jungle / rotation tips and draft picks. " +
            "They are saved on the phone: the Player, Builder and Drafter use them offline. Your own settings still win.", 13f)
        text("The Groq API key is read from  groq_key.txt  in your MLBB-Research folder (Model page).", 12f, color = "#9AA0A6")
        // one switch: use the online meta everywhere, keep it fresh for the hero I play, use it for drafting
        switch("Use the online meta (auto-updated, also for drafting)", Cfg.useOnlineMeta && Cfg.onlineAuto) {
            Cfg.useOnlineMeta = it; Cfg.onlineAuto = it; Cfg.onlineDraft = it
        }
        val st = text("", 13f)
        upd {
            st.text = "Groq: " + (if (Cfg.groqKey.isBlank()) "no key (put groq_key.txt in the folder)" else "key set, ${com.example.mlbbresearch.ai.GroqClient.calls} answers this session") +
                (if (com.example.mlbbresearch.ai.MetaAdvisor.busy.isNotEmpty()) "\nWorking: ${com.example.mlbbresearch.ai.MetaAdvisor.busy}..." else "") +
                (if (com.example.mlbbresearch.ai.MetaAdvisor.lastMessage.isNotEmpty()) "\nLast: ${com.example.mlbbresearch.ai.MetaAdvisor.lastMessage}" else "")
        }

        title("UPDATE")
        btn("Update the patch meta (tier list, bans, picks)") {
            leaveHooks.forEach { it() }
            if (Cfg.groqKey.isBlank()) toast("No Groq key: put groq_key.txt in the MLBB-Research folder") else {
                toast("Asking Groq (can take 10-30 s)...")
                com.example.mlbbresearch.ai.MetaAdvisor.updateGeneral { msg -> runOnUiThread { toast(msg) } }
            }
        }
        val heroBox = field("Hero to update (empty = the hero I play)", "", false) { }
        btn("Update this hero (build, combo, tips)") {
            leaveHooks.forEach { it() }
            val h = HeroDb.match(heroBox.text.toString()) ?: GameStateTracker.ownHero() ?: HeroDb.match(Cfg.myHero)
            when {
                Cfg.groqKey.isBlank() -> toast("No Groq key: put groq_key.txt in the MLBB-Research folder")
                h == null -> toast("Type a hero name")
                else -> {
                    toast("Asking Groq about $h (can take 10-30 s)...")
                    com.example.mlbbresearch.ai.MetaAdvisor.updateHero(h) { msg -> runOnUiThread { toast(msg) } }
                }
            }
        }

        title("PATCH META")
        val gc = card()
        val gt = cardText(gc, 13f)
        title("HERO META")
        val hc = card()
        val ht = cardText(hc, 13f)
        upd {
            val g = com.example.mlbbresearch.game.MetaStore.general()
            gt.text = if (g == null) "(not downloaded yet)" else buildString {
                append("Patch: ").append(g.patch).append("  (").append("%.0f".format(com.example.mlbbresearch.game.MetaStore.ageDays(g.updatedAt))).append(" days old)")
                append("\nTop bans: ").append(g.bans.joinToString())
                for ((r, l) in g.picks) append("\n").append(r.name.lowercase()).append(": ").append(l.joinToString())
                if (g.notes.isNotBlank()) append("\n").append(g.notes)
                if (g.sources.isNotEmpty()) append("\nSources: ").append(g.sources.joinToString())
            }
            val h = HeroDb.match(heroBox.text.toString()) ?: GameStateTracker.ownHero() ?: HeroDb.match(Cfg.myHero)
            val m = com.example.mlbbresearch.game.MetaStore.hero(h)
            ht.text = if (m == null) "${h ?: "No hero"}: not downloaded yet" else buildString {
                append(m.hero).append(" - ").append(m.patch).append("  (").append("%.0f".format(com.example.mlbbresearch.game.MetaStore.ageDays(m.updatedAt))).append(" days old)")
                append("\nBuild: ").append(m.build.joinToString())
                append("\nEmblem: ").append(m.emblem).append("\nSpell: ").append(m.spell ?: "-").append("   Lane: ").append(m.lane?.label ?: "-")
                append("\nCombo: ").append(m.combo ?: "-").append("   Dash: ").append(m.dash ?: "-")
                if (m.laning.isNotEmpty()) append("\nLaning: ").append(m.laning.joinToString(" / "))
                if (m.jungle.isNotEmpty()) append("\nJungle: ").append(m.jungle.joinToString(" / "))
                if (m.rotation.isNotEmpty()) append("\nRotations: ").append(m.rotation.joinToString(" / "))
                if (m.counters.isNotEmpty()) append("\nCountered by: ").append(m.counters.joinToString())
                if (m.changes.isNotBlank()) append("\nRecent changes: ").append(m.changes)
                if (m.sources.isNotEmpty()) append("\nSources: ").append(m.sources.joinToString())
            }
        }
        btn("Delete all downloaded meta") {
            AlertDialog.Builder(this).setTitle("Delete the downloaded meta?")
                .setPositiveButton("Delete") { _, _ -> com.example.mlbbresearch.game.MetaStore.clear(); toast("Deleted") }
                .setNegativeButton("Cancel", null).show()
        }
    }

    // =====================================================================
    //  MODEL
    // =====================================================================

    private fun modelPage() {
        title("FILES")
        text("Put the downloaded files in  Download/MLBB-Research  and choose that folder once. The app copies, loads and " +
            "applies them by itself every time it starts:\n" +
            "  - Gemma 3n E2B model: the  .litertlm  file\n" +
            "  - minimap detector:  mlbb_detector.tflite  +  labels.txt\n" +
            "  - screen detector:  mlbb_screen_detector.tflite  +  screen_labels.txt\n" +
            "  - tactical network:  policy.tflite  +  policy_info.json\n" +
            "  - Groq API key:  groq_key.txt  (the key on the first line)", 13f)
        val c = card()
        val st = cardText(c, 14f, true)
        upd {
            val af = com.example.mlbbresearch.models.AutoFolder
            val pn = com.example.mlbbresearch.learning.PolicyNet
            fun mark(ok: Boolean) = if (ok) "\u2713" else "\u2717"
            st.text = "Folder: " + (af.treeUri?.let { Uri.decode(it.lastPathSegment ?: "chosen").substringAfter(':') } ?: "not chosen yet") +
                "\n${af.status}" +
                "\n\n${mark(GemmaEngine.ready)} Gemma: ${ModelManager.status(this)}, ${GemmaEngine.state}" +
                (if (GemmaEngine.calls > 0) " (last answer ${GemmaEngine.lastLatencyMs} ms)" else "") +
                "\n${mark(ObjectDetector.ready)} Minimap detector: ${ObjectDetector.state}" +
                (if (ObjectDetector.ready) ", ${ObjectDetector.lastMs} ms per run" else "") +
                "\n${mark(com.example.mlbbresearch.vision.ScreenDetector.ready)} Screen detector: ${com.example.mlbbresearch.vision.ScreenDetector.state}" +
                (if (com.example.mlbbresearch.vision.ScreenDetector.ready) ", ${com.example.mlbbresearch.vision.ScreenDetector.lastMs} ms per run; " +
                    com.example.mlbbresearch.vision.ScreenIntel.added else "") +
                "\n${mark(pn.ready)} Tactical network: ${pn.state}" +
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
        btn("Gemma 3n E2B (Hugging Face, about 3 GB)") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.MODEL_PAGE))) }
        btn("Training notebooks: detector and tactical network (GitHub)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/georgenaval1-blip/Ai-App/tree/main/notebooks")))
        }
        btn("Groq API key (free)") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }

        title("SETTINGS")
        switch("Run Gemma on the GPU (faster; falls back to the CPU)", Cfg.useGpu) { Cfg.useGpu = it }
        // the detector and the tactical network are used together: one switch
        switch("Use the trained models (detectors + tactical network)", Cfg.detectorOn && Cfg.policyOn) {
            Cfg.detectorOn = it; Cfg.policyOn = it
        }
    }


    // =====================================================================
    //  LEARN
    // =====================================================================

    private fun learnPage() {
        DemoRecorder.refreshTotal()
        com.example.mlbbresearch.learning.LiveRecorder.refreshTotal()
        title("SMART FIGHTS - the AI learns from its mistakes")
        text("When an enemy is close, the AI chooses how to handle it: all in, poke from range, back off, fall back to " +
            "allies, keep farming, or the normal rules. 4 seconds later it scores the result: damage dealt, kills, HP kept, " +
            "dying. It learns which choice works best in which situation (your HP vs theirs, how many on each side, skills " +
            "ready...) for each hero, and sometimes tries something new to keep learning. Low HP always retreats.", 13f)
        switch("Smart fights (learning AI)", Cfg.smartFights) { Cfg.smartFights = it }
        val levels = listOf("Low (mostly uses what it knows)", "Normal", "High (tries new things more)")
        val explore = btn("") { Cfg.exploreLevel = (Cfg.exploreLevel + 1) % 3 }
        upd { explore.text = "Curiosity: ${levels[Cfg.exploreLevel.coerceIn(0, 2)]}  (tap to change)" }
        switch("Also learn from my own fights (needs 'Learn from me' on)", Cfg.brainLearnsFromYou) { Cfg.brainLearnsFromYou = it }

        val c = card()
        val head = cardText(c, 15f, true)
        val table = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.parseColor("#E6E6E6")) }
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
        val lessons = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.parseColor("#C8C8C8")) }
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
        btn("Export my recorded play (CSV) - trains the tactical network") {
            DatasetManager.exportDemos(this) { msg -> runOnUiThread { toast(msg) } }
        }

        title("LEARN FROM LIVE MATCHES (watching another player)")
        text("Open a player's live match in MLBB while learning is on (start the AI or Learn). Their screen is read like a " +
            "match of yours: their hero (the blue bar in the middle), its HP from the green line in their panel, enemies, " +
            "minions, monsters and the minimap (all enemies show there). Their walking comes from the camera, their skills " +
            "from the panel icons going dark, attacks from standing next to a target. The AI never plays on this screen. " +
            "Export gives mlbb-live-*.csv: the same columns as your recorded play, so the notebook trains on it " +
            "(set HERO to the hero you watched).", 13f)
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
        btn("Export live matches (CSV) - trains the tactical network") {
            DatasetManager.exportLive(this) { msg -> runOnUiThread { toast(msg) } }
        }
        btn("Delete the live match moments") {
            AlertDialog.Builder(this).setTitle("Delete all live match moments?")
                .setPositiveButton("Delete") { _, _ -> com.example.mlbbresearch.learning.LiveRecorder.clear { runOnUiThread { toast("Deleted") } } }
                .setNegativeButton("Cancel", null).show()
        }

        title("HOW THE AI DOES PER HERO AND ROLE")
        text("Every match is logged (hero, role, who played, K/D/A, level, length) - to see where the AI plays well and " +
            "which heroes / roles need work. Win / loss is read from the Victory / Defeat screens.", 13f)
        val ml = text("", 12f)
        upd { ml.text = com.example.mlbbresearch.game.MatchLog.summary() }
        btn("Clear the match log") { com.example.mlbbresearch.game.MatchLog.clear(); toast("Cleared") }

        title("DETECTOR TRAINING PICTURES")
        text("While you play, pictures are saved with boxes already drawn from what the app sees:\n" +
            "  - the minimap every 6 s (hero icons, towers, turtle / lord) -> Download/MLBB-Research/dataset, for " +
            "train_mlbb_detector.ipynb\n" +
            "  - the whole screen every 10 s (heroes, minions, creeps, buffs, turtle, lord, enemy towers, bushes, walls) -> " +
            "Download/MLBB-Research/screen_dataset, for train_screen_detector.ipynb\n" +
            "Fixing the boxes in Roboflow before training makes the detectors much better than the app's own guesses.", 13f)
        switch("Collect detector training pictures", Cfg.collectDataset) { Cfg.collectDataset = it }
        val dsInfo = text("", 12f, color = "#9AA0A6")
        upd { dsInfo.text = "Minimap: " + com.example.mlbbresearch.learning.DatasetCollector.status +
            "\nScreen: " + com.example.mlbbresearch.learning.ScreenDatasetCollector.status }
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
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true); setTextColor(Color.parseColor("#C8C8C8"))
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
        val nt = btn("3. Notifications (capture notice + STOP button)") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotify)
        }
        upd {
            mark(nt, Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        }
        val cap = btn("4. Screen capture (asked each time it starts)") { askScreenCapture() }
        upd { mark(cap, ScreenCaptureService.running) }
        text("On POCO/Xiaomi also allow: Settings > Apps > MLBB Research > Other permissions > 'Display pop-up windows while running in the background', and turn off 'Manage app if unused'.", 12f, color = "#9AA0A6")

        title("SCREEN")
        val scr = text("", 12f, color = "#9AA0A6")
        upd {
            scr.text = "Screen: " + com.example.mlbbresearch.core.ScreenGeom.describe(this) +
                "\nVision, touches and drawings all use the whole screen (a POCO X6 Pro is 2712 x 1220), so positions match MLBB exactly."
        }

        title("CALIBRATE BUTTON POSITIONS")
        text("Easiest: open MLBB in a match, then come back and tap a calibrate button. After 5 seconds yellow markers " +
            "appear over the game: drag each one onto its button, or tap Auto-detect to let the app find them, then Save. " +
            "'Next screen' moves on to the shop and hero-select markers.", 12f, color = "#9AA0A6")
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
        text("If the AI misreads the minimap (your icon, towers, camps, walls), teach it the colours: choose the icon " +
            "type, tap 'Pick it on the game', switch to MLBB, then tap that icon on the minimap within 5 seconds. " +
            "Up to 4 samples per type. Also set the minimap corners (in-game buttons calibration). " +
            "With 'Show what the AI sees' on, your icon, camps and enemy towers (with range) are drawn on the minimap.", 12f, color = "#9AA0A6")
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

        title("PRESETS (one per phone or MLBB button layout)")
        val active = text("", 13f)
        upd { active.text = "Active preset: " + Cfg.activePreset.ifEmpty { "none (current positions)" } }
        val nameBox = EditText(this).apply { hint = "Preset name"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        content.addView(nameBox)
        btn("Save current positions as a preset") {
            if (Layout.savePreset(nameBox.text.toString())) { toast("Preset saved"); show("setup") } else toast("Type a name first")
        }
        for (name in Layout.presetNames()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 14f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Load"; isAllCaps = false
                setOnClickListener { if (Layout.loadPreset(name)) { toast("Loaded $name"); show("setup") } }
            })
            row.addView(Button(this).apply {
                text = "Delete"; isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Delete preset $name?")
                        .setPositiveButton("Delete") { _, _ -> Layout.deletePreset(name); show("setup") }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            content.addView(row)
        }

        title("TYPE POSITIONS BY HAND")
        text("Values are x,y in % of the screen (landscape). 'Show markers' draws them on the game without blocking it.", 12f, color = "#9AA0A6")
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
