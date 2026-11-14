package com.sphynx.vision

import com.sphynx.core.Cfg
import com.sphynx.core.Layout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Recognises your hero and battle spell, with no AI model.
//  - Hero: by its PORTRAIT (inside your green-ringed minimap icon) and its in-game MODEL
//    (the colours of the character under your HP bar). Skins change both a little, so up to
//    4 looks are kept per hero.
//  - Battle spell: by its button icon.
// The first time the app knows your hero (picked by the AI, read by Gemma, or you tapped
// "This is my hero"), it remembers a small colour fingerprint. From then on it recognises
// that hero by itself.
object IconMemory {

    private const val GRID = 6
    const val SIZE = GRID * GRID * 3

    // ---------------- fingerprints ----------------

    // 6x6 grid of average colours inside the icon, divided by the icon's mean brightness
    // so a dimmed (cooling down) icon still looks alike. null if the spot is off screen.
    fun print(px: IntArray, w: Int, h: Int, key: String, radiusFrac: Float): FloatArray? {
        val (fx, fy) = Layout.get(key)
        val half = radiusFrac * 0.62f * w           // stay inside the round frame
        val cx = fx * w
        val cy = fy * h
        if (cx - half < 0 || cy - half < 0 || cx + half >= w || cy + half >= h) return null
        val out = FloatArray(SIZE)
        val cell = 2 * half / GRID
        var total = 0f
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val x = (cx - half + (gx + (sx + 0.5f) / 3f) * cell).toInt()
                val y = (cy - half + (gy + (sy + 0.5f) / 3f) * cell).toInt()
                val p = px[y * w + x]
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
            }
            val i = (gy * GRID + gx) * 3
            out[i] = r.toFloat() / n; out[i + 1] = g.toFloat() / n; out[i + 2] = b.toFloat() / n
            total += out[i] + out[i + 1] + out[i + 2]
        }
        val mean = max(1f, total / SIZE)
        if (mean < 12f) return null                  // black: nothing there
        for (i in out.indices) out[i] /= mean
        return out
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) s += abs(a[i] - b[i])
        return s / max(1, n)
    }

    // Portrait inside your minimap icon: icon = centre x, y, radius as fractions of the width
    fun portrait(px: IntArray, w: Int, h: Int, icon: FloatArray): FloatArray? {
        val cx = icon[0] * w
        val cy = icon[1] * w
        val half = icon[2] * w * 0.6f            // inside the ring
        if (half < 3f || cx - half < 0 || cy - half < 0 || cx + half >= w || cy + half >= h) return null
        // coarse 4x4 grid, each cell the average of a 4x4 patch: the portrait is only ~11 px
        // across in the capture, so this keeps a 1 px offset from changing the fingerprint much
        val g = 4
        val out = FloatArray(g * g * 3)
        var total = 0f
        val cell = 2 * half / g
        for (gy in 0 until g) for (gx in 0 until g) {
            var r = 0f; var gg = 0f; var b = 0f; var n = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val x = (cx - half + (gx + (sx + 0.5f) / 4f) * cell).toInt()
                val y = (cy - half + (gy + (sy + 0.5f) / 4f) * cell).toInt()
                val p = px[y * w + x]
                r += (p shr 16) and 0xff; gg += (p shr 8) and 0xff; b += p and 0xff; n++
            }
            val i = (gy * g + gx) * 3
            out[i] = r / n; out[i + 1] = gg / n; out[i + 2] = b / n
            total += out[i] + out[i + 1] + out[i + 2]
        }
        val mean = max(1f, total / out.size)
        for (i in out.indices) out[i] /= mean
        return out
    }

    const val HIST = 64

    // Colours of your hero's model: 4x4x4 colour histogram of the area under your HP bar
    fun model(px: IntArray, w: Int, h: Int, self: Seen): FloatArray {
        val out = FloatArray(HIST)
        val x0 = ((self.x - 0.03f) * w).toInt().coerceAtLeast(0)
        val x1 = ((self.x + 0.03f) * w).toInt().coerceAtMost(w - 1)
        val y0 = ((self.barBottom + 0.03f) * h).toInt().coerceAtLeast(0)
        val y1 = ((self.barBottom + 0.17f) * h).toInt().coerceAtMost(h - 1)
        var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = px[y * w + x]
                val i = (((p shr 16) and 0xff) / 64) * 16 + (((p shr 8) and 0xff) / 64) * 4 + ((p and 0xff) / 64)
                out[i]++; n++
                x += 1
            }
            y += 1
        }
        if (n > 0) for (i in out.indices) out[i] /= n
        return out
    }

    // Hero signature = [portrait, model]. The portrait counts most; the model breaks ties.
    fun heroDistance(a: List<FloatArray>, b: List<FloatArray>): Float {
        if (a.size != 2 || b.size != 2 || a[1].size != HIST || b[1].size != HIST) return Float.MAX_VALUE
        var hist = 0f
        for (i in 0 until HIST) hist += abs(a[1][i] - b[1][i])
        return 0.75f * distance(a[0], b[0]) + 0.25f * hist / 2f
    }

    // ---------------- memory ----------------

    private class Entry(val name: String, val prints: List<FloatArray>)

    private var heroes: MutableList<Entry>? = null
    private var spells: MutableList<Entry>? = null

    const val HERO_MATCH = 0.24f     // same hero measured <= 0.14, a different hero 0.39
    const val SPELL_MATCH = 0.14f

    @Synchronized
    private fun heroList(): MutableList<Entry> = heroes ?: load("iconmem_hero2").also { heroes = it }

    @Synchronized
    private fun spellList(): MutableList<Entry> = spells ?: load("iconmem_spell").also { spells = it }

    private fun load(key: String): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(Cfg.raw(key) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ps = o.getJSONArray("p")
                val prints = (0 until ps.length()).map { j ->
                    val a = ps.getJSONArray(j)
                    FloatArray(a.length()) { a.getDouble(it).toFloat() }
                }
                out.add(Entry(o.getString("n"), prints))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(key: String, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            val ps = JSONArray()
            for (p in e.prints) ps.put(JSONArray(p.map { Math.round(it * 100) / 100.0 }))
            arr.put(JSONObject().put("n", e.name).put("p", ps))
        }
        Cfg.setRaw(key, arr.toString())
    }

    // best match: name and distance (smaller = more alike)
    @Synchronized
    fun matchHero(sig: List<FloatArray>): Pair<String, Float>? =
        heroList().map { it.name to heroDistance(sig, it.prints) }.minByOrNull { it.second }

    // v36.80: the two parts of the look matched SEPARATELY (your priority: in-game model before the
    // minimap portrait). A part counts only when its best hero is clearly closer than any other hero.
    // (Thresholds: the portrait is the part the combined check was measured on - same hero up to about
    // 0.14 - so 0.18; the model's colour histogram is looser: 0.22, margin 0.08.)
    const val PORTRAIT_MATCH = 0.18f
    const val MODEL_MATCH = 0.22f
    fun portraitDistance(a: List<FloatArray>, b: List<FloatArray>): Float =
        if (a.isEmpty() || b.isEmpty()) Float.MAX_VALUE else distance(a[0], b[0])
    fun modelDistance(a: List<FloatArray>, b: List<FloatArray>): Float {
        if (a.size != 2 || b.size != 2 || a[1].size != HIST || b[1].size != HIST) return Float.MAX_VALUE
        var hist = 0f
        for (i in 0 until HIST) hist += abs(a[1][i] - b[1][i])
        return hist / 2f
    }
    private fun bestBy(sig: List<FloatArray>, d: (List<FloatArray>, List<FloatArray>) -> Float, limit: Float, margin: Float): String? {
        val per = heroList().groupBy { it.name }.mapValues { (_, es) -> es.minOf { d(sig, it.prints) } }.entries.sortedBy { it.value }
        val b = per.getOrNull(0) ?: return null
        val second = per.getOrNull(1)?.value ?: Float.MAX_VALUE
        return if (b.value < limit && second - b.value >= margin) b.key else null
    }
    @Synchronized fun matchModel(sig: List<FloatArray>): String? = bestBy(sig, ::modelDistance, MODEL_MATCH, 0.08f)
    @Synchronized fun matchPortrait(sig: List<FloatArray>): String? = bestBy(sig, ::portraitDistance, PORTRAIT_MATCH, 0.06f)

    @Synchronized
    fun matchSpell(p: FloatArray): Pair<String, Float>? =
        spellList().map { it.name to distance(p, it.prints[0]) }.minByOrNull { it.second }

    // Remember a hero's icons (up to 4 looks per hero: skins can change them a little)
    @Synchronized
    fun learnHero(name: String, sig: List<FloatArray>): Boolean {
        if (sig.size != 2) return false
        val list = heroList()
        val same = list.filter { it.name == name }
        if (same.any { heroDistance(sig, it.prints) < HERO_MATCH * 0.5f }) return false
        if (same.size >= 4) list.remove(same.first())
        // another hero with the same icons was a wrong label: replace it
        list.removeAll { it.name != name && heroDistance(sig, it.prints) < HERO_MATCH * 0.5f }
        list.add(Entry(name, sig))
        while (list.size > 400) list.removeAt(0)
        save("iconmem_hero2", list)
        return true
    }

    @Synchronized
    fun learnSpell(name: String, p: FloatArray): Boolean {
        val list = spellList()
        // Do not re-learn if we already have a close print for this spell (stops log/storage churn)
        if (list.any { it.name == name && distance(p, it.prints[0]) < SPELL_MATCH }) return false
        list.removeAll { distance(p, it.prints[0]) < SPELL_MATCH * 0.5f }
        list.add(Entry(name, listOf(p)))
        while (list.size > 60) list.removeAt(0)
        save("iconmem_spell", list)
        return true
    }

    @Synchronized
    fun heroCount(): Int = heroList().map { it.name }.distinct().size

    @Synchronized
    fun spellCount(): Int = spellList().map { it.name }.distinct().size

    @Synchronized
    fun forgetCache() { heroes = null; spells = null }

    @Synchronized
    fun forget() {
        heroes = ArrayList(); spells = ArrayList()
        Cfg.setRaw("iconmem_hero2", null)
        Cfg.setRaw("iconmem_spell", null)
    }
}
