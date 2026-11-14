package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultsOffTest {

    @Test fun newInstallKeepsNothing() {
        assertTrue(Cfg.oldDefaultsToKeep(emptySet()).isEmpty())
    }

    @Test fun existingPhoneKeepsEveryUnchangedSwitchOn() {
        val keep = Cfg.oldDefaultsToKeep(setOf("my_hero", "auto_record"))
        assertEquals(Cfg.OLD_ON_DEFAULTS.size - 1, keep.size)
        assertTrue("auto_record" !in keep)
        assertTrue("collect_ds" in keep && "gpu" in keep)
    }

    @Test fun onlyOnce() {
        assertTrue(Cfg.oldDefaultsToKeep(setOf("my_hero", "defaults_gen")).isEmpty())
    }

    @Test fun safetyGuardsAreNotTurnedOff() {
        assertTrue(Cfg.OLD_ON_DEFAULTS.none { it in setOf("dry_run", "qb_full", "qb_gold") })
    }
}
