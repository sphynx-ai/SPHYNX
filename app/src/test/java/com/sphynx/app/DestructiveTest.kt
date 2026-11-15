package com.sphynx.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestructiveTest {
    @Test fun deletingButtonsAreDestructive() {
        for (l in listOf("Clear the match log", "Forget the remembered level badges", "Reset all positions to defaults",
                "Remove gemma.gguf", "Delete history", "  Clear saved map scenery")) assertTrue(l, Destructive.isLabel(l))
    }

    @Test fun otherButtonsAreNot() {
        for (l in listOf("Enter", "Sync 3D Scenery & Minimap Now", "Clearance check", "Resetting", "Open the first-run setup", ""))
            assertFalse(l, Destructive.isLabel(l))
    }
}
