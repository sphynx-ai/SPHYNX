package com.sphynx.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

// THE BOTTOM BAR'S ICONS, drawn in code (owner rule for icons: never text symbols or emoji - a phone's font may
// lack them). One line icon per section, on a 20 x 20 grid, in any colour and size.
object NavIcons {

    fun icon(sectionKey: String, color: Int, sizePx: Int): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
        }
        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

        override fun draw(cv: Canvas) {
            val b = bounds
            val u = minOf(b.width(), b.height()) / 20f          // one grid unit
            paint.strokeWidth = 1.9f * u
            cv.save(); cv.translate(b.left.toFloat(), b.top.toFloat())
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = cv.drawLine(x1 * u, y1 * u, x2 * u, y2 * u, paint)
            fun path(vararg p: Float, close: Boolean = true) {
                val pa = Path(); pa.moveTo(p[0] * u, p[1] * u)
                var i = 2; while (i < p.size) { pa.lineTo(p[i] * u, p[i + 1] * u); i += 2 }
                if (close) pa.close(); cv.drawPath(pa, paint)
            }
            when (sectionKey) {
                "play" -> path(6.5f, 4f, 16f, 10f, 6.5f, 16f)                                   // a play triangle
                "hero" -> path(10f, 3f, 16.5f, 5.5f, 16.5f, 11f, 10f, 17.5f, 3.5f, 11f, 3.5f, 5.5f)   // a shield
                "knowledge" -> {                                                                   // an open book
                    path(10f, 5.5f, 3.5f, 4f, 3.5f, 15f, 10f, 16.5f, 16.5f, 15f, 16.5f, 4f)
                    line(10f, 5.5f, 10f, 16.5f)
                }
                "ai" -> {                                                                          // a chip
                    cv.drawRoundRect(RectF(6f * u, 6f * u, 14f * u, 14f * u), 1.5f * u, 1.5f * u, paint)
                    line(10f, 2.5f, 10f, 6f); line(10f, 14f, 10f, 17.5f); line(2.5f, 10f, 6f, 10f); line(14f, 10f, 17.5f, 10f)
                }
                else -> {                                                                          // settings: sliders
                    line(3.5f, 6f, 16.5f, 6f); line(3.5f, 14f, 16.5f, 14f)
                    cv.drawCircle(7.5f * u, 6f * u, 2.1f * u, paint); cv.drawCircle(12.5f * u, 14f * u, 2.1f * u, paint)
                }
            }
            cv.restore()
        }
    }
}
