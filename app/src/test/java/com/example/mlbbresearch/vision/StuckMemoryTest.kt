package com.example.mlbbresearch.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-13: the AI should remember where it got stuck
class StuckMemoryTest {

    @Test fun stuckTwiceIsAWallAndItIsKept() {
        val m = StuckMemory.parse(null)
        assertEquals(1, StuckMemory.hit(m, 815))
        assertTrue(StuckMemory.walls(m).isEmpty())
        // (the next match: read back from the settings)
        val again = StuckMemory.parse(StuckMemory.format(m))
        assertEquals(2, StuckMemory.hit(again, 815))
        assertEquals(listOf(815), StuckMemory.walls(again))
        assertEquals(listOf(815), StuckMemory.walls(StuckMemory.parse(StuckMemory.format(again))))
    }

    @Test fun badTextIsIgnoredAndCountsAreCapped() {
        val m = StuckMemory.parse("12=3;x=1;;7=;9=0;40=99")
        assertEquals(setOf(12, 40), m.keys)
        assertEquals(StuckMemory.MAX_COUNT, m[40])
        repeat(30) { StuckMemory.hit(m, 12) }
        assertEquals(StuckMemory.MAX_COUNT, m[12])
    }

    @Test fun onlyTheMostHitCellsAreKept() {
        val m = StuckMemory.parse(null)
        for (c in 0 until StuckMemory.MAX_CELLS + 50) StuckMemory.hit(m, c)
        StuckMemory.hit(m, 5)
        val kept = StuckMemory.parse(StuckMemory.format(m))
        assertEquals(StuckMemory.MAX_CELLS, kept.size)
        assertEquals(2, kept[5])
    }
}
