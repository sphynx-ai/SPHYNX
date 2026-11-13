package com.example.mlbbresearch.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

// A circle graph with a percentage in the middle (the Learn page: how much better than the last training).
// The ring fills by |percent| (100% or more = full): green when better, red when worse, grey when there is
// nothing to compare yet.
class ProgressRing(ctx: Context) : View(ctx) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#283247") }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E9ECF2"); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val box = RectF()
    private var percent: Double? = null
    private var center = "-"

    // percent: improvement vs the last training (null = nothing to compare); text: what the middle says
    fun set(percent: Double?, text: String) {
        if (this.percent == percent && center == text) return
        this.percent = percent; center = text
        arc.color = Color.parseColor(when { percent == null -> "#8D96AA"; percent >= 0 -> "#3DD68C"; else -> "#FF5A6A" })
        contentDescription = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = 6f * density
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        val half = stroke / 2
        box.set(half, half, width - half, height - half)
        canvas.drawArc(box, 0f, 360f, false, track)
        val p = percent
        val sweep = if (p == null) 0f else (Math.min(Math.abs(p), 100.0) / 100.0 * 360.0).toFloat().coerceAtLeast(4f)
        if (sweep > 0f) canvas.drawArc(box, -90f, if (p!! >= 0) sweep else -sweep, false, arc)
        label.textSize = (if (center.length > 7) 10f else 14f) * density
        val y = height / 2f - (label.descent() + label.ascent()) / 2
        canvas.drawText(center, width / 2f, y, label)
    }
}
