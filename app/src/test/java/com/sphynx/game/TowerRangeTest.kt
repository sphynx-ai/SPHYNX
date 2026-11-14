package com.sphynx.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-13: the learned tower range jumped between 6% and 16% in one match
class TowerRangeTest {

    @Test fun misreadsAreIgnored() {
        assertEquals(6, AutoTune.nextTowerRange(6, 16f))
        assertEquals(6, AutoTune.nextTowerRange(6, 2f))
    }

    @Test fun goodMeasurementsMoveItSlowly() {
        assertEquals(6, AutoTune.nextTowerRange(5, 8f))        // one point at a time
        var v = 5
        repeat(10) { v = AutoTune.nextTowerRange(v, 8f) }
        assertTrue("got $v", v in 7..8)
        assertEquals(5, AutoTune.nextTowerRange(5, 5.4f))
    }

    @Test fun anOldBadValueComesBackIntoRange() {
        assertEquals(AutoTune.RANGE_MAX, AutoTune.nextTowerRange(16, 30f))
        assertTrue(AutoTune.nextTowerRange(16, 6f) <= AutoTune.RANGE_MAX)
    }
}
