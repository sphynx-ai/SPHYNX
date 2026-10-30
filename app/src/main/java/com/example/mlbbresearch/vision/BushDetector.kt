package com.example.mlbbresearch.vision

import kotlin.math.abs
import kotlin.math.hypot

// Bushes (tall grass): green / teal and full of thin blades, so neighbouring pixels change a
// lot, while the ground is smooth. Measured on your screenshots: bush 11-21, ground about 3.
// The screen is cut into a 40 x 20 grid; textured green cells next to each other form a bush.
// HUD, minimap and your own hero (also textured) are left out.
class Bush(val x: Float, val y: Float, val cells: Int)

object BushDetector {

    private const val GX = 40
    private const val GY = 20

    class Result(val bushes: List<Bush>, val inBush: Boolean)

    fun detect(px: IntArray, w: Int, h: Int, excl: List<FloatArray>, self: Seen?): Result {
        val bush = BooleanArray(GX * GY)
        for (gy in 0 until GY) for (gx in 0 until GX) {
            val cx = (gx + 0.5f) / GX
            val cy = (gy + 0.5f) / GY
            if (excl.any { cx >= it[0] && cx <= it[2] && cy >= it[1] && cy <= it[3] }) continue
            // your hero's model is textured too: skip the cells it stands on
            if (self != null && abs(cx - self.x) < 0.035f && cy > self.barBottom && cy < self.y + 0.06f) continue
            val x0 = gx * w / GX; val x1 = (gx + 1) * w / GX - 2
            val y0 = gy * h / GY; val y1 = (gy + 1) * h / GY
            var t = 0; var green = 0; var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = px[y * w + x]; val q = px[y * w + x + 2]
                    val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                    t += abs(g - ((q shr 8) and 0xff)) + abs(r - ((q shr 16) and 0xff))
                    if (g >= r + 12 && g >= 70 && b <= g + 40) green++
                    n++
                    x += 2
                }
                y += 2
            }
            if (n > 0 && t >= 16 * n && green * 2 >= n) bush[gy * GX + gx] = true
        }
        // groups of at least 4 touching cells are bushes (single cells are noise)
        val seen = BooleanArray(bush.size)
        val out = ArrayList<Bush>()
        val stack = IntArray(bush.size)
        for (i in bush.indices) {
            if (!bush[i] || seen[i]) continue
            var sp = 0; stack[sp++] = i; seen[i] = true
            var n = 0; var sx = 0f; var sy = 0f
            while (sp > 0) {
                val c = stack[--sp]
                n++; sx += c % GX; sy += c / GX
                val cx = c % GX; val cy = c / GX
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx !in 0 until GX || ny !in 0 until GY) continue
                    val q = ny * GX + nx
                    if (bush[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (n >= 4) out.add(Bush((sx / n + 0.5f) / GX, (sy / n + 0.5f) / GY, n))
        }
        // standing in a bush: most cells right around your feet are bush
        var inBush = false
        if (self != null) {
            var b = 0; var n = 0
            for (gy in 0 until GY) for (gx in 0 until GX) {
                val cx = (gx + 0.5f) / GX; val cy = (gy + 0.5f) / GY
                val d = hypot((cx - self.x) * 2.2f, cy - (self.y + 0.03f))
                if (d in 0.06f..0.16f) { n++; if (bush[gy * GX + gx]) b++ }
            }
            inBush = n > 0 && b * 10 >= n * 4
        }
        return Result(out, inBush)
    }
}
