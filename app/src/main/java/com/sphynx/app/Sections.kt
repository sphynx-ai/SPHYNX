package com.sphynx.app

// THE FIVE SECTIONS (Phase 4; docs/PHASE_3_INFORMATION_ARCHITECTURE.md, approved by the owner 2026-11-15): the
// bottom bar's five destinations and the pages each one holds. Before: 14 tabs of equal weight in a sideways
// scroll. v37.49: the old Player page is split into Hero ("player", the key kept) and Play style ("style").
// Every page belongs to exactly one section (SectionsTest); a section with more than one page shows them
// as a row of sub-pages under the header. Pure Kotlin: unit tested.
object Sections {

    class Section(val key: String, val label: String, val pages: List<String>)

    val ALL: List<Section> = listOf(
        Section("play", "Play", listOf("home")),
        Section("hero", "Hero & match", listOf("player", "style", "draft", "build")),
        Section("knowledge", "Knowledge", listOf("library", "meta")),
        Section("ai", "AI & models", listOf("model", "learn", "practice", "speed")),
        Section("settings", "Settings", listOf("setup", "debug", "logs", "news"))
    )

    /** Every page, in the order of the sections (swiping left / right walks this order). */
    val PAGES: List<String> = ALL.flatMap { it.pages }

    /** A page's name on its sub-page row. */
    val PAGE_LABELS: Map<String, String> = mapOf(
        "home" to "Play",
        "player" to "Hero", "style" to "Play style", "draft" to "Draft", "build" to "Build",
        "library" to "Library", "meta" to "Meta",
        "model" to "Models", "learn" to "Learning", "practice" to "Practice", "speed" to "Performance",
        "setup" to "Setup", "debug" to "Debug data", "logs" to "Logs", "news" to "What's new"
    )

    /** The section a page is in; null for a page outside the bar (the first-run guide). */
    fun of(page: String): Section? = ALL.firstOrNull { page in it.pages }

    /** The page a tap on a section opens: the one last seen there, else its first. */
    fun open(section: Section, lastSeen: Map<String, String>): String =
        lastSeen[section.key]?.takeIf { it in section.pages } ?: section.pages.first()
}
