package com.example.mlbbresearch.vision

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Finds MLBB's buttons on a screenshot so calibration is one tap.
// In a match it finds the round, colourful skill icons (bottom right), the battle spell row
// (bottom middle), the joystick (bottom left) and the golden quick-buy icon (top right).
// On hero select it finds the golden Confirm button. Anything not found keeps its old position.
object ButtonDetector {

    class Blob(val cx: Float, val cy: Float, val w: Float, val h: Float, val count: Int) {
        val size: Float get() = max(w, h)
    }

    class Result(val found: Map<String, Pair<Float, Float>>, val report: String)

    // Connected groups of matching pixels inside [l, t, r, b] (fractions). Positions in fractions.
    fun blobs(px: IntArray, w: Int, h: Int, l: Float, t: Float, r: Float, b: Float, step: Int = 2, test: (Int) -> Boolean): List<Blob> {
        val x0 = (l * w).toInt().coerceIn(0, w - 1)
        val y0 = (t * h).toInt().coerceIn(0, h - 1)
        val x1 = (r * w).toInt().coerceIn(0, w - 1)
        val y1 = (b * h).toInt().coerceIn(0, h - 1)
        val gw = (x1 - x0) / step + 1
        val gh = (y1 - y0) / step + 1
        if (gw <= 1 || gh <= 1) return emptyList()
        val on = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) on[gy * gw + gx] = test(px[(y0 + gy * step) * w + x0 + gx * step])
        val seen = BooleanArray(on.size)
        val stack = IntArray(on.size)
        val out = ArrayList<Blob>()
        for (i in on.indices) {
            if (!on[i] || seen[i]) continue
            var sp = 0
            stack[sp++] = i
            seen[i] = true
            var n = 0
            var minX = Int.MAX_VALUE; var maxX = 0; var minY = Int.MAX_VALUE; var maxY = 0
            while (sp > 0) {
                val c = stack[--sp]
                n++
                val cx = c % gw
                val cy = c / gw
                minX = min(minX, cx); maxX = max(maxX, cx); minY = min(minY, cy); maxY = max(maxY, cy)
                if (cx > 0 && on[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1 }
                if (cx < gw - 1 && on[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1 }
                if (cy > 0 && on[c - gw] && !seen[c - gw]) { seen[c - gw] = true; stack[sp++] = c - gw }
                if (cy < gh - 1 && on[c + gw] && !seen[c + gw]) { seen[c + gw] = true; stack[sp++] = c + gw }
            }
            if (n < 4) continue
            val bx0 = (x0 + minX * step).toFloat() / w
            val bx1 = (x0 + maxX * step).toFloat() / w
            val by0 = (y0 + minY * step).toFloat() / h
            val by1 = (y0 + maxY * step).toFloat() / h
            out.add(Blob((bx0 + bx1) / 2f, (by0 + by1) / 2f, bx1 - bx0, (by1 - by0) * h / w, n))
        }
        return out
    }

    // Round icon: square-ish box, reasonably filled
    private fun round(bl: Blob, minSize: Float, maxSize: Float): Boolean {
        if (bl.size < minSize || bl.size > maxSize) return false
        val ratio = bl.w / max(1e-4f, bl.h)
        return ratio in 0.6f..1.6f
    }

    fun detect(px: IntArray, w: Int, h: Int, hint: ScreenHint): Result {
        val found = LinkedHashMap<String, Pair<Float, Float>>()
        val notes = ArrayList<String>()

        if (hint == ScreenHint.HERO_SELECT) {
            val gold = blobs(px, w, h, 0.30f, 0.75f, 1f, 1f, 2) { ScreenReader.isGold(it) }.maxByOrNull { it.count }
            if (gold != null && gold.w > 0.06f) found["lockIn"] = gold.cx to gold.cy else notes.add("Confirm button not found")
            return Result(found, report(found, notes, "hero select"))
        }
        if (hint == ScreenHint.LOADING || hint == ScreenHint.SHOP || hint == ScreenHint.SCOREBOARD) {
            return Result(found, "This is the ${hint.name.lowercase()} screen, not the match view: go back to the match and detect again")
        }

        // MLBB's HUD scales with the screen HEIGHT and hangs off the corners, so with the
        // attack button found, every other default button sits at a fixed offset from it
        // (measured in screen heights). Custom MLBB layouts: drag the markers instead.
        val hx = h.toFloat() / w       // one screen height, as a fraction of the width
        val attack = blobs(px, w, h, 0.65f, 0.60f, 1f, 1f, 2) { ScreenReader.sat(it) > 0.42f && ScreenReader.lum(it) > 70 }
            .filter { round(it, 0.12f * hx, 0.28f * hx) && it.cx > 1f - 0.55f * hx && it.cy > 0.70f }
            .maxByOrNull { it.count }
        val ax: Float
        val ay: Float
        if (attack != null) {
            ax = attack.cx; ay = attack.cy
        } else {
            ax = 1f - 0.384f * hx; ay = 0.833f
            notes.add("attack button not seen: used MLBB's default layout")
        }
        fun at(key: String, dx: Float, dy: Float) {
            found[key] = (ax + dx * hx).coerceIn(0f, 1f) to (ay + dy).coerceIn(0f, 1f)
        }
        at("attack", 0f, 0f)
        at("s1", -0.249f, 0.017f)
        at("s2", -0.127f, -0.178f)
        at("ult", 0.046f, -0.297f)
        at("s1Up", -0.341f, -0.072f)
        at("s2Up", -0.219f, -0.267f)
        at("ultUp", -0.047f, -0.386f)
        at("spell", -0.429f, 0.059f)
        at("regen", -0.569f, 0.059f)
        at("recall", -0.710f, 0.059f)
        // a golden "+" showing right now beats the estimate
        val plus = blobs(px, w, h, 0.45f, 0.25f, 1f, 1f, 2) { ScreenReader.isGold(it) }
            .filter { round(it, 0.025f * hx, 0.08f * hx) }
        var snapped = 0
        for (key in listOf("s1Up", "s2Up", "ultUp")) {
            val (gx, gy) = found[key]!!
            val near = plus.minByOrNull { hypot(it.cx - gx, (it.cy - gy) * hx) } ?: continue
            if (hypot(near.cx - gx, (near.cy - gy) * hx) < 0.06f * hx) { found[key] = near.cx to near.cy; snapped++ }
        }
        if (snapped == 0) notes.add("no golden + showing: upgrade spots are estimates (check them when a + appears)")

        // quick-buy hangs off the top-right corner, joystick off the bottom-left
        found["quickBuy"] = (1f - 0.283f * hx) to 0.155f
        found["activeItem"] = (1f - 0.155f * hx) to 0.31f
        found["joystick"] = (0.439f * hx) to 0.783f
        found["kdaK"] = (1f - 0.484f * hx) to 0.042f
        found["kdaD"] = (1f - 0.407f * hx) to 0.042f
        found["kdaA"] = (1f - 0.331f * hx) to 0.042f
        found["mapTL"] = (0.089f * hx) to 0f
        found["mapBR"] = (0.422f * hx) to 0.322f
        if (!ScreenReader.quickBuyShowing(px, w, h)) notes.add("quick-buy item not showing right now (position is MLBB's default)")

        return Result(found, report(found, notes, "in game"))
    }

    private fun report(found: Map<String, Pair<Float, Float>>, notes: List<String>, screen: String): String {
        val sb = StringBuilder("Screen: $screen. Found ${found.size}: ")
        sb.append(if (found.isEmpty()) "nothing" else found.keys.joinToString())
        if (notes.isNotEmpty()) sb.append("\n").append(notes.joinToString("\n"))
        return sb.toString()
    }
}
