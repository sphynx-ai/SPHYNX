package com.example.mlbbresearch.vision

import android.content.Context
import android.graphics.BitmapFactory
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.WikiSkills
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

// DETECT, IDENTIFY AND VERIFY YOUR HERO (v36.66) from its HERO ICON and its SKILL ICONS - no model.
//  * Skill icons: every skill button on screen (2, 3 or 4, as ScreenKit reads them) becomes a small
//    colour fingerprint (8x8, brightness-normalised, so a button cooling down still looks alike).
//    Tested on your screenshots: the same hero's buttons correlate 0.81+, two different heroes 0.41 at most.
//  * Compared with (1) the looks learned in your matches and (2) the real skill pictures from each
//    hero's wiki page (the Library's icons, cached on the phone).
//  * Hero icon: your portrait in the minimap (IconMemory) - when both agree the hero is VERIFIED.
//  * Once detected in game the hero is ABSOLUTE (v36.67): it is used even over the hero you typed;
//    the Player page says so when the two differ. (Another hero than the typed one needs 6 agreeing
//    readings instead of 3.) It is forgotten when the match ends.
object HeroVerifier {

    private const val G = 8
    private const val N = G * G * 3
    private const val SAME = 0.70f          // learned look: same hero 0.81+, others 0.41-
    private const val WIKI = 0.45f          // wiki picture vs the in-game button (rendered differently)
    private const val MARGIN = 0.12f

    @Volatile var skillHero: String? = null; private set
    @Volatile var status = "not seen yet (in a match, with the skill buttons lit)"; private set
    @Volatile var verified = false; private set
    @Volatile var mismatch: String? = null; private set
    private var cand: String? = null
    private var candN = 0
    private var ctx: Context? = null

    fun init(c: Context) { ctx = c.applicationContext }
    fun appContext(): Context? = ctx
    // the last skill-button look seen this match and its button count (locked to the hero confirmed on
    // the end-of-match screen - v36.87)
    @Volatile var lastLook: List<FloatArray>? = null; private set
    @Volatile var lastKit = 0; private set
    fun lockLook(hero: String) { lastLook?.let { if (com.example.mlbbresearch.game.HeroKit.kitOf(hero) == it.size) learn(hero, it) } }

    // ---------------- fingerprints ----------------
    private fun norm(v: FloatArray): FloatArray? {
        var m = 0f; for (x in v) m += x; m /= v.size
        var s = 0f; for (x in v) s += (x - m) * (x - m)
        val sd = sqrt(s / v.size)
        if (sd < 6f || m < 20f) return null           // flat / black: no icon there
        return FloatArray(v.size) { (v[it] - m) / sd }
    }
    fun corr(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s / maxOf(1, minOf(a.size, b.size))
    }

    // a button on screen: the square inside its round frame
    fun buttonPrint(px: IntArray, w: Int, h: Int, fx: Float, fy: Float, radius: Float = 0.013f): FloatArray? {
        val r = radius * w; val cx = fx * w; val cy = fy * h
        if (cx - r < 0 || cy - r < 0 || cx + r >= w || cy + r >= h) return null
        val out = FloatArray(N); val cell = 2 * r / G
        for (gy in 0 until G) for (gx in 0 until G) {
            var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (sy in 0 until 2) for (sx in 0 until 2) {
                val x = (cx - r + (gx + (sx + 0.5f) / 2f) * cell).toInt(); val y = (cy - r + (gy + (sy + 0.5f) / 2f) * cell).toInt()
                val p = px[y * w + x]; rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255; n++
            }
            val i = (gy * G + gx) * 3
            out[i] = rr.toFloat() / n; out[i + 1] = gg.toFloat() / n; out[i + 2] = bb.toFloat() / n
        }
        return norm(out)
    }

    // how bright an icon is (a cooling one is darkened)
    fun meanLum(px: IntArray, w: Int, h: Int, fx: Float, fy: Float, radius: Float = 0.010f): Float {
        val r = (radius * w).toInt(); val cx = (fx * w).toInt(); val cy = (fy * h).toInt()
        var sum = 0f; var n = 0
        var dx = -r
        while (dx <= r) { var dy = -r
            while (dy <= r) {
                if (dx * dx + dy * dy <= r * r) { val x = cx + dx; val y = cy + dy
                    if (x in 0 until w && y in 0 until h) { val p = px[y * w + x]
                        sum += 0.3f * ((p shr 16) and 255) + 0.59f * ((p shr 8) and 255) + 0.11f * (p and 255); n++ } }
                dy += 2 }
            dx += 2 }
        return if (n == 0) 0f else sum / n
    }

