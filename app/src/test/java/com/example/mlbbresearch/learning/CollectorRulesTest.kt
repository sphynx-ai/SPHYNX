package com.example.mlbbresearch.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorRulesTest {

    @Test fun nearDuplicatesAreSkipped() {
        val a = listOf(Triple(1, 0.50f, 0.50f), Triple(2, 0.20f, 0.80f))
        assertTrue(CollectorRules.sameScene(a, listOf(Triple(2, 0.21f, 0.79f), Triple(1, 0.51f, 0.50f)), 0.03f))
        assertFalse(CollectorRules.sameScene(a, listOf(Triple(1, 0.60f, 0.50f), Triple(2, 0.20f, 0.80f)), 0.03f))   // moved
        assertFalse(CollectorRules.sameScene(a, a + Triple(3, 0.1f, 0.1f), 0.03f))                                   // one more
        assertFalse(CollectorRules.sameScene(null, a, 0.03f))
    }

    @Test fun theNameTellsTheLayoutAndTheMatch() {
        assertEquals("_gs", CollectorRules.tag(true, "SOLO"))
        assertEquals("_e5", CollectorRules.tag(false, "FIVE_V_FIVE"))
        assertEquals("_uu", CollectorRules.tag(null, "UNKNOWN"))
    }

    @Test fun oneMonsterDoesNotFillTheSet() {
        val b = CollectorRules.Balance(minEach = 3, share = 0.3f)
        repeat(3) { assertTrue(b.allow(setOf("thunder_fenrir"))); b.saved(setOf("thunder_fenrir")) }
        assertFalse(b.allow(setOf("thunder_fenrir")))                // its share is full
        assertTrue(b.allow(setOf("fire_beetle")))                    // another monster is welcome
        assertTrue(b.allow(setOf("thunder_fenrir", "fire_beetle")))
        assertTrue(b.allow(emptySet()))                              // no monsters (heroes, towers): always
    }
}
