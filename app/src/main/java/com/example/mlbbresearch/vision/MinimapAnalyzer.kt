package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout

// Reads the minimap (top-left corner) and counts the red enemy icons and blue ally icons.
// Approximate: it only counts clusters of red/blue pixels, it does not know which hero is which.
class MinimapInfo(
    val enemies: Int,
    val allies: Int,
    val enemyDots: List<Pair<Float, Float>>,
    val self: Pair<Float, Float>? = null,          // your icon (green ring), 0..1 inside the map
    val camps: List<Camp> = emptyList()            // jungle camps on YOUR side of the river
)

// buff = big magenta camp icon, else a small green / cyan camp icon. Positions 0..1 inside the map.
class Camp(val x: Float, val y: Float, val buff: Boolean)

object MinimapAnalyzer {
    // Minimap area as fractions of the screen: left, top, right, bottom
    val rect = floatArrayOf(0.0f, 0.0f, 0.19f, 0.37f)

    private const val STEP = 2

    fun analyze(px: IntArray, w: Int, h: Int): MinimapInfo {
        val x0 = (rect[0] * w).toInt()
        val y0 = (rect[1] * h).toInt()
        val x1 = (rect[2] * w).toInt().coerceAtMost(w - 1)
        val y1 = (rect[3] * h).toInt().coerceAtMost(h - 1)
        val gw = (x1 - x0) / STEP + 1
        val gh = (y1 - y0) / STEP + 1
        if (gw <= 0 || gh <= 0) return MinimapInfo(0, 0, emptyList())
        val grid = IntArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val p = px[(y0 + gy * STEP) * w + (x0 + gx * STEP)]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                grid[gy * gw + gx] = when {
                    r > 170 && g < 90 && b < 90 -> 1
                    b > 170 && r < 100 && b - r > 80 -> 2
                    else -> 0
                }
            }
        }
        val dots = ArrayList<Pair<Float, Float>>()
        var allies = 0
        val seen = BooleanArray(grid.size)
        val stack = IntArray(grid.size)
        for (i in grid.indices) {
            if (grid[i] == 0 || seen[i]) continue
            val cls = grid[i]
            var sp = 0
            stack[sp++] = i
            seen[i] = true
            var n = 0
            var sx = 0
            var sy = 0
            while (sp > 0) {
                val c = stack[--sp]
                n++
                val cx = c % gw
                val cy = c / gw
                sx += cx; sy += cy
                val nb = intArrayOf(c - 1, c + 1, c - gw, c + gw)
                for (k in nb.indices) {
                    val q = nb[k]
                    if (q < 0 || q >= grid.size) continue
                    if ((k == 0 && cx == 0) || (k == 1 && cx == gw - 1)) continue
                    if (!seen[q] && grid[q] == cls) {
                        seen[q] = true
                        stack[sp++] = q
                    }
                }
            }
            if (n < 3 || n > 60) continue   // too small = noise, too big = not an icon
            if (cls == 1) dots.add((sx.toFloat() / n / gw) to (sy.toFloat() / n / gh)) else allies++
        }
        val (self, camps) = jungle(px, w, h)
        return MinimapInfo(dots.size, allies, dots, self, camps)
    }

    // MLBB always draws YOUR base bottom-left and the river from top-left to bottom-right,
    // so your jungle is the part below that diagonal (y > x inside the map square).
    // Lanes run along the left edge (EXP), bottom edge (gold) and the other diagonal (mid).
    fun jungle(px: IntArray, w: Int, h: Int): Pair<Pair<Float, Float>?, List<Camp>> {
        val (l, t) = Layout.get("mapTL")
        val (r, b) = Layout.get("mapBR")
        val mw = r - l
        val mh = b - t
        if (mw < 0.03f || mh < 0.05f) return null to emptyList()
        fun inMap(bl: ButtonDetector.Blob) = ((bl.cx - l) / mw) to ((bl.cy - t) / mh)

        // you: the only BIG green ring (camp dots are tiny)
        val green = ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { isGreen(it) }
        val me = green.filter { it.w / mw >= 0.04f }.maxByOrNull { it.count }?.let { inMap(it) }

        val out = ArrayList<Camp>()
        fun add(bl: ButtonDetector.Blob, buff: Boolean) {
            val (x, y) = inMap(bl)
            if (y < x + 0.03f) return                        // enemy side of the river
            if (x < 0.15f || y > 0.88f) return               // EXP / gold lane edges
            if (kotlin.math.abs(x + y - 1f) < 0.08f) return  // mid lane
            if (me != null && kotlin.math.hypot(x - me.first, y - me.second) < 0.07f) return
            out.add(Camp(x, y, buff))
        }
        for (bl in ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { isMagenta(it) }) if (bl.count >= 8) add(bl, true)
        for (bl in green) if (bl.w / mw < 0.035f && bl.count in 3..10) add(bl, false)
        for (bl in ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { isCyan(it) }) if (bl.count in 3..10) add(bl, false)
        return me to out
    }

    private fun isGreen(p: Int): Boolean {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return g >= 150 && g - r >= 50 && g - b >= 40
    }

    private fun isMagenta(p: Int): Boolean {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return r >= 140 && b >= 140 && g <= 110 && r - g >= 60
    }

    private fun isCyan(p: Int): Boolean {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return g >= 120 && b >= 130 && r <= 60 && kotlin.math.abs(g - b) <= 40
    }
}
