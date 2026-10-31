package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Your hero level (1..15) from the round badge left of your HP bar, plus how full the gold
// EXP ring around it is.
//
// At the capture size (800 px wide) the digits are only about 5 x 6 pixels, too small to read
// reliably as digits. So, like the K/D/A reader, it works without reading digits:
//  - a level starts at 1 when the match starts
//  - the badge's white pattern changing (and staying changed) = one level up
//  - the gold "+" skill-upgrade button showing up again = one level up (every level gives a
//    skill point); both signals from the same level-up within 3 s count once
//  - two digits (wide pattern) = level 10 or more
//  - every badge pattern seen for a while at a known level is REMEMBERED; from then on that
//    pattern gives the level directly (it corrects any miscount). After a few matches every
//    level from 1 to 15 is known. The same patterns also read enemy heroes' badges.
object LevelReader {

    private const val GW = 11
    private const val GH = 10

    class Glyph(val bits: BooleanArray, val wide: Boolean, val count: Int)

    @Volatile var level = 1
        private set
    @Volatile var xp = -1f            // 0..1 how full the EXP ring is, -1 unknown
        private set
    @Volatile var known = false        // level confirmed by a remembered badge pattern
        private set
    @Volatile var lastUpAt = 0L
        private set

    private var stable: Glyph? = null
    private var pending: Glyph? = null
    private var pendingN = 0
    private var stableSince = 0L
    private var upgradeShown = false
    private var upgradeEdges = 0
    private val templates = HashMap<Int, BooleanArray>()
    private var loaded = false

    fun reset() {
        level = 1; xp = -1f; known = false; lastUpAt = 0L
        stable = null; pending = null; pendingN = 0; stableSince = 0L
        upgradeShown = false; upgradeEdges = 0
    }

    val templatesKnown: Int get() { load(); return templates.size }

    fun forgetTemplates() {
        load()
        for (k in templates.keys) Cfg.setRaw("lvl_tpl_$k", null)
        templates.clear()
    }

    // once per frame while your hero is visible
    fun update(px: IntArray, w: Int, h: Int, self: Seen, upgradeReady: Boolean, now: Long) {
        load()
        // a new skill point: the "+" shows up again (the first one is level 1's point)
        if (upgradeReady && !upgradeShown) {
            upgradeEdges++
            if (upgradeEdges > 1) levelUp(now, "skill point")
        }
        upgradeShown = upgradeReady

        xp = ring(px, w, h, self)
        val g = glyph(px, w, h, self) ?: return
        val s = stable
        if (s == null) { stable = g; stableSince = now; afterRead(g, now); return }
        if (diff(g, s) < 0.48f) { pending = null; pendingN = 0; afterRead(s, now); return }
        // a different badge: must stay the same for 3 frames (an effect flying over it does not)
        val q = pending
        if (q != null && diff(g, q) < 0.4f) pendingN++ else { pending = g; pendingN = 1 }
        if (pendingN >= 3) {
            stable = g
            stableSince = now
            pending = null; pendingN = 0
            val t = match(g)
            if (t != null) setLevel(t, now, "remembered badge") else levelUp(now, "badge changed")
            afterRead(g, now)
        }
    }

    // level of any hero bar (enemy heroes too) from the remembered badge patterns, -1 unknown
    fun levelOf(px: IntArray, w: Int, h: Int, bar: Seen): Int {
        load()
        val g = glyph(px, w, h, bar) ?: return -1
        return match(g) ?: if (g.wide) 10 else -1
    }

    private fun afterRead(g: Glyph, now: Long) {
        // two digits = 10 or more; one digit = 9 or less
        if (g.wide && level < 10) setLevel(10, now, "two-digit badge")
        if (!g.wide && g.count >= 3 && level >= 10 && !known) setLevel(9, now, "one-digit badge")
        match(g)?.let { if (it != level) setLevel(it, now, "remembered badge") }
        // seen for 4 s at this level: remember what this level's badge looks like
        if (now - stableSince > 4000 && now - lastUpAt > 4000 && templates[level] == null && g.wide == (level >= 10) &&
            (SEEDS + templates).none { (k, t) -> k != level && diff(t, g.bits) < 0.45f }
        ) {
            templates[level] = g.bits
            Cfg.setRaw("lvl_tpl_$level", g.bits.joinToString("") { if (it) "1" else "0" })
            Diag.info("Remembered the level $level badge")
        }
    }

    private fun levelUp(now: Long, why: String) {
        // the badge and the skill point of one level-up come together: count it once
        if (now - lastUpAt < 3000) return
        setLevel(level + 1, now, why)
    }

    private fun setLevel(l: Int, now: Long, why: String) {
        val v = l.coerceIn(1, 15)
        known = why == "remembered badge"
        if (v == level) return
        if (v > level) lastUpAt = now
        Diag.info("Level ${level} -> $v ($why)")
        level = v
    }

