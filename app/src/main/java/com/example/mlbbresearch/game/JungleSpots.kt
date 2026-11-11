package com.example.mlbbresearch.game

import kotlin.math.hypot

// WHERE EACH NEUTRAL MONSTER LIVES (owner's 20 jungle screenshots, 2026-11-11): the minimap spot where the
// owner's hero stood while fighting it, in minimap fractions (your base bottom-left). The enemy jungle is the
// same map turned half a turn: (1 - x, 1 - y). The river runs from the top-left pit to the bottom-right pit.
//   Thunder Fenrir (purple buff) 0.29,0.52 · Molten Fiend (orange buff) 0.46,0.77 · Lava Golem 0.49,0.70 ·
//   Fire Beetle 0.59,0.82 · Horned Lizard 0.21,0.40 · Scavenger Crab 0.79,0.72 and 0.20,0.25 (the river's
//   two ends) · Lithowanderer 0.38,0.37 and 0.62,0.63 (the river, either side of the mid crossing).
// A monster is named by the nearest spot of ITS kind (a buff bar is long, a camp bar short - JungleDb.kindOf),
// within NEAR of you; else by colour (JungleDb.buff / camp); else "jungle monster".
// Pure logic, unit tested.
object JungleSpots {

    const val NEAR = 0.09f

    class Spot(val monster: Monster, val x: Float, val y: Float)

    private fun both(m: Monster, x: Float, y: Float) = listOf(Spot(m, x, y), Spot(m, 1f - x, 1f - y))

    val BUFFS: List<Spot> = both(JungleDb.THUNDER_FENRIR, 0.29f, 0.52f) + both(JungleDb.MOLTEN_FIEND, 0.46f, 0.77f)

    val CAMPS: List<Spot> = both(JungleDb.LAVA_GOLEM, 0.49f, 0.70f) + both(JungleDb.FIRE_BEETLE, 0.59f, 0.82f) +
        both(JungleDb.HORNED_LIZARD, 0.21f, 0.40f) + both(JungleDb.CRAB, 0.79f, 0.72f) +
        Lithowanderer.SPOTS.map { Spot(JungleDb.LITHOWANDERER, it.first, it.second) }

    // the monster at your minimap position for a bar of this kind; null = no spot near
    fun at(me: Pair<Float, Float>?, kind: MonsterKind, matchSec: Int = 9999): Monster? {
        // (the table is the usual map: with the Gold lane on top this match's map is its mirror - MapSide)
        val m = me?.let { MapSide.view(it) } ?: return null
        val pool = when (kind) { MonsterKind.BUFF -> BUFFS; MonsterKind.CAMP, MonsterKind.RIVER -> CAMPS; else -> return null }
        val s = pool.minByOrNull { hypot(it.x - m.first, it.y - m.second) } ?: return null
        if (hypot(s.x - m.first, s.y - m.second) > NEAR) return null
        // the Lithowanderer only exists from 0:35
        if (s.monster == JungleDb.LITHOWANDERER && matchSec < Lithowanderer.FIRST_SEC) return null
        return s.monster
    }
}
