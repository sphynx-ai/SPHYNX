package com.sphynx.adapters.mlbb

import com.sphynx.core.Cfg
import com.sphynx.game.HeroDb
import com.sphynx.game.Role
import com.sphynx.game.SpellDb
import com.sphynx.testing.MemoryPrefs
import com.sphynx.universal.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The MLBB knowledge the universal layer reads (Phase 2 submilestones 5-6): the same data as the MLBB code uses.
class MlbbKnowledgeTest {

    @Before fun setUp() { Cfg.useForTests(MemoryPrefs()) }

    @Test fun theAdapterOffersItsKnowledge() {
        assertSame(MlbbKnowledge, MlbbAdapter.knowledge)
    }

    @Test fun charactersAreTheHeroList() {
        assertEquals(HeroDb.allNames, MlbbKnowledge.characters)
        assertTrue(MlbbKnowledge.characters.size > 100)
        val any = MlbbKnowledge.characters.first()
        assertEquals(any, MlbbKnowledge.matchCharacter(any.uppercase()))
        assertNull(MlbbKnowledge.matchCharacter(""))
        assertNull(MlbbKnowledge.matchCharacter(null))
    }

    @Test fun everyRoleMapsAndUnknownStaysUnknown() {
        for (r in Role.values()) assertEquals(r.name, MlbbKnowledge.role(r).name)
        assertEquals(CharacterRole.UNKNOWN, MlbbKnowledge.role(null as String?))
        assertEquals(CharacterRole.UNKNOWN, MlbbKnowledge.role("no such hero"))
        for (h in MlbbKnowledge.characters.take(20)) assertEquals(HeroDb.role(h).name, MlbbKnowledge.role(h).name)
    }

    @Test fun unknownCharacterIsNotRanged() {
        assertFalse(MlbbKnowledge.ranged(null))
    }

    @Test fun lanesSpellsItemsAndObjectives() {
        assertEquals(listOf("exp", "gold", "mid", "roam", "jungle"), MlbbKnowledge.lanes.map { it.key })
        assertEquals(SpellDb.names, MlbbKnowledge.spells)
        assertTrue(MlbbKnowledge.items.isNotEmpty())
        assertEquals(listOf("turtle", "lord"), MlbbKnowledge.objectives.map { it.key })
        assertEquals(480, MlbbKnowledge.objectives.first { it.key == "lord" }.fromMatchSec)
    }
}
