package com.sphynx.adapters.mlbb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 13 D3: the meta sources and their honest states
class MlbbMetaSourceTest {

    private val day = 86_400_000L

    @Test fun theOfficialSourceIsUnavailableAndNeverInventsNumbers() {
        val s = MlbbMetaSource.OfficialRank.status(0)
        assertTrue(s.official); assertEquals(MlbbMetaSource.State.UNAVAILABLE, s.state)
        assertEquals("https://www.mobilelegends.com/rank", s.link)
        assertEquals(0, s.heroes); assertEquals(null, s.lastRefreshMs)
    }

    @Test fun appGeneratedIsLabelledAndAges() {
        assertEquals(MlbbMetaSource.State.MISSING, MlbbMetaSource.appGenerated(0, null, null, 0).state)
        val fresh = MlbbMetaSource.appGenerated(120, 10 * day, "1.9.20", 13 * day)
        assertFalse(fresh.official); assertEquals(MlbbMetaSource.State.AVAILABLE, fresh.state)
        assertTrue(MlbbMetaSource.line(fresh).startsWith("App-generated meta (not official statistics): available — 120 heroes; newest 3 days old · patch 1.9.20"))
        val old = MlbbMetaSource.appGenerated(120, 0, null, 20 * day)
        assertEquals(MlbbMetaSource.State.STALE, old.state); assertTrue(old.reason.contains("out of date"))
    }
}
