package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStatusTest {
    @Test fun runningBadge() {
        assertEquals("All off", PlayStatus.runningBadge(anyOn = false, dryRun = true))
        assertEquals("Dry run", PlayStatus.runningBadge(anyOn = true, dryRun = true))
        assertEquals("Live", PlayStatus.runningBadge(anyOn = true, dryRun = false))
    }

    @Test fun engineWords() {
        assertEquals("Off", PlayStatus.engineWord(on = false, dryRun = true, touches = true))
        assertEquals("On · Dry run", PlayStatus.engineWord(on = true, dryRun = true, touches = true))
        assertEquals("On", PlayStatus.engineWord(on = true, dryRun = true, touches = false))   // Learning never touches
        assertEquals("On", PlayStatus.engineWord(on = true, dryRun = false, touches = true))
    }

    private fun item(ok: Boolean, needed: Boolean = true) = PlayStatus.Item("k", "Label", ok, "Granted", "Missing", needed)

    @Test fun readySummary() {
        assertEquals("Ready", PlayStatus.readySummary(listOf(item(true), item(true, needed = false))))
        assertEquals("Ready · 1 optional", PlayStatus.readySummary(listOf(item(true), item(false, needed = false))))
        assertEquals("2 to fix", PlayStatus.readySummary(listOf(item(false), item(false), item(false, needed = false))))
        assertTrue(PlayStatus.allNeededOk(listOf(item(true), item(false, needed = false))))
        assertFalse(PlayStatus.allNeededOk(listOf(item(false))))
    }

    @Test fun onlyWhatIsNotReadyIsListedNeededFirst() {
        val a = PlayStatus.Item("a", "A", true, "Granted", "Missing")
        val b = PlayStatus.Item("b", "B", false, "Loaded", "Not loaded", needed = false)
        val c = PlayStatus.Item("c", "C", false, "Granted", "Missing")
        assertEquals(listOf("c", "b"), PlayStatus.notReady(listOf(a, b, c)).map { it.key })
        assertTrue(PlayStatus.notReady(listOf(a)).isEmpty())
    }

    @Test fun itemWords() {
        assertEquals("Granted", item(true).word)
        assertEquals("Missing", item(false).word)
        assertTrue(item(false).needsFix)
    }
}
