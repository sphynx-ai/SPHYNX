package com.sphynx.app

// THE APP'S COLOURS (Phase 4). Three themes the user chooses between in Settings (owner, 2026-11-15: the grey
// theme "looks and feels dead" - every theme now has an accent colour for what is selected or on; the default
// is A, Signal):
//   A  Signal  the brand's teal on near-black
//   B  Arcane  violet with cyan on a blue-black ground
//   C  Ember   amber on a warm dark ground
// Pages use the names below, never their own hex values; the names read from the theme in use. ThemeTest checks
// the contrast of every pair that carries text, in all three themes. Pure Kotlin: unit tested.
object Theme {

    class Palette(
        val key: String, val label: String,
        val bg: String,            // the app ground
        val surf: String,          // cards, bars
        val surf2: String,         // fields, secondary buttons, rows inside cards
        val stroke: String,        // dividers and card borders (decorative)
        val outline: String,       // outlines of fields and controls (at least 3:1)
        val strong: String,        // headings, values
        val text: String,          // body text
        val muted: String,         // descriptions, metadata
        val accent: String,        // the theme's colour as text / icon: selected, on, links
        val primary: String,       // the main button of a card ...
        val onPrimary: String,     // ... and its text
        val tile: String,          // the accent's dark tint: behind what is selected
        val track: String,         // a switch that is on
        val banner: String,        // the state banner on Play (gradient start) ...
        val bannerEnd: String,     // ... its end ...
        val onBanner: String       // ... and its text
    )

    val SIGNAL = Palette("signal", "Signal (teal)",
        bg = "#0B0D10", surf = "#1B1F26", surf2 = "#252B35", stroke = "#3A414D", outline = "#737B88",
        strong = "#F4F6F8", text = "#C9CED6", muted = "#9AA3AF",
        accent = "#35C2B0", primary = "#35C2B0", onPrimary = "#0B0D10", tile = "#12332F", track = "#35C2B0",
        banner = "#0F7F73", bannerEnd = "#14433F", onBanner = "#FFFFFF")

    val ARCANE = Palette("arcane", "Arcane (violet)",
        bg = "#0A0B14", surf = "#1A1C2C", surf2 = "#262A42", stroke = "#343857", outline = "#8188AD",
        strong = "#F4F6F8", text = "#CBCFE0", muted = "#A3A9C4",
        accent = "#A99BFF", primary = "#4CC9F0", onPrimary = "#0A0B14", tile = "#2A2470", track = "#7C5CFF",
        banner = "#5B3FE0", bannerEnd = "#2A2470", onBanner = "#FFFFFF")

    val EMBER = Palette("ember", "Ember (amber)",
        bg = "#100C0A", surf = "#231C18", surf2 = "#33291F", stroke = "#4A3B31", outline = "#8F8072",
        strong = "#F8F4EE", text = "#D6CEC6", muted = "#ADA298",
        accent = "#F4A93B", primary = "#F4A93B", onPrimary = "#100C0A", tile = "#4A2A12", track = "#D9822B",
        banner = "#A34A18", bannerEnd = "#5A2E16", onBanner = "#FFFFFF")

    val ALL: List<Palette> = listOf(SIGNAL, ARCANE, EMBER)

    /** The saved choice, or the default (Signal) for anything unknown. */
    fun byKey(key: String?): Palette = ALL.firstOrNull { it.key == key } ?: SIGNAL

    @Volatile var current: Palette = SIGNAL
        private set

    fun use(key: String?) { current = byKey(key) }

    // ---- the names the pages use ----
    val BG: String get() = current.bg
    val SURF: String get() = current.surf
    val SURF2: String get() = current.surf2
    val STROKE: String get() = current.stroke
    val OUTLINE: String get() = current.outline
    val STRONG: String get() = current.strong
    val TEXT: String get() = current.text
    val MUTED: String get() = current.muted
    val ACCENT: String get() = current.accent
    val PRIMARY: String get() = current.primary
    val ON_PRIMARY: String get() = current.onPrimary
    val TILE: String get() = current.tile
    val TRACK: String get() = current.track
    val BANNER: String get() = current.banner
    val BANNER_END: String get() = current.bannerEnd
    val ON_BANNER: String get() = current.onBanner
    /** The logo's focus point (the diamond): the theme's colour. */
    val LOGO: String get() = current.primary

    // the same in every theme
    const val STOP = "#C8303F"        // STOP, with white text
    const val ON_STOP = "#FFFFFF"
    const val ON_FILL = "#0B0D10"     // dark text on the green / amber / red fills
    const val GREEN = "#3DD68C"       // success: ready, granted, on (always with a word or an icon too)
    const val GOLD = "#F4C25B"        // warning: needs attention, partial
    const val RED = "#FF6B78"         // danger: error, missing, destructive
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
