package com.example.mlbbresearch.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitWatchTest {

    @Test fun anHpDropMeansBeingHit() {
        val w = HitWatch()
        w.add(0L, 0.6f); w.add(500L, 0.6f)
        assertFalse(w.beingHit())
        w.add(1_000L, 0.55f)                       // -5% within 1.5 s
        assertTrue(w.beingHit())
        w.add(3_000L, 0.56f); w.add(3_200L, 0.57f) // healing, the drop is old
        assertFalse(w.beingHit())
    }
}
