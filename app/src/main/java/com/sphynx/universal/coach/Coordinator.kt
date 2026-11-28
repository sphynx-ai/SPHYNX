package com.sphynx.universal.coach

import com.sphynx.universal.Decision
import com.sphynx.universal.DecisionArbiter
import com.sphynx.universal.DecisionKind
import com.sphynx.universal.EngineSource
import com.sphynx.universal.UniversalMatchState

/**
 * PHASE 12, step C1: the coordinator (roadmap 6.1.2 C). It combines the engines' Decisions into one stream of coaching
 * recommendations, keeping the strategic horizon (the strategic model's plan) apart from the tactical one (the fight
 * engines: rules, learned networks, the tactical model). Conflicts are settled by the existing DecisionArbiter (safety
 * first, then confidence); when two engines disagree with nearly the same confidence and no rule decides, the coordinator
 * reports the conflict instead of advising. Weak, stale or conflicting evidence gives "no recommendation". The output is
 * ranked by urgency, confidence and relevance within a display budget. Pure, unit-tested. Advice only.
 */
object Coordinator {

    const val MIN_CONFIDENCE = 0.4f       // below this, an engine's advice is too weak to show
    const val TIE = 0.1f                  // two different advices this close in confidence, with no rule: a conflict
    const val BUDGET = 2                  // at most this many recommendations at once (the panel)

    class Output(val strategic: Recommendation?, val tactical: Recommendation?, val shown: List<Recommendation>,
                 val conflict: String?, val noAdvice: List<String>, val actionable: Recommendation?) {
        /** The coach's lines for the panel; "no recommendation" with the reason when there is none. */
        fun lines(): List<String> = shown.map { it.line() }.ifEmpty {
            listOf("COACH · no recommendation" + ((conflict ?: noAdvice.firstOrNull())?.let { " ($it)" } ?: ""))
        }
    }

    private fun horizonOf(d: Decision) = if (d.source == EngineSource.STRATEGIC) Recommendation.Horizon.STRATEGIC else Recommendation.Horizon.TACTICAL

    fun urgency(d: Decision, s: UniversalMatchState): Recommendation.Urgency {
        val hp = s.self?.health
        return when {
            d.kind == DecisionKind.RETREAT && hp != null && hp <= 0.3f -> Recommendation.Urgency.CRITICAL
            d.source != EngineSource.STRATEGIC && (d.kind == DecisionKind.RETREAT || d.kind == DecisionKind.ENGAGE) -> Recommendation.Urgency.HIGH
            d.source != EngineSource.STRATEGIC -> Recommendation.Urgency.MEDIUM
            d.kind == DecisionKind.DEFEND || d.kind == DecisionKind.TAKE_OBJECTIVE || d.kind == DecisionKind.RETREAT -> Recommendation.Urgency.MEDIUM
            else -> Recommendation.Urgency.LOW
        }
    }

    private fun record(d: Decision, s: UniversalMatchState, experimental: Boolean, note: String) = Recommendation(
        horizonOf(d), d.kind, urgency(d, s), d.confidence, d.source, d.createdAtMs, d.createdAtMs + d.ttlMs, d.target,
        listOfNotNull("from ${d.source.name.lowercase()}", d.rationale.takeIf { it.isNotBlank() }?.take(120), note.takeIf { it.isNotBlank() }),
        experimental)

    /** One horizon: the arbiter's pick, or why there is none. */
    private fun one(ds: List<Decision>, s: UniversalMatchState, now: Long): Triple<Recommendation?, String?, String?> {
        if (ds.isEmpty()) return Triple(null, null, "no advice")
        val r = DecisionArbiter.resolve(ds, s, now)
        val pick = r.chosen
        if (pick == null) {
            // advice the game's capabilities don't allow acting on can still be shown, marked experimental
            val adv = r.advisory.filter { it.kind != DecisionKind.DEFER && !it.expired(now) }
                .maxByOrNull { it.confidence ?: 0.5f }
            if (adv != null && (adv.confidence ?: 0.5f) >= MIN_CONFIDENCE) return Triple(record(adv, s, true, "advisory only"), null, null)
            return Triple(null, null, r.note + if (r.rejected.isNotEmpty()) " (${r.rejected.first().second.firstOrNull()})" else "")
        }
        if ((pick.confidence ?: 0.5f) < MIN_CONFIDENCE) return Triple(null, null, "advice too weak (${((pick.confidence ?: 0f) * 100).toInt()}%)")
        val safety = r.note.startsWith("conflict")                      // retreat over a fight: settled by the safety rule
        if (!safety && pick.source != EngineSource.RULES) {
            val rival = ds.filter { it !== pick && it.kind != pick.kind && it.kind != DecisionKind.DEFER && !it.expired(now) &&
                r.rejected.none { (d, _) -> d === it } && it.source != EngineSource.RULES }
                .maxByOrNull { it.confidence ?: 0.5f }
            if (rival != null && kotlin.math.abs((rival.confidence ?: 0.5f) - (pick.confidence ?: 0.5f)) < TIE)
                return Triple(null, "the engines disagree: ${pick.kind.name.lowercase()} vs ${rival.kind.name.lowercase()}", null)
        }
        return Triple(record(pick, s, false, if (safety) "safety first: retreat over a fight" else ""), null, null)
    }

    fun coordinate(decisions: List<Decision>, s: UniversalMatchState, nowMs: Long, budget: Int = BUDGET): Output {
        val (st, stConflict, stNone) = one(decisions.filter { it.source == EngineSource.STRATEGIC }, s, nowMs)
        val (ta, taConflict, taNone) = one(decisions.filter { it.source != EngineSource.STRATEGIC }, s, nowMs)
        val shown = listOfNotNull(ta, st)
            .sortedWith(compareByDescending<Recommendation> { it.urgency.ordinal }.thenByDescending { it.confidence ?: 0.5f }
                .thenBy { it.horizon.ordinal.let { h -> if (h == Recommendation.Horizon.TACTICAL.ordinal) 0 else 1 } })
            .take(budget)
        val conflict = listOfNotNull(taConflict, stConflict).firstOrNull()
        return Output(st, ta, shown, conflict, listOfNotNull(taNone?.let { "now: $it" }, stNone?.let { "strategy: $it" }),
            actionable = ta?.takeIf { !it.experimental })
    }
}
