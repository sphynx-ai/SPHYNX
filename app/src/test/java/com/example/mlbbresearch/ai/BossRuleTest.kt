package com.example.mlbbresearch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-12: the Lord only with 3 or more allies near
class BossRuleTest {

    @Test fun threeAlliesOrNoLord() {
        assertFalse(BossRule.mayHitLord(0))
        assertFalse(BossRule.mayHitLord(2))
        assertTrue(BossRule.mayHitLord(3))
        assertTrue(BossRule.mayHitLord(4))
    }

    @Test fun alliesFromScreenOrMinimap() {
        assertEquals(3, BossRule.alliesNear(onScreenNearBoss = 1, onMinimapNearYou = 3))
        assertEquals(2, BossRule.alliesNear(onScreenNearBoss = 2, onMinimapNearYou = 0))
    }
}
