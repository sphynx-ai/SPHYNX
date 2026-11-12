package com.example.mlbbresearch.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

// Gold / EXP lane swapped between matches, the Lord and Turtle pits with them (owner, 2026-11-11).
class MapSideTest {

    @Before fun clean() = MapSide.reset()
    @After fun back() = MapSide.reset()                // other tests use the usual map

    // a frame with a glyph of this colour in the top-left mark's box (measured colours of the owner's "$" / "E")
    private fun frame(r: Int, g: Int, b: Int, w: Int = 800, h: Int = 359): IntArray {
        val px = IntArray(w * h) { (0xFF shl 24) or (60 shl 16) or (70 shl 8) or 80 }
        for (y in (0.006f * h).toInt()..(0.031f * h).toInt()) for (x in (0.048f * w).toInt()..(0.057f * w).toInt())
            if ((x + y) % 2 == 0) px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return px
    }

    @Test fun readsTheMarks() {
        assertEquals(MapSide.Mark.GOLD, MapSide.mark(frame(248, 236, 194), 800, 359))     // the "$"
        assertEquals(MapSide.Mark.EXP, MapSide.mark(frame(240, 219, 255), 800, 359))      // the "E"
        assertEquals(MapSide.Mark.NONE, MapSide.mark(IntArray(800 * 359) { (0xFF shl 24) or 0x506070 }, 800, 359))
    }

    @Test fun believedAfterThreeReadingsInARow() {
        MapSide.observe(MapSide.Mark.GOLD); MapSide.observe(MapSide.Mark.GOLD)
        assertNull(MapSide.goldTop)
        MapSide.observe(MapSide.Mark.EXP)                          // a different reading starts again
        MapSide.observe(MapSide.Mark.GOLD); MapSide.observe(MapSide.Mark.GOLD); MapSide.observe(MapSide.Mark.GOLD)
        assertEquals(true, MapSide.goldTop)
    }

    @Test fun lanesAndPitsSwapWithTheMarks() {
        // lanes: the code's lane geometry is drawn for EXP on top
        assertEquals(Lane.EXP, MapSide.geo(Lane.EXP, null))        // unknown: as before
        assertEquals(Lane.EXP, MapSide.geo(Lane.EXP, false))
        assertEquals(Lane.GOLD, MapSide.geo(Lane.EXP, true))
        assertEquals(Lane.EXP, MapSide.geo(Lane.GOLD, true))
        assertEquals(Lane.MID, MapSide.geo(Lane.MID, true))
        // pits: the Lord by the Gold lane, the Turtle by the EXP lane (the "$"-top-left screenshot: Turtle bottom-right)
        assertEquals(MapSide.TOP_LEFT_PIT, MapSide.lordPit(true))
        assertEquals(MapSide.BOTTOM_RIGHT_PIT, MapSide.turtlePit(true))
        assertEquals(MapSide.TOP_LEFT_PIT, MapSide.lordPit(null))   // unknown: the owner's usual map
        assertEquals(MapSide.BOTTOM_RIGHT_PIT, MapSide.lordPit(false))
        assertEquals(MapSide.TOP_LEFT_PIT, MapSide.turtlePit(false))
    }

    @Test fun theBossFollowsTheMap() {
        fun mini(x: Float, y: Float) = com.example.mlbbresearch.vision.MinimapInfo(0, 0, emptyList(), self = x to y)
        repeat(3) { MapSide.observe(MapSide.Mark.EXP) }
        assertEquals(JungleDb.TURTLE, JungleDb.boss(mini(0.29f, 0.30f), 300, MatchKind.Kind.SOLO))  // EXP on top: the Turtle is top-left
        assertEquals(JungleDb.LORD, JungleDb.boss(mini(0.70f, 0.70f), 600, MatchKind.Kind.SOLO))
        MapSide.reset(); repeat(3) { MapSide.observe(MapSide.Mark.GOLD) }
        assertEquals(JungleDb.LORD, JungleDb.boss(mini(0.29f, 0.30f), 600, MatchKind.Kind.SOLO))    // Gold on top: the Lord is top-left
        assertEquals(JungleDb.TURTLE, JungleDb.boss(mini(0.70f, 0.70f), 300, MatchKind.Kind.SOLO))
    }

    // the mirrored map (EXP on top): the base stays, the top-left and bottom-right corners trade places
    @Test fun theMirror() {
        fun near(a: Pair<Float, Float>, b: Pair<Float, Float>) = kotlin.math.hypot(a.first - b.first, a.second - b.second) < 1e-4f
        assertEquals(true, near(0.3f to 0.2f, MapSide.view(0.3f to 0.2f, true)))     // Gold on top: the tables' own map
        assertEquals(true, near(0.3f to 0.2f, MapSide.view(0.3f to 0.2f, null)))
        assertEquals(true, near(0.07f to 0.93f, MapSide.view(0.07f to 0.93f, false)))
        val pit = MapSide.view(MapSide.TOP_LEFT_PIT, false)
        // (the two pit positions are rounded estimates: the mirror of one lands 0.071 from the other; the boss rule
        // itself looks 0.25 around each pit)
        assertEquals(true, kotlin.math.hypot(pit.first - MapSide.BOTTOM_RIGHT_PIT.first, pit.second - MapSide.BOTTOM_RIGHT_PIT.second) < 0.08f)
        assertEquals(true, near(0.3f to 0.2f, MapSide.view(MapSide.view(0.3f to 0.2f, false), false)))   // its own inverse
    }

    @Test fun jungleMonstersAreNamedOnTheMirroredMap() {
        repeat(3) { MapSide.observe(MapSide.Mark.EXP) }
        // the owner's Horned Lizard spot (0.21, 0.40 on the usual map) is at 0.60, 0.79 on a mirrored one
        assertEquals(JungleDb.HORNED_LIZARD, JungleSpots.at(0.60f to 0.79f, MonsterKind.CAMP))
        assertEquals(JungleDb.THUNDER_FENRIR, JungleSpots.at(0.48f to 0.71f, MonsterKind.BUFF))
        MapSide.reset(); repeat(3) { MapSide.observe(MapSide.Mark.GOLD) }
        assertEquals(JungleDb.HORNED_LIZARD, JungleSpots.at(0.21f to 0.40f, MonsterKind.CAMP))   // not mirrored
    }

    // Solo practice shows no lane marks (owner, 2026-11-11): the side comes from the boss icons' pits
    @Test fun soloSideFromTheBossIcons() {
        // the owner's Solo screenshot (01:26): the Lord top-left, the Turtle bottom-right -> Gold on top
        assertEquals(MapSide.Mark.GOLD, MapSide.fromPits(0.30f to 0.27f, 0.68f to 0.70f))
        assertEquals(MapSide.Mark.EXP, MapSide.fromPits(0.66f to 0.69f, 0.31f to 0.25f))
        assertEquals(MapSide.Mark.NONE, MapSide.fromPits(0.30f to 0.27f, null))      // both must show
        assertEquals(MapSide.Mark.NONE, MapSide.fromPits(0.30f to 0.27f, 0.32f to 0.26f))   // not opposite pits
        repeat(3) { MapSide.observe(MapSide.fromPits(0.30f to 0.27f, 0.68f to 0.70f)) }
        assertEquals(true, MapSide.goldTop)
    }
}
