package com.example.mlbbresearch.game

import com.example.mlbbresearch.vision.Camp
import com.example.mlbbresearch.vision.WallMap
import kotlin.math.hypot

// Optimized Jungle route for heroes with Retribution equipped (v37.13):
//  - Pre-spawn (0:00..0:22): walks directly to the Purple Buff pit entrance (0.29, 0.52) so the Jungler
//    starts hitting the buff on frame 1 of spawn instead of wandering to the river.
//  - 6-camp rotation (owner's screenshots, 2026-11-11): Purple Buff -> Orange Buff -> Lava Golem -> Fire Beetle ->
//    Horned Lizard -> Lithowanderer (river),
//    using both live minimap camp dots and memorized SceneryMap pit entrances so occluded dots are never missed.
//  - Post-clear rotation: once all 6 ally camps are on cooldown, rotates to Turtle/Lord or gankable sidelanes.
object JungleNav {

    private var target: Camp? = null
    private var arrivedAt = 0L
    private val skipped = HashMap<Pair<Int, Int>, Long>()   // camp grid cell -> skip until
    private var sawMonsterAt = 0L
    private var lastMonsterHp = 1f        // the lowest HP bar seen at the current camp (issue #2)
    private var patrolIdx = 0
    @Volatile var next: Camp? = null           // planned while the current camp is being fought
        private set
    @Volatile var campsKilled = 0
        private set
    private var lastSelf: Pair<Float, Float>? = null
    private var lastSelfAt = 0L

    @Volatile var status = "-"
        private set

    // Your jungle camps (the pits, measured on the owner's 20 jungle screenshots, 2026-11-11 - SceneryMap /
    // JungleSpots), in the owner's own clear order from those screenshots: purple buff, orange buff, Lava Golem,
    // Fire Beetle, Horned Lizard, then the Lithowanderer in the river (from 0:35)
    private val ALLY_ROTATION = listOf(
        Camp(0.27f, 0.52f, buff = true, enemy = false),   // 1. Purple Buff (Thunder Fenrir)
        Camp(0.49f, 0.77f, buff = true, enemy = false),   // 2. Orange Buff (Molten Fiend)
        Camp(0.51f, 0.70f, buff = false, enemy = false),  // 3. Lava Golem
        Camp(0.61f, 0.82f, buff = false, enemy = false),  // 4. Fire Beetle
        Camp(0.18f, 0.41f, buff = false, enemy = false),  // 5. Horned Lizard
        Camp(0.41f, 0.38f, buff = false, enemy = false)   // 6. Lithowanderer (river)
    )

    // the rotation now: the Lithowanderer stop goes where it is up (only ONE is up at a time, on either side of
    // the river - owner, 2026-11-11) and is left out while none is up (killed less than 120 s ago)
    private fun rotation(nowMs: Long, matchSec: Int): List<Camp> {
        val litho = Lithowanderer.expectedSpot(nowMs, matchSec)
        val base = ALLY_ROTATION.dropLast(1)
        return when {
            litho == null -> base
            litho == Lithowanderer.SPOTS[0] -> ALLY_ROTATION
            else -> base + Camp(litho.first, litho.second, buff = false, enemy = false)
        }
    }

    // Gank / river patrol points when all 6 ally camps are on respawn cooldown
    private val GANK_PATROL = listOf(
        0.46f to 0.54f,   // Mid river bush
        0.76f to 0.78f,   // Gold lane river crab & gank bush
        0.34f to 0.30f,   // Turtle / top river bush
        0.16f to 0.22f    // EXP lane gank bush
    )

    // true while standing at a camp whose icon is still on the minimap: keep attacking
    @Volatile var atCamp = false
        private set
    val atCampMs: Long get() = if (atCamp && arrivedAt > 0) System.currentTimeMillis() - arrivedAt else 0L
    val atBuff: Boolean get() = atCamp && target?.buff == true
    val targetPos: Pair<Float, Float>? get() = target?.let { it.x to it.y }

    fun reset() {
        target = null
        skipped.clear()
        sawMonsterAt = 0L
        patrolIdx = 0
        status = "-"
    }

    private fun cell(c: Camp) = (c.x * 12).toInt() to (c.y * 12).toInt()

