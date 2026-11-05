package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Layout

// WHICH SKILL BUTTONS ARE SHOWING (v36.61): the normal or the LARGE HUD, and 2, 3 or 4 skill buttons -
// read from the screen. For every layout the button spots are checked (a button = a colourful or
// bright-silver icon there) and the spots of the other layouts must be empty. Tuned on your 25
// screenshots: the HUD size right on all, the number of skills on 24 (the miss: an ultimate's effect
// covering the buttons). A reading counts only after several frames agree.
//  * the HUD size always comes from the screen
//  * the number of skills: the hero list / wiki for a known hero; the screen for an unknown one (10
//    readings in a row, then remembered for that hero), or when the screen keeps disagreeing (20)
object ScreenKit {

    private class Lay(val large: Boolean, val kit: Int, val keys: List<String>)
    private val lays = listOf(
        Lay(false, 2, listOf("s1of2", "s2of2")),
        Lay(false, 3, listOf("s1", "s2", "ult")),
        Lay(false, 4, listOf("s1of4", "s2of4", "s3", "ultOf4")),
        Lay(true, 3, listOf("L3s1", "L3s2", "L3ult")),
        Lay(true, 4, listOf("L4s1", "L4s2", "L4s3", "L4ult")),
    )

    @Volatile var seenLarge: Boolean? = null; private set
    @Volatile var seenKit = 0; private set
    @Volatile var agree = 0; private set
    @Volatile var lastRead = "-"; private set
    private var cand: Lay? = null
    private var candN = 0
    private var hudCand: Boolean? = null
    private var hudN = 0

