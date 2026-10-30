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
import com.example.mlbbresearch.game.GameStateTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A touch to perform, in screen fractions
sealed class Act {
    data class Tap(val x: Float, val y: Float) : Act()
    data class Drag(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long) : Act()
    // hold the joystick (centre cx,cy) pushed towards x,y; the finger stays down between ticks
    data class Joy(val cx: Float, val cy: Float, val x: Float, val y: Float, val ms: Long) : Act()
    object JoyRelease : Act()
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
    private var lastUpgradeTap = 0L
    private var joyDirX = 0f
    private var joyDirY = 0f
    @Volatile private var generation = 0
    private val AIMED = setOf("s1", "s2", "ult")
    private const val SKILL_AIM_R = 0.045f     // how far a skill button is dragged to aim (fraction of width)

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
        if (d.intent == Intent.IDLE) {
            release()
            return
        }
        if (System.currentTimeMillis() < QuickBuySwap.busyUntil) return   // the item swap owns the screen

        val acts = ArrayList<Act>()
        if (d.moveX != 0f || d.moveY != 0f) {
            acts.add(joystick(d.moveX, d.moveY))
        } else if (Cfg.smoothJoystick) {
            joyDirX = 0f; joyDirY = 0f
            if (AccessibilityController.instance?.joystickHeld() == true) acts.add(Act.JoyRelease)
        }
        val self = GameStateTracker.state.self
        for (c in d.casts) {
            val (x, y) = Layout.get(c)
            val me = if (Cfg.aimSkills && c in AIMED && d.targetX >= 0f) self else null
            if (me != null) {
                // drag the skill button towards the target, like aiming by hand
                val aspect = AccessibilityController.screenAspect()
                val dx = (d.targetX - me.x) * aspect
                val dy = d.targetY - me.y
                val l = kotlin.math.hypot(dx, dy)
                if (l > 0.02f) {
                    acts.add(Act.Drag(x, y, x + dx / l * SKILL_AIM_R, y + dy / l * SKILL_AIM_R * aspect, 110))
                } else acts.add(Act.Tap(x, y))
            } else acts.add(Act.Tap(x, y))
            if (c != "attack") CooldownTracker.markCast(c)
        }
        perform(acts)

        if ("regen" in d.casts) note("Regen")
        if ("recall" in d.casts) note("Recall")
        val key = d.intent.name + d.casts.filter { it != "attack" }.joinToString()
        val now = System.currentTimeMillis()
        if (key != lastLogged || now - lastLoggedAt > 3000) {
            lastLogged = key
            lastLoggedAt = now
            note(d.describe())
        }
    }

    // Direction (-1..1) -> joystick touch. Smooth mode turns gradually and keeps the finger down.
    private fun joystick(mx: Float, my: Float): Act {
        val (jx, jy) = Layout.get("joystick")
        val r = Layout.JOYSTICK_RADIUS
        val aspect = AccessibilityController.screenAspect()
        if (!Cfg.smoothJoystick) return Act.Drag(jx, jy, jx + mx * r, jy + my * r * aspect, 300)
        // blend with the last direction so the hero turns instead of snapping (no zig-zag)
        var nx = joyDirX * 0.35f + mx * 0.65f
        var ny = joyDirY * 0.35f + my * 0.65f
        val l = kotlin.math.hypot(nx, ny)
        if (l < 0.2f) { nx = mx; ny = my } else { nx /= l; ny /= l }
        joyDirX = nx; joyDirY = ny
        // push to 90% of the radius: full speed without sliding off the joystick
        return Act.Joy(jx, jy, jx + nx * r * 0.9f, jy + ny * r * 0.9f * aspect, 150)
    }

    // Lift the joystick finger (AI paused or stopped)
    fun release() {
        joyDirX = 0f; joyDirY = 0f
        val svc = AccessibilityController.instance ?: return
        if (svc.joystickHeld()) main.post { try { svc.releaseJoystick() } catch (e: Exception) { } }
    }

    // ---------------- player: level up skills ----------------

    // Taps the golden "+" that MLBB shows over a skill when you can level it up
    fun maybeUpgrade(s: GameState) {
        if (!Cfg.autoUpgradeSkills) return
        val ready = s.hud.upgradeReady
        if (ready.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastUpgradeTap < 700) return
        if (now < QuickBuySwap.busyUntil) return
        val order = Cfg.upgradeOrder.split(",").map { it.trim() }.filter { it in setOf("s1", "s2", "ult") } + listOf("ult", "s1", "s2")
        val slot = order.firstOrNull { it in ready } ?: return
        lastUpgradeTap = now
        val (x, y) = Layout.get(slot + "Up")
        perform(listOf(Act.Tap(x, y)))
        note("Upgrade skill: $slot")
    }

    // ---------------- standard match: hero, battle spell, emblem ----------------

    fun standardSelect(hero: String?) {
        val steps = ArrayList<Pair<Act, Long>>()
        val names = ArrayList<String>()
        fun tap(key: String, wait: Long, what: String) {
            val (x, y) = Layout.get(key)
            steps.add(Act.Tap(x, y) to wait)
            names.add(what)
        }
        if (Cfg.stdPickHero) tap("stdHero", 0L, "hero ${hero ?: ""}".trim())
        if (Cfg.stdChangeSpell) {
            tap("stdSpellBtn", 900L, "battle spell button")
            tap("stdSpellPick", 800L, "battle spell")
            tap("stdClose", 700L, "close")
        }
        if (Cfg.stdChangeEmblem) {
            tap("stdEmblemBtn", 900L, "emblem button")
            tap("stdEmblemPick", 900L, "emblem set")
            tap("stdClose", 700L, "close")
        }
        tap("lockIn", 900L, "confirm")
        if (Cfg.dryRun) {
            var t = 0L
            for ((i, st) in steps.withIndex()) {
                t += st.second + 400L
                val a = st.first as Act.Tap
                main.postDelayed({ AccessibilityController.instance?.overlay?.flashMarker(a.x, a.y, "AI would tap: ${names[i]}") }, t)
            }
            note("[dry run] standard select: ${names.joinToString(" -> ")}")
            return
        }
        note("Standard select: ${names.joinToString(" -> ")}")
        runSteps(steps) { true }
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

    // MLBB shows the next item of your in-game build preset (top right) once you can afford it:
    // tap it to buy. blind = tap even when vision did not see the icon (old Builder behaviour).
    fun maybeQuickBuy(s: GameState, blind: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastQuickBuyTap < (if (blind) 8000 else 2500)) return
        if (!blind && !s.hud.quickBuyVisible) return
        if (s.enemiesWithin(0.7f).isNotEmpty() || s.selfHp in 0f..0.4f) return
        if (now < QuickBuySwap.busyUntil) return
        lastQuickBuyTap = now
        val (x, y) = Layout.get("quickBuy")
        if (blind && Cfg.dryRun) {
            AccessibilityController.instance?.overlay?.flashMarker(x, y, "AI would buy")
            note("[dry run] quick-buy next item")
            return
        }
        perform(listOf(Act.Tap(x, y)))
        note("Quick-buy next item")
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
        release()
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
