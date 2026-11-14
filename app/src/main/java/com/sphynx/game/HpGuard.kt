package com.sphynx.game

// A SUDDEN 0% HP READING IS NOT BELIEVED (owner, 2026-11-12 - the 2026-11-11 recording: 94 frames read your HP as
// 0% while the bar was full, e.g. 0:30-0:44 at the Molten Fiend, where the buff's effects and bars covered it).
// A reading of ~0% right after a healthy one (15%+ within the last 1.5 s) is ignored - the last good reading is
// kept - unless the death screen shows, or the 0% persists for 1.5 s. Pure logic, unit tested.
class HpGuard {

    companion object {
        const val ZERO = 0.02f
        const val HEALTHY = 0.15f
        const val HOLD_MS = 1500L
    }

    private var lastGood = -1f
    private var lastGoodAt = 0L
    private var zeroSince = 0L

    // raw: the bar's reading (-1 = no bar); dead: the death screen is showing. Returns the HP to use.
    fun filter(raw: Float, now: Long, dead: Boolean): Float {
        if (raw < 0f) return raw
        if (raw > ZERO || dead) {
            zeroSince = 0L
            if (raw > ZERO) { lastGood = raw; lastGoodAt = now }
            return raw
        }
        if (zeroSince == 0L) zeroSince = now
        val sudden = lastGood >= HEALTHY && now - lastGoodAt <= HOLD_MS
        return if (sudden && now - zeroSince < HOLD_MS) lastGood else raw
    }

    fun reset() { lastGood = -1f; lastGoodAt = 0L; zeroSince = 0L }
}
