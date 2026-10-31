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
    private var walkSince = 0L
    private var lastAttackTap = 0L
    private var attackGap = 280L
    private var lastJoyAt = 0L
    private var detourUntil = 0L
    private var detourSign = 1f
    private var pushRadius = 1.1f
    private val rnd = java.util.Random()
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
        val assist = Cfg.assistMode
        if (assist) {
            // you move: never touch the joystick
        } else {
            val (mx, my) = holdDirection(d)
            if (mx != 0f || my != 0f) {
                joystick(mx, my, d.intent)?.let { acts.add(it) }
            } else if (Cfg.smoothJoystick) {
                joyDirX = 0f; joyDirY = 0f; headX = 0f; headY = 0f
                if (AccessibilityController.instance?.joystickHeld() == true) acts.add(Act.JoyRelease)
            }
        }
        val gs = GameStateTracker.state
        val self = gs.self
        // assist mode: only skills / combo steps in fights (your own taps keep the rest)
        val casts = if (!assist) d.casts else if (d.intent == Intent.ENGAGE || d.intent == Intent.OBJECTIVE)
            d.casts.filter { it in AIMED || it == "attack" || it == "spell" && gs.spell in com.example.mlbbresearch.game.SpellDb.offensive }
            else emptyList()
        val nowMs = System.currentTimeMillis()
        for (c in casts) {
            // basic attack at a human rhythm (your recording: about every 330 ms); skills go out at once
            if (c == "attack") {
                if (nowMs - lastAttackTap < attackGap) continue
                lastAttackTap = nowMs
                attackGap = 250L + rnd.nextInt(80)
            }
            val (x, y) = Layout.get(c)
            val me = if (Cfg.aimSkills && c in AIMED && d.targetX >= 0f) self else null
            if (me != null) acts.add(aimedCast(c, x, y, me.x, me.y, d, gs)) else acts.add(Act.Tap(x, y))
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

    // When to keep the thumb on the joystick. Like a player: the thumb stays down, pushed to the
    // edge of the ring, and only turns while walking, chasing or running away. It is lifted only
    // to stand and hit something: minions, jungle monsters, turtle / lord, a tower, or to
    // recall / regen / hide. A decision that briefly says "stand still" in a chase or an escape
    // does not lift it: the hero keeps going the same way (melee heroes stick to their target).
    private fun holdDirection(d: TacticalDecision): Pair<Float, Float> {
        val now = System.currentTimeMillis()
        if (d.moveX != 0f || d.moveY != 0f) { lastMoveAt = now; return d.moveX to d.moveY }
        val stay = when (d.intent) {
            Intent.LANE_CLEAR, Intent.JUNGLE, Intent.OBJECTIVE, Intent.PUSH, Intent.RECALL, Intent.REGEN,
            Intent.AMBUSH, Intent.IDLE -> true
            Intent.RETREAT -> d.reason.contains("hiding")
            else -> false
        }
        if (stay || !Cfg.smoothJoystick) return 0f to 0f
        val self = GameStateTracker.state.self
        // a melee hero in a fight keeps walking into the target (attack lands as it walks)
        if (d.intent == Intent.ENGAGE && d.targetX >= 0f && self != null &&
            !com.example.mlbbresearch.game.HeroDb.isRanged(GameStateTracker.state.ownHero)
        ) {
            val ax = (d.targetX - self.x) * AccessibilityController.screenAspect()
            val ay = d.targetY - self.y
            val l = kotlin.math.hypot(ax, ay)
            if (l > 0.02f) return (ax / l) to (ay / l)
        }
        // otherwise keep the last direction for a moment rather than lifting and pressing again
        if ((joyDirX != 0f || joyDirY != 0f) && now - lastMoveAt < 700) return joyDirX to joyDirY
        return 0f to 0f
    }
    private var lastMoveAt = 0L

    // Drag a skill button to aim, like a player: at where a moving enemy WILL be (its speed
    // times the skill's travel time), and drag less far for close targets (range skills).
    private fun aimedCast(c: String, bx: Float, by: Float, sx: Float, sy: Float, d: TacticalDecision, gs: GameState): Act {
        val aspect = AccessibilityController.screenAspect()
        var tx = d.targetX
        var ty = d.targetY
        val dist0 = kotlin.math.hypot((tx - sx) * aspect, ty - sy)
        val track = if (d.targetId >= 0) gs.enemies.firstOrNull { it.id == d.targetId } else null
        if (track != null) {
            val lead = (0.15f + dist0 * 0.7f).coerceAtMost(0.5f)       // seconds until the skill lands
            tx += track.vx * lead
            ty += track.vy * lead
        }
        val dx = (tx - sx) * aspect
        val dy = ty - sy
        val l = kotlin.math.hypot(dx, dy)
        // Like your recording: a quick tap almost every time (the game auto-aims at the nearest
        // target); a deliberate drag only for a target far away (your aimed drags: 290-750 ms)
        if (l < 0.30f) return Act.Tap(bx, by)
        val reach = SKILL_AIM_R * (0.55f + 0.45f * (l / 0.45f).coerceAtMost(1f))
        return Act.Drag(bx, by, bx + dx / l * reach, by + dy / l * reach * aspect, 220L + rnd.nextInt(80))
    }

    // Direction (-1..1) -> joystick touch. Natural mode: the finger stays down, the hero turns
    // at a human rate (no snapping), the push varies a little, and when the camera stops
    // moving while walking (a wall) it steps around it.
    private var sentDirX = 0f
    private var sentDirY = 0f
    private var sentAt = 0L

    // the way the hero is walking: kept until a real change of course is needed
    private var headX = 0f
    private var headY = 0f
    private var headIntent: Intent? = null

    private fun joystick(mx0: Float, my0: Float, intent: Intent = Intent.PUSH): Act? {
        val (jx, jy) = Layout.get("joystick")
        val r = Layout.JOYSTICK_RADIUS
        val aspect = AccessibilityController.screenAspect()
        if (!Cfg.smoothJoystick) return Act.Drag(jx, jy, jx + mx0 * r, jy + my0 * r * aspect, 300)
        val now = System.currentTimeMillis()
        var mx = mx0
        var my = my0
        // WALK STRAIGHT: the decision is re-made several times a second and its direction
        // jitters a little; a player keeps the thumb still and only turns when the course really
        // changes (a new camp / lane, the path bends round a wall or tower, the target moved
        // well off to the side). Small differences keep the current heading.
        val l0 = kotlin.math.hypot(mx, my).coerceAtLeast(1e-4f)
        mx /= l0; my /= l0
        val fight = intent == Intent.ENGAGE || intent == Intent.RETREAT
        val keep = if (fight) 0.906f else 0.766f          // cos 25 deg in fights, cos 40 deg when walking
        val held0 = AccessibilityController.instance?.joystickHeld() == true
        if (held0 && (headX != 0f || headY != 0f) && intent == headIntent && mx * headX + my * headY >= keep && now >= detourUntil) {
            mx = headX; my = headY
        } else {
            headX = mx; headY = my; headIntent = intent
        }
        if (joyDirX == 0f && joyDirY == 0f) walkSince = now
        // stuck on a wall: walking for a while but the camera has not moved for 0.7 s
        if (Cfg.wallAvoid && now - walkSince > 900 && now - GameStateTracker.lastMotionAt > 700 && now > detourUntil + 400) {
            detourUntil = now + 650
            detourSign = -detourSign
            // remember this wall on the minimap so the next path goes around it
            GameStateTracker.state.minimapSelf?.let { com.example.mlbbresearch.vision.WallMap.markBlocked(it, mx, my) }
        }
        if (now < detourUntil) {
            val a = Math.toRadians(75.0 * detourSign)
            val rx = (mx * Math.cos(a) - my * Math.sin(a)).toFloat()
            val ry = (mx * Math.sin(a) + my * Math.cos(a)).toFloat()
            mx = rx; my = ry
        }
        var nx: Float
        var ny: Float
        if (Cfg.naturalMove && (joyDirX != 0f || joyDirY != 0f)) {
            // turn at most ~50 degrees per step, plus a tiny wobble like a thumb
            val cur = kotlin.math.atan2(joyDirY, joyDirX)
            var want = kotlin.math.atan2(my, mx)
            var delta = want - cur
            while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
            while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
            // turn at a thumb's speed: about 340 degrees a second (your recording: 90% of turns
            // under 27 degrees per 1/12 s), however often the AI decides
            val dt = ((now - lastJoyAt).coerceIn(20L, 250L)) / 1000f
            // a reversal (running away, dodging) is a flick to the other side: faster
            val maxTurn = (if (kotlin.math.abs(delta) > 2.1f) 15f else 6.0f) * dt
            want = cur + delta.coerceIn(-maxTurn, maxTurn)
            nx = kotlin.math.cos(want); ny = kotlin.math.sin(want)
            // the thumb sits at the ring's edge (full speed), with a little wobble
            // the thumb sits firmly at (just past) the ring's edge: full speed, the stick clamps it
            pushRadius = 1.1f
        } else {
            val l = kotlin.math.hypot(mx, my).coerceAtLeast(1e-4f)
            nx = mx / l; ny = my / l
            pushRadius = 1.1f
        }
        lastJoyAt = now
        joyDirX = nx; joyDirY = ny
        // the finger is already down: only move it when the direction really changed (> ~6
        // degrees) or every 0.5 s, so taps and skills are not held up by joystick strokes
        val held = AccessibilityController.instance?.joystickHeld() == true
        if (held && nx * sentDirX + ny * sentDirY > 0.995f && now - sentAt < 500 && now >= detourUntil) return null
        sentDirX = nx; sentDirY = ny; sentAt = now
        val ms = if (Cfg.fastMode) 45L + rnd.nextInt(15) else if (Cfg.naturalMove) 140L + rnd.nextInt(35) else 150L
        return Act.Joy(jx, jy, jx + nx * r * pushRadius, jy + ny * r * pushRadius * aspect, ms)
    }

    // Lift the joystick finger (AI paused or stopped)
    fun release() {
        joyDirX = 0f; joyDirY = 0f
        headX = 0f; headY = 0f
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

    // Fast mode sends the touches straight from the AI's thread (no wait for the UI thread)
    fun perform(acts: List<Act>) {
        if (acts.isEmpty()) return
        val svc = AccessibilityController.instance ?: return
        val send = {
            try {
                svc.dispatch(acts, Cfg.showTouches)
                actionsDone += acts.size
            } catch (e: Exception) {
                Diag.error("Touch failed", e)
            }
        }
        if (Cfg.fastMode) send() else main.post { send() }
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
