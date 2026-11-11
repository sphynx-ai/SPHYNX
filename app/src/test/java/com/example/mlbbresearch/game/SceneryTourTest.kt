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
}
