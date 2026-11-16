package com.sphynx.ai

// THE LAST CHECK ON EVERY DECISION (Phase 2, SM8). After the learned blends (the tactical network,
// "Play like me") and before the touches are sent, the decision is checked against the rules that
// must always hold, whatever model or blend produced it:
//  1. a safety decision of the rules (RETREAT, RECALL, REGEN) is never replaced by a blend;
//  2. the joystick is a finite direction inside -1..1 (a NaN or a huge number from a model is
//     treated as standing still / clamped);
//  3. a target position is finite (otherwise there is no target);
//  4. casts are named, and each button is pressed at most once per decision.
// The blends already respect these; this check makes them a guarantee. Game-agnostic, pure, unit tested.
object DecisionGuard {

    private val SAFETY = setOf(Intent.RETREAT, Intent.RECALL, Intent.REGEN)

    /** How many decisions this check had to correct since start-up (for the Debug data page and tests). */
    @Volatile var corrected = 0L
        private set

    /** [rules] = the rules engine's decision for this frame, [d] = the decision after the blends. */
    fun check(rules: TacticalDecision, d: TacticalDecision): TacticalDecision {
        if (rules.intent in SAFETY && d.intent != rules.intent) { corrected++; return sane(rules) }
        return sane(d)
    }

    /** Movement, target and casts made valid (unchanged object when already valid). */
    fun sane(d: TacticalDecision): TacticalDecision {
        val mx = axis(d.moveX); val my = axis(d.moveY)
        val tOk = d.targetX.isFinite() && d.targetY.isFinite()
        val tx = if (tOk) d.targetX else -1f; val ty = if (tOk) d.targetY else -1f
        val hp = if (d.targetHp.isFinite()) d.targetHp else -1f
        val casts = d.casts.filter { it.isNotBlank() }.distinct()
        if (mx == d.moveX && my == d.moveY && tx == d.targetX && ty == d.targetY && hp == d.targetHp && casts == d.casts) return d
        corrected++
        return TacticalDecision(d.intent, mx, my, casts, tx, ty, d.targetLabel, hp, d.targetId, d.reason)
    }

    private fun axis(v: Float): Float = if (!v.isFinite()) 0f else v.coerceIn(-1f, 1f)

    fun resetForTest() { corrected = 0 }
}
