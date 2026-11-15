package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class FreshnessTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val nov15 = 1_794_700_800_000L   // 2026-11-15 00:00 UTC
    private val day = 24L * 3600 * 1000

    @Test fun ago() {
        assertEquals("today", Freshness.ago(nov15, nov15 + 3600_000))
        assertEquals("yesterday", Freshness.ago(nov15, nov15 + day + 1))
        assertEquals("10 days ago", Freshness.ago(nov15, nov15 + 10 * day))
        assertEquals("today", Freshness.ago(nov15 + day, nov15))   // a clock behind: never negative
    }

    @Test fun lineWithSources() {
        assertEquals("Updated 15 Nov 2026 (2 days ago) from mlbb.fandom.com, groq",
            Freshness.line(nov15, listOf("mlbb.fandom.com", " groq ", "groq"), nov15 + 2 * day, utc))
    }

    @Test fun manySourcesAreShortened() {
        assertEquals("Updated 15 Nov 2026 (today) from a, b, c and 2 more",
            Freshness.line(nov15, listOf("a", "b", "c", "d", "e"), nov15, utc))
    }

    @Test fun missingSourceOrDate() {
        assertEquals("Updated 15 Nov 2026 (today) from source not recorded", Freshness.line(nov15, emptyList(), nov15, utc))
        assertEquals("Not downloaded yet", Freshness.line(0L, listOf("x"), nov15, utc))
    }
}
