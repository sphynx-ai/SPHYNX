package com.example.mlbbresearch.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-13: orange emblems around the hero = the orange buff, purple = the purple buff, both possible
class BuffEmblemsTest {

    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b

    // an 800 x 359 frame of grass with square emblems of a colour on the ring around the feet
    private fun frame(vararg colours: Int): IntArray {
        val w = 800; val h = 359
        val px = IntArray(w * h) { rgb(60, 120, 70) }
        val cx = 0.507f * w; val cy = (0.457f + BuffEmblems.FEET_BELOW) * h
        val spots = listOf(40f to 0f, -30f to 18f, 10f to -26f, -38f to -8f, 20f to 24f, -5f to 29f)
        for ((i, c) in colours.withIndex()) {
            val (dx, dy) = spots[i % spots.size]
            for (y in -3..3) for (x in -3..3) px[(cy + dy + y).toInt() * w + (cx + dx + x).toInt()] = c
        }
        return px
    }

    @Test fun theMeasuredEmblemColours() {
        assertTrue(BuffEmblems.isOrange(229, 127, 63))        // from the 2026-11-13 screenshots
        assertTrue(BuffEmblems.isOrange(214, 99, 44))
        assertFalse(BuffEmblems.isOrange(62, 173, 254))       // a blue skill effect
        assertFalse(BuffEmblems.isOrange(60, 120, 70))        // grass
        assertTrue(BuffEmblems.isPurple(170, 90, 240))
        assertFalse(BuffEmblems.isPurple(62, 173, 254))
    }

    @Test fun countsOnlyTheRingAroundTheFeet() {
        val o = rgb(229, 127, 63); val p = rgb(170, 90, 240)
        assertEquals(0, BuffEmblems.count(frame(), 800, 359, 0.507f, 0.457f)[0])
        val both = BuffEmblems.count(frame(o, p, o, p), 800, 359, 0.507f, 0.457f)
        assertEquals(98, both[0]); assertEquals(98, both[1])
        // the same emblems far from the hero are not counted
        assertEquals(0, BuffEmblems.count(frame(o, o, o), 800, 359, 0.2f, 0.2f)[0])
    }

    @Test fun aLookIsYesNoOrCannotTell() {
        assertEquals(false, BuffEmblems.look(0, 800))
        assertEquals(true, BuffEmblems.look(60, 800))
        assertNull(BuffEmblems.look(394, 800))                // an explosion / an orange monster: says nothing
        assertEquals(true, BuffEmblems.look(240, 1600))       // a bigger frame has more pixels
    }

    @Test fun heldAfterSixOfNineAndGoneWhenMissing() {
        val t = BuffEmblems.Tracker()
        repeat(5) { assertFalse(t.add(true)) }
        assertTrue(t.add(true))
        repeat(4) { assertTrue(t.add(null)) }                 // a fight in between changes nothing
        repeat(7) { assertTrue(t.add(false)) }                // still 2 yes among the last 9
        assertFalse(t.add(false))
        repeat(3) { t.add(true) }
        assertFalse(t.held)                                   // a short flicker does not bring it back
    }
}
