package com.sphynx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 7 (L2): the tactical answer may stop as soon as it names its option in full
class TacticalEnoughTest {
    @Test fun stopsOnlyOnAFullOption() {
        assertFalse(TacticalAdvisor.enough(""))
        assertFalse(TacticalAdvisor.enough("RET"))           // half a word
        assertTrue(TacticalAdvisor.enough("RETREAT"))
        assertFalse(TacticalAdvisor.enough("ALL"))
        assertTrue(TacticalAdvisor.enough("ALL IN"))
        assertTrue(TacticalAdvisor.enough("I choose POKE"))
    }

    @Test fun neverInsideThinking() {
        assertFalse(TacticalAdvisor.enough("<think>maybe RETREAT"))
    }
}
