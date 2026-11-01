package com.example.mlbbresearch.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

// Small charts for the Speed page (drawn by hand: no chart library needed).

// The last 2 minutes as a line, with the budget as a dashed line and a soft fill under the line.
class Sparkline(ctx: Context) : View(ctx) {
    private var data = FloatArray(0)
    private var budget = 0f
    private var color = Color.WHITE
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * resources.displayMetrics.density; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density; color = Color.parseColor("#55FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(values: FloatArray, budget: Float, color: Int) { data = values; this.budget = budget; this.color = color; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 4f * resources.displayMetrics.density
        if (data.size < 2) return
        val top = maxOf(data.maxOrNull() ?: 0f, budget * 1.15f, 1e-3f)
        fun x(i: Int) = pad + (w - 2 * pad) * i / (data.size - 1)
        fun y(v: Float) = h - pad - (h - 2 * pad) * (v / top).coerceIn(0f, 1f)
        if (budget > 0f) c.drawLine(pad, y(budget), w - pad, y(budget), dash)
        val p = Path(); p.moveTo(x(0), y(data[0]))
        for (i in 1 until data.size) p.lineTo(x(i), y(data[i]))
        val area = Path(p); area.lineTo(x(data.size - 1), h); area.lineTo(x(0), h); area.close()
        fill.shader = LinearGradient(0f, 0f, 0f, h, (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = color
        c.drawPath(p, line)
        dot.color = color
        c.drawCircle(x(data.size - 1), y(data.last()), 3.5f * resources.displayMetrics.density, dot)
    }
}

// How full the budget is: a rounded track with a coloured part.
class BudgetBar(ctx: Context) : View(ctx) {
    private var frac = 0f
    private var color = Color.GREEN
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#232B3B") }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(fraction: Float, color: Int) { frac = fraction.coerceIn(0f, 1f); this.color = color; invalidate() }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, track)
        if (frac <= 0f) return
        bar.color = color
        c.drawRoundRect(RectF(0f, 0f, maxOf(height.toFloat(), width * frac), height.toFloat()), r, r, bar)
    }
}

// One bar split into parts (what the frame-to-touch time is made of).
class StackBar(ctx: Context) : View(ctx) {
    private var parts: List<Pair<Float, Int>> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#232B3B") }
    fun set(parts: List<Pair<Float, Int>>) { this.parts = parts; invalidate() }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(full, r, r, track)
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) return
        c.save()
        val clip = Path().apply { addRoundRect(full, r, r, Path.Direction.CW) }
        c.clipPath(clip)
        var x = 0f
        val gap = resources.displayMetrics.density
        for ((v, col) in parts) {
            val w = width * v / total
            p.color = col
            c.drawRect(x, 0f, x + w - gap, height.toFloat(), p)
            x += w
        }
        c.restore()
    }
}
