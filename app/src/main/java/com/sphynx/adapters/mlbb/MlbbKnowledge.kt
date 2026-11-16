package com.sphynx.adapters.mlbb

import com.sphynx.game.HeroDb
import com.sphynx.game.ItemDb
import com.sphynx.game.Lane
import com.sphynx.game.Role
import com.sphynx.game.SpellDb
import com.sphynx.universal.CharacterRole
import com.sphynx.universal.GameKnowledge
import com.sphynx.universal.LanePosition
import com.sphynx.universal.ObjectiveInfo

// MLBB's characters, lanes, spells, items and objectives for the universal layer (Phase 2 submilestone 5).
// A read-only view of the existing MLBB data (HeroDb, SpellDb, ItemDb, Lane): nothing is copied or changed, so
// MLBB behaviour stays the same.
object MlbbKnowledge : GameKnowledge {

    override val characters: List<String> get() = HeroDb.allNames
    override fun matchCharacter(raw: String?): String? = HeroDb.match(raw)
    override fun role(character: String?): CharacterRole = role(HeroDb.role(character))
    override fun ranged(character: String?): Boolean = character != null && HeroDb.isRanged(character)

    fun role(r: Role): CharacterRole = when (r) {
        Role.TANK -> CharacterRole.TANK
        Role.FIGHTER -> CharacterRole.FIGHTER
        Role.ASSASSIN -> CharacterRole.ASSASSIN
        Role.MAGE -> CharacterRole.MAGE
        Role.MARKSMAN -> CharacterRole.MARKSMAN
        Role.SUPPORT -> CharacterRole.SUPPORT
        Role.UNKNOWN -> CharacterRole.UNKNOWN
    }

    override val lanes: List<LanePosition> = Lane.values().map { LanePosition(it.name.lowercase(), it.label) }
    override val spells: List<String> get() = SpellDb.names
    override val items: List<String> get() = ItemDb.current
    // the turtle until 8:00, the lord from 8:00 (game/Lanes LaneLogic.objectiveName)
    override val objectives: List<ObjectiveInfo> = listOf(
        ObjectiveInfo("turtle", "Turtle", 0),
        ObjectiveInfo("lord", "Lord", 480)
    )
}
