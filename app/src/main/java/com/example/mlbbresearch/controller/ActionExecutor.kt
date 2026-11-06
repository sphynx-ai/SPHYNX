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
import com.example.mlbbresearch.game.HeroKit
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.SkillKind
import com.example.mlbbresearch.game.SkillBook
import com.example.mlbbresearch.game.CastMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A touch to perform, in screen fractions
sealed class Act {
    // holdMs: 500L for target-lock skills (press -> hold 0.5s -> release), 220-340L for basic attack hold
    data class Tap(val x: Float, val y: Float, val startMs: Long = 0L, val holdMs: Long = 35L) : Act()
    data class Drag(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long, val startMs: Long = 0L) : Act()
    // Two-stage continuous skill aim (v37.09):
    // 1. Press skill button (x1, y1) and hold for pressHoldMs (300 ms) without releasing
    // 2. Re-aim using live target position and hold direction (x2, y2) for aimHoldMs (300 ms short range / dash, 500 ms long range) and lastly release
    data class SkillAim(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val pressHoldMs: Long = CastTiming.AIM_PRESS_HOLD_MS,
        val aimHoldMs: Long = CastTiming.AIM_SHORT_DIR_HOLD_MS,
        val startMs: Long = 0L,
        val slot: String = "",
        val retreating: Boolean = false,
        val fixedEnd: Boolean = false
    ) : Act() {
        val totalMs: Long get() = pressHoldMs + aimHoldMs
    }
    // hold the joystick (centre cx,cy) pushed towards x,y; the finger stays down between ticks
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
    private var attackGap = CastTiming.ATTACK_HOLD_MS + 25L
    private var lastJoyAt = 0L
    private var detourUntil = 0L
    private var detourSign = 1f
    private var pushRadius = 1.15f
    @Volatile private var generation = 0
    private val AIMED = setOf("s1", "s2", "s3", "ult")
    private const val SKILL_AIM_R = 0.045f     // how far a skill button is dragged to aim (fraction of width)

    @Volatile var lastDecision: String = "-"
        private set
    @Volatile var lastTarget: String = "-"
        private set
    @Volatile var actionsDone = 0
        private set
    @Volatile private var latestDecision: TacticalDecision = TacticalDecision.IDLE

    // Long-range skillshot check (v37.09):
    // Short-range skillshot or dash escape -> 0.3s press hold + 0.3s direction hold (600 ms)
    // Long-range skillshot (range > 0.48) -> 0.3s press hold + 0.5s direction hold (800 ms)
    fun isLongRangeSkill(hero: String?, slot: String, retreating: Boolean = false): Boolean {
        if (retreating) return false
        if (slot == HeroDb.dashSlot(hero)) return false
        val info = SkillBook.get(hero)[slot]
        if (info?.kind == SkillKind.DASH) return false
        return (info?.range ?: 0.45f) > 0.48f
    }

    // Live Stage-2 Re-Aiming (v37.09):
    // Called by AccessibilityController at the exact moment Stage 1 (300ms press hold) finishes so Stage 2
    // drags toward where the enemy hero or creep is RIGHT NOW instead of 300ms ago!
    fun freshAimEnd(sa: Act.SkillAim): Pair<Float, Float> {
        if (sa.fixedEnd || sa.slot.isEmpty()) return sa.x2 to sa.y2
        val gs = GameStateTracker.state
        val self = gs.self ?: return sa.x2 to sa.y2
        val d = latestDecision
        val refreshed = aimDrag(sa.slot, sa.x1, sa.y1, self.x, self.y, d, gs, sa.aimHoldMs, sa.aimHoldMs >= CastTiming.AIM_LONG_DIR_HOLD_MS)
        return if (refreshed is Act.SkillAim) refreshed.x2 to refreshed.y2 else sa.x2 to sa.y2
    }

