package com.example.mlbbresearch.vision

import kotlin.math.abs
import kotlin.math.hypot

// Bushes (tall grass): dark teal and full of thin UPRIGHT blades, so neighbouring pixels change
// more side to side than up and down. Measured on your screenshots and gameplay video:
// bushes texX 6-6.6 with a side/up ratio 1.33-1.48 and mostly teal; ground, stone floor, river,
// rock (ratio ~0.6), tree trunks (~0.9) and leaves (~0.8) fail it.
// The screen is cut into a 40 x 20 grid; textured green cells next to each other form a bush.
// HUD, minimap and your own hero (also textured) are left out.
class Bush(val x: Float, val y: Float, val cells: Int)

object BushDetector {

    private const val GX = 40
    private const val GY = 20

    private fun lum(p: Int) = (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10

    // the bush cells of the last frame (for the overlay / tests)
    @Volatile var lastCells = BooleanArray(GX * GY)
        private set
    @Volatile var lastWalls = BooleanArray(GX * GY)
        private set

    // Is the way ahead blocked? Looks at the cells 0.08-0.2 screen heights from your feet in
    // direction (dx, dy) (screen directions, dy down). Returns the share of wall cells there.
    fun wallAhead(walls: BooleanArray, sx: Float, sy: Float, dx: Float, dy: Float, aspect: Float): Float {
        var hit = 0; var n = 0
        var d = 0.08f
        while (d <= 0.2f) {
            for (side in floatArrayOf(-0.03f, 0f, 0.03f)) {
                val x = sx + (dx * d - dy * side) / aspect
                val y = sy + dy * d + dx * side
                val gx = (x * GX).toInt(); val gy = (y * GY).toInt()
                if (gx !in 0 until GX || gy !in 0 until GY) continue
                n++
                if (walls[gy * GX + gx]) hit++
            }
            d += 0.04f
        }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    class Result(val bushes: List<Bush>, val inBush: Boolean, val walls: BooleanArray = BooleanArray(GX * GY))

    // units on screen (heroes, monsters - the turtle's shell is textured teal too) are skipped
    fun detect(px: IntArray, w: Int, h: Int, excl: List<FloatArray>, self: Seen?, units: List<Seen> = emptyList()): Result {
        val bush = BooleanArray(GX * GY)
        val wall = BooleanArray(GX * GY)
        for (gy in 0 until GY) for (gx in 0 until GX) {
            val cx = (gx + 0.5f) / GX
            val cy = (gy + 0.5f) / GY
            if (excl.any { cx >= it[0] && cx <= it[2] && cy >= it[1] && cy <= it[3] }) continue
            // your hero's model is textured too: skip the cells it stands on
            if (self != null && abs(cx - self.x) < 0.035f && cy > self.barBottom && cy < self.y + 0.06f) continue
            if (units.any { u -> cx >= u.barLeft - 0.01f && cx <= u.barRight + 0.01f && cy > u.barBottom && cy < u.y + when { u.team == Team.NEUTRAL && u.hero -> 0.35f; u.hero -> 0.12f; else -> 0.06f } }) continue
            val x0 = gx * w / GX; val x1 = (gx + 1) * w / GX - 1
            val y0 = gy * h / GY; val y1 = (gy + 1) * h / GY - 1
            // per cell: side-to-side change (texX), up-down change (texY), teal share, brightness
            var tx = 0; var ty = 0; var teal = 0; var lumSum = 0; var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = px[y * w + x]
                    val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                    val l = (r * 3 + g * 6 + b) / 10
                    tx += abs(l - lum(px[y * w + x + 1]))
                    ty += abs(l - lum(px[(y + 1) * w + x]))
                    if (g > r + 20 && b > r && l in 40..140) teal++
                    lumSum += l
                    n++
                    x++
                }
                y++
            }
            if (n == 0) continue
            val texX = tx.toFloat() / n
            val texY = ty.toFloat() / n
            val bright = lumSum / n
            // Measured (sharp screenshots + your video): bushes have texX 6-6.6, blade ratio
            // texX / texY 1.33-1.48 (vertical blades) and 71-96% teal. Ground, stone floor, river,
            // rock, trunks and leaves all fail at least one of these.
            if (texX >= 4.0f && texX >= texY * 1.05f && teal * 100 >= n * 45 && bright in 40..115) bush[gy * GX + gx] = true
            // Walls on screen (rock, cliff, tree canopy): strong UP-DOWN change (texY 6-10 vs ground
            // 2.6-3.3), more up-down than side-to-side (ratio 0.6-0.8), lighter, not teal.
            // Stone floor and grass are smooth, so they are not walls.
            else if (texY >= 4.5f && texX <= texY * 0.82f && bright in 60..175 && teal * 100 < n * 45) wall[gy * GX + gx] = true
        }
        lastCells = bush
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
        lastWalls = wall
        return Result(out, inBush, wall)
    }
}
