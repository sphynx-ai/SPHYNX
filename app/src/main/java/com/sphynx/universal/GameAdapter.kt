package com.sphynx.universal

// One supported game. An adapter translates the game's own readings (type S) into the universal
// model and declares what it can provide. A new game should need a new adapter and its knowledge,
// not a rewrite of the AI.
interface GameAdapter<S> {
    val gameId: String
    val displayName: String
    val capabilities: CapabilitySet
    /** Raised when the adapter's knowledge, rules or mapping change meaning (docs/ADAPTER_GUIDE.md, versioning). */
    val adapterVersion: Int get() = 1
    /** The game's characters, roles, lanes, spells, items and objectives (submilestone 6). */
    val knowledge: GameKnowledge
    fun toUniversal(state: S): UniversalMatchState
}
