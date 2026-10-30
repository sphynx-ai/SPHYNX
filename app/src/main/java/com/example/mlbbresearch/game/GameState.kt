package com.example.mlbbresearch.game

import com.example.mlbbresearch.vision.HudInfo
import com.example.mlbbresearch.vision.Seen
import com.example.mlbbresearch.vision.Track
import kotlin.math.hypot

// DRAFT = hero select (standard) or ban & pick (ranked).
// SHOP / SCOREBOARD / MAP = in a match, with the equipment screen, the Tab scoreboard or the big map open.
enum class Phase(val label: String) {
    UNKNOWN("unknown"), LOBBY("lobby"), DRAFT("hero select / draft"), LOADING("loading"), IN_GAME("in game"),
    DEAD("dead (respawning)"), SHOP("equipment shop open"), SCOREBOARD("scoreboard open"), MAP("big map open"),
    RESULT("result");

    // anything that happens during a match
    val inMatch: Boolean get() = this == IN_GAME || this == DEAD || this == SHOP || this == SCOREBOARD || this == MAP
}

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
    val itemsGoldAgeMs: Long,
    val hud: HudInfo = HudInfo.NONE
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
        sb.append("phase=").append(phase.label)
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
