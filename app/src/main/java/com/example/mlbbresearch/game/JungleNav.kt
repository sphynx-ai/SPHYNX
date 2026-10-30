package com.example.mlbbresearch.game

import com.example.mlbbresearch.vision.Camp
import kotlin.math.hypot

// Jungle route from the minimap: walks to your nearest jungle camp that is still up
// (buffs first in the opening), farms it (the tactical engine takes over once the monsters
// are on screen), then moves on. A camp you reached and saw no monster at for a few seconds
// is skipped for a minute. If your icon stops moving (stuck on a wall) it steps sideways.
object JungleNav {

    private var target: Camp? = null
    private var arrivedAt = 0L
    private val skipped = HashMap<Pair<Int, Int>, Long>()   // camp grid cell -> skip until
    private var lastPos: Pair<Float, Float>? = null
    private var lastPosAt = 0L
    private var lastMoveAt = 0L
    private var sidestepUntil = 0L
    private var sidestepSign = 1f
    private var lastSelf: Pair<Float, Float>? = null
    private var lastSelfAt = 0L

    @Volatile var status = "-"
        private set

    fun reset() {
        target = null
        skipped.clear()
        lastPos = null
        status = "-"
    }

    private fun cell(c: Camp) = (c.x * 12).toInt() to (c.y * 12).toInt()

    // direction to walk (-1..1) and why, or null when the minimap gives nothing to go on
    fun direction(s: GameState): Triple<Float, Float, String>? {
        val now = s.ts
        // your icon may blink out for a moment (e.g. low HP shrinks the green ring)
        val me = s.minimapSelf?.also { lastSelf = it; lastSelfAt = now }
            ?: lastSelf?.takeIf { now - lastSelfAt < 3000 } ?: run { status = "your minimap icon is not visible"; return null }

        skipped.entries.removeAll { it.value < now }
        val camps = s.camps.filter { (skipped[cell(it)] ?: 0L) < now }
        if (camps.isEmpty()) {
            target = null
            status = "no jungle camp up: heading to the river"
            return toward(me, 0.45f, 0.55f, "to the river (no camp up)")
        }
        // keep the current target while it is still shown; else pick the nearest
        // (buffs first during the first 90 seconds)
        val keep = target?.let { t -> camps.minByOrNull { hypot(it.x - t.x, it.y - t.y) }?.takeIf { hypot(it.x - t.x, it.y - t.y) < 0.05f } }
        val opening = s.matchSec in 0..90
        val t = keep ?: camps.minByOrNull { hypot(it.x - me.first, it.y - me.second) - if (opening && it.buff) 0.3f else 0f }!!
        if (t !== keep) { target = t; arrivedAt = 0L }

        val d = hypot(t.x - me.first, t.y - me.second)
        if (d < 0.06f) {
            // at the camp: the monsters should be on screen; if not, it is already taken
            if (arrivedAt == 0L) arrivedAt = now
            if (s.creeps.isEmpty() && now - arrivedAt > 3500) {
                skipped[cell(t)] = now + 60_000
                target = null
                status = "camp empty, next one"
                return null
            }
            status = "at a ${if (t.buff) "buff" else "camp"}, waiting for the monsters"
            return Triple(0f, 0f, "waiting at the camp")
        }

        // stuck on a wall: icon has not moved for 2.5 s while walking -> step sideways
        val lp = lastPos
        if (lp == null || hypot(me.first - lp.first, me.second - lp.second) > 0.012f) {
            lastPos = me; lastPosAt = now; lastMoveAt = now
        } else if (now - lastMoveAt > 2500 && now > sidestepUntil) {
            sidestepUntil = now + 1200
            sidestepSign = -sidestepSign
            lastMoveAt = now
        }
        val (dx, dy) = norm(t.x - me.first, t.y - me.second)
        if (now < sidestepUntil) {
            status = "stuck, stepping around"
            val (sx, sy) = norm(dx - dy * sidestepSign * 1.5f, dy + dx * sidestepSign * 1.5f)
            return Triple(sx, sy, "going around a wall")
        }
        status = "walking to a ${if (t.buff) "buff" else "camp"} (${(d * 100).toInt()}% of the map away)"
        return Triple(dx, dy, "to a jungle ${if (t.buff) "buff" else "camp"}")
    }

    private fun toward(me: Pair<Float, Float>, x: Float, y: Float, why: String): Triple<Float, Float, String>? {
        if (hypot(x - me.first, y - me.second) < 0.05f) return null
        val (dx, dy) = norm(x - me.first, y - me.second)
        return Triple(dx, dy, why)
    }

    private fun norm(x: Float, y: Float): Pair<Float, Float> {
        val l = hypot(x, y)
        return if (l < 1e-4f) 0f to 0f else (x / l) to (y / l)
    }
}
