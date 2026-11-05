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
    val barBottom: Float,
    val species: String = "",  // NEUTRAL only: JungleDb id (lord, turtle, molten_fiend, thunder_fenrir, ...)
    val extra: Float = 0f      // heroes: the light part right after the HP colour = shield / armour /
                               // stored energy, as a share of the whole bar (HeroKit bar models)
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

    // on while the hero is Masha (HeroKit): her bar is dark green
    @Volatile var darkGreenSelf = false

    // MASHA's bar (v36.56, from your screenshots): three HP layers on top of each other, each its own
    // green - 3 = dark (18,94,4), 2 = medium (74,139,45), 1 = bright (131,242,23; the last layer, no
    // "x" label). The top layer drains first and shows the layer under it. 0 = not a Masha green.
    fun mashaLayer(r: Int, g: Int, b: Int): Int = when {
        g >= 160 && r <= 145 && b <= 125 && g - max(r, b) >= 55 -> 1
        r <= 45 && g in 60..150 && b <= 40 && g - max(r, b) >= 50 -> 3
        r in 45..110 && g in 100..170 && b in 20..75 && g - r >= 45 && g - b >= 60 -> 2
        else -> 0
    }
    // on while the hero's bar shows a shield / armour / stored energy after the HP (Esmeralda,
    // X.Borg, Fredrinn: HeroKit): wider dividers are stepped over on your own bar
    @Volatile var specialBar = false

    // off = monsters are not looked for (set by Cfg.detectMonsters)
    @Volatile var neutralOn = true

    private class Blob(val cls: Int, var minX: Int, var maxX: Int, var minY: Int, var maxY: Int, var count: Int)

    fun classify(p: Int): Int {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        if (r >= 170 && g <= 95 && b <= 95 && r - max(g, b) >= 95) return RED
        if (b >= 165 && r <= 110 && g <= 205 && b - r >= 85) return BLUE
        if (g >= 160 && r <= 145 && b <= 125 && g - max(r, b) >= 55) return GREEN
        // Masha's own bar is DARK green (17,93,5 on your screenshot): only while she is played
        if (darkGreenSelf && mashaLayer(r, g, b) > 0) return GREEN
        if (neutralOn && MonsterDetector.matches(r, g, b)) return NEUTRAL
        return NONE
    }

    private fun lum(p: Int): Int {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        return (r * 3 + g * 6 + b) / 10
    }

    // the light segment after the HP colour: near-white / pale grey (shield) or pale gold (armour)
    private fun isExtraBar(p: Int, armour: Boolean = false): Boolean {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val whiteish = mn >= 150 && mx - mn < 50
        val gold = r >= 190 && g >= 150 && b <= 130 && r - b >= 80
        // X.Borg's armour (measured on your screenshot: 164,103,82)
        val salmon = r in 125..235 && g in 70..150 && b in 50..125 && r - g >= 40 && r - b >= 55
        // (pale gold / salmon only on your own bar while playing such a hero: an enemy bar's
        // just-lost HP flashes light red)
        return whiteish || armour && (gold || salmon)
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
        val longPiece = (w * 0.02f).toInt()
        val mergeGap = max(3, w / 450)
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
                    if (cls != NONE) {
                        // HP bars have thin dark tick marks every few hundred HP: a gap of up to
                        // 2 px between two runs of the same colour is one bar, not two
                        val last = rowRuns.lastOrNull()
                        // (the bar's bigger segment dividers are ~5 px on a 2712-wide screen: v36.55)
                        // (only for your green bar, between two long pieces; other bars keep the 3 px rule)
                        val gap = start - last.let { it?.get(1) ?: -99 }
                        if (last != null && last[2] == cls && (gap <= 3 || cls == GREEN && gap <= mergeGap &&
                                last[1] - last[0] + 1 >= longPiece && x - start >= (if (specialBar) 4 else longPiece))) last[1] = x - 1
                        else rowRuns.add(intArrayOf(start, x - 1, cls))
                    }
                    cls = c
                    start = x
                }
            }
            // a very short coloured run is still a bar when a long grey "missing HP" part follows
            // it: a unit (often a monster) at its last few % HP shows only 1-5 px of colour
            rowRuns.removeAll { it[1] - it[0] + 1 < minRun && !(it[1] - it[0] + 1 >= 2 && emptyAfter(px, w, base, it[1] + 1) >= minRun * 3) }
            // attach runs to blobs that touched the previous rows; a run touching two blobs
            // joins them (a bar split by tick marks in one row is whole in the next)
            for (run in rowRuns) {
                var attached: Blob? = null
                val itB = active.iterator()
                while (itB.hasNext()) {
                    val bl = itB.next()
                    // the same bar continues: most of the shorter piece lies under the other. Two
                    // different bars in a crowded fight only touch at their ends (a hero's bar under
                    // the lord's, two heroes side by side) and must stay apart.
                    // (only between two long, bar-sized pieces: small effect pieces keep joining, so
                    // clutter still forms one shapeless blob that is thrown away)
                    val ov = min(run[1], bl.maxX) - max(run[0], bl.minX) + 1 + 2
                    val shorter = min(run[1] - run[0] + 1, bl.maxX - bl.minX + 1)
                    val separate = shorter >= longPiece && ov < shorter * 0.6f
                    if (bl.cls == run[2] && bl.maxY >= y - 2 && run[0] <= bl.maxX + 2 && run[1] >= bl.minX - 2 && !separate) {
                        val a = attached
                        if (a == null) { attached = bl; continue }
                        a.minX = min(a.minX, bl.minX); a.maxX = max(a.maxX, bl.maxX)
                        a.count += bl.count
                        a.minY = min(a.minY, bl.minY)
                        itB.remove()
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
            val nearlyEmpty = (bw < minRun || bw < bh * 3) && emptyAfter(px, w, ((bl.minY + bl.maxY) / 2) * w, bl.maxX + 1) >= max(minRun * 3, bh * 6)
            if (bh > maxBarH || (bw < minRun || bw < bh * 3) && !nearlyEmpty) continue
            if (bl.count.toFloat() / (bw * bh) < 0.55f) continue
            val cx = (bl.minX + bl.maxX) / 2
            val cy = (bl.minY + bl.maxY) / 2
            if (exclPx.any { cx >= it[0] && cx <= it[2] && cy >= it[1] && cy <= it[3] }) continue
            if (!hasDarkFrame(px, w, h, bl)) continue
            if (!hasSolidRow(px, w, bl)) continue

            // the missing-HP part of the bar is dark grey inside the bar's dark frame:
            // measure it to get HP %. It ends where the frame ends or the colour changes.
            var empty = 0
            var x = bl.maxX + 1
            // SHIELD / ARMOUR / STORED ENERGY (v36.54): a hero's bar can show a light (white / pale /
            // golden) segment right after the HP colour - a shield (Esmeralda and any shielded hero),
            // X.Borg's armour, Fredrinn's stored crystalline energy. It is measured on its own (it is
            // not HP and not lost HP) and the grey empty part is measured after it.
            var extraW = 0
            if (bl.cls != NEUTRAL) {
                // (a blended edge pixel or a tick mark of up to 2 px may sit between the HP colour and it)
                val stop = min(w - 1, bl.minX + (w * 0.079f).toInt() + 2)
                // a column of the segment: light across the bar's height (not a thin digit stroke)
                // (some bars are bright only in their upper rows: X.Borg's armour - the middle row and one
                // of the rows just above / below it)
                val y1 = (cy - 2).coerceAtLeast(bl.minY); val y2 = (cy + 2).coerceAtMost(bl.maxY)
                val arm = bl.cls == GREEN && specialBar
                fun extraAt(xx: Int) = isExtraBar(px[cy * w + xx], arm) && (isExtraBar(px[y1 * w + xx], arm) || isExtraBar(px[y2 * w + xx], arm)) && framed(px, w, h, bl, xx)
                val k0 = (0..(if (bl.cls == GREEN && specialBar) mergeGap + 3 else 2)).firstOrNull { k -> x + k <= stop && extraAt(x + k) } ?: 99
                var xx = if (k0 == 99) x else x + k0
                if (k0 != 99) extraW = k0
                while (xx <= stop) {
                    if (extraAt(xx)) { extraW++; xx++; continue }
                    // a divider inside the segment (X.Borg's armour has them): step over it
                    val sk = (1..(if (arm) mergeGap else 2)).firstOrNull { xx + it <= stop && extraAt(xx + it) } ?: break
                    extraW += sk; xx += sk
                }
                if (extraW - (if (k0 == 99) 0 else k0) >= 3) {
                    // and the whole bar (HP + segment + empty) must be a hero bar's standard width
                    var e2 = 0; var x2 = xx
                    while (x2 <= stop && isEmptyBar(px[cy * w + x2])) { e2++; x2++ }
                    val whole = (bw + extraW + e2).toFloat() / w
                    if (whole in 0.064f..0.083f) x = xx else extraW = 0
                } else extraW = 0
            }
            // every hero's HP bar has the same full width on screen (about 7.7% of its width): the grey
            // empty part never reaches past that, so a dark bush or wall right after a full bar
            // is not read as lost HP
            val fullMax = (w * 0.079f).toInt()
            val limit = min(w - 1, if (bw < fullMax) bl.minX + fullMax else bl.maxX + bw * 6)
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
                    val skip = (1..(if (bl.cls == GREEN && specialBar) mergeGap else 3)).firstOrNull { x + it <= limit && emptyAt(x + it) } ?: break
                    empty += skip
                    x += skip
                    continue
                }
                empty++
                x++
            }
            val total = bw + extraW + empty
            var hp = (bw.toFloat() / total).coerceIn(0f, 1f)
            // Masha: her whole HP = (layers under the top one + the top one's part) / 3
            if (bl.cls == GREEN && darkGreenSelf) {
                fun layerAt(xx: Int): Int { val p = px[cy * w + xx]; return mashaLayer((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff) }
                val top = (bl.minX..min(bl.minX + 4, bl.maxX)).map { layerAt(it) }.filter { it > 0 }.maxOrNull() ?: 1
                var run = 0; var xx = bl.minX
                while (xx <= bl.maxX) {
                    if (layerAt(xx) == top) { run++; xx++; continue }
                    // the bar's tick marks / dividers inside one layer
                    val sk = (1..mergeGap).firstOrNull { xx + it <= bl.maxX && layerAt(xx + it) == top } ?: break
                    run += sk; xx += sk
                }
                hp = (((top - 1) + run.toFloat() / total) / 3f).coerceIn(0f, 1f)
            }
            val extraFrac = (extraW.toFloat() / total).coerceIn(0f, 1f)
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
                    barRight = right, barBottom = bl.maxY.toFloat() / h,
                    extra = extraFrac
                )
            )
        }
        return out
    }

    // length of the grey empty-bar stretch starting at x on the row starting at base (thin dark
    // tick marks of 1-3 px are stepped over), up to 80 px
    private fun emptyAfter(px: IntArray, w: Int, base: Int, x0: Int): Int {
        var n = 0
        var x = x0
        while (x < w && n < 80) {
            if (isEmptyBar(px[base + x]) && lum(px[base + x]) >= 45) { n++; x++; continue }
            val skip = (1..3).firstOrNull { x + it < w && isEmptyBar(px[base + x + it]) && lum(px[base + x + it]) >= 45 } ?: break
            n += skip; x += skip
        }
        return n
    }

    // An HP bar is a solid strip: at least one row is its colour almost end to end (tick marks
    // are single pixels). Red damage numbers and skill effects have strokes with holes.
    private fun hasSolidRow(px: IntArray, w: Int, bl: Blob): Boolean {
        val bw = bl.maxX - bl.minX + 1
        for (y in bl.minY..bl.maxY) {
            var n = 0
            for (x in bl.minX..bl.maxX) if (classify(px[y * w + x]) == bl.cls) n++
            if (n >= bw * 0.85f) return true
        }
        return false
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
