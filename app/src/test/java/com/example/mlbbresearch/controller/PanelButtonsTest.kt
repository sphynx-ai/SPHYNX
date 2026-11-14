package com.example.mlbbresearch.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The floating panel's buttons: what they say, and that no label depends on a text symbol
// (owner, 2026-11-14: the AI button showed no icon on a phone whose font lacks the power symbol).
class PanelButtonsTest {

    private val all = listOf(
        PanelButtons.stop(), PanelButtons.record(false, 0), PanelButtons.record(true, 12),
        PanelButtons.combo(true), PanelButtons.combo(false),
        PanelButtons.calibrate(false), PanelButtons.calibrate(true),
        PanelButtons.ai(true), PanelButtons.ai(false))

    @Test fun labelsArePlainLettersAndDigits() {
        for (b in all) assertTrue(b.label, b.label.all { it in 'A'..'Z' || it in '0'..'9' || it == ' ' })
    }

    @Test fun aCompactButtonShowsItsIconOnly() {
        for (b in all) { assertEquals("", b.text(true)); assertEquals(b.label, b.text(false)) }
    }

    @Test fun theAiButtonShowsItsState() {
        assertEquals("AI ON", PanelButtons.ai(true).label)
        assertEquals("AI OFF", PanelButtons.ai(false).label)
        assertEquals(PanelIcons.Kind.POWER, PanelButtons.ai(true).icon)
        assertNotEquals(PanelButtons.ai(true).fill, PanelButtons.ai(false).fill)
    }

    @Test fun recordingShowsTheSizeAndAStopIcon() {
        assertEquals("REC 12MB", PanelButtons.record(true, 12).label)
        assertEquals(PanelIcons.Kind.STOP, PanelButtons.record(true, 12).icon)
        assertEquals(PanelIcons.Kind.RECORD, PanelButtons.record(false, 0).icon)
    }

    @Test fun aButtonIsRedrawnOnlyWhenItChanges() {
        assertEquals(PanelButtons.record(true, 12).key(false), PanelButtons.record(true, 12).key(false))
        assertNotEquals(PanelButtons.record(true, 12).key(false), PanelButtons.record(true, 13).key(false))
        assertNotEquals(PanelButtons.ai(true).key(false), PanelButtons.ai(true).key(true))
    }

    @Test fun theBadgeWordFollowsTheState() {
        assertEquals("DRY", PanelButtons.badge(running = true, dryRun = true, error = false).first)
        assertEquals("ON", PanelButtons.badge(running = true, dryRun = false, error = false).first)
        assertEquals("ERR", PanelButtons.badge(running = false, dryRun = false, error = true).first)
        assertEquals("OFF", PanelButtons.badge(running = false, dryRun = false, error = false).first)
    }
}
