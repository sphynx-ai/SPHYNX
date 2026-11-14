package com.sphynx.ai

// BEING HIT? (owner, 2026-11-12 - the 2026-11-11 recording, 2:29: "regenerating" while taking hits). Your HP
// dropping by 3% or more within the last 1.5 s means something is hitting you - an enemy you cannot see, a
// tower, a monster - so standing still to regenerate is wrong. Pure logic, unit tested.
class HitWatch {

    companion object {
        const val WINDOW_MS = 1500L
        const val DROP = 0.03f
    }

    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun add(now: Long, hp: Float) {
        if (hp < 0f) return
        samples.addLast(now to hp)
        while (samples.isNotEmpty() && now - samples.first().first > WINDOW_MS) samples.removeFirst()
    }

    fun beingHit(): Boolean {
        val last = samples.lastOrNull()?.second ?: return false
        val top = samples.maxOfOrNull { it.second } ?: return false
        return top - last >= DROP
    }

    fun reset() = samples.clear()
}
