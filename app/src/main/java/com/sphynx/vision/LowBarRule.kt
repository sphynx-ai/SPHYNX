package com.sphynx.vision

// PHANTOM "3% HP" MONSTERS (owner, 2026-11-13, from that day's recording): small orange / red things next to dark
// ground (the emblems that circle your hero while you hold a buff - orange for the orange buff, purple for the
// purple buff, owner 2026-11-13 - and red damage numbers) were read as monster or minion HP bars with 3-5% left,
// and the Player then "finished the monster first" on empty ground. A real bar gets to 3% by going DOWN: it was
// seen there with more HP a moment before. So a monster / minion bar at 6% or less only counts where a bar with
// more HP was seen in the last 2 seconds. Turtle / Lord bars are not touched. One instance per screen reader.
// Pure logic, unit tested.
class LowBarRule {

    companion object {
        const val LOW = 0.06f            // HP at or under this needs an earlier, fuller sighting
        const val NEAR = 0.10f           // screen fractions (the camera moves a little while you walk)
        const val MEMORY_MS = 2000L
    }

    private class Spot(val x: Float, val y: Float, var at: Long)
    private val real = ArrayList<Spot>()

    // a bar with more than LOW was seen here now
    fun seen(x: Float, y: Float, hp: Float, nowMs: Long) {
        if (hp <= LOW) return
        val s = real.firstOrNull { near(it, x, y) }
        if (s != null) s.at = nowMs else real.add(Spot(x, y, nowMs))
    }

    // does a bar at this HP count? (call seen() for every bar of the frame first)
    fun counts(x: Float, y: Float, hp: Float, nowMs: Long): Boolean {
        real.removeAll { nowMs - it.at > MEMORY_MS }
        return hp > LOW || real.any { near(it, x, y) }
    }

    private fun near(s: Spot, x: Float, y: Float) = kotlin.math.abs(s.x - x) < NEAR && kotlin.math.abs(s.y - y) < NEAR
}