    fun testCombo(): String {
        if (com.example.mlbbresearch.ai.AiRuntime.playerOn) return "Test combo is disabled while Player is on"
        if (AccessibilityController.instance == null) return "Test combo: turn on the app's Accessibility service first"
        val gs = GameStateTracker.state
        val fresh = gs.phase == com.example.mlbbresearch.game.Phase.IN_GAME && gs.self != null
        val hero = Cfg.manualHero.ifBlank { null } ?: gs.ownHero ?: Cfg.myHero.ifBlank { null }
        Layout.kitSize = HeroKit.count(hero)
        var steps = com.example.mlbbresearch.game.ComboBook.steps(hero).map { it.slot }.filter { it in AIMED && HeroKit.has(hero, it) }
        if (steps.isEmpty()) steps = HeroKit.slots(hero)
        val sx = if (fresh) gs.self!!.x else 0.5f
        val sy = if (fresh) gs.self!!.y else 0.5f
        val e = if (fresh) gs.enemies.minByOrNull { gs.distToSelf(it.x, it.y) } else null
        val tx = e?.x ?: (sx + 0.15f); val ty = e?.y ?: sy
        val aspect = AccessibilityController.screenAspect()
        val bursts = ArrayList<MutableList<String>>()
        for (sl in steps) {
            val b = bursts.lastOrNull()
            val hold = SkillBook.castMode(hero, sl) == CastMode.HOLD || HeroKit.special(hero, sl)?.kind == HeroKit.Special.HOLD_OR_TAP
            if (b == null || b.size >= 3 || sl in b || hold || b.any { SkillBook.castMode(hero, it) == CastMode.HOLD || HeroKit.special(hero, it)?.kind == HeroKit.Special.HOLD_OR_TAP }) bursts.add(mutableListOf(sl)) else b.add(sl)
        }
        val gen = generation
        var delay = 0L
        val (ax, ay) = Layout.get("attack")
        val testSkip = HashSet<String>()
        val specialNotes = ArrayList<String>()
        var lingBladeTestIdx = 0
        var fannySideTest = 1
        var gusionS1Seen = 0
        var gusionS2Seen = 0
        for (b in bursts) {
            for (sl in b) {
                if (sl in testSkip) continue
                val (bx, by) = Layout.get(sl)
                var mode = SkillBook.castMode(hero, sl)
                var dx = (tx - sx) * aspect; var dy = ty - sy
                if (hero == "Ling" && sl == "s2" && lingBladeTestIdx in 1..4) {
                    val ang = floatArrayOf((-Math.PI / 4.0).toFloat(), (3.0 * Math.PI / 4.0).toFloat(), (Math.PI / 4.0).toFloat(), (-3.0 * Math.PI / 4.0).toFloat())[(lingBladeTestIdx - 1) % 4]
                    dx = kotlin.math.cos(ang); dy = kotlin.math.sin(ang)
                    lingBladeTestIdx++
                } else if (hero == "Ling" && sl == "ult") {
                    lingBladeTestIdx = 1
                    specialNotes.add("ult -> 4 Field Eye blade dashes")
                } else if (hero == "Fanny" && sl == "s2") {
                    dy += 0.08f * fannySideTest
                    fannySideTest = -fannySideTest
                } else if (hero == "Gusion") {
                    if (sl == "s1") { gusionS1Seen++; if (gusionS1Seen >= 2) mode = CastMode.TAP }
                    if (sl == "s2") { gusionS2Seen++; if (gusionS2Seen >= 3) mode = CastMode.TAP }
                }
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                val ex = bx + dx / l * SKILL_AIM_R; val ey = by + dy / l * SKILL_AIM_R * aspect
                val longRange = isLongRangeSkill(hero, sl, false)
                val aimDirHold = if (longRange) CastTiming.AIM_LONG_DIR_HOLD_MS else CastTiming.AIM_SHORT_DIR_HOLD_MS
                val sp = HeroKit.special(hero, sl)
                val special: Act? = when (sp?.kind) {
                    HeroKit.Special.DRAG_TO -> if (sp.other in b) { testSkip.add(sp.other); val (ox, oy) = Layout.get(sp.other); Act.SkillAim(bx, by, ox, oy, CastTiming.AIM_PRESS_HOLD_MS, CastTiming.AIM_SHORT_DIR_HOLD_MS, 0L, sl, false, true) } else null
                    HeroKit.Special.HOLD_OR_TAP -> { mode = CastMode.HOLD; null }
                    HeroKit.Special.SWIPE_ULT -> Act.SkillAim(bx, by, bx + (if (HeroKit.soraTorrent(if (fresh) gs.enemiesWithin(0.6f).size else 0, if (fresh) gs.selfHp else 1f)) 1f else -1f) * SKILL_AIM_R * 1.6f, by, CastTiming.AIM_PRESS_HOLD_MS, CastTiming.AIM_SHORT_DIR_HOLD_MS, 0L, sl, false, true)
                    null -> null
                }
                if (sp != null) specialNotes.add("$sl: ${sp.note}")
                val skillAct: Act = special ?: when (mode) {
                    CastMode.HOLD -> Act.SkillAim(bx, by, ex, ey, CastTiming.AIM_PRESS_HOLD_MS, SkillBook.holdMs(hero, sl).coerceAtLeast(CastTiming.AIM_LONG_DIR_HOLD_MS), 0L, sl, false, true)
                    CastMode.AIM -> Act.SkillAim(bx, by, ex, ey, CastTiming.AIM_PRESS_HOLD_MS, aimDirHold, 0L, sl, false, true)
                    else -> Act.Tap(bx, by, 0L, CastTiming.TAP_HOLD_MS)
                }
                val stepDur = when (skillAct) {
                    is Act.SkillAim -> skillAct.totalMs
                    is Act.Tap -> skillAct.holdMs
                    is Act.Drag -> skillAct.ms
                    else -> 500L
                }
                val stepDelay = delay
                main.postDelayed({
                    if (gen != generation || com.example.mlbbresearch.ai.AiRuntime.playerOn) return@postDelayed
                    val svc = AccessibilityController.instance ?: return@postDelayed
                    try { svc.dispatch(listOf(skillAct), true) } catch (x: Exception) { Diag.error("Test combo", x) }
                }, stepDelay)
                delay += stepDur + 70L
                val atkDelay = delay
                main.postDelayed({
                    if (gen != generation || com.example.mlbbresearch.ai.AiRuntime.playerOn) return@postDelayed
                    val svc = AccessibilityController.instance ?: return@postDelayed
                    try { svc.dispatch(listOf(Act.Tap(ax, ay, 0L, CastTiming.ATTACK_HOLD_MS)), true) } catch (x: Exception) { Diag.error("Test combo", x) }
                }, atkDelay)
                delay += CastTiming.ATTACK_HOLD_MS + 65L
            }
        }
        val text = "Test combo (${hero ?: "hero unknown: choose it on the Player page"}): " + bursts.joinToString("  |  ") { it.joinToString(" ") } +
            " in ${bursts.size} burst${if (bursts.size > 1) "s" else ""}, about $delay ms" +
            (if (e != null) ", at enemy #${e.id}" else ", aimed to the right of your hero") +
            (if (specialNotes.isNotEmpty()) " · special: " + specialNotes.distinct().joinToString("; ") else "") +
            " · kit: " + HeroKit.describe(hero ?: "?")
        note(text)
        return text
    }

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
        latestDecision = d
        lastDecision = d.describe()
        lastTarget = if (d.targetHp >= 0f) "${d.targetLabel} (HP ${(d.targetHp * 100).toInt()}%)" else "none"
        if (d.intent == Intent.IDLE) {
            release()
            return
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs < QuickBuySwap.busyUntil) return
        verify(GameStateTracker.state)

