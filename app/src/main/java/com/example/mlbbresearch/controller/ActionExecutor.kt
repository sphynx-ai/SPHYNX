package com.example.mlbbresearch.controller

import android.os.Handler
import android.os.Looper
import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A touch to perform, in screen fractions
sealed class Act {
    data class Tap(val x: Float, val y: Float) : Act()
    data class Drag(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long) : Act()
}

// Turns decisions into touches. Kept separate from the AI, so the AI can be replaced
// without touching how the game is controlled.
object ActionExecutor {

    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val history = ArrayDeque<String>()
    private var lastLogged = ""
    private var lastLoggedAt = 0L
    private var lastQuickBuyTap = 0L
    @Volatile private var generation = 0

    @Volatile var lastDecision: String = "-"
        private set
    @Volatile var lastTarget: String = "-"
        private set
    @Volatile var actionsDone = 0
        private set

    // ---------------- history ----------------

    @Synchronized
    fun note(line: String) {
        history.addFirst("${fmt.format(Date())}  $line")
        while (history.size > 60) history.removeLast()
    }

    @Synchronized
    fun history(n: Int): List<String> = history.take(n)

    @Synchronized
    fun clearHistory() = history.clear()

    // ---------------- player ----------------

    fun execute(d: TacticalDecision) {
        lastDecision = d.describe()
        lastTarget = if (d.targetHp >= 0f) "${d.targetLabel} (HP ${(d.targetHp * 100).toInt()}%)" else "none"
        if (d.intent == Intent.IDLE) return
        if (System.currentTimeMillis() < QuickBuySwap.busyUntil) return   // the item swap owns the screen

        val acts = ArrayList<Act>()
        if (d.moveX != 0f || d.moveY != 0f) {
            val (jx, jy) = Layout.get("joystick")
            val r = Layout.JOYSTICK_RADIUS
            val aspect = AccessibilityController.screenAspect()
            acts.add(Act.Drag(jx, jy, jx + d.moveX * r, jy + d.moveY * r * aspect, 300))
        }
        for (c in d.casts) {
            val (x, y) = Layout.get(c)
            acts.add(Act.Tap(x, y))
            if (c != "attack") CooldownTracker.markCast(c)
        }
        perform(acts)

        val key = d.intent.name + d.casts.filter { it != "attack" }.joinToString()
        val now = System.currentTimeMillis()
        if (key != lastLogged || now - lastLoggedAt > 3000) {
            lastLogged = key
            lastLoggedAt = now
            note(d.describe())
        }
    }

    // ---------------- drafter (autonomous) ----------------

    fun draftSelect(x: Float, y: Float, label: String) {
        val (lx, ly) = Layout.get("lockIn")
        if (Cfg.dryRun) {
            AccessibilityController.instance?.overlay?.flashMarker(x, y, "AI would tap: $label")
            note("[dry run] draft $label at ${(x * 100).toInt()}%,${(y * 100).toInt()}% then lock-in")
            return
        }
        note("Draft: $label (tap ${(x * 100).toInt()}%,${(y * 100).toInt()}%, then lock-in)")
        runSteps(listOf(Act.Tap(x, y) to 0L, Act.Tap(lx, ly) to 900L)) { true }
    }

    // ---------------- builder (autonomous) ----------------

    // MLBB shows the next item of your in-game build preset on the left: tap it to buy.
    fun maybeQuickBuy(s: GameState) {
        val now = System.currentTimeMillis()
        if (now - lastQuickBuyTap < 8000) return
        if (s.enemiesWithin(0.7f).isNotEmpty() || s.selfHp in 0f..0.4f) return
        lastQuickBuyTap = now
        val (x, y) = Layout.get("quickBuy")
        if (Cfg.dryRun) {
            AccessibilityController.instance?.overlay?.flashMarker(x, y, "AI would buy")
            note("[dry run] quick-buy next item")
            return
        }
        perform(listOf(Act.Tap(x, y)))
        note("Builder: quick-buy next item")
    }

    // ---------------- low level ----------------

    fun runSteps(steps: List<Pair<Act, Long>>, guard: () -> Boolean) {
        val gen = generation
        var t = 0L
        for ((act, gap) in steps) {
            t += gap
            main.postDelayed({ if (gen == generation && guard()) perform(listOf(act)) }, t)
        }
    }

    fun perform(acts: List<Act>) {
        if (acts.isEmpty()) return
        val svc = AccessibilityController.instance ?: return
        main.post {
            try {
                svc.dispatch(acts, Cfg.showTouches)
                actionsDone += acts.size
            } catch (e: Exception) {
                Diag.error("Touch failed", e)
            }
        }
    }

    // Emergency stop: drop every queued touch
    fun cancelAll() {
        generation++
        main.removeCallbacksAndMessages(null)
        QuickBuySwap.busyUntil = 0L
        lastDecision = "stopped"
        lastTarget = "-"
    }
}

// Builder: when about to die with a full build and enough gold, sell the last item and
// cycle Immortality -> Winter Crown -> Rose Gold Meteor -> Feather of Heaven, letting each
// item's effect trigger before selling it and buying the next.
object QuickBuySwap {
    @Volatile var busyUntil = 0L
    @Volatile var status = "idle"
    private var lastTrigger = 0L

    private val order = listOf(
        "itemImmortality" to "Immortality",
        "itemWinterCrown" to "Winter Crown",
        "itemRoseGold" to "Rose Gold Meteor",
        "itemFeather" to "Feather of Heaven"
    )
    private val waitMs = mapOf("Immortality" to 1500L, "Winter Crown" to 400L, "Rose Gold Meteor" to 1500L, "Feather of Heaven" to 1500L)

    fun watch(s: GameState) {
        val now = System.currentTimeMillis()
        val fresh = s.itemsGoldAgeMs < 90_000
        status = when {
            now < busyUntil -> "swap running..."
            now - lastTrigger < 20_000 -> "cooling down"
            s.selfHp < 0f -> "your HP bar is not visible"
            s.selfHp > Cfg.qbHpPercent / 100f -> "HP is fine (${(s.selfHp * 100).toInt()}%)"
            Cfg.qbRequireFull && !(fresh && s.items >= 6) -> "HP low, but a full build is not confirmed"
            Cfg.qbRequireGold && !(fresh && s.gold >= Cfg.qbMinGold) -> "HP low, but gold is not enough or unknown"
            else -> ""
        }
        if (status.isEmpty()) {
            status = "TRIGGERED"
            start()
        }
    }

    fun start() {
        val steps = ArrayList<Pair<Act, Long>>()
        fun tap(key: String, wait: Long) {
            val (x, y) = Layout.get(key)
            steps.add(Act.Tap(x, y) to wait)
        }
        tap("shopOpen", 0L)
        tap("shopLastSlot", 700L); tap("shopSell", 250L)
        for ((key, name) in order) {
            tap(key, 450L); tap("shopBuy", 250L)
            if (name == "Winter Crown") tap("activeItem", 400L)
            tap("shopLastSlot", waitMs[name] ?: 1000L)
            tap("shopSell", 250L)
        }
        tap("shopClose", 500L)
        val total = steps.sumOf { it.second }
        val now = System.currentTimeMillis()
        lastTrigger = now
        busyUntil = now + total + 1500L
        Diag.info("Quick-buy swap started")
        ActionExecutor.note("Builder: emergency item swap")
        ActionExecutor.runSteps(steps) { System.currentTimeMillis() < busyUntil }
    }
}