    private fun score(px: IntArray, w: Int, h: Int, at: Pair<Float, Float>): Float {
        val cx = (at.first * w).toInt(); val cy = (at.second * h).toInt(); val r = (0.009f * w).toInt()
        var c = 0; var br = 0; var t = 0
        var dx = -r
        while (dx <= r) {
            var dy = -r
            while (dy <= r) {
                if (dx * dx + dy * dy <= r * r) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until w && y in 0 until h) {
                        val p = px[y * w + x]; val rr = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                        val mx = maxOf(rr, g, b); val mn = minOf(rr, g, b)
                        t++
                        if (mx - mn > 70 && mx > 110) c++ else if (mn > 165) br++
                    }
                }
                dy += 3
            }
            dx += 3
        }
        if (t == 0) return 0f
        return maxOf(c.toFloat() / t, br.toFloat() / t * 1.5f)
    }

    // every few frames in a match
    fun observe(px: IntArray, w: Int, h: Int) {
        val pts = lays.associateWith { l -> l.keys.map { Layout.point(it) } }
        val all = pts.values.flatten().distinct()
        val sc = all.associateWith { score(px, w, h, it) }
        fun near(a: Pair<Float, Float>, b: Pair<Float, Float>) = kotlin.math.abs(a.first - b.first) < 0.03f && kotlin.math.abs(a.second - b.second) < 0.05f
        var best: Lay? = null; var bestV = -9f; var second = -9f; var bestMin = 0f
        for (l in lays) {
            val mine = pts[l]!!
            val present = mine.map { sc[it]!! }.sorted()
            val empty = all.filter { p -> mine.none { near(p, it) } }.maxOfOrNull { sc[it]!! } ?: 0f
            val v = (present[0] + present[1]) / 2f - empty * 0.5f
            if (v > bestV) { second = bestV; bestV = v; best = l; bestMin = present[0] } else if (v > second) second = v
        }
        val b = best ?: return
        if (bestV < 0.15f || bestMin < 0.25f) { if (fromPlus(px, w, h)) return }
        if (bestV < 0.15f) { lastRead = "no skill buttons seen"; return }   // shop / map / effects
        // every button of the layout must be lit: a skill not learned yet is a dark button (and has no
        // gold crescent until upgraded), so early in a match the count is not read from a half-dark row
        // a clear winner only (on your Esmeralda screenshot her ultimate's effect made 2 skills look
        // almost as likely as 3: such a reading is not counted)
        if (bestV - second < 0.15f) { lastRead = "unclear (%.2f vs %.2f): skill count not read".format(bestV, second); return }
        if (bestMin < 0.25f) { lastRead = "some skill buttons dark (not learned yet?): skill count not read"
            if (hudCand == b.large) hudN++ else { hudCand = b.large; hudN = 1 }
            if (hudN >= 3) { seenLarge = b.large; Layout.largeHud = b.large }
            return }
        lastRead = "%s HUD, %d skills (%.2f vs %.2f)".format(if (b.large) "large" else "normal", b.kit, bestV, second)
        // the HUD size: 3 agreeing readings
        if (hudCand == b.large) hudN++ else { hudCand = b.large; hudN = 1 }
        if (hudN >= 3) { seenLarge = b.large; Layout.largeHud = b.large }
        // the skill count: kept as a count of agreeing readings
        if (cand === b) candN++ else { cand = b; candN = 1 }
        seenKit = b.kit; agree = candN
        // confirmed for this hero: remembered (next matches use it from the start)
        val hero = com.example.mlbbresearch.game.GameStateTracker.ownHero()
        if (hero != null && candN == 10) com.example.mlbbresearch.core.Cfg.setRaw("kit_screen_" + hero.lowercase().filter { it.isLetterOrDigit() }, b.kit.toString())
    }
    // the skill count read from the SCREEN alone (3 agreeing readings), 0 = not read yet - never from
    // the hero list (v36.80: a wrong hero's list made Fredrinn's 4 buttons be read at 3-skill places)
    fun screenKit(): Int = if (agree >= 3 && seenKit in 2..4) seenKit else 0
    // the button places of that layout (normal / large HUD)
    fun keysFor(kit: Int): List<String>? = lays.firstOrNull { it.kit == kit && it.large == (seenLarge == true) }?.keys
    fun remembered(hero: String?): Int? = hero?.let { com.example.mlbbresearch.core.Cfg.raw("kit_screen_" + it.lowercase().filter { c -> c.isLetterOrDigit() })?.toIntOrNull() }

    // EARLY IN A MATCH the skill buttons are dark (not learned) but the golden "+" show: their places
    // tell the layout (your level-1 screenshots: 4 skills = 3 "+" in a row, 3 skills = 2, Hirara's 2 =
    // 2 at other places; the ultimate's "+" comes only from level 4). Normal HUD only.
    private val plusSets = listOf(4 to listOf("s1Up4", "s2Up4", "s3Up4"), 3 to listOf("s1Up", "s2Up"), 2 to listOf("s1Up2", "s2Up2"))
    private fun goldAt(px: IntArray, w: Int, h: Int, at: Pair<Float, Float>): Float {
        val cx = (at.first * w).toInt(); val cy = (at.second * h).toInt(); val r = (0.010f * w).toInt()
        var n = 0; var t = 0
        var dx = -r
        while (dx <= r) { var dy = -r
            while (dy <= r) { val x = cx + dx; val y = cy + dy
                if (x in 0 until w && y in 0 until h) { t++; if (ScreenReader.isGold(px[y * w + x])) n++ }
                dy += 3 }
            dx += 3 }
        return if (t == 0) 0f else n.toFloat() / t
    }
    private fun fromPlus(px: IntArray, w: Int, h: Int): Boolean {
        val sc = plusSets.map { (k, keys) -> k to keys.map { goldAt(px, w, h, Layout.point(it)) } }
        // a set counts when all its "+" are lit; the largest such set wins (4 contains look-alikes of 3)
        val lit = sc.filter { (_, v) -> v.all { it > 0.25f } }
        val k = lit.maxByOrNull { it.first }?.first ?: return false
        // 3 and 2 can both look lit only by accident: then nothing is read
        if (k != 4 && lit.size > 1) return false
        lastRead = "normal HUD, $k skills (from the + signs: " + sc.joinToString { (kk, v) -> "$kk:" + v.joinToString("/") { "%.2f".format(it) } } + ")"
        if (hudCand == false) hudN++ else { hudCand = false; hudN = 1 }
        if (hudN >= 3) { seenLarge = false; Layout.largeHud = false }
        val l = lays.first { !it.large && it.kit == k }
        if (cand === l) candN++ else { cand = l; candN = 1 }
        seenKit = k; agree = candN
        val hero = com.example.mlbbresearch.game.GameStateTracker.ownHero()
        if (hero != null && candN == 10) com.example.mlbbresearch.core.Cfg.setRaw("kit_screen_" + hero.lowercase().filter { it.isLetterOrDigit() }, k.toString())
        return true
    }

    // the number of skill buttons to use for this hero
    fun kitFor(hero: String?, listed: Int, heroKnown: Boolean): Int {
        val k = seenKit
        if (k == 0 || agree < 10) return if (!heroKnown) remembered(hero) ?: listed else listed
        if (!heroKnown) return k
        return if (k != listed && agree >= 20) k else listed
    }

    fun reset() { cand = null; candN = 0; seenKit = 0; agree = 0; hudCand = null; hudN = 0 }
}