        // COACH: while your finger is on the screen (you steer or cast), the AI touches nothing
        if (Cfg.coachMode && com.example.mlbbresearch.learning.Coach.userActive()) {
            joyDirX = 0f; joyDirY = 0f; headX = 0f; headY = 0f; headOct = -1
            lastDecision = "you are playing: AI waits (learning)"
            return
        }

        val acts = ArrayList<Act>()
        val (mx, my) = holdDirection(d)
        if (mx != 0f || my != 0f) {
            zeroMoveSince = 0L
            joystick(mx, my, d.intent)?.let { acts.add(it) }
        } else {
            val stopImmediately = "attack" in d.casts || d.targetX >= 0f ||
                d.intent in setOf(Intent.RECALL, Intent.REGEN, Intent.AMBUSH, Intent.IDLE, Intent.LANE_CLEAR)
            if (zeroMoveSince == 0L) zeroMoveSince = nowMs
            if (stopImmediately || nowMs - zeroMoveSince >= 140L) {
                // Standing still to attack a stationary entity or channel: release the joystick cleanly
                joyDirX = 0f; joyDirY = 0f; headX = 0f; headY = 0f; headOct = -1
                val svc = AccessibilityController.instance
                if (svc != null && svc.joystickHeld()) {
                    svc.clearWantedJoystick()
                    acts.add(Act.JoyRelease)
                }
            }
        }

        // If a skill hold (0.5s Tap, 0.6s Short Aim, or 0.8s Long Aim) is currently on screen,
        // keep updating (or releasing) the joystick in the queue without interrupting the skill hold!
        if (nowMs < holdUntil) {
            if (acts.isNotEmpty()) perform(acts)
            return
        }

        val gs = GameStateTracker.state
        val self = gs.self
        val p = HeroDb.profile(gs.ownHero)
        val baReliant = HeroDb.isBasicAttackReliant(gs.ownHero)
        val atkHoldDur = if (baReliant) CastTiming.CONTINUOUS_ATTACK_HOLD_MS else CastTiming.ATTACK_HOLD_MS
        val rawCasts = kitCasts(d, gs)
        val casts = rawCasts.filter { it != "attack" } + rawCasts.filter { it == "attack" }
        val skip = HashSet<String>()
        var t = 0L
        var prevSkill: String? = null
        var skillCastDuration = 0L

