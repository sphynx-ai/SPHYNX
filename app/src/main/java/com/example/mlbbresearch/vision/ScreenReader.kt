package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Which MLBB screen is showing, read from colours only (no Gemma needed).
// Measured on the hero-select, loading, shop, scoreboard and big-map screens.
enum class ScreenHint { NONE, HERO_SELECT, LOADING, SHOP, SCOREBOARD, MAP }

// Small HUD facts read every frame
class HudInfo(
    val upgradeReady: Set<String>,      // "s1", "s2", "ult" whose golden "+" is showing
    val quickBuyVisible: Boolean,       // the recommended item icon (top right) is showing
    val motionX: Float,                 // where the camera (= your hero) moved since the last frame, -1..1
    val motionY: Float
) {
    companion object {
        val NONE = HudInfo(emptySet(), false, 0f, 0f)
    }
}

object ScreenReader {

    // ---------------- colours ----------------

    private fun r(p: Int) = (p shr 16) and 0xff
    private fun g(p: Int) = (p shr 8) and 0xff
    private fun b(p: Int) = p and 0xff
    fun lum(p: Int) = (r(p) * 3 + g(p) * 6 + b(p)) / 10

    fun sat(p: Int): Float {
        val mx = max(r(p), max(g(p), b(p)))
        val mn = min(r(p), min(g(p), b(p)))
        return if (mx < 20) 0f else (mx - mn).toFloat() / mx
    }

    // golden "+" / quick-buy ring / Confirm button
    fun isGold(p: Int) = r(p) >= 150 && g(p) >= 120 && b(p) <= 140 && r(p) - b(p) >= 70 && g(p) - b(p) >= 45 && r(p) + 10 >= g(p)

    private fun isWhite(p: Int) = lum(p) >= 215 && sat(p) < 0.22f

    // loading-screen bars: bright where loaded, darker where not, but always clearly blue / red
    private fun isBlueBar(p: Int) = b(p) >= 90 && b(p) - r(p) >= 50 && b(p) >= g(p)
    private fun isRedBar(p: Int) = r(p) >= 100 && r(p) - g(p) >= 60 && r(p) - b(p) >= 25

    // dark red ring around an enemy portrait (big map)
    private fun isPortraitRed(p: Int) = r(p) >= 100 && r(p) - g(p) >= 60 && r(p) - b(p) >= 60

