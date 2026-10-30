package com.example.mlbbresearch.vision

// Reads the minimap (top-left corner) and counts the red enemy icons and blue ally icons.
// Approximate: it only counts clusters of red/blue pixels, it does not know which hero is which.
class MinimapInfo(val enemies: Int, val allies: Int, val enemyDots: List<Pair<Float, Float>>)

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
        return MinimapInfo(dots.size, allies, dots)
    }
}
