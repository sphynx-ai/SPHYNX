package com.example.mlbbresearch.game

import com.example.mlbbresearch.testutil.TestSupport
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

// The scenery calibration tour reaches the enemy towers and base (owner, 2026-11-11). Tower positions
// measured on the owner's minimap screenshot (2026-11-10), in minimap fractions.
class SceneryTourTest {

    @Before fun setUp() { TestSupport.freshCfg() }

    private val enemyTowers = listOf(
        0.27f to 0.06f, 0.54f to 0.05f, 0.72f to 0.06f,        // top lane: outer, inner, base
        0.60f to 0.42f, 0.70f to 0.32f, 0.78f to 0.21f,        // mid
        0.93f to 0.73f, 0.93f to 0.53f, 0.92f to 0.27f,        // gold lane
        0.87f to 0.10f                                          // base core
    )

    @Test fun everyEnemyTowerAndTheBaseHaveAStopNextToThem() {
        val tour = SceneryMap.tourWaypoints
        for ((x, y) in enemyTowers) {
            val d = tour.minOf { hypot(it.x - x, it.y - y) }
            assertTrue("no stop near the enemy structure at $x,$y (nearest %.3f)".format(d), d <= 0.08f)
        }
    }

    @Test fun stopsAreOnTheMapAndTheWalkEndsAtTheFountain() {
        for (w in SceneryMap.tourWaypoints + SceneryMap.returnWaypoints) assertTrue(w.name, w.x in 0f..1f && w.y in 0f..1f)
        val last = SceneryMap.returnWaypoints.last()
        assertTrue(last.x < 0.12f && last.y > 0.88f)
    }

    // the jungle pits are where the owner's 20 jungle screenshots put them (2026-11-11): standing at a camp's
    // pit is standing where the owner stood fighting it; the old built-in pits (e.g. a "Rockursa") are gone
    @Test fun pitsMatchTheOwnersJungleScreenshots() {
        SceneryMap.ensureLoaded()
        for ((x, y) in listOf(0.29f to 0.52f, 0.46f to 0.78f, 0.48f to 0.70f, 0.58f to 0.82f, 0.20f to 0.41f, 0.38f to 0.37f)) {
            val pit = SceneryMap.pits.minByOrNull { hypot(it.standX - x, it.standY - y) }!!
            assertTrue("no pit to stand at near $x,$y", hypot(pit.standX - x, pit.standY - y) <= 0.03f)
        }
        // the enemy jungle: the same, turned half a turn
        val enemyLizard = SceneryMap.pitStandSpot(0.82f, 0.59f)
        assertTrue(hypot(enemyLizard.first - 0.80f, enemyLizard.second - 0.59f) <= 0.03f)
        assertTrue(SceneryMap.isOldDefaultPit(0.20f, 0.66f))           // the old "Rockursa"
        assertTrue(!SceneryMap.isOldDefaultPit(0.18f, 0.41f))
        assertTrue(SceneryMap.pits.none { it.label.contains("rockursa") })
    }

    // calibration only in Solo mode vs computer (god mode - owner, 2026-11-11), once the lane mark is read
    @Test fun calibrationOnlyInSoloPractice() {
        val solo = MatchKind.Kind.SOLO
        org.junit.Assert.assertNull(SceneryMap.calibrationBlock(true, solo, true))      // either lane on top, once read
        org.junit.Assert.assertNotNull(SceneryMap.calibrationBlock(true, MatchKind.Kind.FIVE_V_FIVE, true))
        org.junit.Assert.assertNotNull(SceneryMap.calibrationBlock(true, MatchKind.Kind.UNKNOWN, true))
        org.junit.Assert.assertNotNull(SceneryMap.calibrationBlock(false, solo, true))
        org.junit.Assert.assertNotNull(SceneryMap.calibrationBlock(true, solo, false))  // the lane mark not read
    }
}
