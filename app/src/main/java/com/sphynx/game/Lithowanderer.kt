package com.sphynx.game

import kotlin.math.hypot

// THE LITHOWANDERER AND WHAT IT LEAVES (owner, 2026-11-11 - see JungleDb.LITHOWANDERER for the owner's notes):
//  - alive it is a NEUTRAL monster (a target like a camp): it spawns at 0:35 in the river by the mid lane,
//    on either side - but ONLY ONE at a time (owner, 2026-11-11) - and comes back 120 s after its kill; it
//    never attacks and runs away;
//  - killed, it becomes the Walkie Grass (follows the killer; no bar, so never a unit here) and the
//    Stone Roamer: a FRIENDLY patrol for 45 s with a blue bar. It is NOT an ally hero: it must not count as
//    backup in a fight, and not as an ally minion either (that could lure the hero under a tower).
// It is told by WHERE and WHEN (minimap fractions, your icon - see JungleSpots for every monster's spot):
//  - a camp-sized bar at a Lithowanderer spot after 0:35 = it;
//  - it is "killed" when it vanishes within 2.5 s of being seen at low HP; for 45 s after that, near the
//    spot, a blue "ally hero" on the screen with no ally icon around you on the minimap is the Stone Roamer.
// The Stone Roamer's bar (owner's screenshot, 2026-11-11): BLUE, segmented, about 0.067 of the screen wide,
// with no level badge - narrower than a hero's, so it can be read as an ally hero OR an ally minion.
// Pure logic, unit tested.
object Lithowanderer {

    // its two spots in the RIVER, either side of the mid crossing (the river runs top-left -> bottom-right).
    // Measured on the owner's screenshots (2026-11-11): the hero at 0.38,0.37 fighting it, and the Stone
    // Roamer at 0.39,0.39 after the kill; the other side is the mirror.
    val SPOTS = listOf(0.38f to 0.37f, 0.62f to 0.63f)
    const val NEAR_SPOT = 0.09f
    const val FIRST_SEC = 35
    const val ROAMER_MS = 45_000L
    const val ROAMER_NEAR = 0.15f
    const val LOW_HP = 0.2f
    const val KILL_GAP_MS = 2500L
    const val RESPAWN_MS = 120_000L

    // positions come in on THIS match's map; the spots are on the usual map (MapSide). The two spots are each
    // other's mirror, so this only matters for which one is which.
    private fun usual(me: Pair<Float, Float>) = MapSide.view(me)

    fun spotNear(me: Pair<Float, Float>?): Pair<Float, Float>? =
        me?.let { usual(it) }?.let { m -> SPOTS.firstOrNull { hypot(it.first - m.first, it.second - m.second) <= NEAR_SPOT } }

    // ONLY ONE AT A TIME (owner, 2026-11-11): once it is seen alive at one spot, the other spot has none until
    // it is killed; after a kill there is none anywhere for 120 s (then it may come back on either side).
    // nowMs = 0: no memory used (only the spot and the time of the match).
    fun couldBeAt(spot: Pair<Float, Float>, nowMs: Long): Boolean {
        if (nowMs <= 0L) return true
        if (killedAt > 0L && nowMs - killedAt in 0 until RESPAWN_MS) return false
        val alive = aliveSpot ?: return true
        if (nowMs - seenAt > RESPAWN_MS) return true          // not seen for long: it may be anywhere again
        return alive == spot
    }

    // is an unnamed jungle monster seen now the Lithowanderer?
    fun isIt(me: Pair<Float, Float>?, matchSec: Int, nowMs: Long = 0L): Boolean {
        if (matchSec < FIRST_SEC) return false
        val spot = spotNear(me) ?: return false
        return couldBeAt(spot, nowMs)
    }

    // where the jungle route should look for it (usual map): where it was last seen alive, else the first
    // spot; null = none up now (killed less than 120 s ago, or not spawned yet)
    fun expectedSpot(nowMs: Long, matchSec: Int): Pair<Float, Float>? {
        if (matchSec < FIRST_SEC) return null
        if (killedAt > 0L && nowMs - killedAt in 0 until RESPAWN_MS) return null
        val alive = aliveSpot
        return if (alive != null && nowMs - seenAt <= RESPAWN_MS) alive else SPOTS[0]
    }

    @Volatile var killedAt = 0L; private set
    @Volatile var killSpot: Pair<Float, Float>? = null; private set
    private var lowAt = 0L
    private var lowSpot: Pair<Float, Float>? = null
    private var seenAt = 0L
    @Volatile var aliveSpot: Pair<Float, Float>? = null; private set     // where it is up now (usual map)

    @Synchronized fun reset() { killedAt = 0L; killSpot = null; lowAt = 0L; lowSpot = null; seenAt = 0L; aliveSpot = null }

    // every frame: is the Lithowanderer on screen now, with what HP (null = not seen)
    @Synchronized fun observe(nowMs: Long, me: Pair<Float, Float>?, hp: Float?) {
        if (hp != null) {
            seenAt = nowMs
            spotNear(me)?.let { aliveSpot = it }
            if (hp <= LOW_HP) { lowAt = nowMs; lowSpot = spotNear(me) ?: lowSpot }
            return
        }
        // gone: killed if it was low a moment ago (and this kill is not counted yet)
        if (lowAt > killedAt && nowMs - lowAt in 0..KILL_GAP_MS && nowMs - seenAt <= KILL_GAP_MS) {
            killedAt = nowMs; killSpot = lowSpot; aliveSpot = null
        }
    }

    fun roamerActive(nowMs: Long, me: Pair<Float, Float>?): Boolean {
        val spot = killSpot ?: return false
        val m = me?.let { usual(it) } ?: return false
        return nowMs - killedAt in 0..ROAMER_MS && hypot(spot.first - m.first, spot.second - m.second) <= ROAMER_NEAR
    }

    // right at the spot where it was killed (the river, away from the lanes)
    fun atSpot(me: Pair<Float, Float>?): Boolean {
        val spot = killSpot ?: return false
        val m = me?.let { usual(it) } ?: return false
        return hypot(spot.first - m.first, spot.second - m.second) <= 0.10f
    }

    // how many of the blue "ally heroes" on the screen are real: as many as the ally icons the minimap shows
    // around you (the rest is the Stone Roamer). Returns how many to drop.
    fun roamerCount(screenAllyHeroes: Int, minimapAlliesNear: Int): Int = (screenAllyHeroes - minimapAlliesNear).coerceAtLeast(0)
}
