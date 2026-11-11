package com.example.mlbbresearch.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The Lithowanderer is a neutral monster; after its kill the Stone Roamer is a friendly patrol for 45 s,
// not an ally hero (owner, 2026-11-11).
class LithowandererTest {

    private val L = Lithowanderer
    private val spot = 0.39f to 0.62f

    @Before fun clean() = L.reset()

    @Test fun namedOnlyAtItsSpotsAfterItSpawns() {
        assertTrue(L.isIt(0.40f to 0.61f, 40))
        assertTrue(L.isIt(0.60f to 0.39f, 200))       // the enemy side's spot
        assertFalse(L.isIt(0.40f to 0.61f, 20))       // before 0:35
        assertFalse(L.isIt(0.27f to 0.56f, 60))       // the purple buff pit
        assertFalse(L.isIt(null, 60))
    }

    @Test fun killedWhenItVanishesAtLowHp() {
        L.observe(1_000, spot, 0.6f)
        L.observe(1_500, spot, 0.1f)
        L.observe(1_800, spot, null)
        assertEquals(1_800L, L.killedAt)
        assertTrue(L.roamerActive(10_000, spot))
        assertTrue(L.roamerActive(46_000, 0.45f to 0.60f))
        assertFalse(L.roamerActive(47_000, spot))         // 45 s are over
        assertFalse(L.roamerActive(10_000, 0.8f to 0.2f)) // far from the spot
    }

    @Test fun walkingAwayAtHighHpIsNotAKill() {
        L.observe(1_000, spot, 0.9f)
        L.observe(1_300, spot, null)
        assertEquals(0L, L.killedAt)
        assertFalse(L.roamerActive(5_000, spot))
        // seen low, but gone only much later (it ran off screen): not a kill
        L.observe(6_000, spot, 0.1f)
        L.observe(20_000, spot, null)
        assertEquals(0L, L.killedAt)
    }

    @Test fun theRoamerIsTheBlueBarWithoutAnAllyIcon() {
        assertEquals(1, L.roamerCount(screenAllyHeroes = 1, minimapAlliesNear = 0))
        assertEquals(0, L.roamerCount(screenAllyHeroes = 1, minimapAlliesNear = 1))
        assertEquals(1, L.roamerCount(screenAllyHeroes = 3, minimapAlliesNear = 2))
        assertEquals(0, L.roamerCount(screenAllyHeroes = 0, minimapAlliesNear = 2))
    }
}
