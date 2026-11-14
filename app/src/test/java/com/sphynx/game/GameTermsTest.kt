package com.sphynx.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Crowd control named in a skill description, with its level from the game's State Guide.
class GameTermsTest {
    @Test fun findsCrowdControlWithItsLevel() {
        val cc = GameTerms.ccIn("Deals damage, stuns the target and slows nearby enemies.")
        assertTrue(cc.contains("Stun (Basic control)"))
        assertTrue(cc.contains("Slow"))
    }

    @Test fun selfTransformIsNotCrowdControl() {
        assertFalse(GameTerms.ccIn("The hero transforms into a dragon.").any { it.startsWith("Transform") })
        assertTrue(GameTerms.ccIn("Transforms the target into a critter.").any { it.startsWith("Transform") })
    }

    @Test fun suppressAndFrozenMomentCannotBeRemoved() {
        assertTrue("Suppress" in GameTerms.unremovable && "Frozen Moment" in GameTerms.unremovable)
    }
}
