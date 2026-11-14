package com.sphynx.universal

// What a game adapter can actually provide. Different MOBA games expose different information, so
// nothing is assumed: every capability has an explicit status (the owner's scale).
enum class SupportStatus(val label: String) {
    OPERATIONAL("Operational"),
    PARTIAL("Partially Operational"),
    EXPERIMENTAL("Experimental"),
    PLANNED("Planned"),
    UNSUPPORTED("Unsupported");

    // may other parts of the app rely on it (with their own checks)?
    val usable: Boolean get() = this == OPERATIONAL || this == PARTIAL
}

enum class Capability(val label: String) {
    GAME_RECOGNITION("Game recognition"),
    SCREEN_PHASE("Screen / phase recognition"),
    OWN_CHARACTER_ID("Own character identification"),
    UNIT_HEALTH("Health bars / characters on screen"),
    ENEMY_TRACKING("Enemy tracking across frames"),
    MINIMAP("Minimap analysis"),
    COOLDOWNS("Ability / cooldown state"),
    ITEMS_GOLD("Items / gold"),
    OBJECTIVES("Objective state"),
    STRATEGIC_ADVICE("Strategic assistance"),
    TACTICAL_ADVICE("Tactical assistance"),
    DRAFT_ADVICE("Draft advice"),
    BUILD_ADVICE("Build advice"),
    POST_MATCH("Post-match analysis")
}

// unlisted capabilities are UNSUPPORTED - support is never assumed
class CapabilitySet(private val statuses: Map<Capability, SupportStatus>) {
    fun status(c: Capability): SupportStatus = statuses[c] ?: SupportStatus.UNSUPPORTED
    fun usable(c: Capability): Boolean = status(c).usable
    fun all(): Map<Capability, SupportStatus> = Capability.values().associateWith { status(it) }

    companion object {
        val NONE = CapabilitySet(emptyMap())
    }
}
