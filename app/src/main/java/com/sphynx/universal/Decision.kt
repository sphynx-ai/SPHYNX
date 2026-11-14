package com.sphynx.universal

// THE SHARED DECISION FORMAT (Stage 2, step 3 of docs/ARCHITECTURE_AUDIT.md).
// Every engine - the strategic model, the small tactical model, the Kotlin rules, the learned
// networks - states its recommendation in this one format, so recommendations can be checked
// (DecisionValidator) and conflicts settled (DecisionArbiter) the same way for every game.
// This is ADVICE: nothing here sends input to a game.

enum class DecisionKind(val label: String) {
    ENGAGE("fight now (full commitment)"),
    POKE("harass from range"),
    FARM("keep farming"),
    RETREAT("back off"),
    GROUP("regroup with allies"),
    TAKE_OBJECTIVE("take an objective"),
    ROTATE("move to another zone"),
    DEFEND("defend a structure"),
    RECALL("return to base"),
    DEFER("no opinion - leave it to the other engines");

    // choices that commit you to a fight
    val aggressive: Boolean get() = this == ENGAGE || this == POKE || this == TAKE_OBJECTIVE
}

enum class EngineSource { STRATEGIC, TACTICAL, RULES, LEARNED }

data class Decision(
    val kind: DecisionKind,
    val source: EngineSource,
    val createdAtMs: Long,
    val ttlMs: Long,                       // how long the advice stays valid
    val confidence: Float? = null,         // 0..1, null = the engine gave none (never assumed)
    val target: String? = null,            // zone / objective / character id in the adapter's terms
    val rationale: String = ""
) {
    fun expired(nowMs: Long): Boolean = nowMs - createdAtMs > ttlMs
}

// the capability an engine's advice depends on
fun EngineSource.capability(): Capability? = when (this) {
    EngineSource.STRATEGIC -> Capability.STRATEGIC_ADVICE
    EngineSource.TACTICAL -> Capability.TACTICAL_ADVICE
    EngineSource.RULES, EngineSource.LEARNED -> null
}
