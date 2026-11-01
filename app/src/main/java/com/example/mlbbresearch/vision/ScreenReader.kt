package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Which MLBB screen is showing, read from colours only (no Gemma needed).
// Measured on screenshots of every screen from the home screen to the match-end scoreboard.
enum class ScreenHint(val label: String) {
    NONE("match view / unknown"),
    LIVE("live match (watching another player)"),
    HOME("home screen"),
    LOBBY("lobby (Start Game)"),
    QUEUE("match found (Enter)"),
    HERO_SELECT("hero select: choosing"),
    HERO_LOCKED("hero select: hero locked in"),
    LOADING("loading"),
    DEAD("death screen (respawn countdown)"),
    SHOP("equipment shop"),
    SCOREBOARD("in-game scoreboard"),
    MAP("big map"),
    RESULT_SPLASH("match end: Victory / Defeat"),
    RESULT_STATS("match end: MVP / ratings"),
    RESULT_BOARD("match end: scoreboard")
}

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

    private fun avgLum(px: IntArray, w: Int, h: Int, l: Float, t: Float, rr: Float, bb: Float): Int {
        val a = avg(px, w, h, l, t, rr, bb); return (a[0] * 3 + a[1] * 6 + a[2]) / 10
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

    // the cream-gold of the "VICTORY" letters (the buttons' gold is deeper)
    private fun isPaleGold(p: Int) = r(p) >= 190 && g(p) >= 165 && b(p) in 110..200 && r(p) - b(p) >= 35
    // the silver of the "DEFEAT" letters
    private fun isSilver(p: Int) = r(p) >= 170 && abs(r(p) - g(p)) <= 25 && abs(g(p) - b(p)) <= 30
    // the red band behind the DEFEAT letters
    private fun isBannerRed(p: Int) = r(p) >= 90 && r(p) - g(p) >= 50 && r(p) - b(p) >= 40
    // the light-blue buttons of the match-end screens (Data, Quit, Confirm)
    private fun isButtonBlue(p: Int) = b(p) >= 215 && g(p) >= 165 && r(p) in 90..190 && b(p) - r(p) >= 50
    // the grey (not yet pressable) Confirm button of hero select
    private fun isButtonGrey(p: Int) = r(p) in 115..170 && abs(g(p) - r(p)) <= 14 && b(p) - r(p) in -5..24
    // the orange-red V-shaped ornament around the respawn countdown
    private fun isEmber(p: Int) = r(p) >= 135 && r(p) - g(p) >= 70 && g(p) <= 85 && b(p) <= 70
    private fun isLoadBlue(p: Int) = b(p) >= 150 && b(p) - r(p) >= 110 && g(p) in 80..150
    private fun isLoadRed(p: Int) = r(p) >= 95 && g(p) <= 45 && b(p) <= 45
    private fun isDark(p: Int) = lum(p) < 80

    // Win or loss of the last match end screen: true = VICTORY (gold letters), false = DEFEAT
    // (silver letters on red), null = no match end seen
    @Volatile var lastResultWin: Boolean? = null
        private set

    fun classify(px: IntArray, w: Int, h: Int): ScreenHint {
        fun f(l: Float, t: Float, r: Float, b: Float, test: (Int) -> Boolean) = fraction(px, w, h, l, t, r, b, 2, test)

        // watching someone's live match: their info panel along the bottom
        if (livePanel(px, w, h)) return ScreenHint.LIVE

        // menus darken the middle of the screen (76%+ measured); the match view does not (41-59%):
        // keeps skill effects and blue / red ground from looking like a menu
        val midDark = f(0.10f, 0.25f, 0.90f, 0.85f) { isDark(it) } > 0.7f

        // ---- match end (dark screens with their own buttons): before anything else ----
        val quit = f(0.82f, 0.885f, 0.935f, 0.95f) { isButtonBlue(it) }
        val data = f(0.07f, 0.885f, 0.18f, 0.95f) { isButtonBlue(it) }
        if (midDark && quit > 0.15f && data > 0.15f) {                       // scoreboard with Data + Quit
            lastResultWin = f(0.40f, 0.03f, 0.60f, 0.13f) { isPaleGold(it) } > 0.05f
            return ScreenHint.RESULT_BOARD
        }
        val confirmBlue = f(0.77f, 0.885f, 0.89f, 0.95f) { isButtonBlue(it) }
        if (confirmBlue > 0.18f && data < 0.08f) {                // MVP / ratings with Confirm
            // its title (top left): gold VICTORY or silver DEFEAT. The defeat version has a bright sky
            // (only 53% dark), so the title also counts as proof that this is not the match view
            val gold = f(0.15f, 0.10f, 0.34f, 0.18f) { isPaleGold(it) }
            val silver = f(0.16f, 0.10f, 0.33f, 0.18f) { isSilver(it) }
            if (midDark || gold > 0.08f || silver > 0.07f) {
                lastResultWin = gold > 0.06f
                return ScreenHint.RESULT_STATS
            }
        }
        val cornersDark = f(0f, 0f, 0.25f, 0.30f) { isDark(it) } > 0.8f && f(0.75f, 0.70f, 1f, 1f) { isDark(it) } > 0.8f
        val banner = f(0.33f, 0.40f, 0.68f, 0.56f) { isPaleGold(it) }
        if (cornersDark && banner > 0.10f) {                      // the big VICTORY letters
            lastResultWin = true
            return ScreenHint.RESULT_SPLASH
        }
        // the big silver DEFEAT letters on a red band (measured 0.12 / 0.17; any other screen: 0.05 / 0.04 at most)
        if (cornersDark && f(0.30f, 0.40f, 0.70f, 0.58f) { isBannerRed(it) } > 0.10f && f(0.36f, 0.42f, 0.63f, 0.54f) { isSilver(it) } > 0.07f) {
            lastResultWin = false
            return ScreenHint.RESULT_SPLASH
        }

        // ---- before the match ----
        // home: the big gold CLASSIC / RANKED button, taller than the hero-select Confirm
        val homeBtn = f(0.76f, 0.87f, 0.97f, 0.97f) { isGold(it) }
        val homeTop = f(0.80f, 0.875f, 0.95f, 0.905f) { isGold(it) }
        if (homeBtn > 0.45f && homeTop > 0.35f) return ScreenHint.HOME
        // lobby: the gold Start Game button (bottom, left of the middle)
        if (homeBtn < 0.2f && f(0.27f, 0.80f, 0.48f, 0.86f) { isGold(it) } > 0.45f) return ScreenHint.LOBBY
        // match found: the gold Enter button (bottom middle)
        if (homeBtn < 0.2f && f(0.40f, 0.85f, 0.60f, 0.91f) { isGold(it) } > 0.45f) return ScreenHint.QUEUE

        // hero select: the Confirm button (bottom right): gold when a hero is chosen, grey otherwise;
        // the right panel shows the Hero tab while choosing and the Skins tab once locked in
        val (cx, cy) = Layout.get("lockIn")
        val confirm = f(cx - 0.05f, cy - 0.02f, cx + 0.05f, cy + 0.02f) { isGold(it) }
        val greyBtn = f(0.80f, 0.925f, 0.945f, 0.975f) { isButtonGrey(it) }
        if (confirm > 0.45f) return ScreenHint.HERO_SELECT
        if (greyBtn > 0.6f && f(0.80f, 0.10f, 0.96f, 0.88f) { isDark(it) } > 0.55f) {
            val heroTab = avgLum(px, w, h, 0.80f, 0.01f, 0.87f, 0.065f)
            val skinsTab = avgLum(px, w, h, 0.88f, 0.01f, 0.95f, 0.065f)
            return if (skinsTab > heroTab + 10) ScreenHint.HERO_LOCKED else ScreenHint.HERO_SELECT
        }

        // loading: progress bars under my team's cards (blue) and the enemies' (red); two layouts
        val blue = fraction(px, w, h, 0.07f, 0.38f, 0.58f, 0.40f, 2) { isBlueBar(it) }
        val red = fraction(px, w, h, 0.40f, 0.92f, 0.90f, 0.94f, 2) { isRedBar(it) }
        if (blue > 0.35f && red > 0.35f) return ScreenHint.LOADING
        if (f(0.06f, 0.44f, 0.66f, 0.455f) { isLoadBlue(it) } > 0.15f && f(0.33f, 0.955f, 0.93f, 0.972f) { isLoadRed(it) } > 0.12f)
            return ScreenHint.LOADING

        // ---- in the match ----
        // dead: the orange-red V around the respawn countdown (left wing, right wing and its tip)
        if (f(0.415f, 0.695f, 0.45f, 0.725f) { isEmber(it) } > 0.04f && f(0.55f, 0.695f, 0.585f, 0.725f) { isEmber(it) } > 0.04f &&
            f(0.485f, 0.74f, 0.515f, 0.765f) { isEmber(it) } > 0.03f) return ScreenHint.DEAD

        // scoreboard: the "VS" header is blue on the left and red on the right
        val left = avg(px, w, h, 0.15f, 0.15f, 0.35f, 0.20f)
        val right = avg(px, w, h, 0.62f, 0.15f, 0.85f, 0.20f)
        if (midDark && left[2] - left[0] >= 30 && right[0] - right[2] >= 20) return ScreenHint.SCOREBOARD

        // shop: a big dark panel over the middle of the screen (measured: the shop 93% dark;
        // dark jungle and the lord pit in play reach 66-79%, so the line sits at 86%)
        val dark = fraction(px, w, h, 0.12f, 0.15f, 0.88f, 0.80f, 6) { lum(it) < 80 }
        if (dark > 0.86f) return ScreenHint.SHOP

        // big map: a column of 5 enemy portraits with dark red rings right of the map
        var rings = 0
        var hitsAll = 0
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
            hitsAll += hits
        }
        // real map: 17 of the 20 points (top portraits complete); a red fire effect in the middle
        // of the match view reached 3 rings but only 7-8 points
        if (rings >= 3 && hitsAll >= 12) return ScreenHint.MAP
        return ScreenHint.NONE
    }

    // ---------------- live match (watching another player's game) ----------------
    // The bottom panel: navy, with their portrait, level, a green HP line, K/D/A, skill and spell
    // icons (they darken while cooling down), items and gold. Measured on a 2712 x 1220 screen.
    private fun isPanelNavy(p: Int) = r(p) in 12..55 && g(p) in 32..85 && b(p) in 45..110 && b(p) - r(p) >= 18
    private fun isHpGreen(p: Int) = g(p) >= 140 && g(p) - max(r(p), b(p)) >= 40
    private fun isHpEmpty(p: Int) = max(r(p), max(g(p), b(p))) < 75 && max(r(p), max(g(p), b(p))) - min(r(p), min(g(p), b(p))) < 20

    fun livePanel(px: IntArray, w: Int, h: Int): Boolean =
        fraction(px, w, h, 0.36f, 0.848f, 0.80f, 0.858f, 2) { isPanelNavy(it) } > 0.9f &&
            fraction(px, w, h, 0.43f, 0.869f, 0.61f, 0.876f, 2) { isHpGreen(it) || isHpEmpty(it) } > 0.85f

    // their HP: the green share of the line (0..1), -1 when it cannot be read
    fun liveHp(px: IntArray, w: Int, h: Int): Float {
        val y = (0.872f * h).toInt().coerceIn(0, h - 1)
        val x0 = (0.421f * w).toInt(); val x1 = (0.616f * w).toInt()
        var green = 0; var empty = 0
        for (x in x0..x1) {
            val p = px[y * w + x]
            if (isHpGreen(p)) green++ else if (isHpEmpty(p)) empty++
        }
        val n = x1 - x0 + 1
        return if (green + empty < n * 0.8f) -1f else green.toFloat() / n
    }

    // skill / spell icons of the panel (centre x, y)
    val LIVE_ICONS = linkedMapOf("s1" to (0.417f to 0.946f), "s2" to (0.462f to 0.946f), "ult" to (0.505f to 0.946f),
        "spell" to (0.564f to 0.946f))

    // average brightness of an icon (a disc around its centre): drops when it starts cooling down
    fun iconBrightness(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val rad = 0.012f * w
        var sum = 0L; var n = 0
        val cx = fx * w; val cy = fy * h
        var y = (cy - rad).toInt()
        while (y <= cy + rad) {
            var x = (cx - rad).toInt()
            while (x <= cx + rad) {
                if (x in 0 until w && y in 0 until h && hypot(x - cx, y - cy) <= rad) { sum += lum(px[y * w + x]); n++ }
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    // what is NOT the field on the live screen (no unit is ever read there)
    val LIVE_MASKS = listOf(
        floatArrayOf(0.28f, 0.00f, 1.00f, 0.085f),  // scoreboard, hero portraits, streamer name / likes
        floatArrayOf(0.895f, 0.00f, 1.00f, 0.80f),  // the right column of buttons (shop, like, gift, ranking...)
        floatArrayOf(0.32f, 0.835f, 0.875f, 1.00f), // their info panel
        floatArrayOf(0.00f, 0.83f, 0.35f, 1.00f),   // chat bar and its buttons
        floatArrayOf(0.18f, 0.00f, 0.28f, 0.06f),   // battery / ping
        floatArrayOf(0.36f, 0.085f, 0.58f, 0.18f),  // "Lord / Turtle ready in ..." notice under the scoreboard
        MinimapAnalyzer.rect
    )

    // camera motion only (the live screen has no skill buttons or quick-buy icon)
    fun liveHud(px: IntArray, w: Int, h: Int): HudInfo {
        val (mx, my) = motion(px, w, h)
        return HudInfo(emptySet(), false, mx, my)
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
