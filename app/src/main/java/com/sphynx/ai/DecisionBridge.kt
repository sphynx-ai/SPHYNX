package com.sphynx.ai

import com.sphynx.learning.FightOption
import com.sphynx.universal.Decision
import com.sphynx.universal.DecisionKind
import com.sphynx.universal.EngineSource

// Translates the app's existing engine outputs into the shared Decision format (read-only).
object DecisionBridge {

    const val FIGHT_TTL_MS = 1000L            // the tactical advisor's own validity window (TacticalAdvisor.VALID_MS)
    const val PLAN_TTL_MS = 120_000L          // a strategic plan is a direction for the next couple of minutes

    fun kind(o: FightOption): DecisionKind = when (o) {
        FightOption.ALL_IN -> DecisionKind.ENGAGE
        FightOption.POKE -> DecisionKind.POKE
        FightOption.FARM -> DecisionKind.FARM
        FightOption.RETREAT -> DecisionKind.RETREAT
        FightOption.GROUP -> DecisionKind.GROUP
        FightOption.RULES -> DecisionKind.DEFER
    }

    fun fromFight(o: FightOption, source: EngineSource, atMs: Long, confidence: Float? = null) =
        Decision(kind(o), source, atMs, FIGHT_TTL_MS, confidence, rationale = o.label)

    // Gemma's plan words (DecisionParser): farm | push | fight | retreat | group | defend.
    // The default plan (no Gemma answer yet) is not advice.
    fun fromPlan(p: StrategyPlan): Decision? {
        if (p.at <= 0L) return null
        val k = when (p.plan.trim().lowercase()) {
            "farm" -> DecisionKind.FARM
            "push" -> DecisionKind.TAKE_OBJECTIVE
            "fight" -> DecisionKind.ENGAGE
            "retreat" -> DecisionKind.RETREAT
            "group" -> DecisionKind.GROUP
            "defend" -> DecisionKind.DEFEND
            else -> return null
        }
        // Gemma gives no confidence; its 'aggression' is a style, not a confidence, so it is not used as one
        return Decision(k, EngineSource.STRATEGIC, p.at, PLAN_TTL_MS, null, rationale = p.note.take(120))
    }
}
