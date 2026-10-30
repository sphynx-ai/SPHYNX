package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout
import kotlin.math.max
import kotlin.math.min

// Reads the K / D / A numbers at the top right without reading digits: it remembers what
// each number looks like (a small black-and-white pattern) and reports +1 when the pattern
// changes and stays changed for 2 frames. Kills, deaths and assists only go up, so a change
// means one more. Used to confirm kills and as reward for the learning AI.
object KdaReader {

    private const val GW = 10
    private const val GH = 6
    private val keys = listOf("kdaK", "kdaD", "kdaA")
    private val stable = arrayOfNulls<BooleanArray>(3)
    private val pending = arrayOfNulls<BooleanArray>(3)

    // events since the last call: kills, deaths, assists
    fun read(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(3)
        for (i in 0 until 3) {
            val p = pattern(px, w, h, keys[i]) ?: continue
            val s = stable[i]
            if (s == null) { stable[i] = p; continue }
            if (diff(p, s) < 0.18f) { pending[i] = null; continue }
            val q = pending[i]
            if (q != null && diff(p, q) < 0.18f) {
                stable[i] = p
                pending[i] = null
                out[i] = 1
            } else pending[i] = p
        }
        return out
    }

    fun reset() {
        for (i in 0 until 3) { stable[i] = null; pending[i] = null }
    }

    // white pixels in a box starting at the number (numbers grow to the right)
    private fun pattern(px: IntArray, w: Int, h: Int, key: String): BooleanArray? {
        val (fx, fy) = Layout.get(key)
        val bw = 0.022f * w
        val bh = 0.026f * h
        val x0 = fx * w - bw * 0.35f
        val y0 = fy * h - bh / 2
        if (x0 < 0 || y0 < 0 || x0 + bw >= w || y0 + bh >= h) return null
        val out = BooleanArray(GW * GH)
        var any = false
        for (gy in 0 until GH) for (gx in 0 until GW) {
            val x = (x0 + (gx + 0.5f) * bw / GW).toInt()
            val y = (y0 + (gy + 0.5f) * bh / GH).toInt()
            val p = px[y * w + x]
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            val white = min(r, min(g, b)) >= 190 && max(r, max(g, b)) - min(r, min(g, b)) < 50
            out[gy * GW + gx] = white
            if (white) any = true
        }
        return if (any) out else null
    }

    private fun diff(a: BooleanArray, b: BooleanArray): Float {
        var d = 0
        for (i in a.indices) if (a[i] != b[i]) d++
        return d.toFloat() / a.size
    }
}
