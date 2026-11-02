package com.example.mlbbresearch.game

import kotlin.math.abs

// GAME STATE ENGINE: the big picture every role decision looks at.
//  - phase: EARLY (before 8:00, levels / farm / turtle), MID (8:00-15:00, lord appears, towers),
//    LATE (after 15:00, one fight can end the game)
//  - team advantage (-1 behind .. +1 ahead): towers still standing on each side (minimap), your
//    level against the enemy's, your kills / deaths / assists
//  - what that means: ahead = press (fights, towers, lord); behind = farm safely, defend, fight
//    only together; late game = stay with the team, objectives over farm
object MatchSense {

    enum class GamePhase(val label: String) { EARLY("early game"), MID("mid game"), LATE("late game") }

    fun phase(s: GameState): GamePhase = when {
        s.matchSec in 0..479 -> GamePhase.EARLY
        s.matchSec in 480..899 -> GamePhase.MID
        s.matchSec >= 900 -> GamePhase.LATE
        else -> GamePhase.EARLY
    }

    @Volatile var lastAdvantage = 0f
        private set
    @Volatile var status = "-"
        private set

    fun advantage(s: GameState): Float {
        var a = 0f
        val why = ArrayList<String>()
        // towers: 9 per side; every tower more than the enemy has is a real lead
        val ours = s.allyTowers.size; val theirs = s.towers.size
        if (ours + theirs > 0) {
            val t = ((ours - theirs) / 9f).coerceIn(-1f, 1f) * 0.5f
            a += t; if (abs(t) > 0.01f) why.add("towers $ours vs $theirs")
        }
        if (s.enemyLevel > 0) {
            val l = ((s.level - s.enemyLevel) / 5f).coerceIn(-1f, 1f) * 0.3f
            a += l; why.add("level ${s.level} vs ${s.enemyLevel}")
        }
        val kd = GameStateTracker.kills + GameStateTracker.assists / 2f - GameStateTracker.deaths
        val k = (kd / 6f).coerceIn(-1f, 1f) * 0.3f
        a += k
        why.add("K/D/A ${GameStateTracker.kills}/${GameStateTracker.deaths}/${GameStateTracker.assists}")
        val v = a.coerceIn(-1f, 1f)
        lastAdvantage = v
        status = "${phase(s).label}, " + (if (v > 0.15f) "ahead" else if (v < -0.15f) "behind" else "even") +
            " (%+.2f: ${why.joinToString()})".format(v)
        return v
    }
}