    // direction to walk (-1..1) and why, or null when the minimap gives nothing to go on
    // maxDist: only camps this close (laners grabbing a side camp between waves); jungler: 9f
    // allowBuffs: false for non-Retribution laners so they never steal the Jungler's Purple/Orange buffs!
    fun direction(s: GameState, maxDist: Float = 9f, allowBuffs: Boolean = true): Triple<Float, Float, String>? {
        val now = s.ts
        atCamp = false
        val me = s.minimapSelf?.also { lastSelf = it; lastSelfAt = now }
            ?: lastSelf?.takeIf { now - lastSelfAt < 3000 } ?: run { status = "your minimap icon is not visible"; return null }

        skipped.entries.removeAll { it.value < now }
        val isJungler = maxDist >= 1f && (s.lane == Lane.JUNGLE || s.spell == "Retribution")

        // 0) Pre-spawn opening (0:00..0:21): walk straight to starting Buff entrance (Purple Buff by default,
        // or Orange Buff if online/built-in meta for this hero prefers Red/Orange start!) so we hit frame 1 at 0:20! (v37.16)
        if (isJungler && s.matchSec in 0..21 && s.creeps.isEmpty()) {
            val orangeStart = RoleProfile.preferOrangeBuffStart(s.ownHero)
            val buffName = if (orangeStart) "Orange Buff" else "Purple Buff"
            // (pitStandSpot works on this match's map; the buff pits are given on the usual map - MapSide)
            val startStand = if (orangeStart) SceneryMap.pitStandSpot(MapSide.view(0.49f to 0.77f).first, MapSide.view(0.49f to 0.77f).second)
                else SceneryMap.pitStandSpot(MapSide.view(0.27f to 0.52f).first, MapSide.view(0.27f to 0.52f).second)
            val dStart = hypot(startStand.first - me.first, startStand.second - me.second)
            if (dStart < 0.042f) {
                status = "waiting at $buffName pit for 0:20 spawn"
                return Triple(0f, 0f, "at $buffName pit (waiting for spawn)")
            }
            val (dx, dy) = WallMap.direction(me, startStand) ?: norm(startStand.first - me.first, startStand.second - me.second)
            status = "opening meta route: walking to $buffName"
            return Triple(dx, dy, "to $buffName (opening meta route)")
        }

        var camps = s.camps.filter {
            !it.enemy && (allowBuffs || !it.buff) && (skipped[cell(it)] ?: 0L) < now && hypot(it.x - me.first, it.y - me.second) <= maxDist
        }

        // If Jungler sees no minimap camp dots (e.g. occluded by hero icons), check canonical 6 ally pits not on skip cooldown
        if (camps.isEmpty() && isJungler && s.matchSec >= 20) {
            camps = rotation(now, s.matchSec).map { c -> MapSide.view(c.x to c.y).let { (x, y) -> Camp(x, y, c.buff, c.enemy) } }.filter { (skipped[cell(it)] ?: 0L) < now && hypot(it.x - me.first, it.y - me.second) <= maxDist }
        }

        // the target camp's icon is hidden under YOUR icon when you stand on it: it is not gone
        val hidden = target?.takeIf { t ->
            (allowBuffs || !t.buff) && (skipped[cell(t)] ?: 0L) < now &&
                hypot(t.x - me.first, t.y - me.second) < 0.09f && camps.none { hypot(it.x - t.x, it.y - t.y) < 0.05f }
        }
        if (camps.isEmpty() && hidden == null && maxDist < 9f) return null
        if (camps.isEmpty() && hidden == null) {
            target = null
            val pt = GANK_PATROL[patrolIdx % GANK_PATROL.size]
            if (hypot(pt.first - me.first, pt.second - me.second) < 0.06f) {
                patrolIdx = (patrolIdx + 1) % GANK_PATROL.size
            }
            val goal = GANK_PATROL[patrolIdx % GANK_PATROL.size]
            status = "all camps cleared: rotating to river / gank bush"
            val (dx, dy) = WallMap.direction(me, goal) ?: norm(goal.first - me.first, goal.second - me.second)
            return Triple(dx, dy, "rotating to river / gank position (all camps on cooldown)")
        }

        val keep = hidden ?: target?.let { t -> camps.minByOrNull { hypot(it.x - t.x, it.y - t.y) }?.takeIf { hypot(it.x - t.x, it.y - t.y) < 0.05f } }
        val opening = s.matchSec in 0..105
        // Score camps by A* walking distance, prioritizing buffs and staying in the current jungle quadrant (top-left vs bottom-right)
        val inTopLeftQuadrant = me.first < 0.44f && me.second < 0.70f
        val t = keep ?: camps.minByOrNull { c ->
            val walkDist = WallMap.distance(me, SceneryMap.pitStandSpot(c.x, c.y))
            val buffBonus = if (c.buff) (if (opening) 0.34f else 0.18f) else 0f
            val sameQuadrantBonus = if (inTopLeftQuadrant == (c.x < 0.44f && c.y < 0.70f)) 0.06f else 0f
            walkDist - buffBonus - sameQuadrantBonus
        }!!
        if (t !== keep && t !== target) { target = t; arrivedAt = 0L; sawMonsterAt = 0L; lastMonsterHp = 1f }

        val stand = SceneryMap.pitStandSpot(t.x, t.y)
        val d = minOf(hypot(t.x - me.first, t.y - me.second), hypot(stand.first - me.first, stand.second - me.second))
        val creepInReach = s.creeps.any { s.distToSelf(it.x, it.y) <= 0.36f }
        if (d < 0.068f || creepInReach) {
            if (arrivedAt == 0L) arrivedAt = now
            if (s.creeps.isNotEmpty() || s.boss != null) {
                sawMonsterAt = now
                (s.creeps.map { it.hp } + listOfNotNull(s.boss?.hp)).minOrNull()?.let { lastMonsterHp = it }
            }
            val campIconUp = s.camps.any { !it.enemy && hypot(it.x - t.x, it.y - t.y) < 0.05f }
            val killed = campDone(sawMonsterAt, lastMonsterHp, now, campIconUp)
            val giveUp = if (sawMonsterAt > 0) now - arrivedAt > 38_000 else now - arrivedAt > 5_200
            next = camps.filter { hypot(it.x - t.x, it.y - t.y) > 0.05f }
                .minByOrNull { WallMap.distance(t.x to t.y, SceneryMap.pitStandSpot(it.x, it.y)) - if (it.buff) 0.14f else 0f }
            if (killed || giveUp) {
                skipped[cell(t)] = now + if (t.buff) 115_000 else 65_000
                arrivedAt = 0L
                sawMonsterAt = 0L
                lastMonsterHp = 1f
                if (killed) campsKilled++
                val n = next
                target = n
                next = null
                if (n == null) {
                    status = if (killed) "camp killed, rotating" else "camp on cooldown, moving on"
                    return null
                }
                status = (if (killed) "camp killed" else "camp empty") + ", moving to next ${if (n.buff) "buff" else "camp"}"
                val nextStand = SceneryMap.pitStandSpot(n.x, n.y)
                val (dx, dy) = WallMap.direction(me, nextStand) ?: norm(nextStand.first - me.first, nextStand.second - me.second)
                return Triple(dx, dy, "to the next jungle ${if (n.buff) "buff" else "camp"}")
            }
            atCamp = true
            status = "killing a ${if (t.buff) "buff" else "camp"} (${(now - arrivedAt) / 1000}s" +
                (if (sawMonsterAt > 0) ", monsters seen)" else ", starting attack on creep)")
            if (d < 0.044f || creepInReach || sawMonsterAt > 0) {
                return Triple(0f, 0f, "killing the camp")
            }
            val (stepX, stepY) = WallMap.direction(me, stand) ?: norm(stand.first - me.first, stand.second - me.second)
            return Triple(stepX, stepY, "stepping into ${if (t.buff) "buff" else "camp"} pit")
        }

        val path = WallMap.direction(me, stand)
        val (dx, dy) = path ?: norm(stand.first - me.first, stand.second - me.second)
        status = "walking to ${if (t.buff) "buff" else "camp"} (${(d * 100).toInt()}% away" +
            (if (path != null) ", around walls)" else ")")
        return Triple(dx, dy, "to a jungle ${if (t.buff) "buff" else "camp"}")
    }

    // Issue #2 (device test: the hero "left mid-fight" with "to the next jungle camp" on screen, and the
    // monster went home and healed): a camp used to count as killed after its HP bars were unseen for
    // 1.2 s - but skill effects and damage numbers hide them that long. Now it is done only when:
    //  - the bars are gone for 1.2 s AND the monster was last seen nearly dead (25% or less), or
    //  - the bars are gone for 1.2 s AND the camp's icon has left the minimap (it respawns later), or
    //  - nothing has been seen for 6 s (it really is gone, or we cannot see it at all).
    internal fun campDone(sawMonsterAt: Long, lastMonsterHp: Float, now: Long, campIconUp: Boolean): Boolean {
        if (sawMonsterAt <= 0L) return false
        val unseen = now - sawMonsterAt
        return unseen > 1200 && (lastMonsterHp <= 0.25f || !campIconUp) || unseen > 6000
    }

    private fun norm(x: Float, y: Float): Pair<Float, Float> {
        val l = hypot(x, y)
        return if (l < 1e-4f) 0f to 0f else (x / l) to (y / l)
    }
}
