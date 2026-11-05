package com.example.mlbbresearch.controller

import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.game.Damage
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.HeroDb

// ACTIVE ITEMS the AI presses by itself (v36.57). ONE button (right side, under the quick-buy item;
// your screenshots) shows the active of the item you own, and its icon tells which:
//   Frozen (Winter Crown, blue)   - immune to damage but you cannot act: only when very low, enemy on you
//   Wind Chant (Wind of Nature, teal) - a short immunity to physical damage: low HP, physical enemies on you
//   Conceal (roaming boots, purple)   - you and nearby allies go concealed: to sneak up on a fight, or to
//                                       slip away when hurt
// Pressed only while the screen shows the button lit (owned and ready). The low-HP Winter Crown swap of
// the quick-buy keeps working as before.
object ActiveItems {

    enum class Kind { NONE, FROZEN, GUST, CONCEAL }
    @Volatile var kind = Kind.NONE; private set
    @Volatile var lastNote = "-"; private set
    @Volatile private var lastPress = 0L
    val itemLit: Boolean get() = kind != Kind.NONE

    // every few frames: which active shows on the button (measured on your screenshots:
    // Conceal 78 purple points, Frozen 140 blue, Wind Chant 49 teal; no item: none)
    fun observe(px: IntArray, w: Int, h: Int) {
        val (fx, fy) = Layout.get("activeItem")
        val cx = (fx * w).toInt(); val cy = (fy * h).toInt(); val step = maxOf(2, w / 542)
        var n = 0; var purple = 0; var teal = 0; var blue = 0
        for (dx in -8..8) for (dy in -8..8) {
            val x = cx + dx * step; val y = cy + dy * step
            if (x !in 0 until w || y !in 0 until h) continue
            val p = px[y * w + x]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (maxOf(r, g, b) - minOf(r, g, b) <= 60 || maxOf(r, g, b) <= 100) continue
            n++
            if (r > 120 && b > 140 && g < 120) purple++
            if (g > 120 && b > 120 && r < 110) teal++
            if (b > 150 && g < 150 && r < 110) blue++
        }
        kind = when {
            n < 40 -> Kind.NONE
            purple >= 40 -> Kind.CONCEAL
            blue >= 90 -> Kind.FROZEN
            teal >= 25 -> Kind.GUST
            else -> Kind.NONE
        }
    }

    // the button to press now, or null
    fun decide(d: TacticalDecision, s: GameState): String? {
        val now = System.currentTimeMillis()
        if (now - lastPress < 3000 || now < QuickBuySwap.busyUntil) return null
        val hp = s.selfHp
        val close = s.enemiesWithin(0.35f)
        val nearest = s.enemies.minOfOrNull { s.distToSelf(it.x, it.y) } ?: -1f
        when (kind) {
            Kind.NONE -> {}
            Kind.FROZEN -> if (hp in 0f..0.22f && close.isNotEmpty())
                return mark(now, "Frozen (Winter Crown) at ${(hp * 100).toInt()}% HP with an enemy on you")
            Kind.GUST -> {
                val physical = s.enemyTeam.isEmpty() || s.enemyTeam.count { HeroDb.damage(it) == Damage.PHYSICAL } * 2 >= s.enemyTeam.size
                if (physical && hp in 0f..0.35f && close.isNotEmpty())
                    return mark(now, "Wind Chant (Wind of Nature): physical enemies on you at ${(hp * 100).toInt()}% HP")
            }
            Kind.CONCEAL -> {
                val sneaking = (d.intent == Intent.ENGAGE || d.intent == Intent.AMBUSH) && nearest in 0.45f..1.0f
                val escaping = d.intent == Intent.RETREAT && hp in 0f..0.5f && nearest in 0f..0.6f
                if (sneaking || escaping) return mark(now, if (sneaking) "Conceal: sneaking up on the fight" else "Conceal: slipping away")
            }
        }
        return null
    }

    private fun mark(now: Long, why: String): String { lastPress = now; lastNote = why; return "activeItem" }
}
