package com.example.mlbbresearch.game

import com.example.mlbbresearch.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Owner decision 2026-11-07: Gemma's reading of the loading-card spell is a HINT; only the card's
// spell icon matching the wiki picture makes it the verified loading-screen spell.
class LoadingSpellVerificationTest {

    @Before fun clean() {
        TestSupport.freshCfg()
        GameStateTracker.apply {
            buttonSpell = null; scoreboardSpell = null; loadingSpell = null; gemmaLoadingSpell = null
            gemmaSpell = null; iconSpell = null; blessedRetribution = null; lockedSpell = null
        }
    }

    @Test fun gemmaReadingAloneIsNotVerified() {
        GameStateTracker.onLoadingCardRead("Player1", "Karina", "Flicker", "test")
        assertEquals("Flicker", GameStateTracker.gemmaLoadingSpell)
        assertNull(GameStateTracker.loadingSpell)
        assertFalse(GameStateTracker.isSpellVerified())
        assertEquals("Flicker", GameStateTracker.spell())              // still used as the best guess
        assertEquals("read by Gemma", GameStateTracker.spellSource())
    }

    @Test fun iconMatchMakesItVerified() {
        GameStateTracker.onLoadingCardRead("Player1", "Karina", "Flicker", "test")
        GameStateTracker.onLoadingSpellVerified("Flicker", 0.7f)
        assertEquals("Flicker", GameStateTracker.loadingSpell)
        assertTrue(GameStateTracker.isSpellVerified())
        assertEquals("loading screen card (verified vs wiki icon)", GameStateTracker.spellSource())
    }

    @Test fun iconWinsWhenGemmaMisread() {
        GameStateTracker.onLoadingCardRead("Player1", "Karina", "Flicker", "test")
        GameStateTracker.onLoadingSpellVerified("Execute", 0.7f)
        assertEquals("Execute", GameStateTracker.verifiedSpell())
    }

    @Test fun retributionUpgradeStillWorksFromGemmaHint() {
        GameStateTracker.onLoadingCardRead("Player1", "Karina", "Retribution", "test")
        GameStateTracker.onButtonSpellVerified("Ice Retribution", 0.7f)
        assertEquals("Retribution Upgraded to Ice", GameStateTracker.spellDisplay())
    }
}
