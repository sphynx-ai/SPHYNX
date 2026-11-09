package com.example.mlbbresearch.universal

// Checks one recommendation against the current universal state. Game-neutral versions of the
// Kotlin checks the MLBB tactical advisor already used (TacticalAdvisor.check):
//  - REJECTED: the advice contradicts what is known (all in at very low health, fight with no
//    enemy in view, farm with nothing to farm, group with no ally in view, malformed / expired).
//  - ADVISORY ONLY: the advice is valid, but its engine's capability is not usable for this game
//    (an engine whose capability is Experimental for this game): it may be shown / logged, never acted on.
// Unknown values (null) never cause a rejection - only known contradictions do.
object DecisionValidator {

    const val LOW_HEALTH = 0.2f

    enum class Outcome { ACCEPTED, ADVISORY_ONLY, REJECTED }

    data class Verdict(val outcome: Outcome, val reasons: List<String>) {
        val usable: Boolean get() = outcome != Outcome.REJECTED
    }

    fun validate(d: Decision, s: UniversalMatchState, nowMs: Long): Verdict {
        val problems = ArrayList<String>()
        d.confidence?.let { if (it !in 0f..1f) problems.add("confidence $it outside 0..1") }
        if (d.ttlMs <= 0) problems.add("no validity period")
        if (d.expired(nowMs)) problems.add("expired")

        val inMatchKinds = d.kind != DecisionKind.DEFER
        if (inMatchKinds && s.phase != MatchPhase.UNKNOWN && !s.phase.inMatch) problems.add("${d.kind.name.lowercase()} outside a match (${s.phase.name.lowercase()})")

        val hp = s.self?.health
        val enemiesSeen = s.enemyCharactersSeen.isNotEmpty()
        when (d.kind) {
            DecisionKind.ENGAGE -> {
                if (hp != null && hp <= LOW_HEALTH) problems.add("engage at ${(hp * 100).toInt()}% health")
                if (s.capabilities.usable(Capability.UNIT_HEALTH) && !enemiesSeen) problems.add("engage with no enemy in view")
            }
            DecisionKind.POKE ->
                if (s.capabilities.usable(Capability.UNIT_HEALTH) && !enemiesSeen) problems.add("poke with no enemy in view")
            DecisionKind.FARM ->
                if (s.capabilities.usable(Capability.UNIT_HEALTH) &&
                    s.units(Side.ENEMY, UnitKind.MINION).isEmpty() && s.units(Side.NEUTRAL, UnitKind.NEUTRAL_MONSTER).isEmpty())
                    problems.add("farm with nothing to farm in view")
            DecisionKind.GROUP ->
                if (s.capabilities.usable(Capability.UNIT_HEALTH) && s.allyCharactersSeen.isEmpty()) problems.add("group with no ally in view")
            else -> {}
        }
        if (problems.isNotEmpty()) return Verdict(Outcome.REJECTED, problems)

        val cap = d.source.capability()
        if (cap != null && !s.capabilities.usable(cap))
            return Verdict(Outcome.ADVISORY_ONLY, listOf("${cap.label} is ${s.capabilities.status(cap).label} for ${s.gameId}"))
        return Verdict(Outcome.ACCEPTED, emptyList())
    }
}
