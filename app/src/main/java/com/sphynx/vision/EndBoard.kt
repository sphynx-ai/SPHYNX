package com.sphynx.vision

import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.game.GameStateTracker
import com.sphynx.game.HeroDb

// THE END-OF-MATCH SCOREBOARD (v36.87, your screenshots): it shows each player's NAME and the hero's
// PORTRAIT (no hero name). Your row is the highlighted one (measured: 85-112 bright against 41-51 for
// the others) - and when you entered your player name, Gemma reads the names and your row must carry
// it. The portrait of your row is compared with the heroes' portrait pictures (the Library's icons);
// the hero found VERIFIES what was detected during the match and LOCKS it: the looks learned this match
// (portrait / model, skill buttons, skill count) are kept for that hero, wrong labels replaced.
object EndBoard {

    // rows (centre y) and portrait centres (x), fractions of the screen - your 2712 x 1220 screenshot
    val ROWS = floatArrayOf(0.27f, 0.398f, 0.526f, 0.653f, 0.781f)
    const val LEFT_X = 0.165f
    const val RIGHT_X = 0.8325f

    @Volatile var seenAt = 0L; private set
    @Volatile var highlightRow = -1; private set
    private var prints = arrayOfNulls<FloatArray>(5)
    private var endSpells = arrayOfNulls<String>(5)
    @Volatile var nameRow = -1                         // the row with your name (Gemma), -1 = not read
    @Volatile var nameChecked = false
    @Volatile var lockedHero: String? = null; private set
    @Volatile var lockedSpell: String? = null; private set
    @Volatile var status = "-"; private set
    private var lockedFor = 0L

    // every few frames while the end scoreboard shows
    fun observe(px: IntArray, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        if (now - seenAt > 60_000) { nameRow = -1; nameChecked = false; lockedHero = null; lockedSpell = null; status = "reading the end scoreboard…" }
        seenAt = now
        // the highlighted row: clearly brighter than the other four
        val lum = ROWS.map { fy -> band(px, w, h, 0.40f, 0.43f, fy - 0.05f, fy - 0.03f) }
        val best = lum.indices.maxByOrNull { lum[it] } ?: return
        val others = lum.filterIndexed { i, _ -> i != best }.sorted()
        highlightRow = if (lum[best] > others[others.size / 2] + 20f) best else -1
        for (i in ROWS.indices) {
            prints[i] = HeroVerifier.buttonPrint(px, w, h, LEFT_X, ROWS[i], 0.012f)
            if (i == highlightRow || i == nameRow) {
                PortraitMatch.matchEndBoardSpell(px, w, h, i)?.let { endSpells[i] = it.first }
            }
        }
        tryLock()
    }

