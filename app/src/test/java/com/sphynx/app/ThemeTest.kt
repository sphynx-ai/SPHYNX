package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The colours keep the contrast the design tokens promise (docs/PHASE_3_DESIGN_TOKENS.md): text at least
// 4.5:1, control outlines at least 3:1. A colour changed without checking fails here.
class ThemeTest {

    private fun atLeast(min: Double, name: String, fg: String, bg: String) {
        val c = Theme.contrast(fg, bg)
        assertTrue("$name: %.2f:1, needs %.1f:1".format(c, min), c >= min)
    }

    @Test fun textIsReadableOnEveryGround() {
        for ((bgName, bg) in listOf("bg" to Theme.BG, "surface" to Theme.SURF, "surface-2" to Theme.SURF2)) {
            atLeast(4.5, "strong on $bgName", Theme.STRONG, bg)
            atLeast(4.5, "text on $bgName", Theme.TEXT, bg)
            atLeast(4.5, "muted on $bgName", Theme.MUTED, bg)
            for ((n, col) in listOf("green" to Theme.GREEN, "gold" to Theme.GOLD, "red" to Theme.RED,
                    "accent" to Theme.ACCENT, "experimental" to Theme.EXPERIMENTAL))
                atLeast(4.5, "$n on $bgName", col, bg)
        }
    }

    @Test fun buttonsAreReadable() {
        atLeast(4.5, "primary button", Theme.ON_PRIMARY, Theme.PRIMARY)
        atLeast(4.5, "STOP", Theme.ON_STOP, Theme.STOP)
        // dark text on the coloured fills (granted permission, Train, ...)
        for (fill in listOf(Theme.GREEN, Theme.GOLD, Theme.RED)) atLeast(4.5, "text on $fill", Theme.ON_PRIMARY, fill)
    }

    @Test fun outlinesCanBeSeen() {
        atLeast(3.0, "outline on surface", Theme.OUTLINE, Theme.SURF)
        atLeast(3.0, "outline on surface-2", Theme.OUTLINE, Theme.SURF2)
    }

    @Test fun contrastIsTheWcagFormula() {
        assertEquals(21.0, Theme.contrast("#000000", "#FFFFFF"), 0.01)
        assertEquals(1.0, Theme.contrast("#777777", "#777777"), 0.001)
    }

    @Test fun anUnknownHeadingFontFallsBackToTheDefault() {
        assertEquals(Fonts.PIXELIFY, Fonts.choice(null))
        assertEquals(Fonts.PIXELIFY, Fonts.choice("something else"))
        assertEquals(Fonts.SILKSCREEN, Fonts.choice("silkscreen"))
    }
}
