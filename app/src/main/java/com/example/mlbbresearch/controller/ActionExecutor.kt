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
import com.example.mlbbresearch.game.SkillBook
import com.example.mlbbresearch.game.CastMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A touch to perform, in screen fractions
sealed class Act {
    data class Tap(val x: Float, val y: Float) : Act()
    data class Drag(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long) : Act()
    // hold the joystick (centre cx,cy) pushed towards x,y; the finger stays down between ticks
    // via: points the thumb slides through on its way (along the ring, for a smooth turn)
    data class Joy(val cx: Float, val cy: Float, val x: Float, val y: Float, val ms: Long,
                   val via: List<Pair<Float, Float>> = emptyList()) : Act()
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
        verify(GameStateTracker.state)
        if (System.currentTimeMillis() < holdUntil) return     // a hold-to-charge skill is being held

        val acts = ArrayList<Act>()
        // COACH: while your finger is on the screen (you steer or cast), the AI touches nothing:
        // any touch it sends would cancel yours. It takes over again 1.2 s after you let go,
        // and learns from what you did (Coach).
        if (Cfg.coachMode && com.example.mlbbresearch.learning.Coach.userActive()) {
            joyDirX = 0f; joyDirY = 0f; headX = 0f; headY = 0f; headOct = -1
            lastDecision = "you are playing: AI waits (learning)"
            return
        }
        val (mx, my) = holdDirection(d)
        if (mx != 0f || my != 0f) {
            joystick(mx, my, d.intent)?.let { acts.add(it) }
        } else if (Cfg.smoothJoystick) {
            joyDirX = 0f; joyDirY = 0f; headX = 0f; headY = 0f; headOct = -1
            if (AccessibilityController.instance?.joystickHeld() == true) acts.add(Act.JoyRelease)
        }
        val gs = GameStateTracker.state
        val self = gs.self
        val casts = d.casts
        val nowMs = System.currentTimeMillis()
        for (c in casts) {
            // basic attack every 150 ms (the game queues it: no attack is lost between skills);
            // skills go out at once
            if (c == "attack") {
                if (nowMs - lastAttackTap < attackGap) continue
                lastAttackTap = nowMs
                attackGap = 150L
            }
            val (x, y) = Layout.get(c)
            if (c in AIMED && self != null) {
                // how this skill is cast, from its description and what worked before:
                // tap (quick cast), aim (drag towards where the target will be), hold (press,
                // hold to charge, aim, release)
                val mode = SkillBook.castMode(gs.ownHero, c)
                val act = when {
                    mode == CastMode.HOLD -> {
                        val hold = SkillBook.holdMs(gs.ownHero, c)
                        holdUntil = nowMs + hold + 60          // nothing else may touch until it is released
                        aimDrag(c, x, y, self.x, self.y, d, gs, hold)
                    }
                    // a quick 90 ms flick (a longer drag was often cut off by the next touch, and the
                    // skill had to be cast again); nothing else is sent until it is done
                    mode == CastMode.AIM && Cfg.aimSkills -> {
                        holdUntil = maxOf(holdUntil, nowMs + 100)
                        aimDrag(c, x, y, self.x, self.y, d, gs, 90L)
                    }
                    else -> Act.Tap(x, y)
                }
                acts.add(act)
                checks.add(Check(c, mode, nowMs, d.targetId, d.targetHp, SkillBook.get(gs.ownHero)[c]?.travelSec ?: 0.3f, gs.ownHero))
            } else acts.add(Act.Tap(x, y))
            if (c != "attack") { CooldownTracker.markCast(c); aiCastAt[c] = nowMs; lastAiCastAt = nowMs }
            if (nowMs < holdUntil) break                     // a held skill goes out alone
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

    // ---- execution feedback: did the skill go off, did it hit? ----
    // After every skill: 0.5-1.5 s later the button must be dark (it fired). Two misfires in a
    // row with one cast mode -> the next mode is learned for that skill (tap -> aim -> hold).
    // An aimed skill whose target lost no HP by the time it should have landed = a miss: the
    // aim lead (how far ahead of a moving enemy) is nudged, the other way after a second miss.
    private class Check(val slot: String, val mode: CastMode, val at: Long, val targetId: Int, val targetHp: Float,
                        val travel: Float, val hero: String?, var fired: Boolean? = null)
    private val checks = java.util.concurrent.CopyOnWriteArrayList<Check>()
    private val misfires = HashMap<String, Int>()
    private val misses = HashMap<String, Int>()
    @Volatile private var holdUntil = 0L
    @Volatile var lastFeedback = "-"
        private set

    private fun verify(gs: GameState) {
        val now = System.currentTimeMillis()
        for (c in checks) {
            val age = now - c.at
            val key = (c.hero ?: "?") + c.slot
            if (c.fired == null && age > 500 + (if (c.mode == CastMode.HOLD) SkillBook.holdMs(c.hero, c.slot) else 0L)) {
                if (!CooldownTracker.screenKnown(c.slot)) { checks.remove(c); continue }
                c.fired = !CooldownTracker.lookReady(c.slot)
                if (c.fired == true) misfires[key] = 0 else {
                    val n = (misfires[key] ?: 0) + 1
                    misfires[key] = n
                    if (n >= 2) {
                        // only tap <-> aim: "hold" (which blocks every other touch while it charges)
                        // comes from the skill's description alone, never from a guess
                        val next = if (c.mode == CastMode.TAP) CastMode.AIM else CastMode.TAP
                        SkillBook.setCastMode(c.hero, c.slot, next)
                        misfires[key] = 0
                        lastFeedback = "${c.slot} did not go off twice with ${c.mode.name.lowercase()}: now ${next.name.lowercase()}"
                        note("Learned: cast ${c.slot} with ${next.name.lowercase()}")
                    } else lastFeedback = "${c.slot} did not go off"
                    checks.remove(c); continue
                }
            }
            if (c.fired == true && age > (c.travel * 1000).toLong() + 600) {
                checks.remove(c)
                if (c.mode == CastMode.TAP || c.targetId < 0 || c.targetHp < 0f) continue
                val t = gs.enemies.firstOrNull { it.id == c.targetId } ?: continue
                if (c.targetHp - t.hp >= 0.03f) {
                    misses[key] = 0
                    lastFeedback = "${c.slot} hit"
                } else {
                    val n = (misses[key] ?: 0) + 1
                    misses[key] = n
                    val lead = SkillBook.aimLead(c.hero, c.slot)
                    val nl = (lead + if (n % 2 == 1) 0.3f else -0.6f).coerceIn(0f, 2f)
                    SkillBook.setAimLead(c.hero, c.slot, nl)
                    lastFeedback = "${c.slot} missed: aim lead ${"%.1f".format(lead)} -> ${"%.1f".format(nl)}"
                }
            }
            if (age > 5000) checks.remove(c)
        }
    }

    // Drag a skill button towards where the target WILL be (its speed x the skill's travel time
    // x the learned lead), or the way the hero walks when there is no target (dashes, escapes).
    // holdMs: how long the finger stays down (a hold-to-charge skill is released at the end).
    private fun aimDrag(c: String, bx: Float, by: Float, sx: Float, sy: Float, d: TacticalDecision, gs: GameState, holdMs: Long): Act {
        val aspect = AccessibilityController.screenAspect()
        if (d.targetX < 0f || d.intent == Intent.RETREAT) {
            val (wx, wy) = if (d.moveX != 0f || d.moveY != 0f) d.moveX to d.moveY else joyDirX to joyDirY
            if (wx == 0f && wy == 0f) return if (holdMs > 400) Act.Drag(bx, by, bx + 0.0005f, by, holdMs) else Act.Tap(bx, by)
            val l = kotlin.math.hypot(wx, wy)
            return Act.Drag(bx, by, bx + wx / l * SKILL_AIM_R, by + wy / l * SKILL_AIM_R * aspect, holdMs)
        }
        var tx = d.targetX
        var ty = d.targetY
        val track = if (d.targetId >= 0) gs.enemies.firstOrNull { it.id == d.targetId } else null
        if (track != null) {
            val travel = (SkillBook.get(gs.ownHero)[c]?.travelSec ?: 0.3f) + holdMs.coerceAtMost(400) / 1000f * 0.5f
            val lead = travel * SkillBook.aimLead(gs.ownHero, c)
            tx += track.vx * lead
            ty += track.vy * lead
        }
        val dx = (tx - sx) * aspect
        val dy = ty - sy
        val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
        val range = SkillBook.get(gs.ownHero)[c]?.range ?: 0.45f
        val reach = SKILL_AIM_R * (0.35f + 0.65f * (l / range).coerceAtMost(1f))
        return Act.Drag(bx, by, bx + dx / l * reach, by + dy / l * reach * aspect, holdMs)
    }

    // When to keep the thumb on the joystick. Like a player: the thumb stays down, pushed to the
    // edge of the ring, and only turns while walking, chasing or running away. It is lifted only
    // to stand and hit something: minions, jungle monsters, turtle / lord, a tower, or to
    // recall / regen / hide. A decision that briefly says "stand still" in a chase or an escape
    // does not lift it: the hero keeps going the same way (melee heroes stick to their target).
    private fun holdDirection(d: TacticalDecision): Pair<Float, Float> {
        val now = System.currentTimeMillis()
        if (d.moveX != 0f || d.moveY != 0f) { lastMoveAt = now; return d.moveX to d.moveY }
        // the thumb is lifted only to stand and hit something (the joystick would override the
        // attack), to recall / regen, or to hide in a bush; never just to stand around
        val stay = when (d.intent) {
            Intent.RECALL, Intent.REGEN, Intent.AMBUSH -> true
            Intent.RETREAT -> d.reason.contains("hiding")
            Intent.LANE_CLEAR, Intent.JUNGLE, Intent.OBJECTIVE, Intent.PUSH -> "attack" in d.casts || d.targetX >= 0f
            else -> false
        }
        if (stay || !Cfg.smoothJoystick) return 0f to 0f
        val self = GameStateTracker.state.self
        // in a fight the thumb never stops: a melee hero walks into its target (the attack lands
        // as it walks), a ranged hero side-steps around it at its range (harder to hit back)
        if (d.intent == Intent.ENGAGE && d.targetX >= 0f && self != null) {
            val ax = (d.targetX - self.x) * AccessibilityController.screenAspect()
            val ay = d.targetY - self.y
            val l = kotlin.math.hypot(ax, ay)
            if (l > 0.02f) {
                if (!com.example.mlbbresearch.game.HeroDb.isRanged(GameStateTracker.state.ownHero)) return (ax / l) to (ay / l)
                if (now - strafeFlipAt > 1800) { strafeFlipAt = now; strafeSign = -strafeSign }
                return (-ay / l * strafeSign) to (ax / l * strafeSign)
            }
        }
        // otherwise keep walking the same way rather than lifting and pressing again
        if ((joyDirX != 0f || joyDirY != 0f) && now - lastMoveAt < 3000) return joyDirX to joyDirY
        return 0f to 0f
    }
    private var lastMoveAt = 0L
    // what the AI itself pressed (Coach tells your casts and moves from the AI's)
    private val aiCastAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var lastAiCastAt = 0L
        private set
    fun aiCastAgo(slot: String): Long = System.currentTimeMillis() - (aiCastAt[slot] ?: 0L)
    // the way the AI is steering right now (0,0 = joystick not held)
    fun steering(): Pair<Float, Float> =
        if (AccessibilityController.instance?.joystickHeld() == true) joyDirX to joyDirY else 0f to 0f
    private var strafeFlipAt = 0L
    private var strafeSign = 1f


    // Direction (-1..1) -> joystick touch. The thumb presses the centre, slides out to the edge
    // and stays down; it keeps one heading and only slides to a new one when the course really
    // changes. When the camera stops moving while walking (a wall) it steps around it.
    private var sentDirX = 0f
    private var sentDirY = 0f
    private var sentAt = 0L

    // the way the hero is walking: kept until a real change of course is needed
    private var headX = 0f
    private var headY = 0f
    private var headIntent: Intent? = null
    private var headSince = 0L
    private fun Intent.walking() = this == Intent.PUSH || this == Intent.GROUP || this == Intent.JUNGLE || this == Intent.LANE_CLEAR

    private fun joystick(mx0: Float, my0: Float, intent: Intent = Intent.PUSH): Act? {
        val (jx, jy) = Layout.get("joystick")
        val r = Layout.JOYSTICK_RADIUS
        val aspect = AccessibilityController.screenAspect()
        if (!Cfg.smoothJoystick) return Act.Drag(jx, jy, jx + mx0 * r, jy + my0 * r * aspect, 300)
        val now = System.currentTimeMillis()
        var mx = mx0
        var my = my0
        // EIGHT DIRECTIONS, WALK STRAIGHT: like a thumb on the stick the hero goes N, NE, E, SE,
        // S, SW, W or NW. The decision is re-made several times a second and jitters a little; the
        // heading only changes when the wanted direction is clearly closer to another of the 8
        // (more than 37 deg off the current one when walking, 30 deg in a fight) and, when
        // walking, not sooner than ~0.9 s after the last turn (or what Coach learned from your
        // thumb). A reversal (running away, a dodge) or a wall detour turns at once.
        val l0 = kotlin.math.hypot(mx, my).coerceAtLeast(1e-4f)
        mx /= l0; my /= l0
        val fight = intent == Intent.ENGAGE || intent == Intent.RETREAT
        val want = kotlin.math.atan2(my, mx)
        val wantOct = (Math.round(want / OCT).toInt() + 8) % 8
        val held0 = AccessibilityController.instance?.joystickHeld() == true
        val hasHead = held0 && headOct >= 0
        val off = if (hasHead) angleDiff(want, headOct * OCT) else 9f
        val hyst = Math.toRadians(if (fight) 30.0 else 37.5).toFloat()
        val minHold = if (fight) 250L else com.example.mlbbresearch.learning.Coach.holdMs
        val turn = !hasHead || now < detourUntil || off > Math.toRadians(100.0).toFloat() ||
            off > hyst && now - headSince >= minHold
        fromOct = headOct
        if (turn && wantOct != headOct) { headOct = wantOct; headSince = now }
        mx = kotlin.math.cos(headOct * OCT); my = kotlin.math.sin(headOct * OCT)
        headX = mx; headY = my; headIntent = intent
        if (joyDirX == 0f && joyDirY == 0f) walkSince = now
        // stuck on a wall: walking for a while but the camera has not moved for 0.7 s
        if (Cfg.wallAvoid && now - walkSince > 900 && now - GameStateTracker.lastMotionAt > 700 && now > detourUntil + 400) {
            detourUntil = now + 650
            detourSign = -detourSign
            // remember this wall on the minimap so the next path goes around it
            GameStateTracker.state.minimapSelf?.let { com.example.mlbbresearch.vision.WallMap.markBlocked(it, mx, my) }
        }
        if (now < detourUntil) {
            // step around the wall: two of the eight directions (90 deg) to one side
            val o = (headOct + (if (detourSign > 0) 2 else 6)) % 8
            mx = kotlin.math.cos(o * OCT); my = kotlin.math.sin(o * OCT)
        }
        // the turn is ONE slide of the thumb straight to the new direction (no curving arcs),
        // held firmly just past the ring's edge (full speed; the stick clamps it)
        val l = kotlin.math.hypot(mx, my).coerceAtLeast(1e-4f)
        val nx = mx / l
        val ny = my / l
        pushRadius = 1.1f
        lastJoyAt = now
        joyDirX = nx; joyDirY = ny
        // the finger is already down: only move it when the direction really changed (> ~6
        // degrees) or every 0.5 s, so taps and skills are not held up by joystick strokes
        val held = AccessibilityController.instance?.joystickHeld() == true
        if (held && nx * sentDirX + ny * sentDirY > 0.995f && now - sentAt < 500 && now >= detourUntil) return null
        sentDirX = nx; sentDirY = ny; sentAt = now
        val ms = 40L                       // no random "human" variation: the turn goes out at once
        // a turn of 90 deg or more slides the thumb ALONG the ring through the directions in
        // between (E -> NE -> N), so the hero curves round smoothly instead of snapping
        val via = ArrayList<Pair<Float, Float>>()
        val from = fromOct
        val to = (Math.round(kotlin.math.atan2(ny, nx) / OCT).toInt() + 8) % 8
        if (held && from >= 0 && from != to && false) {   // (the slide along the ring cost 35 ms per step: straight to the new heading)
            var steps = (to - from + 8) % 8
            val dirStep = if (steps <= 4) 1 else { steps = 8 - steps; -1 }
            for (k in 1 until steps) {
                val o = (from + dirStep * k + 8) % 8
                via.add((jx + kotlin.math.cos(o * OCT) * r * pushRadius) to (jy + kotlin.math.sin(o * OCT) * r * pushRadius * aspect))
            }
        }
        return Act.Joy(jx, jy, jx + nx * r * pushRadius, jy + ny * r * pushRadius * aspect, ms + via.size * 35L, via)
    }

    private val OCT = (Math.PI / 4).toFloat()
    private var headOct = -1
    private var fromOct = -1
    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return kotlin.math.abs(d)
    }

    // Lift the joystick finger (AI paused or stopped)
    fun release() {
        joyDirX = 0f; joyDirY = 0f
        headX = 0f; headY = 0f; headOct = -1
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
        // the hero's priority (online meta / guides, else its skill data) - no setting needed
        val order = com.example.mlbbresearch.game.SkillOrder.of(s.ownHero)
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
        // a skill being held to charge: any other touch now would cancel it
        if (System.currentTimeMillis() < holdUntil && acts.none { it is Act.Drag && (it as Act.Drag).ms > 400 }) return
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
