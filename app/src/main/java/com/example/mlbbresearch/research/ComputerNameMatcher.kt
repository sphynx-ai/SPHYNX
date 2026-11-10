package com.example.mlbbresearch.research

import kotlin.math.sqrt

// Finds the word "Computer" (MLBB's name for its bot opponents) at a scoreboard name position.
// The template is a 169x32 crop of that word from the owner's own practice scoreboard
// (reference-library MLBB-SCREEN-014), stored as raw 8-bit greyscale in
// assets/research/computer_word_169x32.gray. It contains no player data.
// Matching: normalised cross-correlation on the brightest colour channel, so the white
// (ally) and red (enemy) spellings both match. Measured on 2712x1220 screenshots:
// "Computer" 0.91..1.00, real player names 0.21..0.51.
class ComputerNameMatcher(template: ByteArray, private val tw: Int = 169, private val th: Int = 32) {

    companion object {
        const val REF_WIDTH = 2712           // frame width the template was cut from
        const val ASSET = "research/computer_word_169x32.gray"
    }

    init { require(template.size == tw * th) { "template must be ${tw}x$th bytes" } }
    private val t = FloatArray(tw * th) { (template[it].toInt() and 255).toFloat() }

    private fun scaledTemplate(scale: Float): Triple<FloatArray, Int, Int> {
        if (scale == 1f) return Triple(t, tw, th)
        val w = (tw * scale).toInt().coerceAtLeast(8); val h = (th * scale).toInt().coerceAtLeast(4)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = t[((y * th) / h) * tw + (x * tw) / w]
        return Triple(out, w, h)
    }

    // best match of the template with its top-left corner near (x0, y0), in screen fractions;
    // searches +-dx / +-dy reference pixels. Returns -1 when the window leaves the frame.
    // sizeMul: the word's size relative to the scoreboard's (the solo loading card: 0.8)
    fun score(px: IntArray, w: Int, h: Int, x0: Float, y0: Float, dx: Int = 24, dy: Int = 16, sizeMul: Float = 1f): Float {
        val scale = w.toFloat() / REF_WIDTH * sizeMul
        val (tp, sw, sh) = scaledTemplate(scale)
        var tMean = 0f; for (v in tp) tMean += v; tMean /= tp.size
        var tVar = 0f; for (v in tp) tVar += (v - tMean) * (v - tMean)
        val cx = (x0 * w).toInt(); val cy = (y0 * h).toInt()
        val sx = (dx * scale).toInt().coerceAtLeast(1); val sy = (dy * scale).toInt().coerceAtLeast(1)
        val step = (2 * scale).toInt().coerceAtLeast(1)
        var best = -1f
        var y = cy - sy
        while (y <= cy + sy) {
            var x = cx - sx
            while (x <= cx + sx) {
                if (x >= 0 && y >= 0 && x + sw <= w && y + sh <= h) {
                    var pm = 0f
                    for (j in 0 until sh) { val r = (y + j) * w + x; for (i in 0 until sw) pm += bright(px[r + i]) }
                    pm /= tp.size
                    var num = 0f; var pv = 0f
                    for (j in 0 until sh) {
                        val r = (y + j) * w + x
                        for (i in 0 until sw) { val p = bright(px[r + i]) - pm; num += p * (tp[j * sw + i] - tMean); pv += p * p }
                    }
                    val d = sqrt(pv * tVar)
                    if (d > 0f) best = maxOf(best, num / d)
                }
                x += step
            }
            y += step
        }
        return best
    }

    private fun bright(c: Int): Float = maxOf((c shr 16) and 255, (c shr 8) and 255, c and 255).toFloat()
}