    // a wiki skill picture: the same middle part the round button shows (~74% of the picture)
    private val wikiCache = HashMap<String, FloatArray?>()
    @Synchronized
    fun clearWikiCache() { wikiCache.clear() }
    @Synchronized
    fun wikiPrint(file: String): FloatArray? {
        if (wikiCache.containsKey(file)) return wikiCache[file]
        val c = ctx ?: return null
        val f = File(File(c.filesDir, "icons"), file)
        if (!f.exists()) return null                        // not downloaded yet (Library) - try later
        val p = try {
            val b = BitmapFactory.decodeFile(f.path) ?: return null
            val bw = b.width; val bh = b.height
            val x0 = bw * 0.13f; val y0 = bh * 0.13f; val cw = bw * 0.74f / G; val ch = bh * 0.74f / G
            val out = FloatArray(N)
            for (gy in 0 until G) for (gx in 0 until G) {
                var rr = 0; var gg = 0; var bb = 0; var n = 0
                for (sy in 0 until 3) for (sx in 0 until 3) {
                    val x = (x0 + (gx + (sx + 0.5f) / 3f) * cw).toInt().coerceIn(0, bw - 1)
                    val y = (y0 + (gy + (sy + 0.5f) / 3f) * ch).toInt().coerceIn(0, bh - 1)
                    val q = b.getPixel(x, y)
                    if ((q ushr 24) < 128) continue                // transparent corners
                    rr += (q shr 16) and 255; gg += (q shr 8) and 255; bb += q and 255; n++
                }
                val i = (gy * G + gx) * 3
                if (n > 0) { out[i] = rr.toFloat() / n; out[i + 1] = gg.toFloat() / n; out[i + 2] = bb.toFloat() / n }
            }
            norm(out)
        } catch (e: Exception) { null }
        wikiCache[file] = p
        return p
    }

