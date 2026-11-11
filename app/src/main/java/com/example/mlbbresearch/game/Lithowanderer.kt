package com.example.mlbbresearch.game

import kotlin.math.hypot

// THE LITHOWANDERER AND WHAT IT LEAVES (owner, 2026-11-11 - see JungleDb.LITHOWANDERER for the owner's notes):
//  - alive it is a NEUTRAL monster (a target like a camp): it spawns at 0:35 in the river by the mid lane,
//    on either side, and comes back every 120 s; it never attacks and runs away;
//  - killed, it becomes the Walkie Grass (follows the killer; no bar, so never a unit here) and the
//    Stone Roamer: a FRIENDLY patrol for 45 s with a blue bar. It is NOT an ally hero: it must not count as
//    backup in a fight, and not as an ally minion either (that could lure the hero under a tower).
// No colour of its own is measured yet, so it is told by WHERE and WHEN (minimap fractions, your icon):
//  - a jungle monster that the colour check could not name, at a Lithowanderer spot after 0:35 = it;
//  - it is "killed" when it vanishes within 2.5 s of being seen at low HP; for 45 s after that, near the
//    spot, a blue "ally hero" on the screen with no ally icon around you on the minimap is the Stone Roamer.
// Pure logic, unit tested.
object Lithowanderer {

    // the two river spots by the mid lane (your side, the enemy's) - JungleNav / the calibration tour
    val SPOTS = listOf(0.39f to 0.62f, 0.61f to 0.38f)
    const val NEAR_SPOT = 0.09f
    const val FIRST_SEC = 35
    const val ROAMER_MS = 45_000L
    const val ROAMER_NEAR = 0.15f
    const val LOW_HP = 0.2f
    const val KILL_GAP_MS = 2500L

    fun spotNear(me: Pair<Float, Float>?): Pair<Float, Float>? =
        me?.let { m -> SPOTS.firstOrNull { hypot(it.first - m.first, it.second - m.second) <= NEAR_SPOT } }

    // is an unnamed jungle monster seen now the Lithowanderer?
    fun isIt(me: Pair<Float, Float>?, matchSec: Int): Boolean = matchSec >= FIRST_SEC && spotNear(me) != null

    @Volatile var killedAt = 0L; private set
    @Volatile var killSpot: Pair<Float, Float>? = null; private set
    private var lowAt = 0L
    private var lowSpot: Pair<Float, Float>? = null
    private var seenAt = 0L

    @Synchronized fun reset() { killedAt = 0L; killSpot = null; lowAt = 0L; lowSpot = null; seenAt = 0L }

    // every frame: is the Lithowanderer on screen now, with what HP (null = not seen)
    @Synchronized fun observe(nowMs: Long, me: Pair<Float, Float>?, hp: Float?) {
        if (hp != null) {
            seenAt = nowMs
            if (hp <= LOW_HP) { lowAt = nowMs; lowSpot = spotNear(me) ?: lowSpot }
            return
        }
        // gone: killed if it was low a moment ago (and this kill is not counted yet)
        if (lowAt > killedAt && nowMs - lowAt in 0..KILL_GAP_MS && nowMs - seenAt <= KILL_GAP_MS) {
            killedAt = nowMs; killSpot = lowSpot
        }
    }

    fun roamerActive(nowMs: Long, me: Pair<Float, Float>?): Boolean {
        val spot = killSpot ?: return false
        val m = me ?: return false
        return nowMs - killedAt in 0..ROAMER_MS && hypot(spot.first - m.first, spot.second - m.second) <= ROAMER_NEAR
    }

    // how many of the blue "ally heroes" on the screen are real: as many as the ally icons the minimap shows
    // around you (the rest is the Stone Roamer). Returns how many to drop.
    fun roamerCount(screenAllyHeroes: Int, minimapAlliesNear: Int): Int = (screenAllyHeroes - minimapAlliesNear).coerceAtLeast(0)
}
