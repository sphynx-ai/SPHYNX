package com.sphynx.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// PortraitMatch.pickSpell: which wiki spell picture an icon on screen is, from its likeness ranking
// (best first). Synthetic rankings - no pictures needed.
class SpellPickTest {

    private fun r(vararg p: Pair<String, Float>) = p.toList().sortedByDescending { it.second }

    @Test fun clearWinnerIsPicked() {
        assertEquals("Flicker", PortraitMatch.pickSpell(r("Flicker" to 0.80f, "Sprint" to 0.55f), min = 0.55f, margin = 0.10f)?.first)
    }

    @Test fun tooCloseOrTooWeakIsNotPicked() {
        assertNull(PortraitMatch.pickSpell(r("Flicker" to 0.70f, "Sprint" to 0.66f), min = 0.55f, margin = 0.10f))
        assertNull(PortraitMatch.pickSpell(r("Flicker" to 0.50f, "Sprint" to 0.10f), min = 0.55f, margin = 0.10f))
    }

    @Test fun expectedSpellIsConfirmedWhenNearlyAsGood() {
        val m = PortraitMatch.pickSpell(r("Inspire" to 0.71f, "Retribution" to 0.69f), min = 0.55f, margin = 0.10f, expected = "Retribution")
        assertEquals("Retribution", m?.first)
    }

    @Test fun expectedSpellBelowTheThresholdIsNotForced() {
        // Retribution is expected but reads below the threshold: the clear winner stays Inspire
        assertEquals("Inspire", PortraitMatch.pickSpell(r("Inspire" to 0.65f, "Retribution" to 0.50f), min = 0.55f, margin = 0.10f, expected = "Retribution")?.first)
    }

    @Test fun blessedRetributionWinsOverPlainRetributionWhenAllowed() {
        val ranking = r("Retribution" to 0.74f, "Bloody Retribution" to 0.72f, "Flicker" to 0.40f)
        assertEquals("Bloody Retribution",
            PortraitMatch.pickSpell(ranking, min = 0.55f, margin = 0.10f, includeBlessed = true)?.first)
    }

    @Test fun blessedNeedsAClearGapToOtherSpells() {
        // an Ice Retribution reading barely ahead of Purify is not trusted
        val ranking = r("Ice Retribution" to 0.70f, "Purify" to 0.66f, "Retribution" to 0.60f)
        assertNull(PortraitMatch.pickSpell(ranking, min = 0.55f, margin = 0.10f, includeBlessed = true))
    }

    @Test fun twoRetributionPicturesCloseTogetherStillGiveRetribution() {
        // plain Retribution best, a Blessed picture right behind, other spells far away (blessed not allowed)
        val ranking = r("Retribution" to 0.78f, "Flicker" to 0.45f)
        assertEquals("Retribution", PortraitMatch.pickSpell(ranking, min = 0.55f, margin = 0.10f)?.first)
    }

    @Test fun blessedKindIsTheFirstWord() {
        assertEquals("Bloody", PortraitMatch.blessedKind("Bloody Retribution"))
        assertEquals("Ice", PortraitMatch.blessedKind("Ice Retribution"))
        assertEquals("Flame", PortraitMatch.blessedKind("Flame Retribution"))
        assertNull(PortraitMatch.blessedKind("Retribution"))
        assertNull(PortraitMatch.blessedKind("Flicker"))
    }
}
