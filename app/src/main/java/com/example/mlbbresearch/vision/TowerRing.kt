package com.example.mlbbresearch.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

// The circle MLBB draws around an ENEMY tower when you come near, and what its colour means:
//   GREEN  - your minions are inside: safe to walk in and hit the tower
//   YELLOW - you are just outside and no minions are inside: a warning, do not walk in
//   RED    - you are inside with no minions: the tower is (or will be) shooting you, get out
// Found by colour (bright lime / yellow / red-orange pixels outside the HUD and the HP bars),
// kept only when they form a big arc, and a circle is fitted to them (seen from the camera's
// tilt, so the y axis is stretched first): that gives the tower's position on screen, the
// range, and whether your hero stands inside.
object TowerRing {

    enum class Zone { NONE, GREEN, YELLOW, RED }

    class Ring(val zone: Zone, val cx: Float, val cy: Float, val rx: Float, val ry: Float, val inside: Boolean) {
        // direction from a point to the tower centre (screen fractions -> unit vector in screen-height units)
        fun dirFrom(x: Float, y: Float, aspect: Float): Pair<Float, Float> {
            val dx = (cx - x) * aspect; val dy = cy - y
            val l = hypot(dx, dy).coerceAtLeast(1e-4f)
            return (dx / l) to (dy / l)
        }
    }

    val NONE = Ring(Zone.NONE, -1f, -1f, 0f, 0f, false)

    private const val TILT = 1.45f      // the ring is a circle on the ground: about 1.45x wider than tall on screen

    private fun colour(p: Int): Zone {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return when {
            g >= 175 && g - r >= 45 && g - b >= 70 && r >= 80 -> Zone.GREEN
            r >= 200 && g >= 175 && b <= 150 && r - b >= 80 && r - g < 45 -> Zone.YELLOW
            r >= 190 && g in 70..175 && b <= 140 && r - g >= 50 -> Zone.RED
            else -> Zone.NONE
        }
    }

    fun detect(px: IntArray, w: Int, h: Int, excl: List<FloatArray>, units: List<Seen>, selfX: Float, selfY: Float): Ring {
        val step = 2
        val xs = Array(4) { ArrayList<Int>() }
        val ys = Array(4) { ArrayList<Int>() }
        val exclPx = excl.map { intArrayOf((it[0] * w).toInt(), (it[1] * h).toInt(), (it[2] * w).toInt(), (it[3] * h).toInt()) }
        // HP bars (red enemies, green you) and the bodies right under them are not the ring
        val unitPx = units.map { u -> intArrayOf(((u.barLeft - 0.03f) * w).toInt(), ((u.barTop - 0.03f) * h).toInt(),
            ((u.barRight + 0.01f) * w).toInt(), ((u.barBottom + 0.02f) * h).toInt()) }
        var y = (0.10f * h).toInt()
        while (y < h) {
            var x = 0
            while (x < w) {
                val z = colour(px[y * w + x])
                if (z != Zone.NONE && exclPx.none { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] } &&
                    unitPx.none { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] }
                ) { xs[z.ordinal].add(x); ys[z.ordinal].add(y) }
                x += step
            }
            y += step
        }
        // the colour with the biggest arc wins
        var best = NONE
        var bestN = 0
        for (z in listOf(Zone.GREEN, Zone.YELLOW, Zone.RED)) {
            val n = xs[z.ordinal].size
            if (n < 60 || n <= bestN) continue
            val r = fit(xs[z.ordinal], ys[z.ordinal], w, h, z, selfX, selfY) ?: continue
            best = r; bestN = n
        }
        return best
    }

    private fun fit(xs: List<Int>, ys: List<Int>, w: Int, h: Int, z: Zone, selfX: Float, selfY: Float): Ring? {
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        // a ring is big: a long arc across the screen
        if (maxX - minX < w * 0.18f || maxY - minY < h * 0.18f) return null
        var idx = xs.indices.toList()
        var c: DoubleArray? = null
        repeat(2) {
            c = kasa(idx.map { xs[it].toDouble() }, idx.map { ys[it] * TILT.toDouble() }) ?: return null
            val (a, b, r) = c!!
            // drop what is far from the circle (skill effects, the tower's shot) and fit again
            idx = idx.filter { abs(hypot(xs[it] - a, ys[it] * TILT - b) - r) < r * 0.12 }
            if (idx.size < 50) return null
        }
        val (a, b, r) = c!!
        val inl = idx.size.toFloat() / xs.size
        if (inl < 0.45f || r < w * 0.15 || r > w * 0.9) return null
        val cx = (a / w).toFloat(); val cy = (b / TILT / h).toFloat()
        val rx = (r / w).toFloat(); val ry = (r / TILT / h).toFloat()
        val dx = (selfX - cx) / rx; val dy = (selfY - cy) / ry
        return Ring(z, cx, cy, rx, ry, dx * dx + dy * dy < 1f)
    }

    // least-squares circle through points: (a, b, radius)
    private fun kasa(x: List<Double>, y: List<Double>): DoubleArray? {
        val n = x.size
        if (n < 10) return null
        val mx = x.average(); val my = y.average()
        var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (i in 0 until n) {
            val u = x[i] - mx; val v = y[i] - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-9) return null
        val rhs1 = 0.5 * (suuu + suvv); val rhs2 = 0.5 * (svvv + svuu)
        val uc = (rhs1 * svv - rhs2 * suv) / det
        val vc = (suu * rhs2 - suv * rhs1) / det
        val r = sqrt(uc * uc + vc * vc + (suu + svv) / n)
        return doubleArrayOf(uc + mx, vc + my, r)
    }
}
