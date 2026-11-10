package com.example.mlbbresearch.research

import com.example.mlbbresearch.learning.FightOption
import com.example.mlbbresearch.universal.Decision
import com.example.mlbbresearch.universal.DecisionArbiter
import com.example.mlbbresearch.universal.DecisionKind
import com.example.mlbbresearch.universal.UniversalMatchState

// Research mode, phase R3, the first experiment (pure logic, unit tested): in a VERIFIED PRACTICE
// match only, the DecisionArbiter settles the engines' advice and picks the fight option, instead of
// the app's normal order (fight network, then the small model, then the learning brain).
// Fail-closed, in this order:
//  - not allowed (research off, practice not verified, a human seen) -> the normal choice, untouched;
//  - no fight choice this moment (no enemy nearby) -> nothing to decide;
//  - the arbiter picks nothing, or a kind that is not a fight option (push, defend, ...) -> normal;
//  - the arbiter's option fails the app's own Kotlin checks (TacticalAdvisor.check) -> normal.
// Engines whose capability is Experimental stay advisory (the validator never lets them win). Since the owner's
// decision of 2026-11-09 the small tactical model is not Experimental for MLBB, so it competes like the others.
// This decides WHICH fight option is played; it adds no new kind of action.
class ArbiterFightExperiment(private val maxLog: Int = 30) {

    data class Outcome(val option: FightOption?, val changed: Boolean, val note: String)

    var choices = 0; private set               // moments the arbiter decided
    var changed = 0; private set               // ... where it played something other than the normal choice
    var keptByCheck = 0; private set           // ... where its pick failed the Kotlin checks
    private val log = ArrayDeque<String>()

    fun toOption(k: DecisionKind): FightOption? = when (k) {
        DecisionKind.ENGAGE -> FightOption.ALL_IN
        DecisionKind.POKE -> FightOption.POKE
        DecisionKind.FARM -> FightOption.FARM
        DecisionKind.RETREAT -> FightOption.RETREAT
        DecisionKind.GROUP -> FightOption.GROUP
        else -> null                            // not a fight option: the normal choice stands
    }

    @Synchronized fun choose(
        allowed: Boolean, normal: FightOption?, advice: List<Decision>, u: UniversalMatchState, nowMs: Long,
        kotlinCheck: (FightOption) -> String?
    ): Outcome {
        if (!allowed) return Outcome(normal, false, "not allowed")
        if (normal == null) return Outcome(null, false, "no fight choice")
        val r = DecisionArbiter.resolve(advice, u, nowMs)
        val pick = r.chosen?.let { toOption(it.kind) } ?: return Outcome(normal, false, "arbiter: ${r.note}")
        choices++
        if (pick == normal) return Outcome(normal, false, "arbiter agrees (${r.note})")
        kotlinCheck(pick)?.let { why ->
            keptByCheck++
            add("$nowMs kept ${normal.name}: arbiter's ${pick.name} failed the checks ($why)")
            return Outcome(normal, false, "arbiter's ${pick.name} failed the checks")
        }
        changed++
        add("$nowMs ${normal.name} -> ${pick.name} (${r.chosen?.source?.name?.lowercase()}, ${r.note})")
        return Outcome(pick, true, r.note)
    }

    @Synchronized fun summary(): String = "$choices arbiter choices, $changed changed, $keptByCheck kept by checks"
    @Synchronized fun recent(): List<String> = log.toList()
    @Synchronized fun reset() { choices = 0; changed = 0; keptByCheck = 0; log.clear() }

    private fun add(s: String) { if (log.size >= maxLog) log.removeFirst(); log.addLast(s) }
}
