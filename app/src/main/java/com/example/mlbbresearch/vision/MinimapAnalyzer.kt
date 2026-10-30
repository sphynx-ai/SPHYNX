package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout

// Reads the minimap (top-left corner) and counts the red enemy icons and blue ally icons.
// Approximate: it only counts clusters of red/blue pixels, it does not know which hero is which.
class MinimapInfo(
    val enemies: Int,
    val allies: Int,
    val enemyDots: List<Pair<Float, Float>>,
    val self: Pair<Float, Float>? = null,          // your icon (green ring), 0..1 inside the map
    val camps: List<Camp> = emptyList(),           // jungle camps on YOUR side of the river
    val towers: List<MapTower> = emptyList(),             // enemy towers still standing
    val enemyBase: Pair<Float, Float>? = null,            // the enemy base (destroy it to win)
    val allyWaves: List<Pair<Float, Float>> = emptyList(),// your minion waves (small blue marks)
    val allyTowers: List<Pair<Float, Float>> = emptyList(),
    val enemyWaves: List<Pair<Float, Float>> = emptyList(),
    val selfIcon: FloatArray? = null                      // your icon on screen: centre x, y, radius (fractions of width)
)

// bonus = red-and-yellow icon: an enemy tower that gives extra gold when attacked
class MapTower(val x: Float, val y: Float, val bonus: Boolean) {
    val pos: Pair<Float, Float> get() = x to y
}

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
        val icon = lastIcon
        val st = structures(px, w, h)
        // enemy towers are red too: do not count them as enemy heroes
        val heroDots = dots.filter { d ->
            st.towers.none { kotlin.math.hypot(it.x - d.first, it.y - d.second) < 0.05f } &&
                st.enemyWaves.none { kotlin.math.hypot(it.first - d.first, it.second - d.second) < 0.02f }
        }
        return MinimapInfo(heroDots.size, allies, heroDots, self, camps, st.towers, st.base, st.waves, st.allyTowers, st.enemyWaves, icon)
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

        // you: the only BIG green ring (camp dots are tiny). The ring can be partly hidden, so
        // its centre comes from a circle fitted through the ring's pixels.
        val green = ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { isGreen(it) }
        val ring = green.filter { it.w / mw >= 0.04f }.maxByOrNull { it.count }
        lastIcon = ring?.let { fitRing(px, w, h, it, mw) }
        val me = lastIcon?.let { ((it[0] - l) / mw) to ((it[1] * w / h - t) / mh) } ?: ring?.let { inMap(it) }

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

    class Structures(
        val towers: List<MapTower>, val base: Pair<Float, Float>?, val waves: List<Pair<Float, Float>>,
        val allyTowers: List<Pair<Float, Float>>, val enemyWaves: List<Pair<Float, Float>>
    )

    // Enemy towers: compact solid red icons. Enemy base: the biggest red icon (top-right).
    // Hero portraits only have a thin red ring, so they make small red groups.
    // Destroyed towers are drawn yellow/grey, so they are not picked up.
    fun structures(px: IntArray, w: Int, h: Int): Structures {
        val (l, t) = Layout.get("mapTL")
        val (r, b) = Layout.get("mapBR")
        val mw = r - l
        val mh = b - t
        if (mw < 0.03f || mh < 0.05f) return Structures(emptyList(), null, emptyList(), emptyList(), emptyList())
        fun inMap(bl: ButtonDetector.Blob) = ((bl.cx - l) / mw) to ((bl.cy - t) / mh)
        fun near(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Float) = kotlin.math.hypot(a.first - b.first, a.second - b.second) < d
        val red = ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { p ->
            val rr = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bb = p and 0xff
            rr >= 170 && g <= 110 && bb <= 120 && rr - g >= 80 || MinimapColors.matches(MinimapColors.Kind.ENEMY_TOWER, p)
        }
        val base = red.filter { it.w / mw >= 0.075f && it.count >= 20 }.maxByOrNull { it.count }?.let { inMap(it) }
        val redTowers = red.filter { it.count >= 12 && it.w / mw in 0.018f..0.07f }.map { inMap(it) }
            .filter { tw -> base == null || !near(tw, base, 0.08f) }
        // yellow tower icons: a yellow body with a RED cap = enemy tower with a gold bonus,
        // a yellow body with a BLUE cap = one of your towers
        val yellowBlobs = ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { p ->
            val rr = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bb = p and 0xff
            rr >= 200 && g >= 160 && bb <= 150 && rr - bb >= 80 && g - bb >= 50 || MinimapColors.matches(MinimapColors.Kind.BONUS_TOWER, p)
        }.filter { it.count >= 8 && it.w / mw in 0.012f..0.07f }
        val yellowEnemy = ArrayList<Pair<Float, Float>>()
        val yellowAlly = ArrayList<Pair<Float, Float>>()
        for (yb in yellowBlobs) {
            val top = yb.cy - yb.h * w / h / 2f
            val capH = 0.025f * mh
            var redCap = 0
            var blueCap = 0
            var yy = top - capH
            while (yy < top) {
                var xx = yb.cx - 0.02f * mw
                while (xx <= yb.cx + 0.02f * mw) {
                    val xi = (xx * w).toInt().coerceIn(0, w - 1)
                    val yi = (yy * h).toInt().coerceIn(0, h - 1)
                    val p = px[yi * w + xi]
                    val rr = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bb = p and 0xff
                    if (rr >= 190 && g <= 140 && rr - g >= 70) redCap++
                    if (bb >= 170 && rr <= 130 && bb - rr >= 60) blueCap++
                    xx += 1f / w
                }
                yy += 1f / h
            }
            val pos = inMap(yb)
            if (redCap + blueCap < 2 && yb.count < 12) continue      // not a tower icon
            if (pos.second < pos.first - 0.02f) yellowEnemy.add(pos) else yellowAlly.add(pos)
        }
        // the river runs corner to corner from top-left to bottom-right: your half is below it
        // (y > x), the enemy half above it. Towers are told apart by side, not by colour
        // (both teams' outer towers can be drawn yellow).
        fun enemySide(p: Pair<Float, Float>) = p.second < p.first - 0.02f
        val towers = redTowers.filter { enemySide(it) }.map { tw -> MapTower(tw.first, tw.second, yellowEnemy.any { near(it, tw, 0.05f) }) } +
            yellowEnemy.filter { enemySide(it) }.filter { y -> redTowers.none { near(it, y, 0.05f) } && (base == null || !near(y, base, 0.08f)) }
                .map { MapTower(it.first, it.second, true) }
        // ally towers: compact solid blue icons (portrait rings are bigger, minion marks smaller)
        val blueAll = ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { p ->
            val rr = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bb = p and 0xff
            bb >= 170 && rr <= 120 && bb - rr >= 70 && g >= 100 || MinimapColors.matches(MinimapColors.Kind.ALLY_TOWER, p)
        }
        // your towers stand on your half (left edge, bottom edge, lower mid); enemy ones on theirs
        fun allySide(p: Pair<Float, Float>) = p.second > p.first + 0.02f
        val allyTowers = (blueAll.filter { it.count in 15..50 && it.w / mw in 0.04f..0.10f }.map { inMap(it) } +
            yellowAlly.filter { y -> blueAll.none { it.count in 15..50 && near(inMap(it), y, 0.04f) } }).filter { allySide(it) }
        // enemy minion waves: small red marks
        val enemyWaves = red.filter { it.count in 4..9 && it.w / mw <= 0.03f }.map { inMap(it) }
            .filter { wv -> towers.none { near(it.pos, wv, 0.03f) } }
        // your minion waves: small blue marks in a lane
        val waves = blueAll.filter { it.count in 4..9 && it.w / mw <= 0.03f }.map { inMap(it) }
        return Structures(towers, base, waves, allyTowers, enemyWaves)
    }

    @Volatile private var lastIcon: FloatArray? = null

    // Kasa least-squares circle through the green ring pixels near a blob.
    // Returns x, y (y as a fraction of the WIDTH, so the circle stays round) and radius, as fractions of the width.
    private fun fitRing(px: IntArray, w: Int, h: Int, bl: ButtonDetector.Blob, mw: Float): FloatArray? {
        val reach = 0.07f * mw * w
        val cx0 = bl.cx * w; val cy0 = bl.cy * h
        var n = 0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        var y = (cy0 - reach).toInt().coerceAtLeast(0)
        while (y <= (cy0 + reach).toInt().coerceAtMost(h - 1)) {
            var x = (cx0 - reach).toInt().coerceAtLeast(0)
            while (x <= (cx0 + reach).toInt().coerceAtMost(w - 1)) {
                if (isGreen(px[y * w + x])) {
                    val xd = x.toDouble(); val yd = y.toDouble(); val z = xd * xd + yd * yd
                    n++; sx += xd; sy += yd; sxx += xd * xd; syy += yd * yd; sxy += xd * yd
                    sxz += xd * z; syz += yd * z; sz += z
                }
                x++
            }
            y++
        }
        if (n < 8) return null
        // solve [sxx sxy sx; sxy syy sy; sx sy n] [a b c] = [sxz syz sz]; centre = (a/2, b/2)
        val m = arrayOf(doubleArrayOf(sxx, sxy, sx, sxz), doubleArrayOf(sxy, syy, sy, syz), doubleArrayOf(sx, sy, n.toDouble(), sz))
        for (col in 0 until 3) {
            val piv = (col until 3).maxByOrNull { kotlin.math.abs(m[it][col]) }!!
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            if (kotlin.math.abs(m[col][col]) < 1e-9) return null
            for (row in 0 until 3) if (row != col) {
                val f = m[row][col] / m[col][col]
                for (k in col until 4) m[row][k] -= f * m[col][k]
            }
        }
        val a = m[0][3] / m[0][0]; val b = m[1][3] / m[1][1]; val c = m[2][3] / m[2][2]
        val cx = a / 2; val cy = b / 2
        val rad = kotlin.math.sqrt((c + cx * cx + cy * cy).coerceAtLeast(0.0))
        if (rad < 0.025 * mw * w || rad > 0.09 * mw * w) return null
        return floatArrayOf((cx / w).toFloat(), (cy / w).toFloat(), (rad / w).toFloat())
    }

    private fun isGreen(p: Int): Boolean {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return g >= 150 && g - r >= 50 && g - b >= 40 ||
            MinimapColors.matches(MinimapColors.Kind.SELF, p) || MinimapColors.matches(MinimapColors.Kind.CREEP, p)
    }

    private fun isMagenta(p: Int): Boolean {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return r >= 140 && b >= 140 && g <= 110 && r - g >= 60 || MinimapColors.matches(MinimapColors.Kind.BUFF, p)
    }

    private fun isCyan(p: Int): Boolean {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return g >= 120 && b >= 130 && r <= 60 && kotlin.math.abs(g - b) <= 40
    }
}
