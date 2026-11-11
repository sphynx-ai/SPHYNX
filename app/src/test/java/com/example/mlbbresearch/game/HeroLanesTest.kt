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
        val hylos = wiki("Hylos", Role.TANK)                  // EXP, initiator roamer, tank jungler
        assertEquals(Lane.EXP, hylos.primary); assertEquals(Lane.ROAM, hylos.secondary); assertTrue(hylos.flex)
        val granger = wiki("Granger", Role.MARKSMAN)          // burst gold + ranged jungler
        assertEquals(Lane.GOLD, granger.primary); assertEquals(Lane.JUNGLE, granger.secondary)
        assertEquals(Lane.JUNGLE, wiki("Fanny", Role.ASSASSIN).primary)
    }

    @Test fun byRoleWhenNotListed() {
        val r = HeroLanes.of("Nobody", null, Role.MAGE)
        assertEquals(Lane.MID, r.primary); assertNull(r.secondary); assertFalse(r.flex)
    }

    @Test fun theAutomaticLane() {
        val hylos = wiki("Hylos", Role.TANK)                  // a flex pick: EXP first, then Roam
        val expAlly = HeroLanes.Rec(Lane.EXP, null, false, "test")
        assertEquals(Lane.EXP, HeroLanes.assign(hylos, false, emptyList())!!.first)
        // a flex pick gives way when an ally's primary is the same lane
        assertEquals(Lane.ROAM, HeroLanes.assign(hylos, false, listOf(expAlly))!!.first)
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

    // the owner (2026-11-11): Alice is played EXP or Jungle - not Roam or Mid, though the wiki lists her for Mid
    @Test fun aliceAsTheOwnerSays() {
        val a = HeroLanes.of("Alice", null, Role.MAGE)
        assertEquals(Lane.EXP, a.primary); assertEquals(Lane.JUNGLE, a.secondary); assertFalse(a.flex)
        assertEquals(Lane.EXP, HeroLanes.assign(a, false, emptyList())!!.first)
        assertEquals(Lane.JUNGLE, HeroLanes.assign(HeroLanes.Rec(Lane.JUNGLE, Lane.EXP, false, "t"), true, emptyList())!!.first)
    }

    @Test fun yourSettingComesFirst() {
        val r = HeroLanes.of("Alice", null, Role.MAGE, manual = "jungle, exp, flex")
        assertEquals(Lane.JUNGLE, r.primary); assertEquals(Lane.EXP, r.secondary); assertTrue(r.flex)
        assertEquals(Lane.GOLD, HeroLanes.parse("Gold")!!.primary)
        assertNull(HeroLanes.parse("something"))
        assertNull(HeroLanes.parse(""))
    }

    // the owner's corrections (2026-11-11)
    @Test fun theOwnersCorrections() {
        fun r(h: String) = HeroLanes.of(h, null, Role.FIGHTER)
        fun check(h: String, p: Lane, s: Lane?) { assertEquals(h, p, r(h).primary); assertEquals(h, s, r(h).secondary) }
        check("Alpha", Lane.JUNGLE, Lane.EXP)
        check("Chou", Lane.EXP, Lane.ROAM)
        check("Lapu-Lapu", Lane.EXP, null)
        check("Masha", Lane.EXP, Lane.ROAM)
        check("Minsitthar", Lane.EXP, Lane.ROAM)
        check("Paquito", Lane.EXP, Lane.JUNGLE)
        check("Phoveus", Lane.EXP, null)
        check("Valentina", Lane.MID, Lane.EXP)
        check("Yi Sun-shin", Lane.JUNGLE, Lane.GOLD)
        // without Retribution the junglers play their second lane
        assertEquals(Lane.EXP, HeroLanes.assign(r("Alpha"), false, emptyList())!!.first)
        assertEquals(Lane.GOLD, HeroLanes.assign(r("Yi Sun-shin"), false, emptyList())!!.first)
        assertEquals(Lane.JUNGLE, HeroLanes.assign(r("Yi Sun-shin"), true, emptyList())!!.first)
    }
}
