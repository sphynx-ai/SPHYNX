package com.example.mlbbresearch.universal

// THE UNIVERSAL MOBA MODEL (Stage 2, step 1 of docs/ARCHITECTURE_AUDIT.md).
// A game-independent description of a match situation. Every game adapter translates its own
// readings into this, so the strategic / tactical engines can reason without knowing which game
// it is. Rules:
//  - no game-specific names or terms here (those belong in an adapter's gameSpecific map);
//  - an unknown value is null - never a guess, never a sentinel like -1;
//  - confidence is null when the source gives none (it is NOT assumed to be 1.0).
// Pure Kotlin, no Android types: unit tested on the JVM.

data class Vec2(val x: Float, val y: Float)

enum class MatchPhase {
    UNKNOWN,
    OUT_OF_MATCH,        // menus, lobby, queue
    CHARACTER_SELECT,    // draft / pick and ban
    LOADING,
    IN_MATCH,
    PLAYER_DEAD,         // in a match, waiting to respawn
    POST_MATCH,          // result / scoreboard after the match
    SPECTATING;          // watching someone else's match

    val inMatch: Boolean get() = this == IN_MATCH || this == PLAYER_DEAD
}

enum class Side { SELF, ALLY, ENEMY, NEUTRAL }

enum class UnitKind {
    CHARACTER,           // a hero / champion controlled by a player
    MINION,              // lane wave unit
    NEUTRAL_MONSTER,     // jungle camp
    MAJOR_OBJECTIVE,     // large neutral objective (boss)
    STRUCTURE            // tower / turret / base
}

data class UnitState(
    val side: Side,
    val kind: UnitKind,
    val characterId: String? = null,     // game adapter's id for the character / monster, if known
    val health: Float? = null,           // 0..1, null = not read
    val screenPos: Vec2? = null,         // fractions of the screen
    val mapPos: Vec2? = null,            // fractions of the minimap / map
    val velocity: Vec2? = null,          // screen fractions per second, null = not tracked
    val confidence: Float? = null,       // 0..1, null = the source gives none
    val gameSpecific: Map<String, String> = emptyMap()
)

data class ResourceState(
    val gold: Int? = null,
    val itemCount: Int? = null,
    val level: Int? = null,
    val levelProgress: Float? = null     // 0..1 towards the next level
)

data class UniversalMatchState(
    val gameId: String,
    val timestampMs: Long,
    val phase: MatchPhase,
    val matchTimeSec: Int? = null,
    val self: UnitState? = null,
    val units: List<UnitState> = emptyList(),    // everything else seen: allies, enemies, minions, neutrals, structures
    val resources: ResourceState = ResourceState(),
    val assignedZone: String? = null,            // the player's lane / zone / role id, in the adapter's terms
    val allyCharacters: List<String> = emptyList(),
    val enemyCharacters: List<String> = emptyList(),
    val gameSpecific: Map<String, String> = emptyMap(),
    val capabilities: CapabilitySet = CapabilitySet.NONE
) {
    fun units(side: Side, kind: UnitKind? = null): List<UnitState> =
        units.filter { it.side == side && (kind == null || it.kind == kind) }

    val enemyCharactersSeen: List<UnitState> get() = units(Side.ENEMY, UnitKind.CHARACTER)
    val allyCharactersSeen: List<UnitState> get() = units(Side.ALLY, UnitKind.CHARACTER)
}
