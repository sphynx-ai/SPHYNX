package com.example.mlbbresearch.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// NEUTRAL = jungle monsters, turtle and lord (orange / yellow HP bars)
enum class Team { SELF, ALLY, ENEMY, NEUTRAL }

// One unit found on screen. All positions are fractions of the frame (0..1).
data class Seen(
    val team: Team,
    val hero: Boolean,       // false = minion / creep; for NEUTRAL: true = turtle / lord (very wide bar)
    val x: Float,            // body position (below the HP bar)
    val y: Float,
    val hp: Float,           // 0..1 read from how full the bar is
    val barLeft: Float,
    val barTop: Float,
    val barRight: Float,
    val barBottom: Float
)

// Finds HP bars by colour: green = you, blue = allies, red = enemies, orange/yellow = monsters.
// Hero bars are wider than minion bars. No AI needed, so it runs at full frame rate.
// Tuning lives in Cfg (hero bar width) and the constants below.
object HeroDetector {

    private const val NONE = 0
    private const val RED = 1
    private const val BLUE = 2
    private const val GREEN = 3
    private const val NEUTRAL = 4

    // off = monsters are not looked for (set by Cfg.detectMonsters)
    @Volatile var neutralOn = true

    private class Blob(val cls: Int, var minX: Int, var maxX: Int, val minY: Int, var maxY: Int, var count: Int)

    fun classify(p: Int): Int {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        if (r >= 170 && g <= 95 && b <= 95 && r - max(g, b) >= 95) return RED
        if (b >= 165 && r <= 110 && g <= 205 && b - r >= 85) return BLUE
        if (g >= 160 && r <= 145 && b <= 125 && g - max(r, b) >= 55) return GREEN
        if (neutralOn && MonsterDetector.matches(r, g, b)) return NEUTRAL
        return NONE
    }

    private fun lum(p: Int): Int {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        return (r * 3 + g * 6 + b) / 10
    }

    private fun isEmptyBar(p: Int): Boolean {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return mx < 110 && mx - mn < 45
    }

    // excl: list of [left, top, right, bottom] fractions to ignore (HUD, our own overlay)
    fun scan(px: IntArray, w: Int, h: Int, excl: List<FloatArray>, heroMinWidthFrac: Float, bodyBelowFrac: Float): List<Seen> {
        val minRun = max(4, (w * 0.008f).toInt())
        val maxBarH = max(3, (h * 0.024f).toInt())
        val exclPx = excl.map { intArrayOf((it[0] * w).toInt(), (it[1] * h).toInt(), (it[2] * w).toInt(), (it[3] * h).toInt()) }

        val active = ArrayList<Blob>()
        val done = ArrayList<Blob>()
        val rowRuns = ArrayList<IntArray>()   // [x0, x1, cls]

        for (y in 0 until h) {
            rowRuns.clear()
            var cls = NONE
            var start = 0
            val base = y * w
            for (x in 0..w) {
                val c = if (x < w) classify(px[base + x]) else NONE
                if (c != cls) {
                    if (cls != NONE && x - start >= minRun) rowRuns.add(intArrayOf(start, x - 1, cls))
                    cls = c
                    start = x
                }
            }
            // attach runs to blobs that touched the previous rows
            for (run in rowRuns) {
                var attached: Blob? = null
                for (bl in active) {
                    if (bl.cls == run[2] && bl.maxY >= y - 2 && run[0] <= bl.maxX + 2 && run[1] >= bl.minX - 2) {
                        attached = bl
                        break
                    }
                }
                if (attached != null) {
                    attached.minX = min(attached.minX, run[0])
                    attached.maxX = max(attached.maxX, run[1])
                    attached.maxY = y
                    attached.count += run[1] - run[0] + 1
                } else {
                    active.add(Blob(run[2], run[0], run[1], y, y, run[1] - run[0] + 1))
                }
            }
            // close blobs that ended
            val it = active.iterator()
            while (it.hasNext()) {
                val bl = it.next()
                if (bl.maxY < y - 2) {
                    done.add(bl)
                    it.remove()
                }
            }
        }
        done.addAll(active)

        val out = ArrayList<Seen>()
        val heroMinW = heroMinWidthFrac * w
        for (bl in done) {
            val bw = bl.maxX - bl.minX + 1
            val bh = bl.maxY - bl.minY + 1
            if (bh > maxBarH || bw < minRun || bw < bh * 3) continue
            if (bl.count.toFloat() / (bw * bh) < 0.55f) continue
            val cx = (bl.minX + bl.maxX) / 2
            val cy = (bl.minY + bl.maxY) / 2
            if (exclPx.any { cx >= it[0] && cx <= it[2] && cy >= it[1] && cy <= it[3] }) continue
            if (!hasDarkFrame(px, w, h, bl)) continue

            // the missing-HP part of the bar is dark grey inside the bar's dark frame:
            // measure it to get HP %. It ends where the frame ends or the colour changes.
            var empty = 0
            var x = bl.maxX + 1
            val limit = min(w - 1, bl.maxX + bw * 6)
            // the empty (grey) part's brightness, taken past a tick mark that may sit right at
            // the end of the coloured part (a black tick there used to cut the measurement short)
            // (or a soft blended pixel): use the brightest real grey among the next 5 pixels
            var firstLum = -1
            for (k in 0..4) {
                val xx = x + k
                if (xx < w && isEmptyBar(px[cy * w + xx])) firstLum = maxOf(firstLum, lum(px[cy * w + xx]))
            }
            if (firstLum < 0) firstLum = if (x < w) lum(px[cy * w + x]) else 0
            fun emptyAt(xx: Int): Boolean {
                val p = px[cy * w + xx]
                return isEmptyBar(p) && abs(lum(p) - firstLum) <= 30 && framed(px, w, h, bl, xx)
            }
            while (x <= limit) {
                if (!emptyAt(x)) {
                    // the bar has thin black tick marks every few hundred HP: step over them
                    val skip = (1..3).firstOrNull { x + it <= limit && emptyAt(x + it) } ?: break
                    empty += skip
                    x += skip
                    continue
                }
                empty++
                x++
            }
            val total = bw + empty
            val hp = (bw.toFloat() / total).coerceIn(0f, 1f)
            val team = when (bl.cls) { GREEN -> Team.SELF; BLUE -> Team.ALLY; NEUTRAL -> Team.NEUTRAL; else -> Team.ENEMY }
            // monsters: a bar about twice a hero's width belongs to the turtle or the lord
            val hero = if (team == Team.NEUTRAL) total >= heroMinW * 2f else total >= heroMinW
            if (team == Team.SELF && !hero) continue
            val left = bl.minX.toFloat() / w
            val right = (bl.minX + total).toFloat() / w
            out.add(
                Seen(
                    team, hero,
                    x = (left + right) / 2f,
                    y = (bl.maxY.toFloat() / h + bodyBelowFrac).coerceAtMost(1f),
                    hp = hp,
                    barLeft = left, barTop = bl.minY.toFloat() / h,
                    barRight = right, barBottom = bl.maxY.toFloat() / h
                )
            )
        }
        return out
    }

