package com.sphynx.app

// The capability status badge (roadmap Section 7, design tokens Section 1, state matrix "Capability status"):
// shown on every feature that is not fully operational, always as a word (never colour alone).
enum class Capability(val label: String) {
    OPERATIONAL("Operational"),
    PARTIAL("Partial"),
    EXPERIMENTAL("Experimental"),
    PLANNED("Planned"),
    UNSUPPORTED("Unsupported"),
    NEEDS_INTERNET("Needs internet");

    // operational features carry no badge: only the exceptions are marked
    val shown: Boolean get() = this != OPERATIONAL

    // the badge colour (read when drawn, so it follows the chosen theme)
    val color: String get() = when (this) {
        OPERATIONAL -> Theme.GREEN
        PARTIAL, NEEDS_INTERNET -> Theme.INFO
        EXPERIMENTAL -> Theme.EXPERIMENTAL
        PLANNED, UNSUPPORTED -> Theme.MUTED
    }
}