        for (c in casts) {
            if (c in skip) continue
            if (c == "attack") {
                if (skillCastDuration > 0L) {
                    // Weave a basic attack immediately after the skill hold finishes!
                    val (ax, ay) = Layout.get("attack")
                    val gen = generation
                    main.postDelayed({
                        if (gen == generation && com.example.mlbbresearch.ai.AiRuntime.playerOn) {
                            perform(listOf(Act.Tap(ax, ay, 0L, atkHoldDur)))
                        }
                    }, skillCastDuration + 20L)
                    aiCastAt["attack"] = nowMs + skillCastDuration + atkHoldDur
                    lastAttackTap = nowMs + skillCastDuration
                    continue
                }
                // 3-Stage Basic Attack Glow/Dim feedback (v37.10, calibrated on 1000032040/41/42):
                //  - Stage 3 (DIMMED, right after release): re-press immediately (55 ms) so basic-attack reliant heroes
                //    (Zilong, Argus, Thamuz, Layla, Miya, etc.) keep attacking with zero pause!
                //  - Stage 1 (IDLE, unpressed): re-press after 75 ms.
                //  - Stage 2 (GLOWING, currently held): wait for the current hold to finish (attackGap).
                val minAtkGap = when (CooldownTracker.attackStage) {
                    CooldownTracker.AttackStage.DIMMED -> 55L
                    CooldownTracker.AttackStage.IDLE -> 75L
                    CooldownTracker.AttackStage.GLOWING -> attackGap
                }
                if (nowMs - lastAttackTap < minAtkGap) continue
                lastAttackTap = nowMs
                attackGap = atkHoldDur + 20L
            }
            val (x, y) = Layout.get(c)
            if (c in AIMED && self != null) {
                var mode = SkillBook.castMode(gs.ownHero, c)
                val sp = HeroKit.special(gs.ownHero, c)
                var special: Act? = null
                val dist = if (d.targetX >= 0f) gs.distToSelf(d.targetX, d.targetY) else -1f
                when (sp?.kind) {
                    HeroKit.Special.DRAG_TO -> {
                        val otherReady = sp.other !in skip && (sp.other in casts || CooldownTracker.ready(sp.other, p))
                        if (otherReady) {
                            val (from, to) = HeroKit.dragOrder(gs.ownHero, dist) ?: (c to sp.other)
                            val (fx, fy) = Layout.get(from); val (tx2, ty2) = Layout.get(to)
                            special = Act.SkillAim(fx, fy, tx2, ty2, CastTiming.AIM_PRESS_HOLD_MS, CastTiming.AIM_SHORT_DIR_HOLD_MS, 0L, c, false, true)
                            skip.add(sp.other)
                            CooldownTracker.markCast(sp.other)
                            aiCastAt[sp.other] = nowMs + CastTiming.AIM_PRESS_HOLD_MS + CastTiming.AIM_SHORT_DIR_HOLD_MS
                            com.example.mlbbresearch.ai.ComboRunner.onPairedSkillConsumed(sp.other)
                            mode = CastMode.AIM
                            note("${gs.ownHero} drag combo: $from onto $to")
                        }
                    }
                    HeroKit.Special.HOLD_OR_TAP -> {
                        val near = gs.enemiesWithin(0.6f).size
                        val chasing = d.intent == Intent.ENGAGE && (d.targetHp in 0f..0.28f || dist > 0.42f)
                        val useHold = HeroKit.suyouShouldHold(c, d.intent == Intent.RETREAT, chasing, if (dist >= 0f) dist else 0.30f, d.targetHp, near, gs.selfHp)
                        mode = if (useHold) CastMode.HOLD else CastMode.AIM
                    }
                    HeroKit.Special.SWIPE_ULT -> {
                        val torrent = HeroKit.soraTorrent(gs.enemiesWithin(0.6f).size, gs.selfHp)
                        special = Act.SkillAim(x, y, x + (if (torrent) 1f else -1f) * SKILL_AIM_R * 1.6f, y, CastTiming.AIM_PRESS_HOLD_MS, CastTiming.AIM_SHORT_DIR_HOLD_MS, 0L, c, false, true)
                        mode = CastMode.AIM
                        note("Sora ultimate: ${if (torrent) "Torrent (swipe right)" else "Thunder (swipe left)"}")
                    }
                    null -> {}
                }
                if (HeroKit.shouldRecastTap(gs.ownHero, c)) {
                    mode = CastMode.TAP
                }
                val isDashSkill = (d.intent == Intent.RETREAT || c == HeroDb.dashSlot(gs.ownHero) || SkillBook.get(gs.ownHero)[c]?.kind == SkillKind.DASH)
                if (isDashSkill && mode == CastMode.TAP && !HeroKit.shouldRecastTap(gs.ownHero, c) &&
                    (d.intent == Intent.RETREAT || d.targetX >= 0f || d.moveX != 0f || d.moveY != 0f)
                ) {
                    mode = CastMode.AIM
                }
                val longRange = isLongRangeSkill(gs.ownHero, c, d.intent == Intent.RETREAT)
                if (t > 0L) break
                val act: Act = when {
                    special != null -> {
                        val specDur = if (special is Act.SkillAim) special.totalMs else CastTiming.TAP_HOLD_MS
                        holdUntil = nowMs + specDur + 25L
                        special
                    }
                    mode == CastMode.HOLD -> {
                        val hold = SkillBook.holdMs(gs.ownHero, c).coerceAtLeast(CastTiming.AIM_LONG_DIR_HOLD_MS)
                        holdUntil = nowMs + CastTiming.AIM_PRESS_HOLD_MS + hold + 25L
                        aimDrag(c, x, y, self.x, self.y, d, gs, hold, longRange = true)
                    }
                    mode == CastMode.AIM && Cfg.aimSkills -> {
                        val dirHold = if (longRange) CastTiming.AIM_LONG_DIR_HOLD_MS else CastTiming.AIM_SHORT_DIR_HOLD_MS
                        holdUntil = nowMs + CastTiming.AIM_PRESS_HOLD_MS + dirHold + 25L
                        aimDrag(c, x, y, self.x, self.y, d, gs, dirHold, longRange = longRange)
                    }
                    else -> {
                        holdUntil = nowMs + CastTiming.TAP_HOLD_MS + 20L
                        Act.Tap(x, y, 0L, CastTiming.TAP_HOLD_MS)
                    }
                }
                acts.add(act)
                val castDur = when (act) {
                    is Act.SkillAim -> act.totalMs
                    is Act.Tap -> act.holdMs
                    is Act.Drag -> act.ms
                    else -> CastTiming.TAP_HOLD_MS
                }
                skillCastDuration = castDur
                checks.add(Check(c, mode, nowMs + castDur, d.targetId, d.targetHp, SkillBook.get(gs.ownHero)[c]?.travelSec ?: 0.3f, gs.ownHero, prev = prevSkill))
                prevSkill = c
                t += castDur + 25L
                CooldownTracker.markCast(c)
                HeroKit.onSkillCast(gs.ownHero, c, d.targetX, d.targetY)
                aiCastAt[c] = nowMs + castDur
                lastAiCastAt = nowMs + castDur
            } else {
                if (c == "attack") {
                    acts.add(Act.Tap(x, y, 0L, atkHoldDur))
                    aiCastAt[c] = nowMs + atkHoldDur
                } else {
                    val spellHold = if (c == "spell") 180L else 90L
                    acts.add(Act.Tap(x, y, 0L, spellHold))
                    CooldownTracker.markCast(c)
                    aiCastAt[c] = nowMs + spellHold
                    lastAiCastAt = nowMs + spellHold
                }
            }
        }
        if (skillCastDuration == 0L) {
            ActiveItems.decide(d, gs)?.let { k -> val (ix, iy) = Layout.get(k); acts.add(Act.Tap(ix, iy, 0L, 110L)); note("Active: " + ActiveItems.lastNote) }
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

    private class Check(val slot: String, val mode: CastMode, val at: Long, val targetId: Int, val targetHp: Float,
                        val travel: Float, val hero: String?, var fired: Boolean? = null, val prev: String? = null)

    private fun kitCasts(d: TacticalDecision, gs: GameState): List<String> {
        val hero = gs.ownHero
        var out = d.casts.filter { it !in HeroKit.ALL || HeroKit.has(hero, it) }
        if (HeroKit.rangedNow(hero) != null && (hero == "Roger" || hero == "Selena")) {
            val nearest = gs.enemies.minOfOrNull { gs.distToSelf(it.x, it.y) } ?: -1f
            val fighting = d.intent == Intent.ENGAGE || d.intent == Intent.AMBUSH
            if (!com.example.mlbbresearch.ai.ComboRunner.running) {
                out = out.filter { it != "ult" }
            }
            if ("ult" !in out && HeroKit.wantsSwitch(hero, fighting, nearest, gs.selfHp) && CooldownTracker.ready("ult", HeroDb.profile(hero))) {
                out = listOf("ult") + out
            }
        }
        return out
    }

    fun castGap(hero: String?, slot: String, mode: CastMode): Long = CastTiming.gap(hero, slot, mode)
    private fun adjustGap(hero: String?, slot: String, mode: CastMode, delta: Long) = CastTiming.adjust(hero, slot, mode, delta)
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
                if (c.fired == true) { HeroKit.confirmed(c.hero, c.slot); if (c.slot == "ult") HeroKit.onUltCast(c.hero) }
                else if (HeroKit.misfired(c.hero, c.slot)) note("Learned: ${c.hero} has no ${c.slot} button (it never went off)")
                c.prev?.let { pv -> adjustGap(c.hero, pv, SkillBook.castMode(c.hero, pv), if (c.fired == true) -5L else 40L) }
                if (c.fired == true) misfires[key] = 0 else {
                    lastFeedback = "${c.slot} cast (${c.mode.name.lowercase()})"
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

    private fun aimDrag(c: String, bx: Float, by: Float, sx: Float, sy: Float, d: TacticalDecision, gs: GameState, dirHoldMs: Long, longRange: Boolean = false): Act {
        val aspect = AccessibilityController.screenAspect()
        val pressHold = CastTiming.AIM_PRESS_HOLD_MS
        val aimHold = dirHoldMs.coerceAtLeast(if (longRange) CastTiming.AIM_LONG_DIR_HOLD_MS else CastTiming.AIM_SHORT_DIR_HOLD_MS)
        val retreating = d.intent == Intent.RETREAT

        // 1) Fanny's Steel Cable (s2): aim at a wall that pulls Fanny across the enemy (or away when retreating)
        if (gs.ownHero == "Fanny" && c == "s2") {
            val wall = HeroKit.fannyCableTarget(gs, sx, sy, d.targetX, d.targetY, retreating)
            if (wall != null) {
                val dx = (wall.first - sx) * aspect
                val dy = wall.second - sy
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                return Act.SkillAim(bx, by, bx + dx / l * SKILL_AIM_R, by + dy / l * SKILL_AIM_R * aspect, pressHold, aimHold, 0L, c, retreating, true)
            }
        }

        // 2) Ling's Defiant Sword (s2) during Tempest of Blades 4-blade window: aim directly at the next Field Eye blade!
        if (gs.ownHero == "Ling" && c == "s2" && !retreating) {
            val blade = HeroKit.nextLingBladeTarget(sx, sy, d.targetX, d.targetY, aspect)
            if (blade != null) {
                val dx = (blade.first - sx) * aspect
                val dy = blade.second - sy
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                return Act.SkillAim(bx, by, bx + dx / l * SKILL_AIM_R, by + dy / l * SKILL_AIM_R * aspect, pressHold, aimHold, 0L, c, retreating, true)
            }
        }

        // Find live target coordinates (refresh from live enemy hero or monster if available!)
        var tx = d.targetX
        var ty = d.targetY
        val liveEnemy = if (d.targetId >= 0) {
            gs.enemies.firstOrNull { it.id == d.targetId } ?: gs.enemies.minByOrNull { gs.distToSelf(it.x, it.y) }
        } else if (d.intent == Intent.ENGAGE) {
            gs.enemies.minByOrNull { gs.distToSelf(it.x, it.y) }
        } else null
        if (liveEnemy != null) {
            tx = liveEnemy.x
            ty = liveEnemy.y
            val travel = (SkillBook.get(gs.ownHero)[c]?.travelSec ?: 0.25f) + (aimHold / 1000f) * 0.45f
            val lead = travel * SkillBook.aimLead(gs.ownHero, c)
            tx += liveEnemy.vx * lead
            ty += liveEnemy.vy * lead
        } else if (d.intent == Intent.JUNGLE || d.intent == Intent.OBJECTIVE) {
            val liveMon = (gs.creeps + listOfNotNull(gs.boss)).minByOrNull { gs.distToSelf(it.x, it.y) }
            if (liveMon != null) {
                tx = liveMon.x
                ty = liveMon.y
            }
        }

        if (tx < 0f || retreating) {
            var (wx, wy) = if (d.moveX != 0f || d.moveY != 0f) d.moveX to d.moveY else joyDirX to joyDirY
            if (wx == 0f && wy == 0f) {
                val nearestEnemy = gs.enemies.minByOrNull { gs.distToSelf(it.x, it.y) }
                if (nearestEnemy != null) {
                    wx = (sx - nearestEnemy.x) * aspect
                    wy = sy - nearestEnemy.y
                } else {
                    wx = if (retreating) -0.7f else 0.7f
                    wy = if (retreating) 0.7f else 0f
                }
            }
            val l = kotlin.math.hypot(wx, wy).coerceAtLeast(1e-3f)
            return Act.SkillAim(bx, by, bx + wx / l * SKILL_AIM_R, by + wy / l * SKILL_AIM_R * aspect, pressHold, aimHold, 0L, c, retreating, false)
        }
        val dx = (tx - sx) * aspect
        val dy = ty - sy
        val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
        val range = SkillBook.get(gs.ownHero)[c]?.range ?: 0.45f
        val reach = SKILL_AIM_R * (0.45f + 0.55f * (l / range).coerceAtMost(1f))
        return Act.SkillAim(bx, by, bx + dx / l * reach, by + dy / l * reach * aspect, pressHold, aimHold, 0L, c, retreating, false)
    }

    // When to keep the thumb on the joystick.
    // IMPORTANT (v37.09): Never keep holding the joystick when attacking a stationary entity
    // (minions, jungle monsters, turtle/lord, or tower) or when moveX == 0 && moveY == 0 in PVE/Push!
    private fun holdDirection(d: TacticalDecision): Pair<Float, Float> {
        val now = System.currentTimeMillis()
        if (d.moveX != 0f || d.moveY != 0f) { lastMoveAt = now; return d.moveX to d.moveY }
        // If moveX == 0 and moveY == 0, stand completely still for ALL stationary / farming / objective / channel intents!
        val stay = when (d.intent) {
            Intent.RECALL, Intent.REGEN, Intent.AMBUSH, Intent.LANE_CLEAR, Intent.JUNGLE, Intent.OBJECTIVE, Intent.PUSH -> true
            Intent.RETREAT -> d.reason.contains("hiding")
            else -> false
        }
        if (stay || !Cfg.smoothJoystick) return 0f to 0f
        val self = GameStateTracker.state.self
        if (d.intent == Intent.ENGAGE && d.targetX >= 0f && self != null) {
            val dist = GameStateTracker.state.distToSelf(d.targetX, d.targetY)
            val atkR = HeroDb.profile(GameStateTracker.state.ownHero).attackRange
            // If a melee hero is already right on top of the enemy hero (dist <= atkR * 0.85f), stand and swing so attacks don't cancel!
            if (!HeroDb.isRanged(GameStateTracker.state.ownHero) && dist <= atkR * 0.85f) {
                return 0f to 0f
            }
            val ax = (d.targetX - self.x) * AccessibilityController.screenAspect()
            val ay = d.targetY - self.y
            val l = kotlin.math.hypot(ax, ay)
            if (l > 0.02f) {
                if (!HeroDb.isRanged(GameStateTracker.state.ownHero)) {
                    lastMoveAt = now
                    return (ax / l) to (ay / l)
                }
                if (now - strafeFlipAt > 1400) { strafeFlipAt = now; strafeSign = -strafeSign }
                lastMoveAt = now
                return (-ay / l * strafeSign) to (ax / l * strafeSign)
            }
        }
        return 0f to 0f
    }
    private var lastMoveAt = 0L
    private val aiCastAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var lastAiCastAt = 0L
        private set
    fun aiCastAgo(slot: String): Long = System.currentTimeMillis() - (aiCastAt[slot] ?: 0L)
    fun steering(): Pair<Float, Float> =
        if (AccessibilityController.instance?.joystickHeld() == true) joyDirX to joyDirY else 0f to 0f
    private var strafeFlipAt = 0L
    private var strafeSign = 1f

    private var sentDirX = 0f
    private var sentDirY = 0f
    private var sentAt = 0L

    private var headX = 0f
    private var headY = 0f
    private var headIntent: Intent? = null
    private var headSince = 0L

    private fun joystick(mx0: Float, my0: Float, intent: Intent = Intent.PUSH): Act? {
        val (jx, jy) = Layout.get("joystick")
        val r = Layout.JOYSTICK_RADIUS
        val aspect = AccessibilityController.screenAspect()
        if (!Cfg.smoothJoystick) return Act.Drag(jx, jy, jx + mx0 * r, jy + my0 * r * aspect, CastTiming.JOY_HOLD_MS)
        val now = System.currentTimeMillis()
        var mx = mx0
        var my = my0
        val l0 = kotlin.math.hypot(mx, my).coerceAtLeast(1e-4f)
        mx /= l0; my /= l0
        val fight = intent == Intent.ENGAGE || intent == Intent.RETREAT
        val want = kotlin.math.atan2(my, mx)
        val wantOct = (Math.round(want / OCT).toInt() + 8) % 8
        val held0 = AccessibilityController.instance?.joystickHeld() == true
        val hasHead = held0 && (headX != 0f || headY != 0f)
        val curAngle = if (hasHead) kotlin.math.atan2(headY, headX) else want
        val off = if (hasHead) angleDiff(want, curAngle) else 9f
        val hyst = Math.toRadians(if (fight) 12.0 else 20.0).toFloat()
        val minHold = if (fight) 65L else 120L
        val turn = !hasHead || now < detourUntil || off > Math.toRadians(70.0).toFloat() ||
            off > hyst && now - headSince >= minHold
        fromOct = headOct
        if (turn) {
            headOct = wantOct
            headSince = now
            if (fight) {
                headX = mx; headY = my
            } else {
                val step16 = (Math.PI / 8.0).toFloat()
                val idx16 = Math.round(want / step16)
                headX = kotlin.math.cos(idx16 * step16)
                headY = kotlin.math.sin(idx16 * step16)
            }
        }
        mx = headX; my = headY; headIntent = intent
        if (joyDirX == 0f && joyDirY == 0f) walkSince = now
        if (Cfg.wallAvoid && now - walkSince > 850 && now - GameStateTracker.lastMotionAt > 650 && now > detourUntil + 350) {
            detourUntil = now + 650
            detourSign = -detourSign
            GameStateTracker.state.minimapSelf?.let { com.example.mlbbresearch.vision.WallMap.markBlocked(it, mx, my) }
        }
        if (now < detourUntil) {
            val o = (headOct + (if (detourSign > 0) 2 else 6)) % 8
            mx = kotlin.math.cos(o * OCT); my = kotlin.math.sin(o * OCT)
        }
        val l = kotlin.math.hypot(mx, my).coerceAtLeast(1e-4f)
        val nx = mx / l
        val ny = my / l
        pushRadius = 1.15f
        lastJoyAt = now
        joyDirX = nx; joyDirY = ny
        val held = AccessibilityController.instance?.joystickHeld() == true
        if (held && nx * sentDirX + ny * sentDirY > 0.978f && now - sentAt < 280 && now >= detourUntil) return null
        sentDirX = nx; sentDirY = ny; sentAt = now
        val ms = CastTiming.JOY_HOLD_MS
        val via = ArrayList<Pair<Float, Float>>()
        return Act.Joy(jx, jy, jx + nx * r * pushRadius, jy + ny * r * pushRadius * aspect, ms, via)
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

    fun release() {
        joyDirX = 0f; joyDirY = 0f
        headX = 0f; headY = 0f; headOct = -1
        val svc = AccessibilityController.instance ?: return
        svc.clearWantedJoystick()
        if (svc.joystickHeld()) main.post { try { svc.releaseJoystick() } catch (e: Exception) { } }
    }

    private var zeroMoveSince = 0L

    // ---------------- player: level up skills ----------------
    // Safe Ultimate Upgrade Priority (v37.13):
    //  1. Never taps ultUp unless "ult" is genuinely in s.hud.upgradeReady (so it never taps an unavailable ult upgrade!).
    //  2. Never interrupts an active skill combo (ComboRunner.running) or skill hold (holdUntil).
    //  3. Waits 220 ms when a skill point first appears before upgrading s1/s2 so "ult" is always picked first if it is an Ult level (4/8/12).
    //  4. Caps consecutive taps on the same slot to 2 with a 4.5s cooldown so it can never spam-tap or block combos.
    @Volatile private var upgradeWindowStartAt = 0L
    @Volatile private var lastReadySeenAt = 0L
    @Volatile private var lastUpgradedSlot: String? = null
    @Volatile private var sameSlotTaps = 0

    fun resetMatchUpgrades() {
        upgradeWindowStartAt = 0L
        lastReadySeenAt = 0L
        lastUpgradedSlot = null
        sameSlotTaps = 0
    }

    fun maybeUpgrade(s: GameState) {
        if (!Cfg.autoUpgradeSkills) return
        val now = System.currentTimeMillis()
        val ready = s.hud.upgradeReady
        if (ready.isEmpty()) {
            if (now - lastReadySeenAt > 600L) {
                upgradeWindowStartAt = 0L
                lastUpgradedSlot = null
                sameSlotTaps = 0
            }
            return
        }
        lastReadySeenAt = now
        if (upgradeWindowStartAt == 0L) upgradeWindowStartAt = now

        // Never interrupt an active combo or skill hold gesture to tap an upgrade button!
        if (now < holdUntil || com.example.mlbbresearch.ai.ComboRunner.running || now < QuickBuySwap.busyUntil) return
        if (now - lastUpgradeTap < 650L) return

        val hasStandardUlt = HeroKit.has(s.ownHero, "ult") && Layout.kitSize >= 3 && s.ownHero != "Julian"
        // If "ult" is not in ready yet on the first 220 ms of a level-up, wait briefly so "ult" can finish popping in at Lv 4 / 8 / 12
        if ("ult" !in ready && hasStandardUlt && now - upgradeWindowStartAt < 220L) return

        val order = com.example.mlbbresearch.game.SkillOrder.of(s.ownHero)
        // ONLY pick "ult" when "ult" is actually present in `ready`!
        val slot = if ("ult" in ready) {
            "ult"
        } else {
            order.firstOrNull { it in ready } ?: ready.first()
        }

        // Guard against repeating the same tap more than twice if an icon is covered or non-responsive
        if (slot == lastUpgradedSlot && sameSlotTaps >= 2 && now - lastUpgradeTap < 4500L) return
        if (slot == lastUpgradedSlot) sameSlotTaps++ else { lastUpgradedSlot = slot; sameSlotTaps = 1 }

        lastUpgradeTap = now
        val (x, y) = com.example.mlbbresearch.vision.ScreenReader.plusPos[slot] ?: Layout.upgradePoint(slot)
        perform(listOf(Act.Tap(x, y, 0L, 55L)))
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
        if (System.currentTimeMillis() < holdUntil && acts.none {
                it is Act.SkillAim || (it is Act.Tap && it.holdMs >= CastTiming.TAP_HOLD_MS) || (it is Act.Drag && it.ms > 400) || it is Act.Joy || it is Act.JoyRelease
            }) return
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

    fun cancelAll() {
        generation++
        main.removeCallbacksAndMessages(null)
        release()
        QuickBuySwap.busyUntil = 0L
        lastDecision = "stopped"
        lastTarget = "-"
    }
}

object QuickBuySwap {
    @Volatile var busyUntil = 0L
    @Volatile var status = "idle"
    private var lastTrigger = 0L

    private val order = listOf(
        "itemImmortality" to "Immortality",
        "itemWinterCrown" to "Winter Crown",
        "itemRoseGold" to "Rose Gold Meteor",
        "itemGuardian" to "Guardian Helmet"
    )
    private val waitMs = mapOf("Immortality" to 1500L, "Winter Crown" to 400L, "Rose Gold Meteor" to 1500L, "Guardian Helmet" to 1500L)

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
