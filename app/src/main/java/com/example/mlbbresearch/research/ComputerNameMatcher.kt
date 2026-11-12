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

    // AT THE APP'S CAPTURE SIZE (device test 2026-11-10: frames are 800x359, so the word is about 49x9
    // pixels on the scoreboard and 39x7 on a loading card) the template must be shrunk by AVERAGING
    // blocks of pixels - picking every 3rd-4th pixel lost the thin strokes - and, because a one-pixel
    // difference in width or height moves the score a lot at that size, a few sizes around the expected
    // one are tried and the best match is kept. Measured on the owner's screenshots shrunk to 800x359:
    // "Computer" 0.66 -> 0.92 (scoreboard) and 0.59..0.75 -> 0.81..0.89 (loading cards); real players'
    // names 0.50 at most. At 16 px tall or more only the expected size is used (as before).
    private class Tpl(val px: FloatArray, val w: Int, val h: Int, val dev: FloatArray, val ss: Float)
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, Tpl>()

    private fun tpl(w: Int, h: Int): Tpl = cache.getOrPut(w.toLong() * 100_000 + h) {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = y * th / h; val y1 = maxOf(y0 + 1, (y + 1) * th / h)
            for (x in 0 until w) {
                val x0 = x * tw / w; val x1 = maxOf(x0 + 1, (x + 1) * tw / w)
                var sum = 0f
                for (yy in y0 until y1) for (xx in x0 until x1) sum += t[yy * tw + xx]
                out[y * w + x] = sum / ((y1 - y0) * (x1 - x0))
            }
        }
        var mean = 0f; for (v in out) mean += v; mean /= out.size
        val dev = FloatArray(out.size) { out[it] - mean }
        var ss = 0f; for (v in dev) ss += v * v
        Tpl(out, w, h, dev, ss)
    }

    private fun sizes(scale: Float): List<Tpl> {
        val w0 = (tw * scale).toInt().coerceAtLeast(8); val h0 = (th * scale).toInt().coerceAtLeast(4)
        if (h0 >= 16) return listOf(tpl(w0, h0))
        val out = ArrayList<Tpl>()
        for (h in (h0 - 1).coerceAtLeast(4)..h0 + 1) for (w in (w0 - 2).coerceAtLeast(8)..w0 + 2) out.add(tpl(w, h))
        return out
    }

    // best match of the template with its top-left corner near (x0, y0), in screen fractions;
    // searches +-dx / +-dy reference pixels. Returns -1 when the window leaves the frame.
    // sizeMul: the word's size relative to the scoreboard's (loading cards: 0.8)
    fun score(px: IntArray, w: Int, h: Int, x0: Float, y0: Float, dx: Int = 24, dy: Int = 16, sizeMul: Float = 1f): Float {
        val scale = w.toFloat() / REF_WIDTH * sizeMul
        val cx = (x0 * w).toInt(); val cy = (y0 * h).toInt()
        val sx = (dx * scale).toInt().coerceAtLeast(1); val sy = (dy * scale).toInt().coerceAtLeast(1)
        val step = (2 * scale).toInt().coerceAtLeast(1)
        var best = -1f
        for (tp in sizes(scale)) {
            val sw = tp.w; val sh = tp.h
            var y = cy - sy
            while (y <= cy + sy) {
                var x = cx - sx
                while (x <= cx + sx) {
                    if (x >= 0 && y >= 0 && x + sw <= w && y + sh <= h) {
                        var pm = 0f
                        for (j in 0 until sh) { val r = (y + j) * w + x; for (i in 0 until sw) pm += bright(px[r + i]) }
                        pm /= sw * sh
                        var num = 0f; var pv = 0f
                        for (j in 0 until sh) {
                            val r = (y + j) * w + x
                            for (i in 0 until sw) { val p = bright(px[r + i]) - pm; num += p * tp.dev[j * sw + i]; pv += p * p }
                        }
                        val d = sqrt(pv * tp.ss)
                        if (d > 0f) best = maxOf(best, num / d)
                    }
                    x += step
                }
                y += step
            }
        }
        return best
    }

    private fun bright(c: Int): Float = maxOf((c shr 16) and 255, (c shr 8) and 255, c and 255).toFloat()
}
