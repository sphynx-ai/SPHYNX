package com.example.mlbbresearch.game

import com.example.mlbbresearch.vision.Seen
import com.example.mlbbresearch.vision.Track
import kotlin.math.hypot

enum class Phase { UNKNOWN, LOBBY, DRAFT, LOADING, IN_GAME, DEAD, RESULT }

// One snapshot of the match, built 10-15 times a second from vision + what Gemma learned.
class GameState(
    val ts: Long,
    val phase: Phase,
    val self: Seen?,
    val selfHp: Float,                 // -1 when unknown
    val allies: List<Seen>,
    val enemies: List<Track>,
    val enemyMinions: List<Seen>,
    val allyMinions: List<Seen>,
    val minimapEnemies: Int,
    val aspect: Float,
    val ownHero: String?,
    val myTeam: List<String>,
    val enemyTeam: List<String>,
    val items: Int,                    // -1 unknown (read by the Builder)
    val gold: Int,                     // -1 unknown
    val itemsGoldAgeMs: Long
) {
    // distance in screen-height units, so ranges look the same on any phone
    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot((ax - bx) * aspect, ay - by)

    fun distToSelf(x: Float, y: Float): Float {
        val s = self ?: return 9f
        return dist(s.x, s.y, x, y)
    }

    fun enemiesWithin(r: Float): List<Track> = enemies.filter { distToSelf(it.x, it.y) <= r }
    fun alliesWithin(r: Float): List<Seen> = allies.filter { distToSelf(it.x, it.y) <= r }
    fun minionsWithin(r: Float): List<Seen> = enemyMinions.filter { distToSelf(it.x, it.y) <= r }

    fun summary(): String {
        val sb = StringBuilder()
        sb.append("phase=").append(phase.name.lowercase())
        if (selfHp >= 0) sb.append(", my HP ").append((selfHp * 100).toInt()).append("%")
        sb.append(", enemy heroes on screen ").append(enemies.size)
        if (enemies.isNotEmpty()) {
            sb.append(" (HP ").append(enemies.joinToString("/") { "${(it.hp * 100).toInt()}%" }).append(")")
        }
        sb.append(", allies near ").append(allies.size)
        sb.append(", enemy minions ").append(enemyMinions.size)
        sb.append(", enemies on minimap ").append(minimapEnemies)
        if (items >= 0) sb.append(", items ").append(items)
        if (gold >= 0) sb.append(", gold ").append(gold)
        return sb.toString()
    }

    companion object {
        val EMPTY = GameState(0, Phase.UNKNOWN, null, -1f, emptyList(), emptyList(), emptyList(), emptyList(),
            0, 1.78f, null, emptyList(), emptyList(), -1, -1, Long.MAX_VALUE)
    }
}
