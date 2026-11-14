package com.sphynx.vision

import com.sphynx.game.JungleDb
import com.sphynx.game.JungleSpots
import com.sphynx.game.MapSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-12: the buff's bar read as minions made the Player leave it alive
class CampRuleTest {

    @Test fun atACampWithNoWaveMinionBarsAreTheCamp() {
        assertTrue(CampRule.minionIsTheCamp(atCampSpot = true, enemyWaveNear = false))
        assertFalse(CampRule.minionIsTheCamp(atCampSpot = true, enemyWaveNear = true))
        assertFalse(CampRule.minionIsTheCamp(atCampSpot = false, enemyWaveNear = false))
    }

    @Test fun wavesNearYou() {
        assertTrue(CampRule.waveNear(0.5f to 0.5f, listOf(0.55f to 0.55f)))
        assertFalse(CampRule.waveNear(0.5f to 0.5f, listOf(0.8f to 0.8f)))
        assertFalse(CampRule.waveNear(0.5f to 0.5f, emptyList()))
    }

    // the recording: the hero at 0.476, 0.766 fighting the Molten Fiend (its spot 0.46, 0.77)
    @Test fun theCampYouStandAt() {
        MapSide.reset()
        assertEquals(JungleDb.MOLTEN_FIEND, JungleSpots.campAt(0.476f to 0.766f))
        assertEquals(JungleDb.HORNED_LIZARD, JungleSpots.campAt(0.21f to 0.40f))
        assertNull(JungleSpots.campAt(0.5f to 0.5f))
    }
}
