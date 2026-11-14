package com.sphynx.game

import com.sphynx.core.Cfg
import com.sphynx.vision.MapTower
import com.sphynx.vision.Track
import kotlin.math.hypot

// Towers, from the minimap: red = enemy, red/coral/gold-shielded = enemy outer tower with gold plating,
// blue (or blue cap on yellow) = yours. A tower shoots minions first, so:
//  - never walk into enemy tower range unless your minions are in there with you
//  - attack (push) the tower only when your minions are in range AND no enemy hero is seen
//  - never chase an enemy hero who is standing under their tower
//  - go and defend your tower when enemies are at it and it is close
// Tower range reduced to 5% of minimap (v37.16) so heroes don't stop far outside the true turret ring.
object TowerLogic {

    // Canonical enemy outer tower positions on the minimap (EXP outer, MID outer, GOLD outer)
    private val OUTER_TOWER_SPOTS = listOf(
        MapTower(0.275f, 0.042f, true),
        MapTower(0.606f, 0.404f, true),
        MapTower(0.933f, 0.714f, true)
    )
    private val destroyedOuterIdx = HashSet<Int>()
    private var lastMatchStart = 0L

    fun range(): Float = (Cfg.towerRangePct / 100f).coerceIn(0.035f, 0.085f)

    // Returns all standing enemy towers on the minimap, including fallback gold-plated outer towers in the first 4 minutes
    // if an icon/ping momentarily covers a standing outer tower on the minimap (v37.16).
    fun effectiveTowers(s: GameState): List<MapTower> {
        if (GameStateTracker.matchStartAt != lastMatchStart) {
            lastMatchStart = GameStateTracker.matchStartAt
            destroyedOuterIdx.clear()
        }
        val detected = s.towers.toMutableList()
        val me = s.minimapSelf
        for ((idx, spot) in OUTER_TOWER_SPOTS.withIndex()) {
            val seenNear = detected.any { hypot(it.x - spot.x, it.y - spot.y) < 0.065f }
            if (seenNear) continue
            // If we are standing right on the outer tower spot and neither the minimap nor the 3D TowerRing sees a tower, it is destroyed!
            if (me != null && hypot(me.first - spot.x, me.second - spot.y) < 0.038f &&
                s.towerRing.zone == com.sphynx.vision.TowerRing.Zone.NONE
            ) {
                destroyedOuterIdx.add(idx)
            }
            if (idx !in destroyedOuterIdx && s.matchSec in 0..240) {
                detected.add(spot)
            }
        }
        return detected
    }

    private fun all(s: GameState): List<Pair<Float, Float>> = effectiveTowers(s).map { it.pos } + listOfNotNull(s.enemyBase)

    fun nearest(s: GameState): Pair<Pair<Float, Float>, Float>? {
        val me = s.minimapSelf ?: return null
        val t = all(s).minByOrNull { hypot(it.first - me.first, it.second - me.second) } ?: return null
        return t to hypot(t.first - me.first, t.second - me.second)
    }

    fun selfInRange(s: GameState, margin: Float = 0f): Boolean {
        val n = nearest(s) ?: return false
        return n.second < range() + margin
    }

    // your minions are with you at the tower (seen on screen), so the tower shoots them, not you
    fun minionsTanking(s: GameState): Boolean = s.allyMinions.size >= 2 && selfInRange(s, 0.025f)

    // safe to attack the tower right now
    fun canPush(s: GameState): Boolean = selfInRange(s, 0.015f) && minionsTanking(s) && s.enemies.isEmpty()

    // is this enemy (seen on screen) standing under one of their towers?
    // Uses perspective-synchronized SceneryMap projection (v37.16) so 3D screen enemy positions match the minimap!
    fun enemyUnderTower(s: GameState, e: Track): Boolean {
        val me = s.minimapSelf ?: return false
        val self = s.self ?: return false
        val (ex, ey) = SceneryMap.projectToWorld(me, self, e.x, e.y)
        return all(s).any { hypot(it.first - ex, it.second - ey) < range() }
    }

    // A walk that would enter tower range without minion cover is turned away / along the edge.
    // Returns the (possibly changed) direction and true if it was changed.
    fun guard(s: GameState, mx: Float, my: Float): Triple<Float, Float, Boolean> {
        if (!Cfg.towerGuard || (mx == 0f && my == 0f)) return Triple(mx, my, false)
        val n = nearest(s) ?: return Triple(mx, my, false)
        if (n.second > range() + 0.012f || canPush(s)) return Triple(mx, my, false)
        val me = s.minimapSelf ?: return Triple(mx, my, false)
        val tx = n.first.first - me.first
        val ty = n.first.second - me.second
        val l = hypot(tx, ty).coerceAtLeast(1e-4f)
        if (n.second < range() && !minionsTanking(s)) return Triple(-tx / l, -ty / l, true)  // inside: get out
        val toward = (mx * tx + my * ty) / l
        if (toward <= 0.2f) return Triple(mx, my, false)                                      // along / away: fine
        if (minionsTanking(s)) return Triple(mx, my, false)
        val sx = -ty / l
        val sy = tx / l
        val side = if (mx * sx + my * sy >= 0) 1f else -1f
        return Triple(sx * side, sy * side, true)                                              // slide along the edge
    }

    // Next enemy tower of your lane (EXP = top edge, gold = right edge, mid = the rest),
    // bonus (gold-plated) outer towers first, then the nearest; the base when the lane is open.
    fun laneTarget(s: GameState, lane: Lane): MapTower? {
        // top edge / right edge / the rest, then which role lane that is (MapSide: Gold or EXP on top)
        fun laneOf(t: MapTower): Lane = MapSide.geo(when {
            t.y < 0.15f -> Lane.EXP
            t.x > 0.85f -> Lane.GOLD
            else -> Lane.MID
        })
        val mine = effectiveTowers(s).filter { laneOf(it) == lane }
        val me = s.minimapSelf
        fun dist(t: MapTower) = if (me == null) 0f else hypot(t.x - me.first, t.y - me.second)
        val next = mine.minByOrNull { dist(it) - if (it.bonus) 0.18f else 0f }
        return next ?: s.enemyBase?.let { MapTower(it.first, it.second, false) }
    }

    // One of your towers near you with enemy minions / heroes at it: go defend it
    fun towerToDefend(s: GameState): Pair<Float, Float>? {
        val me = s.minimapSelf ?: return null
        val enemies = s.enemyWaves
        return s.allyTowers.filter { t ->
            hypot(t.first - me.first, t.second - me.second) < 0.3f &&
                enemies.any { hypot(it.first - t.first, it.second - t.second) < range() * 1.3f }
        }.minByOrNull { hypot(it.first - me.first, it.second - me.second) }
    }
}
