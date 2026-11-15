package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The five sections of the bottom bar (owner-approved information architecture).
class SectionsTest {

    // the 14 pages the app had as tabs: none may get lost in the move
    private val before = setOf("home", "news", "player", "draft", "build", "meta", "library", "model", "learn",
        "practice", "speed", "logs", "debug", "setup")

    @Test fun thereAreFiveSections() {
        assertEquals(listOf("Play", "Hero & match", "Knowledge", "AI & models", "Settings"), Sections.ALL.map { it.label })
    }

    @Test fun everyOldTabIsInExactlyOneSection() {
        assertEquals(before, Sections.PAGES.toSet())
        assertEquals("a page is in two sections", Sections.PAGES.size, Sections.PAGES.toSet().size)
        for (p in before) assertTrue(p, Sections.of(p) != null)
    }

    @Test fun everyPageHasAName() {
        assertEquals(Sections.PAGES.toSet(), Sections.PAGE_LABELS.keys)
        assertTrue(Sections.PAGE_LABELS.values.none { it.isBlank() })
    }

    @Test fun theFirstRunGuideIsOutsideTheBar() {
        assertNull(Sections.of("welcome"))
    }

    @Test fun aSectionOpensWhereYouLeftIt() {
        val hero = Sections.ALL.first { it.key == "hero" }
        assertEquals("player", Sections.open(hero, emptyMap()))
        assertEquals("build", Sections.open(hero, mapOf("hero" to "build")))
        // a remembered page that is no longer in the section: its first page
        assertEquals("player", Sections.open(hero, mapOf("hero" to "library")))
    }
}
