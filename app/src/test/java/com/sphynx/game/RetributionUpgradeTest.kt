package com.sphynx.game

import com.sphynx.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// "If Retribution is already detected at the loading screen and in game the Blessed one is detected,
// just say 'Retribution Upgraded to Bloody' - the same for Ice and Flame."
class RetributionUpgradeTest {

    @Before fun clean() {
        TestSupport.freshCfg()
        GameStateTracker.apply {
            buttonSpell = null; scoreboardSpell = null; loadingSpell = null; gemmaLoadingSpell = null
            gemmaSpell = null; iconSpell = null; blessedRetribution = null; lockedSpell = null
        }
    }

    @Test fun loadingRetributionThenBloodyButtonSaysUpgraded() {
        GameStateTracker.onLoadingSpellVerified("Retribution", 0.7f)
        assertEquals("Retribution", GameStateTracker.spellDisplay())
        GameStateTracker.onButtonSpellVerified("Bloody Retribution", 0.72f)
        assertEquals("Bloody", GameStateTracker.blessedRetribution)
        assertEquals("Retribution Upgraded to Bloody", GameStateTracker.spellDisplay())
        assertEquals("Retribution", GameStateTracker.spell())       // the AI still uses it as Retribution
        assert(GameStateTracker.verifiedSpellPanel().startsWith("✓ Retribution Upgraded to Bloody ("))
    }

    @Test fun iceAndFlameToo() {
        GameStateTracker.onLoadingSpellVerified("Retribution", 0.7f)
        GameStateTracker.onButtonSpellVerified("Ice Retribution", 0.7f)
        assertEquals("Retribution Upgraded to Ice", GameStateTracker.spellDisplay())
        clean()
        GameStateTracker.onLoadingSpellVerified("Retribution", 0.7f)
        GameStateTracker.onButtonSpellVerified("Flame Retribution", 0.7f)
        assertEquals("Retribution Upgraded to Flame", GameStateTracker.spellDisplay())
    }

    @Test fun gemmaReadingOfRetributionAlsoCountsAsDetected() {
        GameStateTracker.gemmaLoadingSpell = "Retribution"
        GameStateTracker.onButtonSpellVerified("Flame Retribution", 0.7f)
        assertEquals("Flame", GameStateTracker.blessedRetribution)
    }

    @Test fun blessedIconIsIgnoredWithoutRetribution() {
        GameStateTracker.onLoadingSpellVerified("Flicker", 0.7f)
        GameStateTracker.onButtonSpellVerified("Ice Retribution", 0.7f)
        assertNull(GameStateTracker.blessedRetribution)
        assertEquals("Flicker", GameStateTracker.spellDisplay())
    }

    @Test fun plainRetributionAfterTheUpgradeKeepsTheUpgrade() {
        GameStateTracker.onLoadingSpellVerified("Retribution", 0.7f)
        GameStateTracker.onButtonSpellVerified("Bloody Retribution", 0.7f)
        GameStateTracker.onButtonSpellVerified("Retribution", 0.7f)
        assertEquals("Retribution Upgraded to Bloody", GameStateTracker.spellDisplay())
    }

    @Test fun anotherSpellCannotOverwriteAnUpgradedRetribution() {
        GameStateTracker.onLoadingSpellVerified("Retribution", 0.7f)
        GameStateTracker.onButtonSpellVerified("Bloody Retribution", 0.7f)
        GameStateTracker.onButtonSpellVerified("Inspire", 0.6f)
        assertEquals("Retribution", GameStateTracker.buttonSpell)
        assertEquals("Retribution Upgraded to Bloody", GameStateTracker.spellDisplay())
    }

    @Test fun blessedScoreboardIconCountsAsRetribution() {
        GameStateTracker.onLoadingSpellVerified("Retribution", 0.7f)
        GameStateTracker.onScoreboardSpellVerified("Ice Retribution", 0.6f)
        assertEquals("Retribution", GameStateTracker.scoreboardSpell)
        assertNull(GameStateTracker.blessedRetribution)          // the kind comes from the spell button only
    }

    @Test fun enteredSpellStaysAbsolute() {
        com.sphynx.core.Cfg.mySpell = "Flicker"
        GameStateTracker.onLoadingSpellVerified("Retribution", 0.7f)
        GameStateTracker.onButtonSpellVerified("Bloody Retribution", 0.7f)
        assertNull(GameStateTracker.blessedRetribution)
        assertEquals("Flicker", GameStateTracker.spellDisplay())
    }

    // bug 5 (device test 2026-11-10): Retribution missed on the loading card; the Blessed button icon
    // alone proves it, so the lane becomes Jungle
    @Test fun blessedIconWithNoSpellKnownMeansRetribution() {
        GameStateTracker.onButtonSpellVerified("Flame Retribution", 0.7f)
        assertEquals("Retribution", GameStateTracker.spell())
        assertEquals("Flame", GameStateTracker.blessedRetribution)
        assertEquals(Lane.JUNGLE, LaneLogic.effective("Alucard", GameStateTracker.spell()).first)
    }
}
