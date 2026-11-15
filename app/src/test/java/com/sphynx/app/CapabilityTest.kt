package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {
    @Test fun onlyTheExceptionsShowABadge() {
        assertFalse(Capability.OPERATIONAL.shown)
        for (c in Capability.values()) if (c != Capability.OPERATIONAL) assertTrue(c.name, c.shown)
    }

    @Test fun everyBadgeHasItsOwnWord() {
        val labels = Capability.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test fun badgeColoursAreReadableOnCards() {
        // the badge word is drawn in its colour on a card: at least 4.5:1 in every theme
        for (p in Theme.ALL) {
            Theme.use(p.key)
            for (c in Capability.values()) {
                val r = Theme.contrast(c.color, Theme.SURF)
                assertTrue("${c.label} on ${p.key}: $r", r >= 4.5)
            }
        }
        Theme.use("signal")
    }
}
