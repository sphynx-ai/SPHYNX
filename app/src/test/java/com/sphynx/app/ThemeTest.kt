package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Every theme keeps the contrast the design tokens promise (docs/PHASE_3_DESIGN_TOKENS.md): text at least 4.5:1,
// control outlines at least 3:1. A colour changed, or a theme added, without checking fails here.
class ThemeTest {

    private fun atLeast(min: Double, name: String, fg: String, bg: String) {
        val c = Theme.contrast(fg, bg)
        assertTrue("$name: %.2f:1, needs %.1f:1".format(c, min), c >= min)
    }

    @Test fun textIsReadableOnEveryGroundInEveryTheme() {
        for (p in Theme.ALL) for ((gn, g) in listOf("bg" to p.bg, "surface" to p.surf, "surface-2" to p.surf2)) {
            atLeast(4.5, "${p.key}: strong on $gn", p.strong, g)
            atLeast(4.5, "${p.key}: text on $gn", p.text, g)
            atLeast(4.5, "${p.key}: muted on $gn", p.muted, g)
            atLeast(4.5, "${p.key}: accent on $gn", p.accent, g)
            for ((n, col) in listOf("green" to Theme.GREEN, "gold" to Theme.GOLD, "red" to Theme.RED, "experimental" to Theme.EXPERIMENTAL))
                atLeast(4.5, "${p.key}: $n on $gn", col, g)
        }
    }

    @Test fun buttonsBannersAndSelectionsAreReadableInEveryTheme() {
        for (p in Theme.ALL) {
            atLeast(4.5, "${p.key}: primary button", p.onPrimary, p.primary)
            atLeast(4.5, "${p.key}: selected (accent on its tile)", p.accent, p.tile)
            atLeast(4.5, "${p.key}: banner", p.onBanner, p.banner)
            atLeast(4.5, "${p.key}: banner end", p.onBanner, p.bannerEnd)
        }
        atLeast(4.5, "STOP", Theme.ON_STOP, Theme.STOP)
        for (fill in listOf(Theme.GREEN, Theme.GOLD, Theme.RED)) atLeast(4.5, "text on $fill", Theme.ON_FILL, fill)
    }

    @Test fun outlinesCanBeSeenInEveryTheme() {
        for (p in Theme.ALL) { atLeast(3.0, "${p.key}: outline on surface", p.outline, p.surf); atLeast(3.0, "${p.key}: outline on surface-2", p.outline, p.surf2) }
    }

    @Test fun contrastIsTheWcagFormula() {
        assertEquals(21.0, Theme.contrast("#000000", "#FFFFFF"), 0.01)
        assertEquals(1.0, Theme.contrast("#777777", "#777777"), 0.001)
    }

    // owner, 2026-11-15: three themes to choose from, Signal (A) by default
    @Test fun thereAreThreeThemesAndSignalIsTheDefault() {
        assertEquals(listOf("signal", "arcane", "ember"), Theme.ALL.map { it.key })
        assertEquals(Theme.SIGNAL, Theme.byKey(null))
        assertEquals(Theme.SIGNAL, Theme.byKey("no such theme"))
        assertEquals(Theme.EMBER, Theme.byKey("ember"))
        Theme.use("arcane"); assertEquals(Theme.ARCANE.accent, Theme.ACCENT)
        Theme.use(null); assertEquals(Theme.SIGNAL.bg, Theme.BG)
    }

    @Test fun anUnknownHeadingFontFallsBackToTheDefault() {
        assertEquals(Fonts.PIXELIFY, Fonts.choice(null))
        assertEquals(Fonts.PIXELIFY, Fonts.choice("something else"))
        assertEquals(Fonts.SILKSCREEN, Fonts.choice("silkscreen"))
    }
}
