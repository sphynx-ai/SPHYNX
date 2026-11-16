package com.sphynx.universal

// WHAT A GAME IS MADE OF (Phase 2 submilestones 4 and 6): the characters, roles, lanes, spells, items and
// objectives of one game, as plain data. Universal code asks the game's adapter for them and never names a
// game's heroes, items or objectives itself. Each adapter supplies its own (MLBB: adapters/mlbb/MlbbKnowledge).
// Nothing here is guessed: an unknown character has the role UNKNOWN, an unknown name matches nothing.

/** The broad character classes most MOBAs share. A game without one of them simply never uses it. */
enum class CharacterRole { TANK, FIGHTER, ASSASSIN, MAGE, MARKSMAN, SUPPORT, UNKNOWN }

/** A lane or map position a player can be assigned to ("exp", "jungle"...): a stable key and a display label. */
data class LanePosition(val key: String, val label: String)

/** A neutral objective worth contesting (MLBB: turtle, lord), with the match time from which it appears. */
data class ObjectiveInfo(val key: String, val label: String, val fromMatchSec: Int)

interface GameKnowledge {
    /** Every playable character's display name. */
    val characters: List<String>
    /** The character a typed or read name refers to (case, spaces and small typos allowed); null if none. */
    fun matchCharacter(raw: String?): String?
    fun role(character: String?): CharacterRole
    /** Does the character attack from range? (false when unknown) */
    fun ranged(character: String?): Boolean
    val lanes: List<LanePosition>
    /** The summoner / battle spells a player can take. */
    val spells: List<String>
    /** The items that can be bought this patch. */
    val items: List<String>
    val objectives: List<ObjectiveInfo>
}
