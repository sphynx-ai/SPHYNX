package com.sphynx.controller

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable

// THE FLOATING PANEL'S ICONS, DRAWN (owner, 2026-11-14): the panel used text symbols for its icons
// (power, stop, record, combo, map). A phone whose font lacks one of them shows nothing - the AI
// button had no icon. Every icon is now drawn with lines and shapes, so it looks the same on every
// phone. Colours: the Sphynx brand palette (docs/brand).
object PanelIcons {

    enum class Kind { POWER, STOP, RECORD, COMBO, CALIBRATE, CLOSE, MINIMIZE, RESIZE, MARK }

    // the panel's palette: the brand's (Signal) until the app hands it the chosen theme's colours with usePalette
    // (backlog item 12: the panel on the same tokens as the app). This layer does not read the app's theme itself.
    const val SIGNAL = "#35C2B0"
    @Volatile var OBSIDIAN = "#0B0D10"; private set     // ground / ink on a filled accent
    @Volatile var GRAPHITE = "#1B1F26"; private set     // panel and button surface
    @Volatile var SLATE = "#3A414D"; private set        // borders
    @Volatile var MIST = "#9AA3AF"; private set         // muted ink
    @Volatile var TITANIUM = "#C9CED6"; private set     // body ink
    @Volatile var ICE = "#F4F6F8"; private set          // strong ink
    /** The symbol's focus colour (the diamond) and the "on" colour of the panel: the app sets it to its theme's
     *  colour (owner, 2026-11-15: the logo follows the chosen theme; the default is Signal). */
    @Volatile var accentHex: String = SIGNAL
    /** The ink on a button filled with [accentHex]. */
    @Volatile var onAccentHex: String = "#0B0D10"

    /** The chosen theme's colours, from the app (MainApplication / MainActivity). */
    fun usePalette(bg: String, surface: String, stroke: String, muted: String, text: String, strong: String,
                   accent: String, onAccent: String) {
        OBSIDIAN = bg; GRAPHITE = surface; SLATE = stroke; MIST = muted; TITANIUM = text; ICE = strong
        accentHex = accent; onAccentHex = onAccent
    }
    const val RED = "#E5484D"
    const val AMBER = "#F4C25B"
    const val GREEN = "#3DD68C"

    /** One icon, [sizePx] square, its bounds already set. [accent] is the second colour of MARK. */
    fun icon(kind: Kind, color: Int, sizePx: Int, accent: Int = Color.parseColor(accentHex)): Drawable =
        IconDrawable(kind, color, accent).apply { setBounds(0, 0, sizePx, sizePx) }

    /** An icon centred on a round or rounded background, [insetPx] in from its edge. */
    fun on(background: Drawable, kind: Kind, color: Int, insetPx: Int): Drawable =
        LayerDrawable(arrayOf(background, InsetDrawable(IconDrawable(kind, color, Color.parseColor(accentHex)), insetPx)))

