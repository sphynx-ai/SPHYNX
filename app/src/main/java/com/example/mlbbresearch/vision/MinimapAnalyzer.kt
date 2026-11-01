package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout
import kotlin.math.max
import kotlin.math.min

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
    val selfIcon: FloatArray? = null,                     // your icon on screen: centre x, y, radius (fractions of width)
    val allyDots: List<Pair<Float, Float>> = emptyList(), // blue portrait rings (same units as enemyDots)
    val lordIcon: FloatArray? = null,                     // the lord / turtle icon in its pit (alive):
    val turtleIcon: FloatArray? = null                    //   left, top, right, bottom as screen fractions
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

    // live: the minimap of a live match you watch. Its reds are pinker (enemy rings about
    // 210, 85, 100) and the watched player's camera range is drawn over it as big cyan circles,
    // which must not count as ally rings.
    fun analyze(px: IntArray, w: Int, h: Int, live: Boolean = false): MinimapInfo {
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
                grid[gy * gw + gx] = if (live) when {
                    r > 170 && g < 110 && r - b > 60 && r - g > 80 -> 1
                    b > 170 && r < 100 && b - r > 80 && g < 190 -> 2
                    else -> 0
                } else when {
                    r > 170 && g < 90 && b < 90 -> 1
                    b > 170 && r < 100 && b - r > 80 -> 2
                    else -> 0
                }
            }
        }
        val dots = ArrayList<Pair<Float, Float>>()
        val blue = ArrayList<Pair<Float, Float>>()
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
            if (cls == 1) dots.add((sx.toFloat() / n / gw) to (sy.toFloat() / n / gh)) else { allies++; blue.add((sx.toFloat() / n / gw) to (sy.toFloat() / n / gh)) }
        }
        val (self, camps) = jungle(px, w, h)
        val icon = lastIcon
        val st = structures(px, w, h)
        // enemy towers are red too: do not count them as enemy heroes
        if (live) {
            // live: enemy heroes are found by their RING shape (towers / base icons are small and solid)
            val rings = liveRings(px, w, h) { r, g, b -> r > 170 && g < 110 && r - b > 60 && r - g > 80 }
            // ally rings are blue; the camera circles are cyan and far bigger than a portrait
            val allyRings = liveRings(px, w, h) { r, g, b -> b > 170 && r < 120 && b - r > 70 }
            val (lord, turtle) = pits(px, w, h)
            // the watched hero: the camera circles are drawn around them
            val me = liveSelf(px, w, h, allyRings)
            return MinimapInfo(rings.size, allyRings.size, rings, me, camps, st.towers, st.base, st.waves, st.allyTowers, st.enemyWaves, icon, allyRings, lord, turtle)
        }
        val heroDots = dots.filter { d -> CLOUDS.none { kotlin.math.hypot(it.first - d.first, it.second - d.second) < 0.06f } &&
            st.towers.none { kotlin.math.hypot(it.x - d.first, it.y - d.second) < 0.05f } &&
                st.enemyWaves.none { kotlin.math.hypot(it.first - d.first, it.second - d.second) < 0.02f }
        }
        val (lord, turtle) = pits(px, w, h)
        return MinimapInfo(heroDots.size, allies, heroDots, self, camps, st.towers, st.base, st.waves, st.allyTowers, st.enemyWaves, icon, blue, lord, turtle)
    }

    // The watched hero on a live match's minimap: the cyan camera circles are centred on them.
    // A circle fit that drops the points far from the circle (the blue lane highlight, the part
    // cut off at the map edge), then the portrait ring nearest to that centre if one is close.
    private fun liveSelf(px: IntArray, w: Int, h: Int, rings: List<Pair<Float, Float>>): Pair<Float, Float>? {
        val x1 = (rect[2] * w).toInt().coerceAtMost(w - 1); val y1 = (rect[3] * h).toInt().coerceAtMost(h - 1)
        var xs = ArrayList<Float>(); var ys = ArrayList<Float>()
        for (y in 0..y1) for (x in 0..x1) {
            val p = px[y * w + x]
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            if (r < 150 && g > 190 && b > 210) { xs.add(x.toFloat()); ys.add(y.toFloat()) }
        }
        if (xs.size < 60) return null
        var cx = 0.0; var cy = 0.0; var rad = 0.0
        repeat(6) {
            // least squares circle: x^2 + y^2 = 2ax + 2by + c
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0; val n = xs.size.toDouble()
            var sxz = 0.0; var syz = 0.0; var sz = 0.0
            for (i in xs.indices) {
                val x = xs[i].toDouble(); val y = ys[i].toDouble(); val z = x * x + y * y
                sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y; sxz += x * z; syz += y * z; sz += z
            }
            // normal equations for [2a, 2b, c]
            val m = arrayOf(doubleArrayOf(sxx, sxy, sx), doubleArrayOf(sxy, syy, sy), doubleArrayOf(sx, sy, n))
            val v = doubleArrayOf(sxz, syz, sz)
            val sol = solve3(m, v) ?: return null
            cx = sol[0] / 2; cy = sol[1] / 2
            rad = kotlin.math.sqrt((sol[2] + cx * cx + cy * cy).coerceAtLeast(0.0))
            val res = xs.indices.map { kotlin.math.abs(kotlin.math.hypot(xs[it] - cx, ys[it] - cy) - rad) }
            val cut = maxOf(2.0, res.sorted()[(res.size * 0.6).toInt().coerceAtMost(res.size - 1)])
            val nx = ArrayList<Float>(); val ny = ArrayList<Float>()
            for (i in xs.indices) if (res[i] < cut) { nx.add(xs[i]); ny.add(ys[i]) }
            if (nx.size < 30) return@repeat
            xs = nx; ys = ny
        }
        val fx = (cx / (x1 + 1)).toFloat(); val fy = (cy / (y1 + 1)).toFloat()
        if (fx !in 0f..1f || fy !in 0f..1f) return null
        val near = rings.minByOrNull { kotlin.math.hypot(it.first - fx, it.second - fy) }
        return if (near != null && kotlin.math.hypot(near.first - fx, near.second - fy) < 0.1f) near else fx to fy
    }

    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        if (kotlin.math.abs(d) < 1e-9) return null
        return DoubleArray(3) { k -> det(Array(3) { r -> DoubleArray(3) { c -> if (c == k) v[r] else m[r][c] } }) / d }
    }

    // Enemy portrait rings on a live match's minimap: a pink-red outline at least ~1.2% of the
    // screen width across and mostly hollow (measured: rings 12-16 px wide at 800 px, 20-26%
    // filled; tower and base icons 2-8 px and 45-100% filled). Two overlapping portraits give
    // two arcs, so they still count as two.
    private fun liveRings(px: IntArray, w: Int, h: Int, test: (Int, Int, Int) -> Boolean): List<Pair<Float, Float>> {
        val x0 = (rect[0] * w).toInt(); val y0 = (rect[1] * h).toInt()
        val x1 = (rect[2] * w).toInt().coerceAtMost(w - 1); val y1 = (rect[3] * h).toInt().coerceAtMost(h - 1)
        val gw = x1 - x0 + 1; val gh = y1 - y0 + 1
        val m = BooleanArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val p = px[(y0 + y) * w + x0 + x]
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            m[y * gw + x] = test(r, g, b)
        }
        val minSide = (0.0125f * w).toInt().coerceAtLeast(4)
        val maxSide = (0.032f * w).toInt()
        val seen = BooleanArray(m.size)
        val stack = IntArray(m.size)
        val out = ArrayList<Pair<Float, Float>>()
        for (i in m.indices) {
            if (!m[i] || seen[i]) continue
            var sp = 0; stack[sp++] = i; seen[i] = true
            var n = 0; var sx = 0; var sy = 0
            var minX = gw; var maxX = 0; var minY = gh; var maxY = 0
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % gw; val cy = c / gw
                n++; sx += cx; sy += cy
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx; if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val q = ny * gw + nx
                    if (m[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (maxOf(bw, bh) < minSide || maxOf(bw, bh) > maxSide || n.toFloat() / (bw * bh) > 0.35f) continue
            out.add((sx.toFloat() / n / gw) to (sy.toFloat() / n / gh))
        }
        return out
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

    // The two pits are always in the same place (measured on your screenshots, in map units):
    // top-left and bottom-right of the river. What sits in them changes during the match (the
    // turtle can be in the top-left pit mid-game, the lord there late game), so the icon is
    // named by its look, not its pit: the LORD is a tall trophy shape (pink early, gold when it is
    // up, grey when dead), the TURTLE a wide pink blob. A dead / missing one has no coloured icon.
    val PITS = listOf(0.31f to 0.26f, 0.67f to 0.68f)

    fun pits(px: IntArray, w: Int, h: Int): Pair<FloatArray?, FloatArray?> {
        val (l, t) = Layout.get("mapTL")
        val (r, b) = Layout.get("mapBR")
        val mw = r - l; val mh = b - t
        if (mw < 0.03f || mh < 0.05f) return null to null
        var lord: FloatArray? = null; var turtle: FloatArray? = null
        var lordArea = 0; var turtleArea = 0
        for (c in PITS) {
            val x0 = ((l + (c.first - 0.1f) * mw) * w).toInt().coerceAtLeast(0)
            val x1 = ((l + (c.first + 0.1f) * mw) * w).toInt().coerceAtMost(w - 1)
            val y0 = ((t + (c.second - 0.11f) * mh) * h).toInt().coerceAtLeast(0)
            val y1 = ((t + (c.second + 0.1f) * mh) * h).toInt().coerceAtMost(h - 1)
            // coloured pixels of the window, then the BIGGEST connected shape (camp dots and
            // portrait rings nearby are separate shapes)
            val ww = x1 - x0 + 1; val wh = y1 - y0 + 1
            if (ww <= 0 || wh <= 0) continue
            val cls = IntArray(ww * wh)            // 0 none, 1 magenta, 2 gold
            for (y in y0..y1) for (x in x0..x1) {
                val p = px[y * w + x]
                val rr = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bb = p and 0xff
                cls[(y - y0) * ww + (x - x0)] = when {
                    rr > 170 && g > 130 && bb < 125 && rr - bb > 70 && rr >= g -> 2
                    rr > 170 && bb > 140 && g < 140 && rr - g > 60 && bb - g > 60 -> 1
                    else -> 0
                }
            }
            val seen = BooleanArray(cls.size)
            var n = 0; var gold = 0; var minX = w; var maxX = 0; var minY = h; var maxY = 0; var best = -1
            val stack = IntArray(cls.size)
            for (i0 in cls.indices) {
                if (cls[i0] == 0 || seen[i0]) continue
                var sp = 0; stack[sp++] = i0; seen[i0] = true
                var cn = 0; var cg = 0; var ax = w; var bx2 = 0; var ay = h; var by2 = 0
                while (sp > 0) {
                    val i = stack[--sp]
                    val cx = i % ww; val cy = i / ww
                    cn++; if (cls[i] == 2) cg++
                    ax = min(ax, cx); bx2 = max(bx2, cx); ay = min(ay, cy); by2 = max(by2, cy)
                    // pieces up to 2 px apart across / 3 px up-down belong together (the trophy's cup and stem)
                    for (dy in -3..3) for (dx in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx; val ny = cy + dy
                        if (nx !in 0 until ww || ny !in 0 until wh) continue
                        val j = ny * ww + nx
                        if (cls[j] != 0 && !seen[j]) { seen[j] = true; stack[sp++] = j }
                    }
                }
                if (cn > n) { n = cn; gold = cg; minX = ax + x0; maxX = bx2 + x0; minY = ay + y0; maxY = by2 + y0; best = i0 }
            }
            // the lord's trophy has a thin STEM: 3+ rows below the cup with only 1-2 coloured pixels
            // close to the middle; the turtle is a blob without one
            var stem = false
            if (best >= 0) {
                val cxMid = (minX + maxX) / 2f - x0
                var run = 0
                for (yy in (minY - y0 + (maxY - minY) / 3)..(maxY - y0)) {
                    var c = 0; var sx = 0
                    for (xx in (minX - x0)..(maxX - x0)) if (cls[yy * ww + xx] != 0) { c++; sx += xx }
                    run = if (c in 1..2 && kotlin.math.abs(sx.toFloat() / c - cxMid) <= 2.5f) run + 1 else 0
                    if (run >= 3) stem = true
                }
            }
            // a real icon: enough pixels in a compact shape (not a few camp dots)
            if (n < 10 || maxX - minX > w * 0.04f || maxY - minY > h * 0.08f) continue
            val box = floatArrayOf((minX - 1f) / w, (minY - 1f) / h, (maxX + 2f) / w, (maxY + 2f) / h)
            val area = (maxX - minX + 1) * (maxY - minY + 1)
            if (gold * 2 > n || stem) { if (lord == null || area > lordArea) { lord = box; lordArea = area } }
            else if (turtle == null || area > turtleArea) { turtle = box; turtleArea = area }
        }
        return lord to turtle
    }

    // Flying-cloud pick-up spots on the minimap (measured on your screenshots): never towers or enemies
    val CLOUDS = listOf(0.20f to 0.25f, 0.79f to 0.72f)

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
        // towers only stand on the three lanes: the map edges (EXP / gold) and the bottom-left to
        // top-right diagonal (mid). Icons elsewhere (the golden Lord / turtle pit, the flying-cloud
        // pick-ups, coins) are not towers.
        // the two flying-cloud pick-ups (small yellow dots, same place every match, mirrored)
        fun cloud(p: Pair<Float, Float>) = CLOUDS.any { near(it, p, 0.06f) }
        fun onLane(p: Pair<Float, Float>) = !cloud(p) && p.first < 0.14f || p.first > 0.86f || p.second < 0.12f || p.second > 0.88f ||
            kotlin.math.abs(p.first + p.second - 1f) < 0.13f
        val red = ButtonDetector.blobs(px, w, h, l, t, r, b, 1) { p ->
            val rr = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bb = p and 0xff
            rr >= 170 && g <= 110 && bb <= 120 && rr - g >= 80 || MinimapColors.matches(MinimapColors.Kind.ENEMY_TOWER, p)
        }
        val base = red.filter { it.w / mw >= 0.075f && it.count >= 20 }.maxByOrNull { it.count }?.let { inMap(it) }
        val redTowers = red.filter { it.count >= 12 && it.w / mw in 0.018f..0.07f }.map { inMap(it) }
            .filter { tw -> (base == null || !near(tw, base, 0.08f)) && onLane(tw) }
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
                    if (rr >= 190 && g <= 165 && rr - bb >= 90 && rr - g >= 50) redCap++
                    if (bb >= 170 && rr <= 130 && bb - rr >= 60) blueCap++
                    xx += 1f / w
                }
                yy += 1f / h
            }
            val pos = inMap(yb)
            // a tower icon has a coloured cap and stands upright; round dots (flying clouds,
            // coins) and the Lord / turtle pit icon are not towers
            if (redCap + blueCap < 2) continue
            if (yb.h < yb.w * 1.05f) continue
            if (!onLane(pos)) continue
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
        val allyTowers = (blueAll.filter { it.count in 15..50 && it.w / mw in 0.04f..0.10f }.map { inMap(it) }.filter { onLane(it) } +
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