    // is there bar frame (dark) just above or below this column?
    private fun framed(px: IntArray, w: Int, h: Int, bl: Blob, x: Int): Boolean {
        for (yy in intArrayOf(bl.minY - 1, bl.minY - 2, bl.maxY + 1, bl.maxY + 2)) {
            if (yy in 0 until h && lum(px[yy * w + x]) < 70) return true
        }
        return false
    }

    // HP bars have a dark outline/background directly above or below them (up to 3 px away:
    // a soft, anti-aliased bar edge can push the outline one pixel further out)
    private fun hasDarkFrame(px: IntArray, w: Int, h: Int, bl: Blob): Boolean {
        for (yy in intArrayOf(bl.minY - 1, bl.minY - 2, bl.minY - 3, bl.maxY + 1, bl.maxY + 2, bl.maxY + 3)) {
            if (yy < 0 || yy >= h) continue
            var dark = 0
            var n = 0
            var x = bl.minX
            while (x <= bl.maxX) {
                if (lum(px[yy * w + x]) < 85) dark++
                n++
                x += 2
            }
            if (n > 0 && dark.toFloat() / n >= 0.35f) return true
        }
        return false
    }

    // Average colourfulness (0..1). MLBB turns the screen grey when you are dead.
    fun saturation(px: IntArray, w: Int, h: Int): Float {
        var sum = 0f
        var n = 0
        var y = h / 8
        while (y < h * 7 / 8) {
            var x = w / 8
            while (x < w * 7 / 8) {
                val p = px[y * w + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                if (mx > 20) sum += (mx - mn).toFloat() / mx
                n++
                x += 12
            }
            y += 12
        }
        return if (n == 0) 0f else sum / n
    }

    // Average brightness (0..255) of a small square around a point given as fractions
    fun brightness(px: IntArray, w: Int, h: Int, fx: Float, fy: Float, radiusFrac: Float): Float {
        val cx = (fx * w).toInt()
        val cy = (fy * h).toInt()
        val r = max(2, (radiusFrac * w).toInt())
        var sum = 0L
        var n = 0
        var y = max(0, cy - r)
        while (y <= min(h - 1, cy + r)) {
            var x = max(0, cx - r)
            while (x <= min(w - 1, cx + r)) {
                sum += lum(px[y * w + x])
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }
}
