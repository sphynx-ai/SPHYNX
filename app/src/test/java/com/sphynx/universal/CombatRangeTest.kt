package com.sphynx.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatRangeTest {

    private val melee = 0.16f        // HeroProfile default for fighters, tanks, assassins

    @Test fun meleeClosesInWhereTheOldRuleStood() {
        // the old rule stopped at 0.34 (about twice a melee hero's range); now it keeps walking in
        val st = CombatRange.approach(0.30f, 0f, melee)
        assertFalse(st.inRange)
        assertEquals(1f, st.moveX, 1e-6f)
    }

    @Test fun standsStillInsideOwnAttackRange() {
        assertTrue(CombatRange.approach(0.12f, 0.05f, melee).inRange)        // 0.13 <= 0.136
        assertFalse(CombatRange.approach(0.14f, 0f, melee).inRange)          // just outside the reach
        val st = CombatRange.approach(0.10f, 0f, melee)
        assertEquals(0f, st.moveX, 0f); assertEquals(0f, st.moveY, 0f)
    }

    @Test fun reachHasAFloor() {
        assertEquals(CombatRange.MIN_REACH, CombatRange.reach(0.02f), 1e-6f)
        assertEquals(0.34f, CombatRange.reach(0.40f), 1e-6f)
    }

    @Test fun leashBoundary() {
        assertEquals(0f, CombatRange.leashFraction(0f, 0f), 1e-6f)
        assertEquals(1f, CombatRange.leashFraction(0.5f, 0f), 1e-6f)
        assertEquals(1f, CombatRange.leashFraction(0f, 0.22f), 1e-6f)
    }

    @Test fun aStepOutOfTheAggroZoneIsCancelled() {
        // the monster is 0.16 below you (fraction 0.73); walking straight up away from it would pass 0.8
        val held = CombatRange.keepInLeash(0f, 0.16f, 0f, -1f)
        assertEquals(0f to 0f, held)
    }

    @Test fun stepsInsideOrBackTowardTheMonsterAreAllowed() {
        // far inside the zone: moving sideways is fine
        assertEquals(1f to 0f, CombatRange.keepInLeash(0.05f, 0.02f, 1f, 0f))
        // already outside the margin: stepping back toward the monster is always allowed
        assertEquals(0f to 1f, CombatRange.keepInLeash(0f, 0.20f, 0f, 1f))
        // standing still stays still
        assertEquals(0f to 0f, CombatRange.keepInLeash(0f, 0.20f, 0f, 0f))
    }

    @Test fun contactWhenTheGapStopsShrinking() {
        val c = CombatRange.ContactDetector()
        // walking in: the gap shrinks, no contact
        assertFalse(c.inContact(0L, 0.40f, true))
        assertFalse(c.inContact(200L, 0.30f, true))
        assertFalse(c.inContact(400L, 0.22f, true))
        // pressed against the monster's body: the gap stays at 0.21
        assertFalse(c.inContact(600L, 0.21f, true))
        assertFalse(c.inContact(900L, 0.21f, true))
        assertTrue(c.inContact(1200L, 0.21f, true))       // 0.8 s without getting closer
        assertTrue(c.inContact(2000L, 0.21f, false))      // stands still and attacks for 1.5 s
        assertFalse(c.inContact(2800L, 0.21f, true))      // then tries to close in again
    }

    @Test fun contactEndsWhenTheTargetMovesAway() {
        val c = CombatRange.ContactDetector()
        c.inContact(0L, 0.21f, true); c.inContact(300L, 0.21f, true); c.inContact(600L, 0.21f, true)
        assertTrue(c.inContact(800L, 0.21f, true))
        assertFalse(c.inContact(900L, 0.35f, true))       // the monster walked off: approach again
    }

    @Test fun aPauseRestartsTheWatch() {
        val c = CombatRange.ContactDetector()
        c.inContact(0L, 0.21f, true)
        assertFalse(c.inContact(1000L, 0.21f, true))      // 1 s gap between decisions: not a measurement
    }
}
