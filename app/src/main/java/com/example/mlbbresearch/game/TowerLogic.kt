package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.vision.MapTower
import com.example.mlbbresearch.vision.Track
import kotlin.math.hypot

// Towers, from the minimap: red = enemy, red cap on yellow = enemy tower with a gold bonus,
// blue (or blue cap on yellow) = yours. A tower shoots minions first, so:
//  - never walk into enemy tower range unless your minions are in there with you
//  - attack (push) the tower only when your minions are in range AND no enemy hero is seen
//  - never chase an enemy hero who is standing under their tower
//  - go and defend your tower when enemies are at it and it is close
// The game is won by destroying the enemy base: with nothing else to do the laner walks to the
// next enemy tower of its lane (bonus towers first), then the base.
object TowerLogic {

    // one screen height is about this much of the minimap (MLBB camera); used to place
    // enemies seen on screen onto the minimap
    private const val SCREEN_TO_MAP = 0.10f

    fun range(): Float = Cfg.towerRangePct / 100f

    private fun all(s: GameState): List<Pair<Float, Float>> = s.towers.map { it.pos } + listOfNotNull(s.enemyBase)

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
    fun minionsTanking(s: GameState): Boolean = s.allyMinions.size >= 2 && selfInRange(s, 0.04f)

    // safe to attack the tower right now
    fun canPush(s: GameState): Boolean = selfInRange(s, 0.02f) && minionsTanking(s) && s.enemies.isEmpty()

    // is this enemy (seen on screen) standing under one of their towers?
    fun enemyUnderTower(s: GameState, e: Track): Boolean {
        val me = s.minimapSelf ?: return false
        val self = s.self ?: return false
        val ex = me.first + (e.x - self.x) * s.aspect * SCREEN_TO_MAP
        val ey = me.second + (e.y - self.y) * SCREEN_TO_MAP
        return all(s).any { hypot(it.first - ex, it.second - ey) < range() }
    }

    // A walk that would enter tower range without minion cover is turned away / along the edge.
    // Returns the (possibly changed) direction and true if it was changed.
    fun guard(s: GameState, mx: Float, my: Float): Triple<Float, Float, Boolean> {
        if (!Cfg.towerGuard || (mx == 0f && my == 0f)) return Triple(mx, my, false)
        val n = nearest(s) ?: return Triple(mx, my, false)
        if (n.second > range() + 0.03f || canPush(s)) return Triple(mx, my, false)
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
    // bonus (yellow) towers first, then the nearest; the base when the lane is open.
    fun laneTarget(s: GameState, lane: Lane): MapTower? {
        fun laneOf(t: MapTower): Lane = when {
            t.y < 0.15f -> Lane.EXP
            t.x > 0.85f -> Lane.GOLD
            else -> Lane.MID
        }
        val mine = s.towers.filter { laneOf(it) == lane }
        val me = s.minimapSelf
        fun dist(t: MapTower) = if (me == null) 0f else hypot(t.x - me.first, t.y - me.second)
        val next = mine.minByOrNull { dist(it) - if (it.bonus) 0.25f else 0f }
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
