package com.example.mlbbresearch.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-13: phantom "3% HP" monsters on empty ground
class LowBarRuleTest {

    @Test fun aLowBarOutOfNowhereIsAPhantom() {
        val r = LowBarRule()
        assertFalse(r.counts(0.58f, 0.56f, 0.032f, 1000L))
        assertFalse(r.counts(0.58f, 0.56f, 0.032f, 1100L))        // seeing it again does not make it real
    }

    @Test fun aBarThatWentDownCounts() {
        val r = LowBarRule()
        r.seen(0.50f, 0.40f, 0.30f, 1000L)
        assertTrue(r.counts(0.50f, 0.40f, 0.30f, 1000L))
        r.seen(0.52f, 0.41f, 0.04f, 1500L)                         // (a low bar is never remembered as real)
        assertTrue(r.counts(0.52f, 0.41f, 0.04f, 1500L))
        assertTrue(r.counts(0.53f, 0.42f, 0.02f, 2900L))           // still within 2 s of the fuller sighting
        assertFalse(r.counts(0.53f, 0.42f, 0.02f, 3100L))          // long gone
    }

    @Test fun elsewhereOnTheScreenDoesNotCount() {
        val r = LowBarRule()
        r.seen(0.20f, 0.30f, 0.80f, 1000L)
        assertFalse(r.counts(0.58f, 0.56f, 0.03f, 1100L))
        assertTrue(r.counts(0.70f, 0.70f, 0.50f, 1100L))           // a normal bar always counts
    }
}
