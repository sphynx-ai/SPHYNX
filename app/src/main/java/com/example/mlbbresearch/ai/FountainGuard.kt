package com.example.mlbbresearch.ai

import kotlin.math.hypot

// THE ENEMY FOUNTAIN IS OFF LIMITS (owner, 2026-11-11: "the research player should not enter the enemy
// fountain whatever mode it is"). Applied to every decision the Research player sends, last, in every mode
// (fights, laning, jungle, scenery calibration, the walk back to base...).
// The enemy fountain is the top-right corner of the minimap (your base is bottom-left; the enemy base core
// sits at about 0.87, 0.10 - owner's minimap screenshot, 2026-11-10). In minimap fractions:
//  - inside the zone: walk straight back out, no skills;
//  - near its edge: no step toward the fountain (the part of the move toward it is removed, so the hero
//    slides along the edge) and no skills (no dash or chase can carry it in).
// The joystick directions use the minimap's axes (x right, y down), like the rest of the engine.
// Pure logic, unit tested.
object FountainGuard {

    const val CX = 0.955f          // the enemy fountain (minimap fractions)
    const val CY = 0.045f
    const val RADIUS = 0.075f      // the core (0.87, 0.10) stays outside: 0.10 away
    const val MARGIN = 0.045f      // the edge band where no step may lead in

    // (moveX, moveY, castsAllowed, why) for a hero at (mx, my) wanting to move (dx, dy); null = unchanged
    fun guard(mx: Float, my: Float, dx: Float, dy: Float): Triple<Float, Float, String>? {
        val ox = CX - mx; val oy = CY - my
        val d = hypot(ox, oy)
        if (d >= RADIUS + MARGIN) return null
        val ux = if (d > 1e-4f) ox / d else 0.707f; val uy = if (d > 1e-4f) oy / d else -0.707f   // toward the fountain
        if (d < RADIUS) return Triple(-ux, -uy, "inside the enemy fountain zone: walking out")
        val toward = dx * ux + dy * uy
        if (toward <= 0f) return Triple(dx, dy, "next to the enemy fountain: no skills")
        val tx = dx - toward * ux; val ty = dy - toward * uy           // the move along the edge
        val l = hypot(tx, ty)
        return if (l < 0.25f) Triple(-ux, -uy, "next to the enemy fountain: stepping back")
        else Triple(tx / l, ty / l, "next to the enemy fountain: along its edge, not in")
    }

    fun apply(s: com.example.mlbbresearch.game.GameState, d: TacticalDecision): TacticalDecision {
        val me = s.minimapSelf ?: return d
        val (mx, my, why) = guard(me.first, me.second, d.moveX, d.moveY) ?: return d
        return TacticalDecision(d.intent, mx, my, emptyList(), -1f, -1f, d.targetLabel, -1f, -1, "${d.reason} ($why)")
    }
}