    fun circle(fill: Int, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != null) setStroke(strokePx, stroke)
    }

    fun rounded(fill: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusPx
        if (stroke != null) setStroke(strokePx, stroke)
    }

    private class IconDrawable(val kind: Kind, val color: Int, val accent: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var alphaValue = 255

        override fun draw(c: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            if (s <= 0f) return
            val ox = b.left + (b.width() - s) / 2f
            val oy = b.top + (b.height() - s) / 2f
            fun x(f: Float) = ox + f * s
            fun y(f: Float) = oy + f * s
            p.color = color; p.alpha = alphaValue
            p.strokeWidth = s * 0.11f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            when (kind) {
                Kind.POWER -> {
                    p.style = Paint.Style.STROKE
                    // a ring open at the top, and the bar through the opening
                    c.drawArc(RectF(x(0.16f), y(0.20f), x(0.84f), y(0.88f)), -60f, 300f, false, p)
                    c.drawLine(x(0.5f), y(0.10f), x(0.5f), y(0.48f), p)
                }
                Kind.STOP -> {
                    p.style = Paint.Style.FILL
                    c.drawRoundRect(RectF(x(0.20f), y(0.20f), x(0.80f), y(0.80f)), s * 0.10f, s * 0.10f, p)
                }
                Kind.RECORD -> {
                    p.style = Paint.Style.FILL
                    c.drawCircle(x(0.5f), y(0.5f), s * 0.30f, p)
                }
                Kind.COMBO -> {
                    // a bolt: one burst of skills
                    p.style = Paint.Style.FILL
                    poly(floatArrayOf(0.60f, 0.06f, 0.20f, 0.56f, 0.46f, 0.56f, 0.38f, 0.94f, 0.80f, 0.42f, 0.54f, 0.42f), ox, oy, s)
                    c.drawPath(path, p)
                }
                Kind.CALIBRATE -> {
                    // a crosshair: line the scenery up with the map
                    p.style = Paint.Style.STROKE
                    c.drawCircle(x(0.5f), y(0.5f), s * 0.24f, p)
                    c.drawLine(x(0.5f), y(0.06f), x(0.5f), y(0.26f), p)
                    c.drawLine(x(0.5f), y(0.74f), x(0.5f), y(0.94f), p)
                    c.drawLine(x(0.06f), y(0.5f), x(0.26f), y(0.5f), p)
                    c.drawLine(x(0.74f), y(0.5f), x(0.94f), y(0.5f), p)
                }
                Kind.CLOSE -> {
                    p.style = Paint.Style.STROKE
                    c.drawLine(x(0.24f), y(0.24f), x(0.76f), y(0.76f), p)
                    c.drawLine(x(0.76f), y(0.24f), x(0.24f), y(0.76f), p)
                }
                Kind.MINIMIZE -> {
                    p.style = Paint.Style.STROKE
                    c.drawLine(x(0.22f), y(0.62f), x(0.78f), y(0.62f), p)
                }
                Kind.RESIZE -> {
                    // corner grip: two slanted lines
                    p.style = Paint.Style.STROKE
                    c.drawLine(x(0.86f), y(0.30f), x(0.30f), y(0.86f), p)
                    c.drawLine(x(0.86f), y(0.62f), x(0.62f), y(0.86f), p)
                }
                Kind.MARK -> {
                    // the Sphynx symbol (docs/brand/sphynx-symbol-on-dark.svg), on a 120 grid
                    p.style = Paint.Style.FILL
                    poly(MARK_A, ox, oy, s); c.drawPath(path, p)
                    poly(MARK_B, ox, oy, s); c.drawPath(path, p)
                    p.color = accent; p.alpha = alphaValue
                    poly(MARK_FOCUS, ox, oy, s); c.drawPath(path, p)
                }
            }
        }

        private fun poly(pts: FloatArray, ox: Float, oy: Float, s: Float) {
            path.rewind()
            path.moveTo(ox + pts[0] * s, oy + pts[1] * s)
            var i = 2
            while (i < pts.size) { path.lineTo(ox + pts[i] * s, oy + pts[i + 1] * s); i += 2 }
            path.close()
        }

        override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun unit(vararg v: Float) = FloatArray(v.size) { v[it] / 120f }
    private val MARK_A = unit(60f, 4f, 116f, 60f, 104.8f, 71.2f, 60f, 26.4f, 48.8f, 37.6f, 54.4f, 43.2f, 37.6f, 60f, 20.8f, 43.2f)
    private val MARK_B = unit(60f, 116f, 4f, 60f, 15.2f, 48.8f, 60f, 93.6f, 71.2f, 82.4f, 65.6f, 76.8f, 82.4f, 60f, 99.2f, 76.8f)
    private val MARK_FOCUS = unit(60f, 48.8f, 71.2f, 60f, 60f, 71.2f, 48.8f, 60f)
}

// What each of the panel's buttons says and how it looks, decided without Android so it is unit
// tested. Under the compact width a button shows its icon only (the label is empty).
object PanelButtons {

    data class Look(val label: String, val icon: PanelIcons.Kind, val fill: String, val ink: String, val stroke: String? = PanelIcons.SLATE) {
        /** Changes only when the button must be redrawn. */
        fun key(compact: Boolean) = "$label|$icon|$fill|$ink|$stroke|$compact"
        fun text(compact: Boolean) = if (compact) "" else label
    }

    fun stop() = Look("STOP ALL", PanelIcons.Kind.STOP, PanelIcons.RED, PanelIcons.ICE, null)

    fun record(recording: Boolean, megabytes: Long) =
        if (recording) Look("REC ${megabytes}MB", PanelIcons.Kind.STOP, PanelIcons.RED, PanelIcons.ICE, null)
        else Look("REC", PanelIcons.Kind.RECORD, PanelIcons.GRAPHITE, PanelIcons.RED)

    fun combo(allowed: Boolean) =
        Look(if (allowed) "COMBO" else "COMBO OFF", PanelIcons.Kind.COMBO, PanelIcons.GRAPHITE, if (allowed) PanelIcons.ICE else PanelIcons.MIST)

    fun calibrate(running: Boolean) =
        if (running) Look("STOP CALIB", PanelIcons.Kind.STOP, PanelIcons.AMBER, PanelIcons.OBSIDIAN, null)
        else Look("CALIBRATE", PanelIcons.Kind.CALIBRATE, PanelIcons.GRAPHITE, PanelIcons.ICE)

    /** The AI switch shows the state: filled with the theme's colour while the AI is on. */
    fun ai(on: Boolean) =
        if (on) Look("AI ON", PanelIcons.Kind.POWER, PanelIcons.accentHex, PanelIcons.onAccentHex, null)
        else Look("AI OFF", PanelIcons.Kind.POWER, PanelIcons.GRAPHITE, PanelIcons.MIST)

    /** The round icon's short status word and ring colour. */
    fun badge(running: Boolean, dryRun: Boolean, error: Boolean): Pair<String, String> = when {
        running && dryRun -> "DRY" to PanelIcons.AMBER
        running -> "ON" to PanelIcons.GREEN
        error -> "ERR" to PanelIcons.RED
        else -> "OFF" to PanelIcons.SLATE
    }
}
