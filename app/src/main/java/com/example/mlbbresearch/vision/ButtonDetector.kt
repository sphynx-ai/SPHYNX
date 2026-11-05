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

    // how far (screen heights) a skill estimate may move onto a colourful icon: OFF (0) - on your
    // screenshots grass and effects made it as often worse as better than the measured offsets
    @JvmField var SNAP = 0f

    fun detect(px: IntArray, w: Int, h: Int, hint: ScreenHint): Result {
        val found = LinkedHashMap<String, Pair<Float, Float>>()
        val notes = ArrayList<String>()

        if (hint == ScreenHint.HERO_SELECT) {
            val gold = blobs(px, w, h, 0.30f, 0.75f, 1f, 1f, 2) { ScreenReader.isGold(it) }.maxByOrNull { it.count }
            if (gold != null && gold.w > 0.06f) found["lockIn"] = gold.cx to gold.cy else notes.add("Confirm button not found")
            return Result(found, report(found, notes, "hero select"))
        }
        if (hint != ScreenHint.NONE && hint != ScreenHint.MAP) {
            return Result(found, "This is the ${hint.label} screen, not the match view: go back to the match and detect again")
        }

        // MLBB's HUD scales with the screen HEIGHT and hangs off the corners, so with the
        // attack button found, every other default button sits at a fixed offset from it
        // (measured in screen heights). Custom MLBB layouts: drag the markers instead.
        val hx = h.toFloat() / w       // one screen height, as a fraction of the width
        // Detect the Basic Attack button in both its glowing (pressed: high lum/gold rim) and dimmed (idle: warm metallic icon) stages
        val attack = blobs(px, w, h, 0.65f, 0.60f, 1f, 1f, 2) {
            val l = ScreenReader.lum(it); val s = ScreenReader.sat(it)
            (s > 0.28f && l > 52) || l > 145
        }
            .filter { round(it, 0.11f * hx, 0.30f * hx) && it.cx > 1f - 0.55f * hx && it.cy > 0.70f }
            .filter { kotlin.math.abs(it.cx - (1f - 0.372f * hx)) < 0.038f && kotlin.math.abs(it.cy - 0.84f) < 0.045f }
            .maxByOrNull { it.count }
        val ax: Float
        val ay: Float
        if (attack != null) {
            ax = attack.cx; ay = attack.cy
        } else {
            ax = 1f - 0.372f * hx; ay = 0.84f
            notes.add("attack button not seen: used MLBB's default layout")
        }
        fun at(key: String, dx: Float, dy: Float) {
            found[key] = (ax + dx * hx).coerceIn(0f, 1f) to (ay + dy).coerceIn(0f, 1f)
        }
        at("attack", 0f, 0f)
        // (v36.57: offsets re-measured on your 2712 x 1220 screenshots of 3-, 4- and 2-skill heroes)
        at("s1", -0.27f, 0.045f)
        at("s2", -0.169f, -0.153f)
        at("ult", 0.017f, -0.258f)
        // other kits (measured on 2712 x 1220 screenshots): four skills / two skills (Hirara)
        at("s1of4", -0.27f, 0.045f)
        at("s3", -0.101f, -0.201f)
        at("s2of4", -0.211f, -0.091f)
        at("ultOf4", 0.046f, -0.264f)
        at("s1of2", -0.235f, -0.046f)
        at("s2of2", -0.005f, -0.254f)
        at("s1Up", -0.363f, -0.066f)
        at("s2Up", -0.258f, -0.246f)
        at("ultUp", -0.076f, -0.349f)
        at("s1Up4", -0.346f, -0.026f)
        at("s2Up4", -0.288f, -0.169f)
        at("s3Up4", -0.178f, -0.278f)
        at("ultUp4", -0.032f, -0.345f)
        at("s1Up2", -0.339f, -0.130f)
        at("s2Up2", -0.106f, -0.347f)
        at("spell", -0.429f, 0.059f)
        at("regen", -0.569f, 0.059f)
        at("recall", -0.710f, 0.059f)
        // a golden "+" showing right now beats the estimate
        val plus = blobs(px, w, h, 0.45f, 0.25f, 1f, 1f, 2) { ScreenReader.isGold(it) }
            .filter { round(it, 0.025f * hx, 0.08f * hx) }
        var snapped = 0
        for (key in listOf("s1Up", "s2Up", "ultUp", "s1Up4", "s2Up4", "s3Up4", "ultUp4", "s1Up2", "s2Up2")) {
            val (gx, gy) = found[key]!!
            val near = plus.minByOrNull { hypot(it.cx - gx, (it.cy - gy) * hx) } ?: continue
            if (hypot(near.cx - gx, (near.cy - gy) * hx) < 0.06f * hx) { found[key] = near.cx to near.cy; snapped++ }
        }
        if (snapped == 0) notes.add("no golden + showing: upgrade spots are estimates (check them when a + appears)")

        // SKILL BUTTONS ON THEIR ICONS (v36.57): the HUD differs between layouts (your Pharsa screenshot
        // has every skill higher and further right), so each estimate moves onto the nearest round,
        // colourful icon seen - one icon per button, for each kit's set of buttons
        if (attack != null) run {
            val icons = blobs(px, w, h, 0.55f, 0.40f, 1f, 1f, 2) { ScreenReader.sat(it) > 0.42f && ScreenReader.lum(it) > 70 }
                .filter { round(it, 0.08f * hx, 0.22f * hx) }
                .filter { b -> hypot(b.cx - found["attack"]!!.first, (b.cy - found["attack"]!!.second) * hx) > 0.10f * hx }
            var moved = 0
            for (group in listOf(listOf("s1", "s2", "ult"), listOf("s1of4", "s2of4", "s3", "ultOf4"), listOf("s1of2", "s2of2"))) {
                val used = HashSet<Blob>()
                val pairs = group.flatMap { k -> val (gx, gy) = found[k]!!; icons.map { Triple(k, it, hypot(it.cx - gx, (it.cy - gy) * hx)) } }
                    .filter { it.third < SNAP * hx }.sortedBy { it.third }
                val done = HashSet<String>()
                for ((k, b, _) in pairs) {
                    if (k in done || b in used) continue
                    done.add(k); used.add(b)
                    found[k] = b.cx to b.cy; moved++
                }
            }
            notes.add(if (moved > 0) "$moved skill buttons placed on the icons seen" else "skill icons not seen: positions are estimates")
        }
        // quick-buy hangs off the top-right corner, joystick off the bottom-left
        found["quickBuy"] = (1f - 0.283f * hx) to 0.155f
        found["activeItem"] = (1f - 0.391f * hx) to 0.383f
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
