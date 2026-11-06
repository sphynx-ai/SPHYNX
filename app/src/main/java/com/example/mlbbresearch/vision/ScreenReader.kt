package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Which MLBB screen is showing, read from colours only (no Gemma needed).
// Measured on screenshots of every screen from the home screen to the match-end scoreboard.
enum class ScreenHint(val label: String) {
    NONE("match view / unknown"),
    LIVE("live match (watching another player)"),
    LIVE_DEAD("live match: the watched hero is dead (no panel, camera on another hero)"),
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

// Center-bottom horizontal progress bar reading (v37.15, calibrated on 1000033086..33099):
//  - transformPct: 0..1 remaining duration when a RED/CORAL/ORANGE Transformation or Enhancement bar
//    is showing (e.g., Yu Zhong Black Dragon, Thamuz Cauterant Inferno, Lukas, Sora, Yin, Edith), -1 when none.
//  - recallPct: 0..1 progress when the BLUE/CYAN Recall execution bar is showing (1000033098, 1000033099), -1 when none.
class CenterBars(val transformPct: Float, val recallPct: Float) {
    companion object {
        val NONE = CenterBars(-1f, -1f)
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

    // ---------------- Skill Casted Duration Badge, Skill Icon Duration & Center-Bottom Bars (v37.15) ----------------

    // 1. Center-Bottom Transformation/Enhancement Bar vs Blue Recall Execution Bar
    // Measured on 1000033086..1000033099:
    //  - Transformation / Enhancement duration bar (Yu Zhong Black Dragon, Thamuz Cauterant Inferno, Lukas, Sora, etc.):
    //    Red / coral / orange-red fill (r >= 168, r - g >= 30, r - b >= 18) draining right-to-left in x = 0.400..0.600, y = 0.718..0.728.
    //  - Recall execution bar (1000033098, 1000033099):
    //    Blue / cyan fill (b >= 160, g >= 115, b - r >= 60) filling left-to-right in the same bar frame.
    fun centerBars(px: IntArray, w: Int, h: Int): CenterBars {
        val x0 = (0.400f * w).toInt().coerceIn(0, w - 1)
        val x1 = (0.600f * w).toInt().coerceIn(0, w - 1)
        val y0 = (0.718f * h).toInt().coerceIn(0, h - 1)
        val y1 = (0.728f * h).toInt().coerceIn(0, h - 1)
        val total = (x1 - x0 + 1).coerceAtLeast(1)
        val leftLim = x0 + max(8, (total * 0.12f).toInt())
        var redFill = 0
        var blueFill = 0
        var whiteTip = 0
        var empty = 0
        var leftRed = 0
        var leftBlue = 0
        for (x in x0..x1) {
            var cRed = false
            var cBlue = false
            var cTip = false
            var cEmp = false
            for (y in y0..y1) {
                val p = px[y * w + x]
                val pr = r(p); val pg = g(p); val pb = b(p)
                val mx = max(pr, max(pg, pb)); val mn = min(pr, min(pg, pb))
                if (pr >= 168 && pr - pg >= 30 && pr - pb >= 18 && pb in 35..195) {
                    cRed = true
                } else if (pb >= 160 && pg >= 115 && pb - pr >= 60) {
                    cBlue = true
                } else if (pr >= 205 && pg >= 200 && pb >= 200 && mx - mn <= 30) {
                    cTip = true
                } else if (mx in 18..92 && mx - mn <= 55 && pg < 90) {
                    cEmp = true
                }
            }
            when {
                cRed -> {
                    redFill++
                    if (x <= leftLim) leftRed++
                }
                cBlue -> {
                    blueFill++
                    if (x <= leftLim) leftBlue++
                }
                cTip -> whiteTip++
                cEmp -> empty++
            }
        }
        val trackOk = (redFill + blueFill + whiteTip + empty) >= (total * 0.78f).toInt()
        if (!trackOk) return CenterBars.NONE
        val tValid = redFill >= 4 && leftRed >= 3 && leftBlue <= 1
        val rValid = blueFill >= 4 && leftBlue >= 3 && leftRed <= 1
        val tPct = if (tValid) ((redFill + whiteTip).toFloat() / max(1, redFill + whiteTip + empty)).coerceIn(0.02f, 1f) else -1f
        val rPct = if (rValid) ((blueFill + whiteTip).toFloat() / max(1, blueFill + whiteTip + empty)).coerceIn(0.02f, 1f) else -1f
        return CenterBars(tPct, rPct)
    }

    // 2. Skill Casted Duration Badge (replaces the circular Level Badge immediately left of the Hero HP Bar)
    // Measured on 1000033086..1000033099:
    //  - Normal Level Badge (32040..42, 33096, 33098..99): flat dark interior (darkRatio >= 0.45) with white digits (colRatio <= 0.26).
    //  - Active Skill Duration Badge (Cici s1 33090..91, Leomord ult 33093, Thamuz ult 33094..95, Argus ult 33097, Yu Zhong 33086..88):
    //    Colorful skill icon inside the badge (colRatio >= 0.45, darkRatio <= 0.36) surrounded by an outer yellow-gold countdown arc + dark ring.
    // Returns 0.02f..1.0f remaining duration on the badge, or -1f when the normal Level Badge is showing.
    fun badgeSkillDuration(px: IntArray, w: Int, h: Int, bar: Seen): Float {
        val cx = (bar.barLeft * w - w * 0.0155f).toInt()
        val cy = (bar.barTop * h + h * 0.005f).toInt()
        val rIn = max(4, (w * 0.010f).toInt())
        if (cx - rIn < 0 || cy - rIn < 0 || cx + rIn >= w || cy + rIn >= h) return -1f
        var coloredIn = 0
        var darkIn = 0
        var totalIn = 0
        for (dy in -rIn..rIn) {
            for (dx in -rIn..rIn) {
                if (dx * dx + dy * dy <= rIn * rIn) {
                    val p = px[(cy + dy) * w + (cx + dx)]
                    val pr = r(p); val pg = g(p); val pb = b(p)
                    val mx = max(pr, max(pg, pb)); val mn = min(pr, min(pg, pb))
                    totalIn++
                    if (mx - mn >= 38 && mx >= 85) coloredIn++
                    if ((pr + pg + pb) / 3 < 55) darkIn++
                }
            }
        }
        if (totalIn == 0) return -1f
        val colRatio = coloredIn.toFloat() / totalIn
        val darkRatio = darkIn.toFloat() / totalIn
        if (colRatio < 0.45f || darkRatio > 0.36f) return -1f

        var goldAngles = 0
        var darkAngles = 0
        for (aIdx in 0 until 24) {
            val ang = (aIdx / 24.0) * 2.0 * Math.PI - Math.PI / 2.0
            var hasGold = false
            var hasDark = false
            for (rf in floatArrayOf(0.0155f, 0.0170f, 0.0185f)) {
                val rx = (cx + cos(ang) * w * rf).roundToInt()
                val ry = (cy + sin(ang) * w * rf).roundToInt()
                if (rx in 0 until w && ry in 0 until h) {
                    val p = px[ry * w + rx]
                    val pr = r(p); val pg = g(p); val pb = b(p)
                    if (pr >= 175 && pg >= 135 && pb <= 125 && pr - pb >= 65) hasGold = true
                    if ((pr + pg + pb) / 3 < 70) hasDark = true
                }
            }
            if (hasGold) goldAngles++
            else if (hasDark) darkAngles++
        }
        if (goldAngles + darkAngles < 14) return -1f
        return (goldAngles.toFloat() / max(1, goldAngles + darkAngles)).coerceIn(0.02f, 1f)
    }

    // 3. Skill Casted Active Duration Displayed on the Skill Icon itself ("s1", "s2", "s3", "ult")
    // Measured on 1000033090..91 (Cici s1), 1000033093 (Leomord ult), 1000033094 (Thamuz ult), 1000033097 (Argus ult):
    // While a skill's active duration is running on its skill icon, the button interior is darkened by the radial
    // clock-sweep (avgInLum in 18..66), while the outer rim (r = 0.025..0.028 * w) is split into a contiguous bright
    // cyan/gold arc (hiRun >= 6) and a contiguous dark elapsed arc (loRun >= 8).
    fun skillIconDurations(px: IntArray, w: Int, h: Int): Map<String, Float> {
        val slots = when (Layout.kitSize) {
            2 -> listOf("s1", "s2")
            4 -> listOf("s1", "s2", "s3", "ult")
            else -> listOf("s1", "s2", "ult")
        }
        val out = HashMap<String, Float>(4)
        val rimFlags = IntArray(24)
        val loFlags = IntArray(24)
        for (slot in slots) {
            val (fx, fy) = Layout.get(slot)
            val cx = fx * w
            val cy = fy * h
            var hiCount = 0
            var inLumSum = 0
            for (aIdx in 0 until 24) {
                val ang = (aIdx / 24.0) * 2.0 * Math.PI - Math.PI / 2.0
                var bestRim = 0
                var minLum = 999
                for (rf in floatArrayOf(0.0250f, 0.0265f, 0.0280f)) {
                    val x = (cx + cos(ang) * rf * w).roundToInt()
                    val y = (cy + sin(ang) * rf * w).roundToInt()
                    if (x in 0 until w && y in 0 until h) {
                        val p = px[y * w + x]
                        val pr = r(p); val pg = g(p); val pb = b(p)
                        bestRim = max(bestRim, max(pr, pg))
                        minLum = min(minLum, (pr * 3 + pg * 6 + pb) / 10)
                    }
                }
                if (bestRim >= 175) { rimFlags[aIdx] = 1; hiCount++ } else rimFlags[aIdx] = 0
                loFlags[aIdx] = if (minLum < 58) 1 else 0

                val xin = (cx + cos(ang) * 0.0165f * w).roundToInt().coerceIn(0, w - 1)
                val yin = (cy + sin(ang) * 0.0165f * w).roundToInt().coerceIn(0, h - 1)
                inLumSum += lum(px[yin * w + xin])
            }
            val avgInLum = inLumSum / 24f
            if (avgInLum !in 18f..66f) continue
            val hiRun = maxCircularRun(rimFlags)
            val loRun = maxCircularRun(loFlags)
            if (hiRun >= 6 && loRun >= 8) {
                out[slot] = (hiCount / 24f).coerceIn(0.05f, 1f)
            }
        }
        return out
    }

    private fun maxCircularRun(flags: IntArray): Int {
        var cur = 0
        var best = 0
        val n = flags.size
        for (i in 0 until n * 2) {
            if (flags[i % n] != 0) {
                cur++
                if (cur > best) best = min(n, cur)
            } else {
                cur = 0
            }
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
    fun clearResult() { lastResultWin = null }     // at each match start: no stale result

    // the best single row (fraction of matching pixels) between y0 and y1
    private fun rowMax(px: IntArray, w: Int, h: Int, y0: Float, y1: Float, x0: Float, x1: Float, test: (Int) -> Boolean): Float {
        var best = 0f
        val xa = (x0 * w).toInt(); val xb = (x1 * w).toInt()
        for (y in (y0 * h).toInt() until (y1 * h).toInt().coerceAtMost(h)) {
            var n = 0; var m = 0
            var x = xa
            while (x < xb) { n++; if (test(px[y * w + x])) m++; x += 4 }
            if (n > 0) best = maxOf(best, m.toFloat() / n)
        }
        return best
    }

    fun classify(px: IntArray, w: Int, h: Int): ScreenHint {
        fun f(l: Float, t: Float, r: Float, b: Float, test: (Int) -> Boolean) = fraction(px, w, h, l, t, r, b, 2, test)

        // watching someone's live match: their info panel along the bottom. While the watched
        // hero is dead the panel is gone and the camera follows another hero, but the live
        // screen's own buttons (gift, like, ranking...) stay on the right
        if (livePanel(px, w, h)) return ScreenHint.LIVE
        if (liveButtons(px, w, h)) return ScreenHint.LIVE_DEAD

        // menus darken the middle of the screen (76%+ measured); the match view does not (41-59%):
        // keeps skill effects and blue / red ground from looking like a menu
        val midDark = f(0.10f, 0.25f, 0.90f, 0.85f) { isDark(it) } > 0.7f

        // ---- match end (dark screens with their own buttons): before anything else ----
        val quit = f(0.82f, 0.885f, 0.935f, 0.95f) { isButtonBlue(it) }
        val data = f(0.07f, 0.885f, 0.18f, 0.95f) { isButtonBlue(it) }
        // (v36.92, your new end scoreboard: Quit moved left and a gold "Return to Lobby" took its place:
        // measured Data 0.32, new Quit 0.39, gold 0.81)
        val quitNew = f(0.69f, 0.885f, 0.80f, 0.95f) { isButtonBlue(it) }
        val returnGold = f(0.82f, 0.885f, 0.935f, 0.95f) { isGold(it) }
        if (midDark && data > 0.15f && (quit > 0.15f || (quitNew > 0.15f && returnGold > 0.4f))) {   // scoreboard with Data + Quit
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
        // the RANK and WIN STREAK screens (v36.92): a gold Continue button bottom right, nothing gold
        // under it (the Home screen's RANKED button reaches lower: 0.63 there, 0.00-0.07 here). They
        // were read as hero select before - which could start a "new match" at the end of yours
        val contGold = f(0.77f, 0.89f, 0.95f, 0.95f) { isGold(it) }
        if (contGold > 0.45f && f(0.80f, 0.955f, 0.95f, 0.985f) { isGold(it) } < 0.2f) return ScreenHint.RESULT_STATS
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
        // searching for a match: the Home screen with its big button turned blue ("Return to Lobby")
        // and the right side light (measured blue 0.45, right side 43% dark)
        if (homeBtn < 0.2f && f(0.80f, 0.925f, 0.945f, 0.975f) { isButtonBlue(it) } > 0.3f &&
            f(0.80f, 0.10f, 0.96f, 0.88f) { isDark(it) } < 0.55f) return ScreenHint.QUEUE
        // LOADING (v36.92, your new loading screen): the cards of one team stand on a blue band, the
        // other team's on a red line (measured red line 0.91; any other screen 0.13 at most). The teams'
        // rows can swap, so a red line under the TOP row counts too
        val redLow = rowMax(px, w, h, 0.95f, 0.985f, 0.34f, 0.95f) { isRedBar(it) }
        val redHigh = rowMax(px, w, h, 0.42f, 0.47f, 0.06f, 0.66f) { isRedBar(it) }
        if (redLow >= 0.6f || redHigh >= 0.6f) return ScreenHint.LOADING
        // match found: the gold Enter button (bottom middle)
        if (homeBtn < 0.2f && f(0.40f, 0.85f, 0.60f, 0.91f) { isGold(it) } > 0.45f) return ScreenHint.QUEUE

        // hero select: the Confirm button (bottom right): gold when a hero is chosen, grey otherwise;
        // the right panel shows the Hero tab while choosing and the Skins tab once locked in
        val (cx, cy) = Layout.get("lockIn")
        val confirm = f(cx - 0.05f, cy - 0.02f, cx + 0.05f, cy + 0.02f) { isGold(it) }
        val greyBtn = f(0.80f, 0.925f, 0.945f, 0.975f) { isButtonGrey(it) }
        if (confirm > 0.45f) return ScreenHint.HERO_SELECT
        // hero locked in: the Skins tab with a blue (Unlock / skin) button at the bottom right (measured 0.37)
        if (f(0.80f, 0.925f, 0.945f, 0.975f) { isButtonBlue(it) } > 0.25f && f(0.80f, 0.10f, 0.96f, 0.88f) { isDark(it) } > 0.55f) {
            val heroTab = avgLum(px, w, h, 0.80f, 0.01f, 0.87f, 0.065f)
            val skinsTab = avgLum(px, w, h, 0.88f, 0.01f, 0.95f, 0.065f)
            return if (skinsTab > heroTab + 10) ScreenHint.HERO_LOCKED else ScreenHint.HERO_SELECT
        }
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
    // the panel is see-through: the field shows through it a little (greyer navy, a unit's bar)
    private fun isPanelNavy(p: Int) = r(p) in 10..70 && g(p) in 28..95 && b(p) in 40..120 && b(p) - r(p) >= 10 && lum(p) < 90
    private fun isHpGreen(p: Int) = g(p) >= 140 && g(p) - max(r(p), b(p)) >= 40
    private fun isHpEmpty(p: Int) = max(r(p), max(g(p), b(p))) < 75 && max(r(p), max(g(p), b(p))) - min(r(p), min(g(p), b(p))) < 20

    // the panel's top strip in 9 pieces: 6+ must look like the panel (a unit or effect passing
    // behind can spoil a few), and the HP line must be green / empty grey all along.
    // Measured: 8-9 pieces on 7 live screens; any other screen with 5+ pieces has a line of 0.28 at most.
    fun livePanel(px: IntArray, w: Int, h: Int): Boolean {
        if (fraction(px, w, h, 0.43f, 0.869f, 0.61f, 0.876f, 2) { isHpGreen(it) || isHpEmpty(it) } <= 0.85f) return false
        var good = 0
        for (i in 0 until 9) {
            val l = 0.36f + i * 0.05f
            if (fraction(px, w, h, l, 0.848f, l + 0.0499f, 0.858f, 2) { isPanelNavy(it) } >= 0.6f) good++
        }
        return good >= 6
    }

    // the live screen's right column: purple gift, red heart, pink like box, blue ranking podium.
    // Measured: 0.39 / 0.33 / 0.32 / 0.27+ on 16 live screens; at most 0.07 / 0 / 0 / 0.04 on
    // 1,050 other screens and gameplay frames. 3 of the 4 must show.
    fun liveButtons(px: IntArray, w: Int, h: Int): Boolean {
        var n = 0
        if (fraction(px, w, h, 0.91f, 0.45f, 0.955f, 0.52f, 2) { r(it) >= 170 && b(it) >= 200 && g(it) <= 170 && r(it) - g(it) >= 40 } >= 0.2f) n++
        if (fraction(px, w, h, 0.915f, 0.33f, 0.95f, 0.39f, 2) { r(it) >= 200 && g(it) <= 80 && b(it) <= 100 } >= 0.15f) n++
        if (fraction(px, w, h, 0.89f, 0.02f, 0.912f, 0.07f, 2) { r(it) >= 200 && b(it) >= 170 && g(it) <= 150 } >= 0.15f) n++
        if (fraction(px, w, h, 0.915f, 0.56f, 0.945f, 0.63f, 2) { b(it) >= 200 && r(it) <= 120 && g(it) >= 120 } >= 0.12f) n++
        return n >= 3
    }

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

    // skill / spell icons of the panel (centre x, y) - for a 3-skill hero
    // v36.71, re-measured on your 4 live screenshots (Hirara, Fredrinn): the skill icons are packed
    // from the left, one every 0.0387 of the width (2, 3 or 4 of them), the battle spell is always at
    // 0.564. (The old s2 / ult places, 0.462 / 0.505, were between two icons.)
    private const val LIVE_Y = 0.941f
    private val LIVE_SKILL_X = floatArrayOf(0.4156f, 0.4543f, 0.493f, 0.531f)
    // (a 3-skill hero's icons are spread wider: 0.0435 apart - v36.72, your 2 live screenshots)
    private val LIVE_SKILL_X3 = floatArrayOf(0.4163f, 0.4599f, 0.5033f)
    val LIVE_ICONS = linkedMapOf("s1" to (LIVE_SKILL_X3[0] to LIVE_Y), "s2" to (LIVE_SKILL_X3[1] to LIVE_Y), "ult" to (LIVE_SKILL_X3[2] to LIVE_Y),
        "spell" to (0.564f to LIVE_Y))

    // the icons for a hero with 2 / 3 / 4 skills
    fun liveIcons(kit: Int): LinkedHashMap<String, Pair<Float, Float>> {
        val slots = when (kit) { 2 -> listOf("s1", "s2"); 4 -> listOf("s1", "s2", "s3", "ult"); else -> listOf("s1", "s2", "ult") }
        val m = LinkedHashMap<String, Pair<Float, Float>>()
        val xs = if (slots.size == 3) LIVE_SKILL_X3 else LIVE_SKILL_X
        slots.forEachIndexed { i, sl -> m[sl] = xs[i] to LIVE_Y }
        m["spell"] = 0.564f to LIVE_Y
        return m
    }

    // how many skill icons the panel shows (agreed on 3 readings in a row)
    @Volatile var liveKit = 3; private set
    private var liveKitCand = 0
    private var liveKitN = 0
    fun readLiveKit(px: IntArray, w: Int, h: Int): Int {
        var n = 0
        // (the 2/4-skill spots: a 3-skill hero's wider icons still cover the first 3 of them, not the 4th)
        for (x in LIVE_SKILL_X) { if (iconPresence(px, w, h, x, LIVE_Y) >= 18f) n++ else break }
        val k = n.coerceIn(2, 4)
        if (n >= 2) { if (k == liveKitCand) liveKitN++ else { liveKitCand = k; liveKitN = 1 } }
        if (liveKitN >= 3) liveKit = liveKitCand
        return liveKit
    }
    // an icon (even a dark one cooling down) is textured: brightness spread 28+ on your 6 live
    // screenshots; an empty spot of the panel is flat (9 or less). (v36.72: the colour test missed
    // dark cooling icons)
    private fun iconPresence(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val cx = (fx * w).toInt(); val cy = (fy * h).toInt(); val r = (0.010f * w).toInt()
        var sum = 0.0; var sq = 0.0; var t = 0
        var dx = -r
        while (dx <= r) {
            var dy = -r
            while (dy <= r) {
                if (dx * dx + dy * dy <= r * r) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until w && y in 0 until h) {
                        val p = px[y * w + x]
                        val l = 0.3 * ((p shr 16) and 255) + 0.59 * ((p shr 8) and 255) + 0.11 * (p and 255)
                        sum += l; sq += l * l; t++
                    }
                }
                dy += 2
            }
            dx += 2
        }
        if (t == 0) return 0f
        val m = sum / t
        return kotlin.math.sqrt(maxOf(0.0, sq / t - m * m)).toFloat()
    }

    // how colourful an icon is (average max-min of r, g, b over a disc): a cooling skill is greyed
    // out with its countdown on top. Measured on 7 live screens: ready s1 87 / s2 99 / spell 65-67;
    // cooling s1 14-60, s2 70-84, spell 35-48 (brightness alone hardly changed for s2)
    fun iconColour(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val rad = 0.012f * w
        var sum = 0L; var n = 0
        val cx = fx * w; val cy = fy * h
        var y = (cy - rad).toInt()
        while (y <= cy + rad) {
            var x = (cx - rad).toInt()
            while (x <= cx + rad) {
                if (x in 0 until w && y in 0 until h && hypot(x - cx, y - cy) <= rad) {
                    val p = px[y * w + x]
                    sum += max(r(p), max(g(p), b(p))) - min(r(p), min(g(p), b(p))); n++
                }
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

    // Exact detected center of each visible golden "+" upgrade icon on the current frame (v37.10)
    @Volatile var plusPos: Map<String, Pair<Float, Float>> = emptyMap()
        private set

    // Searches a bounce/shift-tolerant window around both the calibrated upgrade "+" position and the
    // relative offset from the skill button so the Ultimate "+" is never missed during its pulse animation
    // or when Large HUD / custom button layouts are active (v37.10).
    // Bright cream-white / pale-yellow "+" cross core inside a golden skill upgrade badge (v37.16, calibrated on 1000033098/33099):
    // Real upgrade badges have a glowing golden disc (gold >= 0.35f) with a bright cream-white "+" cross in the center
    // (r >= 215, g >= 190, b in 95..250, r - b >= 15) and NO dark outer ring (their outer halo is bright gold!).
    private fun isPlusCore(p: Int): Boolean {
        val pr = r(p); val pg = g(p); val pb = b(p)
        return pr >= 215 && pg >= 190 && pb in 95..250 && pr - pb >= 15 && pg - pb >= 10
    }

    private fun isRealUpgradeBadge(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, rad: Float, ry: Float, minGold: Float): Float {
        val gold = fraction(px, w, h, cx - rad, cy - ry, cx + rad, cy + ry, 1) { isGold(it) }
        if (gold < minGold) return 0f
        val coreR = rad * 0.68f
        val coreRy = ry * 0.68f
        val core = fraction(px, w, h, cx - coreR, cy - coreRy, cx + coreR, cy + coreRy, 1) { isPlusCore(it) }
        return if (core >= 0.055f) gold + core * 0.4f else 0f
    }

    private fun findUpgradePlus(px: IntArray, w: Int, h: Int, slot: String, key: String): Pair<Float, Float>? {
        val rad = 0.0115f
        val ry = rad * w / h
        val minGold = if (slot == "ult") 0.34f else 0.36f
        val candidates = ArrayList<Pair<Float, Float>>(4)
        candidates.add(Layout.upgradePoint(slot))
        val (sx, sy) = Layout.get(slot)
        val relX = (sx - (if (Layout.kitSize == 4) 0.035f else 0.042f)).coerceIn(0.05f, 0.95f)
        val relY = (sy - (if (Layout.kitSize == 4) 0.076f else 0.098f)).coerceIn(0.05f, 0.95f)
        if (candidates.none { kotlin.math.hypot(it.first - relX, it.second - relY) < 0.005f }) {
            candidates.add(relX to relY)
        }
        if (slot == "ult") {
            val u3 = Layout.point("ultUp")
            val u4 = Layout.point("ultUp4")
            if (candidates.none { kotlin.math.hypot(it.first - u3.first, it.second - u3.second) < 0.005f }) candidates.add(u3)
            if (candidates.none { kotlin.math.hypot(it.first - u4.first, it.second - u4.second) < 0.005f }) candidates.add(u4)
        }
        var bestPos: Pair<Float, Float>? = null
        var bestScore = minGold
        for ((ux, uy) in candidates) {
            for (dy in floatArrayOf(0f, -0.008f, 0.008f, -0.016f, 0.016f)) {
                for (dx in floatArrayOf(0f, -0.006f, 0.006f, -0.013f, 0.013f)) {
                    val cx = ux + dx
                    val cy = uy + dy
                    val f = isRealUpgradeBadge(px, w, h, cx, cy, rad, ry, minGold)
                    if (f >= bestScore) {
                        bestScore = f
                        bestPos = cx to cy
                    }
                }
            }
            if (bestPos != null && bestScore >= minGold + 0.12f) return bestPos
        }
        return bestPos
    }

    fun hud(px: IntArray, w: Int, h: Int, withMovement: Boolean): HudInfo {
        val ups = HashSet<String>()
        val foundPos = HashMap<String, Pair<Float, Float>>()
        for ((slot, key) in listOf("s1" to "s1Up", "s2" to "s2Up", "s3" to "s3Up", "ult" to "ultUp")) {
            if (slot == "s3" && Layout.kitSize != 4) continue
            if (slot == "ult" && Layout.kitSize == 2) continue     // (Hirara: no ultimate - its "+" spot is her Skill 2's)
            val pos = findUpgradePlus(px, w, h, slot, key)
            if (pos != null) {
                ups.add(slot)
                foundPos[slot] = pos
            }
        }
        plusPos = foundPos
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
