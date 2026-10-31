package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Neutral monsters (jungle creeps, turtle, lord) are found by their HP bars like heroes,
// but their bar colour is not team-coloured. Out of the box it looks for orange / yellow
// bars; it also LEARNS the real colour: when you stand at a jungle camp (minimap) and no
// monster is found, it looks for bar-shaped marks of any other colour next to your hero,
// and once the same colour shows up 3 times it remembers it ("Learn the colour now" on
// the Player page does it in one go).
object MonsterDetector {

    @Volatile private var learned: IntArray? = null      // r, g, b
    private var loaded = false
    private val votes = ArrayList<IntArray>()

    private fun load() {
        if (loaded) return
        loaded = true
        learned = Cfg.raw("monster_rgb")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }?.toIntArray()
    }

    val learnedText: String get() { load(); return learned?.let { "learned colour ${it[0]},${it[1]},${it[2]}" } ?: "red bars without a level badge" }

    // per pixel: is this a monster HP bar colour?
    fun matches(r: Int, g: Int, b: Int): Boolean {
        if (!loaded) load()
        val c = learned
        if (c != null && abs(r - c[0]) + abs(g - c[1]) + abs(b - c[2]) <= 70 && max(r, max(g, b)) - min(r, min(g, b)) >= 50) return true
        // monster HP bars turned out to be RED like enemies (they are told apart by the missing
        // level badge, see FrameProcessor); only a colour you taught is used here
        return false
    }

    fun forget() {
        learned = null
        votes.clear()
        Cfg.setRaw("monster_rgb", null)
    }

    // Look for bar-shaped marks next to your hero whose colour is not a team colour.
    // Returns true when a colour has been learned.
    fun tryLearn(px: IntArray, w: Int, h: Int, self: Seen, force: Boolean = false): Boolean {
        val cands = ButtonDetector.blobs(px, w, h,
            (self.x - 0.35f).coerceAtLeast(0f), (self.y - 0.45f).coerceAtLeast(0f),
            (self.x + 0.35f).coerceAtMost(1f), (self.y + 0.25f).coerceAtMost(1f), 1) { p ->
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            mx >= 120 && mx - mn >= 60 && HeroDetector.classify(p) == 0
        }.filter { bl ->
            val hpx = bl.h * w            // blob height in pixels
            bl.w >= 0.015f && hpx <= max(3f, h * 0.024f) && bl.w * w >= hpx * 4 &&
                !(abs(bl.cx - self.x) < 0.05f && abs(bl.cy - self.y) < 0.12f)   // not your own bars
        }
        if (cands.isEmpty()) return false
        val best = cands.maxByOrNull { it.w }!!
        // average colour along the bar's middle row
        val y = (best.cy * h).toInt().coerceIn(0, h - 1)
        var r = 0; var g = 0; var b = 0; var n = 0
        var x = ((best.cx - best.w / 2) * w).toInt().coerceAtLeast(0)
        val x1 = ((best.cx + best.w / 2) * w).toInt().coerceAtMost(w - 1)
        while (x <= x1) {
            val p = px[y * w + x]
            r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
            x++
        }
        if (n == 0) return false
        val c = intArrayOf(r / n, g / n, b / n)
        votes.add(c)
        val agree = votes.count { abs(it[0] - c[0]) + abs(it[1] - c[1]) + abs(it[2] - c[2]) <= 60 }
        if (agree >= 3 || force) {
            learned = c
            votes.clear()
            Cfg.setRaw("monster_rgb", "${c[0]},${c[1]},${c[2]}")
            Diag.info("Learned the monster HP bar colour: ${c[0]},${c[1]},${c[2]}")
            return true
        }
        if (votes.size > 12) votes.removeAt(0)
        return false
    }
}
