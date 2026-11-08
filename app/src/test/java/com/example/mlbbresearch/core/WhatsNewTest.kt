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
        assertEquals(e.size, e.map { it.pr }.toSet().size)
    }

    @Test fun groupingKeepsEveryEntry() {
        assertEquals(e.size, WhatsNew.byDate().sumOf { it.second.size })
        assertEquals(e.size, WhatsNew.counts().values.sum())
        assertEquals(3, WhatsNew.latest(3).size)
    }
}
