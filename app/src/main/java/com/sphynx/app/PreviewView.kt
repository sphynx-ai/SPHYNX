package com.sphynx.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.sphynx.capture.FrameHub
import com.sphynx.vision.FrameProcessor
import com.sphynx.vision.ObjectDetector
import com.sphynx.vision.Team

// Small live picture of what the AI sees, with a box around each detected unit.
class PreviewView(ctx: Context) : View(ctx) {
    private var frame: Bitmap? = null
    private val dst = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f * ctx.resources.displayMetrics.density; setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val bg = Paint().apply { color = Color.parseColor("#101317") }

    // called by the page about twice a second
    fun refresh() {
        frame = if (FrameHub.isLive()) FrameHub.bitmap() else null
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val f = frame
        if (f == null) {
            c.drawText("No screen capture yet - tap 'Start screen capture'", 12f, height / 2f, text)
            return
        }
        val scale = minOf(width.toFloat() / f.width, height.toFloat() / f.height)
        val w = f.width * scale
        val h = f.height * scale
        val ox = (width - w) / 2f
        val oy = (height - h) / 2f
        dst.set(ox, oy, ox + w, oy + h)
        c.drawBitmap(f, null, dst, bmpPaint)
        val v = FrameProcessor.latest ?: return
        for (s in v.all) {
            box.color = when (s.team) {
                Team.SELF -> Color.WHITE
                Team.ALLY -> Color.parseColor("#64B5F6")
                Team.ENEMY -> if (s.hero) Color.parseColor("#FFD600") else Color.parseColor("#FF8A65")
                Team.NEUTRAL -> Color.parseColor("#CE93D8")
            }
            val r = RectF(ox + s.barLeft * w, oy + s.barTop * h, ox + s.barRight * w, oy + (s.y + 0.04f) * h)
            c.drawRect(r, box)
            if (s.hero) c.drawText("${(s.hp * 100).toInt()}%", r.left, r.top - 2f, text)
        }
        // trained detector results (cyan boxes with labels)
        if (System.currentTimeMillis() - ObjectDetector.latestAt < 1500) {
            box.color = Color.parseColor("#4DD0E1")
            for (d in ObjectDetector.latest) {
                val r = RectF(ox + d.left * w, oy + d.top * h, ox + d.right * w, oy + d.bottom * h)
                c.drawRect(r, box)
                c.drawText("${d.label} ${(d.score * 100).toInt()}%", r.left, r.bottom + text.textSize, text)
            }
        }
    }
}