    // ---------------- learned looks (per hero, per kit size: list of slot prints) ----------------
    private var mem: MutableMap<String, MutableList<List<FloatArray>>>? = null
    private const val KEY = "skillprint_v1"
    @Synchronized
    private fun memory(): MutableMap<String, MutableList<List<FloatArray>>> = mem ?: HashMap<String, MutableList<List<FloatArray>>>().also { m ->
        try {
            val o = JSONObject(Cfg.raw(KEY) ?: "{}")
            for (h in o.keys()) {
                val looks = o.getJSONArray(h)
                m[h] = (0 until looks.length()).map { i ->
                    val sl = looks.getJSONArray(i)
                    (0 until sl.length()).map { j -> val a = sl.getJSONArray(j); FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                }.toMutableList()
            }
        } catch (e: Exception) { }
        mem = m
    }
    @Synchronized
    private fun save() {
        val o = JSONObject()
        for ((h, looks) in memory()) o.put(h, JSONArray(looks.map { l -> JSONArray(l.map { p -> JSONArray(p.map { Math.round(it * 10) / 10.0 }) }) }))
        Cfg.setRaw(KEY, o.toString())
    }
    fun learnedCount(): Int = memory().keys.count { !it.startsWith("live|") }
    fun learnedLiveCount(): Int = memory().keys.count { it.startsWith("live|") }
    @Synchronized fun forget() { mem = HashMap(); Cfg.setRaw(KEY, null) }

    private fun lookScore(a: List<FloatArray>, b: List<FloatArray>): Float =
        if (a.size != b.size) -1f else a.indices.map { corr(a[it], b[it]) }.average().toFloat()

    // the slots on screen, in button order, for the kit ScreenKit reads
    private fun slots(kit: Int) = when (kit) { 2 -> listOf("s1", "s2"); 4 -> listOf("s1", "s2", "s3", "ult"); else -> listOf("s1", "s2", "ult") }

    // ---------------- every few frames in a match ----------------
    fun observe(px: IntArray, w: Int, h: Int) {
        // the number of skill buttons comes from the SCREEN only (v36.80): a 4-button screen can only be a
        // 4-skill hero (Fredrinn was matched as Zilong); not read yet = no reading
        val kit = ScreenKit.screenKit()
        if (kit == 0) { status = "waiting for the skill buttons to be counted on screen"; return }
        val sl = slots(kit)
        val keys = ScreenKit.keysFor(kit) ?: return
        val prints = keys.map { k -> Layout.point(k).let { buttonPrint(px, w, h, it.first, it.second) } }
        if (prints.any { it == null }) return                      // a button hidden / black: not now
        val look = prints.map { it!! }
        lastLook = look; lastKit = kit

        // 1) learned looks
        val learned = memory().filterKeys { !it.startsWith("live|") && com.example.mlbbresearch.game.HeroKit.kitOf(it).let { k -> k == 0 || k == kit } }.mapValues { (_, looks) -> looks.maxOf { lookScore(look, it) } }.filterValues { it > -1f }
            .entries.sortedByDescending { it.value }
        // 2) wiki pictures (same kit size only; each screen button vs that hero's picture for the slot)
        val wiki = HeroDb.allNames.mapNotNull { hero ->
            val ic = WikiSkills.icons(hero)
            if (ic.isEmpty()) return@mapNotNull null
            // the PASSIVE picture is never used: it has no button in game (only the active skill buttons count)
            val heroSlots = listOf("s1", "s2", "s3", "ult").filter { it in ic }
            if (heroSlots.size != kit || com.example.mlbbresearch.game.HeroKit.kitOf(hero).let { k -> k != 0 && k != kit }) return@mapNotNull null
            val ps = heroSlots.map { wikiPrint(ic.getValue(it)) }
            if (ps.any { it == null }) return@mapNotNull null
            hero to look.indices.map { corr(look[it], ps[it]!!) }.average().toFloat()
        }.sortedByDescending { it.second }

        val l0 = learned.getOrNull(0); val l1 = learned.getOrNull(1)
        val w0 = wiki.getOrNull(0); val w1 = wiki.getOrNull(1)
        val byLearned = l0?.takeIf { it.value >= SAME && it.value - (l1?.value ?: 0f) >= MARGIN }?.key
        val byWiki = w0?.takeIf { it.second >= WIKI && it.second - (w1?.second ?: 0f) >= MARGIN }?.first
        val found = byLearned ?: byWiki
        val portrait = GameStateTracker.iconHero

        // a reading counts after 3 agreeing ones in a row
        if (found != null && found == cand) candN++ else { cand = found; candN = if (found != null) 1 else 0 }
        // the detection is absolute (it overrides the typed hero), so a hero other than the one you
        // typed / picked needs twice as many agreeing readings
        val claimed0 = HeroDb.match(Cfg.manualHero) ?: GameStateTracker.loadingHero
        if (found != null && candN >= (if (claimed0 != null && found != claimed0) 6 else 3)) {
            if (skillHero != found) Diag.info("Recognised your hero from its skill icons: $found" + if (byLearned == null) " (wiki pictures)" else "")
            skillHero = found
        }

        // the hero the app was told / picked: verify it
        val claimed = HeroDb.match(Cfg.manualHero) ?: GameStateTracker.loadingHero
        val claimedScore = claimed?.let { c -> maxOf(memory()[c]?.maxOf { lookScore(look, it) } ?: -1f, wiki.firstOrNull { it.first == c }?.second ?: -1f) }
        val sh = skillHero
        verified = sh != null && (portrait == null || portrait == sh) && (claimed == null || claimed == sh)
        mismatch = if (claimed != null && sh != null && sh != claimed && candN >= 6) sh else null

        // learn the look of a hero that is known this match and not contradicted by the icons
        // (only a hero with as many skills as the buttons on screen teaches - v36.80)
        val teacher = (claimed ?: portrait)?.takeIf { com.example.mlbbresearch.game.HeroKit.kitOf(it) == kit }
        if (teacher != null && (sh == null || sh == teacher) && (portrait == null || portrait == teacher) && (byWiki == null || byWiki == teacher)) learn(teacher, look)

        status = when {
            mismatch != null -> "⚠ the skill icons show ${mismatch}, not $claimed - using ${mismatch}"
            sh != null && verified -> "✓ $sh verified" + (if (portrait == sh) " (hero icon + skill icons)" else " (skill icons)")
            sh != null -> "$sh from its skill icons" + (portrait?.let { " (hero icon says $it)" } ?: "")
            claimed != null && claimedScore != null && claimedScore >= WIKI -> "$claimed: skill icons fit (%.2f)".format(claimedScore)
            else -> "reading the skill icons… (" + (l0?.let { "${it.key} %.2f".format(it.value) } ?: w0?.let { "${it.first} %.2f".format(it.second) } ?: "nothing learned yet") + ")"
        }
    }

    // ---------------- LIVE matches you watch (v36.70) ----------------
    // The watched hero's skill icons in the panel at the bottom (s1, s2, ultimate) are read the same
    // way: compared with the looks learned while watching (kept apart from your own buttons - the
    // panel draws them smaller and square) and with the wiki pictures. The result is ABSOLUTE like in
    // your own matches: it replaces the hero Gemma read (another hero than Gemma's needs 6 agreeing
    // readings, the same one 3). Gemma's hero teaches the looks when the icons do not contradict it.
    @Volatile var liveSkillHero: String? = null; private set
    @Volatile var liveStatus = "not seen yet (watching a live match)"; private set
    private var liveCand: String? = null
    private var liveN = 0
    private const val LIVE_R = 0.009f

    fun observeLive(px: IntArray, w: Int, h: Int) {
        val kit = ScreenReader.liveKit
        val icons = ScreenReader.liveIcons(kit)
        val slotsL = icons.keys.filter { it != "spell" }
        val prints = slotsL.map { s -> icons.getValue(s).let { buttonPrint(px, w, h, it.first, it.second, LIVE_R) } }
        if (prints.any { it == null }) return
        val look = prints.map { it!! }
        // an icon cooling down is dark with a number on it (it only matched itself ~0.5 on your
        // screenshots): only the READY icons are compared (2 at least), and only a panel with every icon
        // ready is learned. Ready icons measured 94-147 bright, cooling ones 57-82.
        val ready = slotsL.map { s -> icons.getValue(s).let { meanLum(px, w, h, it.first, it.second) >= 90f } }
        if (ready.count { it } < 2) return
        fun scoreReady(b: List<FloatArray>) = if (b.size != look.size) -1f else look.indices.filter { ready[it] }.map { corr(look[it], b[it]) }.average().toFloat()
        val m = memory()
        val learned = m.filterKeys { it.startsWith("live|") }.mapValues { (_, looks) -> looks.maxOf { scoreReady(it) } }
            .filterValues { it > -1f }.entries.sortedByDescending { it.value }.map { it.key.removePrefix("live|") to it.value }
        // wiki pictures of the heroes with as many skills as the panel shows (2, 3 or 4)
        val wiki = HeroDb.allNames.mapNotNull { hero ->
            val ic = WikiSkills.icons(hero)
            val heroSlots = listOf("s1", "s2", "s3", "ult").filter { it in ic }       // (never the passive)
            if (heroSlots != slotsL) return@mapNotNull null
            val ps = slotsL.map { wikiPrint(ic.getValue(it)) }
            if (ps.any { it == null }) return@mapNotNull null
            hero to look.indices.filter { ready[it] }.map { corr(look[it], ps[it]!!) }.average().toFloat()
        }.sortedByDescending { it.second }
        val l0 = learned.getOrNull(0); val l1 = learned.getOrNull(1)
        val w0 = wiki.getOrNull(0); val w1 = wiki.getOrNull(1)
        val byLearned = l0?.takeIf { it.second >= SAME && it.second - (l1?.second ?: 0f) >= MARGIN }?.first
        val byWiki = w0?.takeIf { it.second >= WIKI && it.second - (w1?.second ?: 0f) >= MARGIN }?.first
        val found = byLearned ?: byWiki
        if (found != null && found == liveCand) liveN++ else { liveCand = found; liveN = if (found != null) 1 else 0 }
        val gemma = HeroDb.match(Cfg.liveHero)
        if (found != null && liveN >= (if (gemma != null && found != gemma) 6 else 3)) {
            if (liveSkillHero != found) Diag.info("Live match: the watched hero's skill icons show $found" + if (gemma != null && gemma != found) " (not $gemma)" else "")
            liveSkillHero = found
            if (!found.equals(Cfg.liveHero, true)) {
                Cfg.liveHero = found
                Cfg.liveLaneName = com.example.mlbbresearch.game.LaneLogic.effective(found, null).first.name.lowercase()
            }
        }
        val teacher = liveSkillHero ?: gemma
        if (teacher != null && ready.all { it } && (byWiki == null || byWiki == teacher) && (byLearned == null || byLearned == teacher)) learn("live|$teacher", look)
        liveStatus = when {
            liveSkillHero != null -> "✓ ${liveSkillHero} from its skill icons"
            else -> "reading the skill icons… (" + (l0?.let { "${it.first} %.2f".format(it.second) } ?: w0?.let { "${it.first} %.2f".format(it.second) } ?: "nothing learned yet") + ")"
        }
    }
    fun resetLive() { liveSkillHero = null; liveCand = null; liveN = 0 }

    private var learnedAt = HashMap<String, Long>()
    private var learnLogged = HashSet<String>()
    @Synchronized
    private fun learn(hero: String, look: List<FloatArray>) {
        // (v36.94: at most every 15 s per hero, logged once - your log had it every second)
        val now = System.currentTimeMillis()
        if (now - (learnedAt[hero] ?: 0L) < 15_000) return
        learnedAt[hero] = now
        val m = memory()
        // another hero with this very look was a wrong label
        val wrong = m.filter { (h, looks) -> h != hero && h.startsWith("live|") == hero.startsWith("live|") && looks.any { lookScore(look, it) >= 0.9f } }.keys
        wrong.forEach { m.remove(it) }
        val looks = m.getOrPut(hero) { ArrayList() }
        if (looks.any { lookScore(look, it) >= 0.85f } && wrong.isEmpty()) return     // already known
        if (looks.size >= 4) looks.removeAt(0)                                    // skins: up to 4 looks
        looks.add(look)
        save()
        if (learnLogged.add(hero)) Diag.info("Learning ${hero.removePrefix("live|")}'s skill icons" + (if (hero.startsWith("live|")) " (live panel)" else "") + " (every 15 s)")
    }

    fun reset() { skillHero = null; cand = null; candN = 0; verified = false; mismatch = null; learnLogged.clear() }
}