    // fraction of sampled pixels in [l, t, r, b] (screen fractions) that match
    fun fraction(px: IntArray, w: Int, h: Int, l: Float, t: Float, rr: Float, bb: Float, step: Int = 3, test: (Int) -> Boolean): Float {
        val x0 = (l * w).toInt().coerceIn(0, w - 1)
        val x1 = (rr * w).toInt().coerceIn(0, w - 1)
        val y0 = (t * h).toInt().coerceIn(0, h - 1)
        val y1 = (bb * h).toInt().coerceIn(0, h - 1)
        var n = 0
        var hit = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                if (test(px[y * w + x])) hit++
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    // average r, g, b of a region
    private fun avg(px: IntArray, w: Int, h: Int, l: Float, t: Float, rr: Float, bb: Float): IntArray {
        val x0 = (l * w).toInt().coerceIn(0, w - 1)
        val x1 = (rr * w).toInt().coerceIn(0, w - 1)
        val y0 = (t * h).toInt().coerceIn(0, h - 1)
        val y1 = (bb * h).toInt().coerceIn(0, h - 1)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = px[y * w + x]
                sr += r(p); sg += g(p); sb += b(p); n++
                x += 4
            }
            y += 4
        }
        if (n == 0) return intArrayOf(0, 0, 0)
        return intArrayOf((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    // ---------------- announcements ----------------

    // Kill / assist / "FIRST BLOOD" banners (top middle) have long red or blue border lines
    // and hero portraits with coloured rings: they must not be read as units.
    // A row with 6%+ of the screen width in banner red / blue = a banner is up.
    val bannerRect = floatArrayOf(0.30f, 0.06f, 0.70f, 0.34f)

    fun announcement(px: IntArray, w: Int, h: Int): Boolean {
        val x0 = (0.33f * w).toInt(); val x1 = (0.67f * w).toInt()
        val need = (0.06f * w).toInt()
        var y = (0.12f * h).toInt()
        val y1 = (0.32f * h).toInt()
        while (y < y1) {
            var red = 0; var blue = 0
            var x = x0
            while (x < x1) {
                val p = px[y * w + x]
                val r = r(p); val g = g(p); val b = b(p)
                if (r >= 150 && g <= 90 && b <= 110 && r - g >= 80) red++
                if (b >= 150 && r <= 90 && b - r >= 70) blue++
                x++
            }
            if (red >= need || blue >= need) return true
            y += 2
        }
        return false
    }

    // ---------------- turtle / lord HP bar ----------------

    // While you fight the turtle or the lord, its HP bar shows under the scoreboard (with its
    // portrait on the left and "11436/12005"). Returns its HP 0..1, or -1 when it is not there.
    // Measured on a 2712 x 1220 screen: the bar runs from 46.5% to 55.7% of the width at 8%.
    fun bossBar(px: IntArray, w: Int, h: Int): Float {
        val x0 = (0.468f * w).toInt(); val x1 = (0.556f * w).toInt()
        var best = -1f
        for (fy in floatArrayOf(0.074f, 0.079f, 0.084f)) {
            val y = (fy * h).toInt().coerceIn(0, h - 1)
            var red = 0; var lastRed = -1
            for (x in x0..x1) {
                val p = px[y * w + x]
                if (r(p) >= 150 && g(p) <= 90 && b(p) <= 100 && r(p) - g(p) >= 80) { red++; lastRed = x }
            }
            if (red >= (x1 - x0) / 8) best = maxOf(best, (lastRed - x0 + 1).toFloat() / (x1 - x0 + 1))
        }
        return best
    }

    // ---------------- which screen ----------------

    fun classify(px: IntArray, w: Int, h: Int): ScreenHint {
        // hero select / draft: the big golden Confirm button (bottom right by default)
        val (cx, cy) = Layout.get("lockIn")
        val confirm = fraction(px, w, h, cx - 0.05f, cy - 0.02f, cx + 0.05f, cy + 0.02f, 2) { isGold(it) }
        if (confirm > 0.45f) return ScreenHint.HERO_SELECT

        // loading: a row of blue progress bars (my team) and a row of red ones (enemies)
        val blue = fraction(px, w, h, 0.07f, 0.38f, 0.58f, 0.40f, 2) { isBlueBar(it) }
        val red = fraction(px, w, h, 0.40f, 0.92f, 0.90f, 0.94f, 2) { isRedBar(it) }
        if (blue > 0.35f && red > 0.35f) return ScreenHint.LOADING

        // scoreboard: the "VS" header is blue on the left and red on the right
        val left = avg(px, w, h, 0.15f, 0.15f, 0.35f, 0.20f)
        val right = avg(px, w, h, 0.62f, 0.15f, 0.85f, 0.20f)
        if (left[2] - left[0] >= 30 && right[0] - right[2] >= 20) return ScreenHint.SCOREBOARD

        // shop: a big dark panel over the middle of the screen (measured: the shop 93% dark;
        // dark jungle and the lord pit in play reach 66-79%, so the line sits at 86%)
        val dark = fraction(px, w, h, 0.12f, 0.15f, 0.88f, 0.80f, 6) { lum(it) < 80 }
        if (dark > 0.86f) return ScreenHint.SHOP

        // big map: a column of 5 enemy portraits with dark red rings right of the map
        var rings = 0
        val rx = 0.021f
        val ry = rx * w / h
        for (cy in floatArrayOf(0.144f, 0.26f, 0.378f, 0.494f, 0.605f)) {
            val pts = listOf(0.4085f + rx to cy, 0.4085f - rx to cy, 0.4085f to cy + ry, 0.4085f to cy - ry)
            val hits = pts.count { (x, y) ->
                val xi = (x * w).toInt().coerceIn(0, w - 1)
                val yi = (y * h).toInt().coerceIn(0, h - 1)
                isPortraitRed(px[yi * w + xi])
            }
            if (hits >= 2) rings++
        }
        if (rings >= 3) return ScreenHint.MAP
        return ScreenHint.NONE
    }

    // ---------------- HUD ----------------


    fun hud(px: IntArray, w: Int, h: Int, withMovement: Boolean): HudInfo {
        val ups = HashSet<String>()
        val rad = 0.011f
        for ((slot, key) in listOf("s1" to "s1Up", "s2" to "s2Up", "ult" to "ultUp")) {
            val (x, y) = Layout.get(key)
            val ry = rad * w / h
            if (fraction(px, w, h, x - rad, y - ry, x + rad, y + ry, 1) { isGold(it) } > 0.35f) ups.add(slot)
        }
        val qb = quickBuyShowing(px, w, h)

        var mx = 0f; var my = 0f
        if (withMovement) {
            val m = motion(px, w, h)
            mx = m.first; my = m.second
        } else {
            history.clear()
        }
        return HudInfo(ups, qb, mx, my)
    }

    // The recommended item: a golden-rimmed icon with its white price written just below it
    fun quickBuyShowing(px: IntArray, w: Int, h: Int): Boolean {
        val (qx, qy) = Layout.get("quickBuy")
        val hx = h.toFloat() / w
        val price = fraction(px, w, h, qx - 0.03f * hx, qy + 0.035f, qx + 0.03f * hx, qy + 0.07f, 1) { isWhite(it) }
        if (price < 0.10f) return false
        return ring(px, w, h, qx, qy, 0.045f * hx) { isGold(it) || isWhite(it) } > 0.12f
    }

    // fraction of a thin ring (radius as fraction of width) that matches
    private fun ring(px: IntArray, w: Int, h: Int, fx: Float, fy: Float, radius: Float, test: (Int) -> Boolean): Float {
        val cx = fx * w
        val cy = fy * h
        val rpx = radius * w
        var n = 0
        var hit = 0
        for (i in 0 until 48) {
            val a = i * Math.PI * 2 / 48
            for (k in floatArrayOf(0.85f, 1f, 1.12f)) {
                val x = (cx + Math.cos(a) * rpx * k).toInt()
                val y = (cy + Math.sin(a) * rpx * k).toInt()
                if (x !in 0 until w || y !in 0 until h) continue
                n++
                if (test(px[y * w + x])) hit++
            }
        }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    // Camera follows your hero, so the whole field shifting left means you walked right.
    // Block matching of the middle of the field against the picture from about a quarter of a
    // second ago (one frame apart the shift is only a few pixels: slow and up / down movement got
    // lost, so diagonal walking was read as "right" and most walking as "standing"). A finer grid
    // (5 px at 800 wide) with sub-pixel interpolation, and the up / down part scaled up for the
    // camera's tilt (ground distances look 1.45x shorter up / down than across).
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }
    private val history = ArrayDeque<Pair<Long, IntArray>>()
    private var histW = 0
    private var histH = 0
    private var lastMove = 0f to 0f
    private var lastMoveAt = 0L

    private fun motion(px: IntArray, w: Int, h: Int): Pair<Float, Float> {
        val now = clock()
        val gw = 160
        val gh = max(16, gw * h / w)
        val grid = IntArray(gw * gh)
        for (gy in 0 until gh) {
            val y = gy * h / gh
            for (gx in 0 until gw) grid[gy * gw + gx] = lum(px[y * w + gx * w / gw])
        }
        if (histW != gw || histH != gh) { history.clear(); histW = gw; histH = gh }
        history.addLast(now to grid)
        while (history.isNotEmpty() && now - history.first().first > 700) history.removeFirst()
        // the reference: the newest picture at least 180 ms old
        val ref = history.lastOrNull { now - it.first >= 180 } ?: return if (now - lastMoveAt < 300) lastMove else (0f to 0f)
        val prev = ref.second
        val span = (now - ref.first).coerceAtLeast(1L)

        val s = 10
        // middle of the field: away from the minimap, skill buttons, joystick and scoreboard
        val ax0 = max(s, (gw * 0.30f).toInt()); val ax1 = min(gw - s, (gw * 0.62f).toInt())
        val ay0 = max(s, (gh * 0.15f).toInt()); val ay1 = min(gh - s, (gh * 0.55f).toInt())
        val costs = LongArray((2 * s + 1) * (2 * s + 1))
        var best = Long.MAX_VALUE
        var bx = 0; var by = 0
        for (dy in -s..s) for (dx in -s..s) {
            var cost = 0L
            var y = ay0
            while (y < ay1) {
                var x = ax0
                val row = y * gw; val prow = (y + dy) * gw + dx
                while (x < ax1) {
                    cost += abs(grid[row + x] - prev[prow + x])
                    x += 2
                }
                y += 2
            }
            costs[(dy + s) * (2 * s + 1) + dx + s] = cost
            if (cost < best) { best = cost; bx = dx; by = dy }
        }
        val zero = costs[s * (2 * s + 1) + s]
        // unclear (effects flashing, fights) or no shift: standing
        if (best > zero * 0.85 || bx == 0 && by == 0) return (0f to 0f).also { lastMove = it; lastMoveAt = now }
        // sub-cell position from a parabola through the neighbouring costs
        fun c(dx: Int, dy: Int): Long? = if (dx in -s..s && dy in -s..s) costs[(dy + s) * (2 * s + 1) + dx + s] else null
        fun sub(cm: Long?, c0: Long, cp: Long?): Float {
            if (cm == null || cp == null) return 0f
            val den = (cm - 2 * c0 + cp).toFloat()
            return if (den <= 0f) 0f else ((cm - cp) / (2f * den)).coerceIn(-0.5f, 0.5f)
        }
        val fx = bx + sub(c(bx - 1, by), best, c(bx + 1, by))
        val fy = (by + sub(c(bx, by - 1), best, c(bx, by + 1))) * 1.45f
        // at least ~1 cell per quarter second (slower = shuffling on the spot)
        val l = hypot(fx, fy)
        val res = if (l * 250f / span < 0.9f) 0f to 0f else (fx / l) to (fy / l)
        lastMove = res; lastMoveAt = now
        return res
    }
}
