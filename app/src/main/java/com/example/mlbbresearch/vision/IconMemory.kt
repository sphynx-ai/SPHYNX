package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Layout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Recognises your hero and battle spell from the button icons, with no AI model.
// Every hero has its own skill icons (Skill 1, Skill 2, Ultimate) and every battle spell
// its own icon. The first time the app knows your hero (picked by the AI, read by Gemma,
// or you tapped "This is my hero"), it remembers a small colour fingerprint of those
// icons. From then on it recognises that hero (or spell) by itself in a fraction of a ms.
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
        for (i in 0 until min(a.size, b.size)) s += abs(a[i] - b[i])
        return s / SIZE
    }

    // Hero signature = the three skill icons. Two of three must agree (one may be dimmed / covered).
    fun heroDistance(a: List<FloatArray>, b: List<FloatArray>): Float {
        if (a.size != 3 || b.size != 3) return Float.MAX_VALUE
        val d = (0 until 3).map { distance(a[it], b[it]) }.sorted()
        return d[1]
    }

    // ---------------- memory ----------------

    private class Entry(val name: String, val prints: List<FloatArray>)

    private var heroes: MutableList<Entry>? = null
    private var spells: MutableList<Entry>? = null

    const val HERO_MATCH = 0.16f
    const val SPELL_MATCH = 0.14f

    @Synchronized
    private fun heroList(): MutableList<Entry> = heroes ?: load("iconmem_hero").also { heroes = it }

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

    @Synchronized
    fun matchSpell(p: FloatArray): Pair<String, Float>? =
        spellList().map { it.name to distance(p, it.prints[0]) }.minByOrNull { it.second }

    // Remember a hero's icons (up to 4 looks per hero: skins can change them a little)
    @Synchronized
    fun learnHero(name: String, sig: List<FloatArray>): Boolean {
        if (sig.size != 3) return false
        val list = heroList()
        val same = list.filter { it.name == name }
        if (same.any { heroDistance(sig, it.prints) < HERO_MATCH * 0.5f }) return false
        if (same.size >= 4) list.remove(same.first())
        // another hero with the same icons was a wrong label: replace it
        list.removeAll { it.name != name && heroDistance(sig, it.prints) < HERO_MATCH * 0.5f }
        list.add(Entry(name, sig))
        while (list.size > 400) list.removeAt(0)
        save("iconmem_hero", list)
        return true
    }

    @Synchronized
    fun learnSpell(name: String, p: FloatArray): Boolean {
        val list = spellList()
        if (list.any { it.name == name && distance(p, it.prints[0]) < SPELL_MATCH * 0.5f }) return false
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
    fun forget() {
        heroes = ArrayList(); spells = ArrayList()
        Cfg.setRaw("iconmem_hero", null)
        Cfg.setRaw("iconmem_spell", null)
    }
}
