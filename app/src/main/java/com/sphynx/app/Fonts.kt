package com.sphynx.app

import android.content.Context
import android.graphics.Typeface
import com.sphynx.R
import com.sphynx.core.Cfg

// THE APP'S FONTS (Phase 4; owner, 2026-11-14: a pixel-style font for headings and numbers, Inter for text).
// Both are inside the app (res/font, SIL Open Font License - assets/licenses), so every phone shows the same
// letters; before, the app showed the phone's own font. Inter is the theme's font (res/values/themes.xml) and
// needs nothing here; the heading font is set by the pages through [display]. Which pixel font it is, is the
// owner's choice on the Setup page (Cfg.headFont) until one is picked for good.
object Fonts {
    const val PIXELIFY = "pixelify"
    const val SILKSCREEN = "silkscreen"
    val CHOICES = listOf(PIXELIFY to "Pixelify Sans", SILKSCREEN to "Silkscreen")

    /** The saved choice, or the default for anything unknown. Pure: unit tested. */
    fun choice(saved: String?): String = if (saved == SILKSCREEN) SILKSCREEN else PIXELIFY

    private var cachedFor: String? = null
    private var cached: Typeface? = null

    /** The heading font. Falls back to the phone's bold font if the file cannot be loaded. */
    fun display(ctx: Context): Typeface {
        val which = choice(Cfg.headFont)
        cached?.let { if (cachedFor == which) return it }
        val tf = try {
            if (which == SILKSCREEN) ctx.resources.getFont(R.font.silkscreen)
            else Typeface.Builder(ctx.resources, R.font.pixelify_sans).setFontVariationSettings("'wght' 600").build()
        } catch (e: Throwable) { null } ?: Typeface.DEFAULT_BOLD
        cached = tf; cachedFor = which
        return tf
    }
}
