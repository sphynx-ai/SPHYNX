package com.sphynx.universal.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 10 E1: the protocol's statistics
class EvalStatsTest {

    @Test fun quantilesAndSpread() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, EvalStats.median(x), 1e-9)
        assertEquals(4.6, EvalStats.quantile(x, 0.9), 1e-9)
        assertEquals(1.0, EvalStats.quantile(x, 0.0), 1e-9)
        assertEquals(1.5811, EvalStats.sd(x), 1e-3)
        assertEquals(2.5, EvalStats.median(listOf(4.0, 1.0, 3.0, 2.0)), 1e-9)
        assertTrue(EvalStats.median(emptyList()).isNaN())
        assertEquals(0.0, EvalStats.sd(listOf(7.0)), 0.0)
    }

    @Test fun theBootstrapIsRepeatableAndHoldsTheMean() {
        val x = (1..60).map { (it % 7) * 10.0 + 100 }
        val a = EvalStats.bootstrapMean(x); val b = EvalStats.bootstrapMean(x)
        assertEquals(a, b)                                   // fixed seed: the same samples give the same interval
        val m = EvalStats.mean(x)
        assertTrue(a.first < m && m < a.second)
        assertTrue(a.second - a.first < 20)
        assertEquals(5.0 to 5.0, EvalStats.bootstrapMean(listOf(5.0)))
    }

    @Test fun summaryText() {
        val s = EvalStats.summary(listOf(400.0, 410.0, 420.0, 600.0))
        assertEquals(4, s.n)
        assertTrue(s.ms(), s.ms().startsWith("median 415 ms, 90% under 546 ms (n 4;"))
        assertEquals("no samples", EvalStats.summary(emptyList()).ms())
    }

    @Test fun wilsonInterval() {
        val (lo, hi) = EvalStats.wilson(30, 40)
        assertEquals(0.598, lo, 0.005); assertEquals(0.858, hi, 0.005)
        assertEquals(0.0 to 1.0, EvalStats.wilson(0, 0))
        assertEquals(0.0, EvalStats.wilson(0, 10).first, 1e-9)
    }
}
