package com.example.mlbbresearch.learning

import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.Phase

// Scores each saved decision 3 seconds later:
//   + target lost HP or disappeared while low (likely kill)
//   + you kept your HP
//   - you lost HP, - - you died
// The average score of fights adjusts how aggressive the AI is with that hero.
object PerformanceEvaluator {

    private class Pending(val id: Long, val at: Long, val selfHp: Float, val intent: Intent, val targetId: Int, val targetHp: Float, val hero: String)

    private val queue = ArrayList<Pending>()
    @Volatile var engageScore = 0f      // running average reward of ENGAGE decisions
        private set
    @Volatile var retreatScore = 0f
        private set
    @Volatile var evaluated = 0
        private set

    @Synchronized
    fun pending(id: Long, at: Long, s: GameState, d: TacticalDecision) {
        if (s.selfHp < 0f) return
        queue.add(Pending(id, at, s.selfHp, d.intent, d.targetId, d.targetHp, s.ownHero ?: "unknown"))
        if (queue.size > 200) queue.removeAt(0)
    }

    // called from the fast loop
    @Synchronized
    fun evaluateDue() {
        val now = System.currentTimeMillis()
        val s = GameStateTracker.state
        val it = queue.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (now - p.at < 3000) continue
            it.remove()
            val died = s.phase == Phase.DEAD
            val hpNow = if (s.selfHp >= 0f) s.selfHp else p.selfHp
            var reward = (hpNow - p.selfHp) * 1.0f
            if (died) reward -= 2f
            if (p.targetId >= 0) {
                val t = s.enemies.firstOrNull { e -> e.id == p.targetId }
                reward += if (t != null) (p.targetHp - t.hp) * 1.5f
                          else if (p.targetHp in 0f..0.3f) 1f       // vanished while low: probably a kill
                          else 0f
            }
            reward = reward.coerceIn(-3f, 3f)
            ExperienceRecorder.setReward(p.id, reward)
            evaluated++
            when (p.intent) {
                Intent.ENGAGE -> {
                    engageScore = engageScore * 0.9f + reward * 0.1f
                    adapt(p.hero)
                }
                Intent.RETREAT -> retreatScore = retreatScore * 0.9f + reward * 0.1f
                else -> {}
            }
        }
    }

    // Fights going badly -> play safer with this hero; going well -> a little bolder.
    private fun adapt(hero: String) {
        val bias = Cfg.learnedBias(hero)
        val next = when {
            engageScore < -0.15f -> bias - 0.01f
            engageScore > 0.15f -> bias + 0.01f
            else -> bias
        }.coerceIn(-0.25f, 0.25f)
        if (next != bias) Cfg.setLearnedBias(hero, next)
    }

    @Synchronized
    fun reset() {
        queue.clear()
        engageScore = 0f
        retreatScore = 0f
    }
}