    private fun band(px: IntArray, w: Int, h: Int, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        var s = 0L; var n = 0
        var y = (y0 * h).toInt()
        while (y < (y1 * h).toInt()) {
            var x = (x0 * w).toInt()
            while (x < (x1 * w).toInt()) { val p = px[y * w + x]; s += ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255); n += 3; x += 3 }
            y += 2
        }
        return if (n == 0) 0f else s.toFloat() / n
    }

    // which hero is in a portrait: the heroes whose portrait picture is on the phone; the heroes
    // detected this match first (their pictures are fetched if missing)
    fun heroOf(p: FloatArray): Pair<String, Float>? {
        val ctx = HeroVerifier.appContext()
        val detected = listOfNotNull(GameStateTracker.ownHero(), GameStateTracker.loadingHero, HeroVerifier.skillHero,
            GameStateTracker.scoreboardHero, GameStateTracker.modelHero, GameStateTracker.portraitHero).distinct()
        if (ctx != null) for (d in detected) com.sphynx.core.Probes.heroIconFile(d)?.let { f ->
            if (!java.io.File(java.io.File(ctx.filesDir, "icons"), f).exists()) com.sphynx.core.Probes.loadIcon(ctx, f)
        }
        PortraitMatch.prefetch()
        val scores = HeroDb.allNames.mapNotNull { h ->
            val f = com.sphynx.core.Probes.heroIconFile(h) ?: return@mapNotNull null
            HeroVerifier.wikiPrint(f)?.let { h to HeroVerifier.corr(p, it) }
        }.sortedByDescending { it.second }
        val b = scores.firstOrNull() ?: return null
        val second = scores.getOrNull(1)?.second ?: -1f
        // a detected hero is CONFIRMED with a fair match; any other hero needs a clear winner
        val conf = scores.firstOrNull { it.first in detected && it.second >= 0.5f }
        return when {
            conf != null && conf.second >= b.second - 0.05f -> conf
            b.second >= 0.55f && b.second - second >= 0.1f -> b
            else -> null
        }
    }

    // the hero portrait on the Victory / Defeat and MVP screens (v36.92): a backup for the scoreboard's
    @Volatile var splashHero: String? = null; private set
    private var splashAt = 0L
    fun observePortrait(px: IntArray, w: Int, h: Int, fx: Float, fy: Float, r: Float, where: String) {
        val now = System.currentTimeMillis()
        if (now - splashAt > 120_000) splashHero = null
        splashAt = now
        if (splashHero != null || lockedHero != null) return
        val p = HeroVerifier.buttonPrint(px, w, h, fx, fy, r) ?: return
        heroOf(p)?.let { splashHero = it.first; status = "$where portrait: ${it.first} (%.2f) - waiting for the scoreboard".format(it.second) }
    }

    private fun tryLock() {
        if (lockedHero != null) return
        // the hero you ENTERED (Enter button) is locked as it is - then the entry is cleared, ready for
        // the next match or another hero (v36.92, your rule)
        val entered = HeroDb.match(Cfg.manualHero)
        if (entered != null) {
            lock(entered, highlightRow, false, "entered by you")
            Cfg.manualHero = ""
            Diag.info("Entered hero $entered locked for this match; the entry is cleared for the next match")
            return
        }
        val name = com.sphynx.game.PlayerName.get()
        // your row: by your name (Gemma), else the highlighted one
        val row = if (nameRow >= 0) nameRow else if (name.isBlank() || !com.sphynx.ai.Roles.images(com.sphynx.ai.Roles.STRATEGIC) || nameChecked) highlightRow else -1
        if (row < 0) { status = if (highlightRow < 0) "your row not found" else "checking your name…"; return }
        val p = prints[row] ?: return
        val m = heroOf(p) ?: splashHero?.let { it to 1f }
            ?: run { status = "row ${row + 1}: hero portrait not recognised (portrait pictures: open the Library once)"; return }
        lock(m.first, row, nameRow >= 0, if (heroOf(p) == null) "Victory / MVP portrait" else null)
    }

    private fun lock(hero: String, row: Int, byName: Boolean, how: String? = null) {
        val detected = GameStateTracker.ownHero()
        lockedHero = hero
        GameStateTracker.lockedHero = hero
        // Lock the battle spell used in this match too (priority: entered > in-game button > scoreboard > loading > end scoreboard)
        val rowSpell = if (row in 0..4) endSpells[row] else null
        val sp = GameStateTracker.spell() ?: rowSpell
        if (sp != null) {
            lockedSpell = sp
            GameStateTracker.lockedSpell = sp
        }
        val spellShown = GameStateTracker.spellDisplay() ?: lockedSpell
        status = "✓ $hero" + (spellShown?.let { " · $it" } ?: "") + " LOCKED (" +
            (how ?: ("row ${row + 1}" + (if (byName) ", your name \"${com.sphynx.game.PlayerName.get()}\"" else ", highlighted row"))) + ")" +
            (if (detected != null && detected != hero) " - corrected from $detected" else if (detected == hero) " - matches the detection" else "")
        Diag.info("End of match: $status")
    }

    fun recent() = System.currentTimeMillis() - seenAt < 8000
    fun needsName() = recent() && lockedHero == null && !nameChecked && com.sphynx.game.PlayerName.get().isNotBlank()

    // Gemma's answer: the names of my team's rows, top to bottom
    fun gotNames(names: List<String>) {
        nameChecked = true
        val name = com.sphynx.game.PlayerName.get()
        nameRow = names.indexOfFirst { com.sphynx.game.PlayerName.same(it, name) }
        if (nameRow >= 0) {
            GameStateTracker.verifiedPlayerName = names[nameRow].ifBlank { name }
        } else {
            Diag.info("End scoreboard: your name \"$name\" not among ${names.joinToString()} - the highlighted row is used")
        }
        tryLock()
    }

    fun namePrompt(): String = "Mobile Legends: Bang Bang screenshot of the END-OF-MATCH scoreboard (Victory / Defeat at the top). " +
        "MY team is on the LEFT: 5 rows, each with a portrait and the player's name. Copy the 5 player names on the LEFT exactly as " +
        "written, top to bottom. Reply with ONLY one JSON object, no other words: {\"names\":[\"\",\"\",\"\",\"\",\"\"]}"

    fun resetForMatch() { lockedFor = 0L }
}
