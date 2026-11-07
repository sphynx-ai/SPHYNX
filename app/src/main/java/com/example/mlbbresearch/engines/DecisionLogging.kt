package com.example.mlbbresearch.engines

import com.example.mlbbresearch.adapters.mlbb.MlbbAdapter
import com.example.mlbbresearch.ai.StrategicAdvisor
import com.example.mlbbresearch.ai.TacticalAdvisor
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.learning.FightOption
import com.example.mlbbresearch.universal.Decision
import com.example.mlbbresearch.universal.DecisionLog
import com.example.mlbbresearch.universal.EngineSource

// Hooks the shared decision format into the running app in LOGGING MODE ONLY: it reads what the
// engines advised and what was played, and never changes either. At most twice a second, only
// while a fight choice exists, and any error here is caught so it can never disturb play.
object DecisionLogging {

    const val EVERY_MS = 500L
    val log = DecisionLog()
    @Volatile private var lastAt = 0L
    @Volatile private var failed = false

    fun observe(s: GameState, brainChoice: FightOption?, played: FightOption?, nowMs: Long = System.currentTimeMillis()) {
        if (!Cfg.decisionLogOn || failed || played == null || nowMs - lastAt < EVERY_MS) return
        lastAt = nowMs
        try {
            val advice = ArrayList<Decision>()
            brainChoice?.let { advice.add(DecisionBridge.fromFight(it, EngineSource.LEARNED, nowMs)) }
            TacticalAdvisor.latestAdvice()?.let { (o, at) -> advice.add(DecisionBridge.fromFight(o, EngineSource.TACTICAL, at)) }
            DecisionBridge.fromPlan(StrategicAdvisor.plan)?.let { advice.add(it) }
            log.record(MlbbAdapter.toUniversal(s), advice, DecisionBridge.kind(played), nowMs)
            if (log.total % 200 == 0) Diag.info(log.summary())
        } catch (t: Throwable) {
            failed = true                     // stop logging for this run rather than risk the play loop
            Diag.error("Decision logging stopped", t)
        }
    }
}