    // the remembered (or built-in) level whose badge is clearly the closest
    private fun match(g: Glyph): Int? {
        var best = -1; var bd = 1f; var second = 1f
        val all = HashMap(SEEDS).apply { putAll(templates) }
        for ((k, t) in all) {
            if ((k >= 10) != g.wide) continue
            val d = diff(t, g.bits)
            if (d < bd) { second = bd; bd = d; best = k } else if (d < second) second = d
        }
        return if (best > 0 && bd < 0.36f && second - bd >= 0.12f) best else null
    }

    // badges measured on real screenshots (POCO X6 Pro, 800 px capture): levels 1, 2, 9, 12.
    // The rest are learned in play; a learned pattern replaces the built-in one.
    private val SEEDS: Map<Int, BooleanArray> = mapOf(
        1 to "00000000000000000000000000000000000000000000000101000000000010000000000100000000001000000000110000000000000000",
        2 to "00000000000000000000000000000000000001100000000000100000000001000000000100000000010000000001111000000000000000",
        9 to "00000000000000000000000000000000000000000000000011100000001001000000010010000000011100000000001000000001100000",
        12 to "00000000000000000000000000000000000000000000001100111000001000001000010000100000100010000001001000001111011110"
    ).mapValues { (_, v) -> BooleanArray(v.length) { v[it] == '1' } }

    private fun load() {
        if (loaded) return
        loaded = true
        for (l in 1..15) {
            val s = Cfg.raw("lvl_tpl_$l") ?: continue
            if (s.length == GW * GH) templates[l] = BooleanArray(s.length) { s[it] == '1' }
        }
    }

    // badge centre: just left of the HP bar, at its height
    private fun centre(w: Int, h: Int, bar: Seen): Pair<Float, Float> =
        (bar.barLeft * w - 0.0125f * w) to ((bar.barTop + bar.barBottom) / 2f * h + 0.004f * h)

    private fun white(p: Int): Boolean {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return min(r, min(g, b)) >= 140 && max(r, max(g, b)) - min(r, min(g, b)) < 60
    }

    // the white digit pixels in a small box in the middle of the badge (the ring is outside it)
    fun glyph(px: IntArray, w: Int, h: Int, bar: Seen): Glyph? {
        val (cx, cy) = centre(w, h, bar)
        val sx = w / 800f
        val x0 = (cx - GW / 2 * sx).toInt()
        val y0 = (cy - GH / 2 * sx).toInt()
        if (x0 < 0 || y0 < 0 || x0 + GW * sx >= w || y0 + GH * sx >= h) return null
        val bits = BooleanArray(GW * GH)
        var n = 0; var minX = GW; var maxX = -1
        for (gy in 0 until GH) for (gx in 0 until GW) {
            val x = (x0 + gx * sx).toInt(); val y = (y0 + gy * sx).toInt()
            if (white(px[y * w + x])) {
                bits[gy * GW + gx] = true; n++
                minX = min(minX, gx); maxX = max(maxX, gx)
            }
        }
        if (n < 2) return null
        return Glyph(bits, maxX - minX + 1 >= 7, n)
    }

    // share of the EXP ring that is gold
    fun ring(px: IntArray, w: Int, h: Int, bar: Seen): Float {
        val (cx, cy) = centre(w, h, bar)
        val r = 7.5f * w / 800f
        var gold = 0; var n = 0
        for (i in 0 until 36) {
            val a = i * Math.PI * 2 / 36
            val x = (cx + cos(a) * r).toInt(); val y = (cy + sin(a) * r).toInt()
            if (x !in 0 until w || y !in 0 until h) continue
            val p = px[y * w + x]
            val rr = (p shr 16) and 0xff; val gg = (p shr 8) and 0xff; val bb = p and 0xff
            n++
            if (rr >= 150 && gg >= 110 && bb < 120 && rr - bb >= 60) gold++
        }
        return if (n == 0) -1f else gold.toFloat() / n
    }

    // how different two patterns are (0 same .. 1), allowing a 1-pixel shift
    private fun diff(a: Glyph, b: Glyph) = diff(a.bits, b.bits)

    fun diff(a: BooleanArray, b: BooleanArray): Float {
        var best = 1f
        for (dy in -1..1) for (dx in -1..1) {
            var xor = 0; var union = 0
            for (y in 0 until GH) for (x in 0 until GW) {
                val av = a[y * GW + x]
                val xx = x + dx; val yy = y + dy
                val bv = xx in 0 until GW && yy in 0 until GH && b[yy * GW + xx]
                if (av || bv) union++
                if (av != bv) xor++
            }
            if (union > 0) best = min(best, xor.toFloat() / union)
        }
        return best
    }
}
