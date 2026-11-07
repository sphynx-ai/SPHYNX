package com.example.mlbbresearch.universal

// Settles several engines' recommendations for the same moment (never blindly runs contradictory
// advice):
//  1. every decision is validated; rejected ones are dropped (with their reasons);
//  2. advisory-only ones are kept for display but cannot win;
//  3. SAFETY FIRST: if one usable decision says RETREAT and another says ENGAGE / POKE /
//     TAKE_OBJECTIVE, RETREAT wins;
//  4. otherwise the most confident usable decision wins (no confidence counts as 0.5);
//     on a tie the strategic engine beats the rules, then the learned networks, then the
//     tactical model; DEFER never wins over a real recommendation.
object DecisionArbiter {

    data class Resolution(
        val chosen: Decision?,
        val advisory: List<Decision>,
        val rejected: List<Pair<Decision, List<String>>>,
        val note: String
    )

    private val sourceRank = mapOf(EngineSource.STRATEGIC to 0, EngineSource.RULES to 1, EngineSource.LEARNED to 2, EngineSource.TACTICAL to 3)

    fun resolve(decisions: List<Decision>, s: UniversalMatchState, nowMs: Long): Resolution {
        val accepted = ArrayList<Decision>(); val advisory = ArrayList<Decision>()
        val rejected = ArrayList<Pair<Decision, List<String>>>()
        for (d in decisions) {
            val v = DecisionValidator.validate(d, s, nowMs)
            when (v.outcome) {
                DecisionValidator.Outcome.ACCEPTED -> accepted.add(d)
                DecisionValidator.Outcome.ADVISORY_ONLY -> advisory.add(d)
                DecisionValidator.Outcome.REJECTED -> rejected.add(d to v.reasons)
            }
        }
        val real = accepted.filter { it.kind != DecisionKind.DEFER }
        if (real.isEmpty()) return Resolution(null, advisory, rejected, if (accepted.isEmpty()) "no usable advice" else "all engines deferred")
        val retreat = real.filter { it.kind == DecisionKind.RETREAT }
        if (retreat.isNotEmpty() && real.any { it.kind.aggressive }) {
            val pick = retreat.minWith(compareBy<Decision>({ -(it.confidence ?: 0.5f) }, { sourceRank[it.source] ?: 9 }))
            return Resolution(pick, advisory, rejected, "conflict: retreat over a fight (safety first)")
        }
        val pick = real.minWith(compareBy<Decision>({ -(it.confidence ?: 0.5f) }, { sourceRank[it.source] ?: 9 }))
        return Resolution(pick, advisory, rejected, "highest confidence")
    }
}
