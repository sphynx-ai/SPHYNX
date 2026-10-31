package com.example.mlbbresearch.game

import com.example.mlbbresearch.vision.Camp
import com.example.mlbbresearch.vision.WallMap
import kotlin.math.hypot

// Jungle route from the minimap: walks to your nearest jungle camp that is still up
// (buffs first in the opening), farms it (the tactical engine takes over once the monsters
// are on screen), then moves on. A camp you reached and saw no monster at for a few seconds
// is skipped for a minute. Paths go around the walls seen on the minimap.
object JungleNav {

    private var target: Camp? = null
    private var arrivedAt = 0L
    private val skipped = HashMap<Pair<Int, Int>, Long>()   // camp grid cell -> skip until
    private var sawMonsterAt = 0L
    @Volatile var next: Camp? = null           // planned while the current camp is being fought
        private set
    @Volatile var campsKilled = 0
        private set
    private var lastSelf: Pair<Float, Float>? = null
    private var lastSelfAt = 0L

    @Volatile var status = "-"
        private set

    // true while standing at a camp whose icon is still on the minimap: keep attacking
    @Volatile var atCamp = false
        private set
    val atCampMs: Long get() = if (atCamp && arrivedAt > 0) System.currentTimeMillis() - arrivedAt else 0L
    val atBuff: Boolean get() = atCamp && target?.buff == true

    fun reset() {
        target = null
        skipped.clear()
        sawMonsterAt = 0L
        status = "-"
    }

    private fun cell(c: Camp) = (c.x * 12).toInt() to (c.y * 12).toInt()

    // direction to walk (-1..1) and why, or null when the minimap gives nothing to go on
    // maxDist: only camps this close (laners grabbing a camp between waves); jungler: any
    fun direction(s: GameState, maxDist: Float = 9f): Triple<Float, Float, String>? {
        val now = s.ts
        atCamp = false
        // your icon may blink out for a moment (e.g. low HP shrinks the green ring)
        val me = s.minimapSelf?.also { lastSelf = it; lastSelfAt = now }
            ?: lastSelf?.takeIf { now - lastSelfAt < 3000 } ?: run { status = "your minimap icon is not visible"; return null }

        skipped.entries.removeAll { it.value < now }
        val camps = s.camps.filter { (skipped[cell(it)] ?: 0L) < now && hypot(it.x - me.first, it.y - me.second) <= maxDist }
        // the target camp's icon is hidden under YOUR icon when you stand on it: it is not gone
        val hidden = target?.takeIf { t ->
            hypot(t.x - me.first, t.y - me.second) < 0.09f && camps.none { hypot(it.x - t.x, it.y - t.y) < 0.05f }
        }
        if (camps.isEmpty() && hidden == null && maxDist < 9f) return null
        if (camps.isEmpty() && hidden == null) {
            target = null
            status = "no jungle camp up: heading to the river"
            return toward(me, 0.45f, 0.55f, "to the river (no camp up)")
        }
        // keep the current target while it is still shown (or hidden under you); else pick the
        // nearest (buffs first during the first 90 seconds)
        val keep = hidden ?: target?.let { t -> camps.minByOrNull { hypot(it.x - t.x, it.y - t.y) }?.takeIf { hypot(it.x - t.x, it.y - t.y) < 0.05f } }
        val opening = s.matchSec in 0..90
        // nearest by WALKING distance (around walls), so the clear route has no back-tracking
        val t = keep ?: camps.minByOrNull { WallMap.distance(me, it.x to it.y) - if (opening && it.buff) 0.3f else 0f }!!
        if (t !== keep && t !== target) { target = t; arrivedAt = 0L; sawMonsterAt = 0L }

        val d = hypot(t.x - me.first, t.y - me.second)
        if (d < 0.07f) {
            // At the camp. The kill is confirmed by the MONSTER DETECTOR: monsters were seen on
            // screen and now none are left for 1.5 s. (The minimap icon is not used: your own
            // icon covers it.) If no monster was ever seen here, attack for 12 s and move on.
            if (arrivedAt == 0L) arrivedAt = now
            if (s.creeps.isNotEmpty() || s.boss != null) sawMonsterAt = now
            val killed = sawMonsterAt > 0 && now - sawMonsterAt > 1500
            // no monster here: leave fast (no standing around)
            val giveUp = if (sawMonsterAt > 0) now - arrivedAt > 40_000 else now - arrivedAt > 4_000
            // THINK AHEAD: while this camp dies, the next one is already chosen
            next = camps.filter { hypot(it.x - t.x, it.y - t.y) > 0.05f }
                .minByOrNull { WallMap.distance(t.x to t.y, it.x to it.y) }
            if (killed || giveUp) {
                skipped[cell(t)] = now + if (t.buff) 120_000 else 70_000     // respawn time
                arrivedAt = 0L
                sawMonsterAt = 0L
                if (killed) campsKilled++
                // go straight on to the camp planned while fighting
                val n = next
                target = n
                next = null
                if (n == null) {
                    status = if (killed) "camp killed, no other camp up" else "no monster found here"
                    return null
                }
                status = (if (killed) "camp killed" else "no monster here") + ", on to the next camp"
                val (dx, dy) = WallMap.direction(me, n.x to n.y) ?: norm(n.x - me.first, n.y - me.second)
                return Triple(dx, dy, "to the next jungle ${if (n.buff) "buff" else "camp"}")
            }
            atCamp = true
            status = "killing a ${if (t.buff) "buff" else "camp"} (${(now - arrivedAt) / 1000}s" +
                (if (sawMonsterAt > 0) ", monsters seen)" else ", looking for the monsters)")
            return Triple(0f, 0f, "killing the camp")
        }

        // walk around walls (path from the minimap wall map), straight if there is none yet
        val path = WallMap.direction(me, t.x to t.y)
        val (dx, dy) = path ?: norm(t.x - me.first, t.y - me.second)
        status = "walking to a ${if (t.buff) "buff" else "camp"} (${(d * 100).toInt()}% of the map away" +
            (if (path != null) ", around walls)" else ")")
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
