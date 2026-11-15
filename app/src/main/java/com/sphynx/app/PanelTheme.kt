package com.sphynx.app

import com.sphynx.controller.PanelIcons

// The floating panel takes the chosen theme's colours (Phase 3 backlog item 12: the panel on the same tokens as the
// app). Called wherever the theme is applied: at start-up and when the user picks another theme.
object PanelTheme {
    fun apply() {
        PanelIcons.usePalette(bg = Theme.BG, surface = Theme.SURF, stroke = Theme.STROKE, muted = Theme.MUTED,
            text = Theme.TEXT, strong = Theme.STRONG, accent = Theme.LOGO, onAccent = Theme.ON_PRIMARY)
    }
}
