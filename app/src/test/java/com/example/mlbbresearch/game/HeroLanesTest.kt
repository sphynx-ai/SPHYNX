package com.example.mlbbresearch.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Recommended lanes per hero: primary, secondary, flex pick (owner, 2026-11-11)
class HeroLanesTest {

    private fun wiki(h: String, role: Role) = HeroLanes.of(h, null, role)

    @Test fun fromTheWikiLists() {
        val layla = wiki("Layla", Role.MARKSMAN)
        assertEquals(Lane.GOLD, layla.primary); assertNull(layla.secondary); assertFalse(layla.flex)
        val chou = wiki("Chou", Role.FIGHTER)                 // EXP, high-dive mid, initiator roamer
        assertEquals(Lane.EXP, chou.primary); assertEquals(Lane.MID, chou.secondary); assertTrue(chou.flex)
        val granger = wiki("Granger", Role.MARKSMAN)          // burst gold + ranged jungler
        assertEquals(Lane.GOLD, granger.primary); assertEquals(Lane.JUNGLE, granger.secondary)
        assertEquals(Lane.JUNGLE, wiki("Fanny", Role.ASSASSIN).primary)
    }

    @Test fun byRoleWhenNotListed() {
        val r = HeroLanes.of("Nobody", null, Role.MAGE)
        assertEquals(Lane.MID, r.primary); assertNull(r.secondary); assertFalse(r.flex)
    }

    @Test fun theAutomaticLane() {
        val chou = wiki("Chou", Role.FIGHTER)
        val hylos = wiki("Hylos", Role.TANK)                  // EXP first
        assertEquals(Lane.EXP, HeroLanes.assign(chou, false, emptyList())!!.first)
        // a flex pick gives way when an ally's primary is the same lane
        assertEquals(Lane.MID, HeroLanes.assign(chou, false, listOf(hylos))!!.first)
        // a specialist keeps its lane
        val layla = wiki("Layla", Role.MARKSMAN)
        assertEquals(Lane.GOLD, HeroLanes.assign(layla, false, listOf(layla))!!.first)
        // a jungler: Jungle only with Retribution; without it, its secondary or nothing (caller's fallback)
        val fanny = wiki("Fanny", Role.ASSASSIN)
        assertEquals(Lane.JUNGLE, HeroLanes.assign(fanny, true, emptyList())!!.first)
        assertNull(HeroLanes.assign(fanny, false, emptyList()))
        val rec = HeroLanes.Rec(Lane.JUNGLE, Lane.EXP, false, "test")
        assertEquals(Lane.EXP, HeroLanes.assign(rec, false, emptyList())!!.first)
    }
}
