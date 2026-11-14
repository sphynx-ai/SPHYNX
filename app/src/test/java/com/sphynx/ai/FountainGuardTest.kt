package com.sphynx.ai

import com.sphynx.game.SceneryMap
import com.sphynx.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

// The Research player never enters the enemy fountain (owner, 2026-11-11), whatever the mode.
class FountainGuardTest {

    @Before fun setUp() { TestSupport.freshCfg() }

    private fun dist(x: Float, y: Float) = hypot(FountainGuard.CX - x, FountainGuard.CY - y)

    @Test fun farAwayNothingChanges() {
        assertNull(FountainGuard.guard(0.5f, 0.5f, 0.7f, -0.7f))
        assertNull(FountainGuard.guard(0.83f, 0.15f, 0.7f, -0.7f))        // the enemy base core stop
    }

    @Test fun insideItWalksOut() {
        val (x, y, _) = FountainGuard.guard(0.94f, 0.06f, 0.7f, -0.7f)!!
        // stepping that way moves away from the fountain
        assertTrue(dist(0.94f + x * 0.01f, 0.06f + y * 0.01f) > dist(0.94f, 0.06f))
    }

    @Test fun atTheEdgeNoStepLeadsIn() {
        val mx = 0.87f; val my = 0.11f                                    // next to the zone (by the core)
        assertTrue(dist(mx, my) < FountainGuard.RADIUS + FountainGuard.MARGIN)
        for (a in 0 until 16) {
            val dx = kotlin.math.cos(a * Math.PI / 8).toFloat(); val dy = kotlin.math.sin(a * Math.PI / 8).toFloat()
            val g = FountainGuard.guard(mx, my, dx, dy)!!
            assertTrue("direction $a leads in", dist(mx + g.first * 0.01f, my + g.second * 0.01f) >= dist(mx, my) - 1e-4f)
        }
    }

    @Test fun walkingAwayIsKeptButWithoutSkills() {
        val g = FountainGuard.guard(0.87f, 0.11f, -0.7f, 0.7f)!!
        assertEquals(-0.7f, g.first, 1e-6f); assertEquals(0.7f, g.second, 1e-6f)
    }

    @Test fun noCalibrationStopIsInsideTheZone() {
        for (w in SceneryMap.tourWaypoints + SceneryMap.returnWaypoints)
            assertTrue(w.name, dist(w.x, w.y) >= FountainGuard.RADIUS + FountainGuard.MARGIN)
    }
}
