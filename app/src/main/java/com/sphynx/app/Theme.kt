package com.sphynx.app

// THE APP'S COLOURS (Phase 4; docs/PHASE_3_DESIGN_TOKENS.md, approved by the owner 2026-11-15): one palette,
// from the brand (docs/brand). Pages use these names, never their own hex values. ThemeTest checks the
// contrast of every pair that carries text. Teal (the brand's Signal) is for the focus point of the symbol
// only, so it is not here. Pure Kotlin: unit tested.
object Theme {
    // ground and text
    const val BG = "#0B0D10"          // Obsidian: the app ground
    const val SURF = "#1B1F26"        // Graphite: cards, bars
    const val SURF2 = "#252B35"       // fields, secondary buttons, rows inside cards
    const val STROKE = "#3A414D"      // Slate: dividers and card borders (decorative)
    const val OUTLINE = "#737B88"     // outlines of fields and controls (at least 3:1)
    const val STRONG = "#F4F6F8"      // Ice: headings, values
    const val TEXT = "#C9CED6"        // Titanium: body text
    const val MUTED = "#9AA3AF"       // Mist: descriptions, metadata

    // actions
    const val PRIMARY = "#F4F6F8"     // the main button of a card, the selected tab: Ice ...
    const val ON_PRIMARY = "#0B0D10"  // ... with Obsidian text
    const val STOP = "#C8303F"        // STOP, with white text
    const val ON_STOP = "#FFFFFF"

    // meaning (always with a word or an icon too)
    const val GREEN = "#3DD68C"       // success: ready, granted, on
    const val GOLD = "#F4C25B"        // warning: needs attention, partial
    const val RED = "#FF6B78"         // danger: error, missing, destructive
    const val ACCENT = "#7FA2FF"      // info: links, neutral highlights
    const val EXPERIMENTAL = "#C3A6FF"

    // ---- contrast (WCAG 2.x) ----
    private fun channel(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }

    fun luminance(hex: String): Double {
        val h = hex.removePrefix("#").takeLast(6)
        val r = h.substring(0, 2).toInt(16); val g = h.substring(2, 4).toInt(16); val b = h.substring(4, 6).toInt(16)
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /** Contrast ratio of two colours, 1.0 (none) to 21.0 (black on white). */
    fun contrast(a: String, b: String): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
