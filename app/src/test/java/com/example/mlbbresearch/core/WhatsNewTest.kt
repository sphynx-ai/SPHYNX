package com.example.mlbbresearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {

    private val e = WhatsNew.ENTRIES

    @Test fun entriesAreComplete() {
        assertTrue(e.isNotEmpty())
        for (x in e) {
            assertTrue("date ${x.date}", Regex("\\d{4}-\\d{2}-\\d{2}").matches(x.date))
            assertTrue(x.title.isNotBlank() && x.details.isNotBlank())
            assertTrue(x.pr > 0)
        }
    }

    @Test fun newestFirstAndOneLinePerPullRequest() {
        assertEquals(e.map { it.date }.sortedDescending(), e.map { it.date })
        // pull request numbers started again at 1 when the project moved to sphynx-ai/SPHYNX
        // (2026-11-13), so a number can appear twice: once before the move and once after
        assertEquals(e.size, e.map { it.pr to (it.date >= "2026-11-14") }.toSet().size)
    }

    // one version step per merged pull request (owner, 2026-11-14): the newest entry carries the app's
    // version, so a pull request that forgets to raise it fails here
    @Test fun theNewestEntryCarriesTheAppVersion() {
        assertEquals(com.example.mlbbresearch.BuildConfig.VERSION_NAME, e.first().version)
        val versions = e.mapNotNull { it.version }
        assertEquals(versions.size, versions.toSet().size)
        assertEquals(versions.sortedDescending(), versions)
    }

    @Test fun groupingKeepsEveryEntry() {
        assertEquals(e.size, WhatsNew.byDate().sumOf { it.second.size })
        assertEquals(e.size, WhatsNew.counts().values.sum())
        assertEquals(3, WhatsNew.latest(3).size)
    }
}
