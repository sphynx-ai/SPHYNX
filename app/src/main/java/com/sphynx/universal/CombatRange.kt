package com.sphynx.universal

import kotlin.math.hypot

// COMBAT RANGE (game-neutral; issue #2): where to stand when hitting a target, so the character is
// inside ITS OWN attack range and never walks out of a monster's aggro zone.
// Offsets are screen offsets from you to the target in screen-height units, with x already
// multiplied by the frame's aspect ratio (the same units as GameState.dist).
object CombatRange {

    // stop this far inside your attack range, so small position errors do not leave you just outside it
    const val REACH_FACTOR = 0.85f
    const val MIN_REACH = 0.08f

    // A monster fights back only inside the zone drawn on the ground around it (seen as a wide
    // ellipse because of the camera tilt). MLBB measurements on the owner's screenshots: about
    // 0.5 screen heights to each side and 0.22 up / down (TacticalEngine.inLeash).
    data class Leash(val halfWidth: Float = 0.5f, val halfHeight: Float = 0.22f)

    fun reach(attackRange: Float): Float = maxOf(attackRange * REACH_FACTOR, MIN_REACH)

    // 0 = on the monster, 1 = on the edge of its zone
    fun leashFraction(dx: Float, dy: Float, l: Leash = Leash()): Float {
        val a = dx / l.halfWidth; val b = dy / l.halfHeight
        return kotlin.math.sqrt(a * a + b * b)
    }

    data class Step(val moveX: Float, val moveY: Float, val inRange: Boolean, val note: String)

    // CONTACT (issue #2 device report: "stuck in its location but still casts skills"):
    // positions come from HP bars, so a big monster's body can stop you before the measured gap is
    // inside your range. Holding the joystick then cancels every basic attack. So: if you walk toward
    // the target and the gap stops shrinking for `windowMs`, you are in contact - stand still and
    // attack for `holdMs` (then try again, in case the target moved).
    class ContactDetector(
        private val windowMs: Long = 700L,
        private val minGain: Float = 0.015f,
        private val holdMs: Long = 1500L
    ) {
        private var startAt = 0L
        private var startD = 0f
        private var lastAt = 0L
        private var contactUntil = 0L
        private var watching = false

        fun reset() { startAt = 0L; startD = 0f; lastAt = 0L; contactUntil = 0L; watching = false }

        // d: the current gap; approaching: this decision walks toward the target
        fun inContact(nowMs: Long, d: Float, approaching: Boolean): Boolean {
            if (nowMs < contactUntil) {
                if (d > startD + 0.08f) { contactUntil = 0L } else return true   // it moved well away: approach again
            }
            if (!approaching || !watching || nowMs - lastAt > 400L || d > startD + 0.05f) {
                watching = approaching; startAt = nowMs; startD = d; lastAt = nowMs; return false
            }
            lastAt = nowMs
            if (d < startD - minGain) { startAt = nowMs; startD = d; return false }   // still getting closer
            if (nowMs - startAt >= windowMs) { contactUntil = nowMs + holdMs; startD = d; watching = false; return true }
            return false
        }
    }

    // Approach a target until it is within reach, then stand still and hit it.
    fun approach(dx: Float, dy: Float, attackRange: Float): Step {
        val d = hypot(dx, dy)
        val r = reach(attackRange)
        if (d <= r) return Step(0f, 0f, true, "in attack range")
        return Step(dx / d, dy / d, false, "closing to attack range")
    }

    // Any movement while fighting a monster: a step that would take you further out than `margin`
    // of the monster's zone is cancelled (you would pull it home and it would heal). `step` is how far
    // one decision moves you, in the same units. Moves that bring you back inside are always allowed.
    fun keepInLeash(dxToMonster: Float, dyToMonster: Float, moveX: Float, moveY: Float, step: Float = 0.06f,
                    margin: Float = 0.8f, l: Leash = Leash()): Pair<Float, Float> {
        if (moveX == 0f && moveY == 0f) return 0f to 0f
        // your position relative to the monster is -(dx, dy); moving changes it by (move * step)
        val nowF = leashFraction(-dxToMonster, -dyToMonster, l)
        val nextF = leashFraction(-dxToMonster + moveX * step, -dyToMonster + moveY * step, l)
        return if (nextF > margin && nextF > nowF) 0f to 0f else moveX to moveY
    }
}
