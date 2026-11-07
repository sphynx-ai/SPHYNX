package com.example.mlbbresearch.vision

import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.core.Layout
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.HeroKit
import com.example.mlbbresearch.game.ItemDb
import com.example.mlbbresearch.game.SpellDb

// HERO PORTRAITS AND BATTLE SPELL ICONS AGAINST THE WIKI'S PICTURES (v36.88 / v37.00, your request):
// 1. Hero portraits ("HeroNNN-icon.png") are compared with a portrait on screen - for heroes that
//    have never been learned on your phone:
//      * the IN-GAME SCOREBOARD: your highlighted row (and the enemies' portraits for their line-up),
//      * the END-OF-MATCH SCOREBOARD (EndBoard),
//      * the MINIMAP portrait - only as a last resort (it is about 11 pixels wide on your screen).
// 2. Battle spell pictures from the Library ("<Spell_Name>.png": the 12 battle spells + Ice, Flame,
//    and Bloody Retribution) are compared with:
//      * the IN-GAME SPELL BUTTON (all 12 spells + Ice / Flame / Bloody Retribution; Blessed Retribution
//        can ONLY be detected here, and only once Retribution is already detected),
//      * the IN-GAME SCOREBOARD spell icons under each portrait (yours and the enemies' spells, vs the 12 base spells),
//      * the LOADING SCREEN spell icon on your card (found by your green player name, vs the 12 base spells).
// A match counts only when one icon is clearly closer than all others.
object PortraitMatch {

    private var prefetched = false
    private var spellsPrefetched = false

    // the 12 battle spells + the 3 Blessed Retributions shown on the Library's Spells page
    val ALL_SPELLS: List<String> get() = SpellDb.names + ItemDb.retributions

    // the portrait pictures of every hero, downloaded once (the Library keeps them)
    fun prefetch() {
        val ctx = HeroVerifier.appContext() ?: return
        if (!prefetched) {
            prefetched = true
            val dir = java.io.File(ctx.filesDir, "icons")
            var n = 0
            for (h in HeroDb.allNames) {
                val f = com.example.mlbbresearch.app.IconCache.heroFile(h) ?: continue
                if (!java.io.File(dir, f).exists()) { com.example.mlbbresearch.app.IconCache.load(ctx, f) { }; n++ }
            }
            if (n > 0) Diag.info("Portrait pictures: fetching $n heroes' pictures for portrait matching")
        }
        prefetchSpells()
    }

    // the 12 battle spell pictures + 3 Blessed Retribution pictures ("<Name>.png") from the Library
    fun prefetchSpells() {
        if (spellsPrefetched) return
        val ctx = HeroVerifier.appContext() ?: return
        spellsPrefetched = true
        val dir = java.io.File(ctx.filesDir, "icons")
        var n = 0
        for (sp in ALL_SPELLS) {
            val f = com.example.mlbbresearch.app.IconCache.itemFile(sp)
            if (!java.io.File(dir, f).exists()) { com.example.mlbbresearch.app.IconCache.load(ctx, f) { }; n++ }
        }
        if (n > 0) Diag.info("Spell pictures: fetching $n battle spell icons from the wiki for spell matching")
    }

    // all heroes (with a picture on the phone) by likeness, best first
    fun rank(print: FloatArray, allowed: (String) -> Boolean = { true }): List<Pair<String, Float>> {
        prefetch()
        val ctx = HeroVerifier.appContext() ?: return emptyList()
        val dir = java.io.File(ctx.filesDir, "icons")
        if (!dir.exists()) return emptyList()
        return HeroDb.allNames.filter(allowed).mapNotNull { h ->
            val f = com.example.mlbbresearch.app.IconCache.heroFile(h) ?: return@mapNotNull null
            HeroVerifier.wikiPrint(f)?.let { h to HeroVerifier.corr(print, it) }
        }.sortedByDescending { it.second }
    }

    // a clear winner only (at least `min`, ahead of the second by `margin`)
    fun best(print: FloatArray, min: Float, margin: Float, allowed: (String) -> Boolean = { true }): Pair<String, Float>? {
        val r = rank(print, allowed)
        val b = r.firstOrNull() ?: return null
        val second = r.getOrNull(1)?.second ?: -1f
        return if (b.second >= min && b.second - second >= margin) b else null
    }

    // ---------------- battle spell icons against the Library's wiki pictures ----------------
    // includeBlessed = true ONLY on the in-game spell button when Retribution is already detected;
    // false on the scoreboard and loading screen.
    fun rankSpells(print: FloatArray, includeBlessed: Boolean = false): List<Pair<String, Float>> {
        prefetchSpells()
        val ctx = HeroVerifier.appContext() ?: return emptyList()
        val dir = java.io.File(ctx.filesDir, "icons")
        if (!dir.exists()) return emptyList()
        val pool = if (includeBlessed) ALL_SPELLS else SpellDb.names
        return pool.mapNotNull { sp ->
            val f = com.example.mlbbresearch.app.IconCache.itemFile(sp)
            HeroVerifier.wikiPrint(f)?.let { sp to HeroVerifier.corr(print, it) }
        }.sortedByDescending { it.second }
    }

    // Upgraded Retribution priority order (when Retribution is detected): Bloody, Ice, Flame > Normal Retribution
    val UPGRADED_RETRIBUTIONS = listOf("Bloody Retribution", "Ice Retribution", "Flame Retribution")

    fun bestSpell(print: FloatArray, min: Float = 0.52f, margin: Float = 0.09f, includeBlessed: Boolean = false, expected: String? = null): Pair<String, Float>? =
        pickSpell(rankSpells(print, includeBlessed), min, margin, includeBlessed, expected)

    // the decision alone, on the likeness ranking (best first) - no pictures needed, so it is unit tested
    fun pickSpell(r: List<Pair<String, Float>>, min: Float = 0.52f, margin: Float = 0.09f, includeBlessed: Boolean = false, expected: String? = null): Pair<String, Float>? {
        val b = r.firstOrNull() ?: return null
        val second = r.getOrNull(1)?.second ?: -1f
        // ABSOLUTE PRIORITY FOR RETRIBUTION: When includeBlessed is true (Retribution is detected and not the other spells),
        // Upgraded version of Retribution (Bloody, Ice, Flame) > Normal Retribution
        if (includeBlessed) {
            val bestUpgraded = r.firstOrNull { it.first in UPGRADED_RETRIBUTIONS }
            val bestOtherSpell = r.firstOrNull { !it.first.endsWith("Retribution") }
            if (bestUpgraded != null && bestUpgraded.second >= min - 0.03f &&
                (bestOtherSpell == null || bestUpgraded.second - bestOtherSpell.second >= margin - 0.02f) &&
                (b.first == "Retribution" && bestUpgraded.second >= b.second - 0.04f || b.first in UPGRADED_RETRIBUTIONS)) {
                return bestUpgraded
            }
        }
        // Only confirm `expected` if it already meets the verification threshold `min`
        if (expected != null) {
            val expMatch = r.firstOrNull { it.first.equals(expected, ignoreCase = true) && it.second >= min }
            if (expMatch != null && expMatch.second >= b.second - 0.03f) return expMatch
        }
        val nextOther = r.firstOrNull { !it.first.endsWith("Retribution") || !b.first.endsWith("Retribution") && it.first != b.first }?.second ?: second
        val effMargin = if (b.first.endsWith("Retribution") && (r.getOrNull(1)?.first?.endsWith("Retribution") == true))
            minOf(b.second - second + 0.04f, b.second - nextOther) else b.second - second
        return if (b.second >= min && effMargin >= margin) b else null
    }

    // blessed kind ("Ice", "Flame", "Bloody") when `spellName` is one of ItemDb.retributions, else null
    fun blessedKind(spellName: String?): String? {
        if (spellName == null) return null
        return if (spellName in ItemDb.retributions) spellName.removeSuffix(" Retribution").trim() else null
    }

    // ---------------- 1) in-game battle spell button (highest detected priority) ----------------
    // Upgraded version of Retribution (Ice, Flame, Bloody) can ONLY be detected here, and ONLY when
    // Retribution is already detected.
    @Volatile var spellButtonStatus = "-"; private set
    private var spellCand: String? = null
    private var spellCandN = 0

    fun observeSpellButton(px: IntArray, w: Int, h: Int) {
        val (sx, sy) = Layout.point("spell")
        // Only compare when the spell button is ready & bright (cooling buttons have white countdown digits
        // and brightness 57-82, which caused false matches like Flameshot 0.49 / Inspire 0.47 in your log)
        if (CooldownTracker.screenKnown("spell") && !CooldownTracker.lookReady("spell")) return
        if (HeroVerifier.meanLum(px, w, h, sx, sy, 0.010f) < 88f) return
        val p = HeroVerifier.buttonPrint(px, w, h, sx, sy, 0.011f) ?: return
        val retriAlready = GameStateTracker.isRetributionDetected()
        val expected = GameStateTracker.scoreboardSpell ?: GameStateTracker.loadingSpell ?: GameStateTracker.iconSpell ?: GameStateTracker.gemmaLoadingSpell
        val m = bestSpell(p, min = 0.55f, margin = 0.10f, includeBlessed = retriAlready, expected = expected)
        if (m == null) {
            spellCand = null; spellCandN = 0
            return
        }
        val found = m.first
        if (found == spellCand) spellCandN++ else { spellCand = found; spellCandN = 1 }
        spellButtonStatus = "$found (%.2f)".format(m.second)
        // Require 3 agreeing readings in a row (or 4 when changing away from an already-verified buttonSpell)
        val needed = if (GameStateTracker.buttonSpell != null && SpellDb.match(found) != GameStateTracker.buttonSpell) 4 else 3
        if (spellCandN >= needed) {
            GameStateTracker.onButtonSpellVerified(found, m.second)
        }
    }

    // ---------------- 2) the in-game scoreboard (portraits + spell icons under each portrait) ----------------
    // rows (centre y) and portrait centres (x) on your 2712 x 1220 screenshot; your row = the highlighted
    // one (92 bright against about 60). Enemy rows: the 12 base spells; your row also the Blessed ones once
    // Retribution is known (they count as Retribution - the Blessed kind is taken only from the spell button).
    val SB_ROWS = floatArrayOf(0.328f, 0.456f, 0.584f, 0.712f, 0.84f)
    const val SB_LEFT = 0.1675f
    const val SB_RIGHT = 0.8315f
    private val SB_SPELL_DY = floatArrayOf(0.042f, 0.048f, 0.054f)
    private val SB_SPELL_DX = floatArrayOf(-0.006f, 0f, 0.006f)

    @Volatile var scoreboardStatus = "-"; private set
    private var sbAt = 0L

    fun observeScoreboard(px: IntArray, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        if (now - sbAt < 3000) return
        sbAt = now
        val lum = SB_ROWS.map { fy -> band(px, w, h, 0.44f, 0.49f, fy - 0.06f, fy - 0.045f) }
        val best = lum.indices.maxByOrNull { lum[it] } ?: return
        val others = lum.filterIndexed { i, _ -> i != best }.sorted()
        val row = if (lum[best] > others[others.size / 2] + 20f) best else -1
        // my hero & my spell: my highlighted row (a hero with as many skills as the buttons in the match)
        if (row >= 0) {
            var rowHero: String? = GameStateTracker.scoreboardHero
            HeroVerifier.buttonPrint(px, w, h, SB_LEFT, SB_ROWS[row], 0.012f)?.let { p ->
                val m = best(p, 0.5f, 0.1f) { HeroKit.fitsScreen(it) }
                if (m != null) {
                    if (GameStateTracker.scoreboardHero == null) {
                        GameStateTracker.scoreboardHero = m.first
                        Diag.info("Scoreboard: your highlighted row's portrait = ${m.first} (%.2f)".format(m.second))
                    }
                    rowHero = m.first
                }
            }
            // once Retribution is known, your scoreboard icon may be the Blessed one bought in game: it is
            // compared with those pictures too (and counts as Retribution here; the kind comes from the button)
            val mySp = matchScoreboardSpell(px, w, h, SB_LEFT, SB_ROWS[row], expected = GameStateTracker.gemmaSpell ?: GameStateTracker.loadingSpell ?: GameStateTracker.gemmaLoadingSpell,
                includeBlessed = GameStateTracker.isRetributionDetected())
            if (mySp != null) {
                GameStateTracker.onScoreboardSpellVerified(mySp.first, mySp.second)
            }
            val spShown = mySp?.first ?: GameStateTracker.scoreboardSpell
            scoreboardStatus = "row ${row + 1}: " + (rowHero ?: "portrait not recognised yet") +
                (spShown?.let { " · $it" } ?: "")
        }
        // the enemies' heroes and their battle spells under each portrait (12 base spells)
        val enemyHeroes = ArrayList<String>(GameStateTracker.enemyTeam)
        val enemySpells = HashMap<String, String>(GameStateTracker.enemySpells)
        var enemySpellsChanged = false
        for (i in SB_ROWS.indices) {
            val fy = SB_ROWS[i]
            val heroAtRow = HeroVerifier.buttonPrint(px, w, h, SB_RIGHT, fy, 0.012f)
                ?.let { best(it, 0.55f, 0.1f)?.first }
                ?: enemyHeroes.getOrNull(i)
            if (heroAtRow != null && heroAtRow !in enemyHeroes && enemyHeroes.size < 5) {
                enemyHeroes.add(heroAtRow)
            }
            if (heroAtRow != null && heroAtRow !in enemySpells) {
                val sp = matchScoreboardSpell(px, w, h, SB_RIGHT, fy, expected = null)
                if (sp != null) {
                    val canon = SpellDb.match(sp.first) ?: sp.first
                    enemySpells[heroAtRow] = canon
                    enemySpellsChanged = true
                }
            }
        }
        if (enemyHeroes.size > GameStateTracker.enemyTeam.size) {
            GameStateTracker.enemyTeam = enemyHeroes.distinct().take(5)
        }
        if (enemySpellsChanged) {
            GameStateTracker.enemySpells = enemySpells
            Diag.info("Scoreboard: enemy spells from icons = " + enemySpells.entries.joinToString { "${it.key}: ${it.value}" })
        }
    }

    private fun matchScoreboardSpell(px: IntArray, w: Int, h: Int, cx: Float, rowY: Float, expected: String?, includeBlessed: Boolean = false): Pair<String, Float>? {
        var bestMatch: Pair<String, Float>? = null
        for (dy in SB_SPELL_DY) {
            for (dx in SB_SPELL_DX) {
                val p = HeroVerifier.buttonPrint(px, w, h, cx + dx, rowY + dy, 0.0075f) ?: continue
                val m = bestSpell(p, min = 0.50f, margin = 0.09f, includeBlessed = includeBlessed, expected = expected) ?: continue
                if (bestMatch == null || m.second > bestMatch.second) bestMatch = m
            }
        }
        return bestMatch
    }

    // ---------------- 3) loading screen spell icon (on your green-name card) ----------------
    private val LOAD_TOP_X = floatArrayOf(0.11f, 0.235f, 0.355f, 0.48f, 0.605f)
    private val LOAD_BOT_X = floatArrayOf(0.40f, 0.52f, 0.645f, 0.77f, 0.89f)
    private val LOAD_DX = floatArrayOf(-0.038f, -0.033f, -0.028f)
    private val LOAD_TOP_Y = floatArrayOf(0.365f, 0.398f, 0.412f)
    private val LOAD_BOT_Y = floatArrayOf(0.895f, 0.922f, 0.936f)

    @Volatile var loadingSpellStatus = "-"; private set
    private var loadSpellCand: String? = null
    private var loadSpellCandN = 0
    private var loadAt = 0L

    fun observeLoadingSpell(px: IntArray, w: Int, h: Int, green: Pair<Int, Int>) {
        val now = System.currentTimeMillis()
        if (now - loadAt < 1200) return
        loadAt = now
        val (row, card) = green
        val xs = if (row == 0) LOAD_TOP_X else LOAD_BOT_X
        val ys = if (row == 0) LOAD_TOP_Y else LOAD_BOT_Y
        val cx = xs.getOrNull(card) ?: return
        val m = matchCardSpell(px, w, h, cx, ys, expected = GameStateTracker.gemmaLoadingSpell) ?: return
        val found = m.first
        if (found == loadSpellCand) loadSpellCandN++ else { loadSpellCand = found; loadSpellCandN = 1 }
        loadingSpellStatus = "$found (%.2f)".format(m.second)
        if (loadSpellCandN >= 2) {
            GameStateTracker.onLoadingSpellVerified(found, m.second)
        }
    }

    private fun matchCardSpell(px: IntArray, w: Int, h: Int, cx: Float, ys: FloatArray, expected: String?): Pair<String, Float>? {
        var bestMatch: Pair<String, Float>? = null
        for (fy in ys) {
            for (dx in LOAD_DX) {
                val p = HeroVerifier.buttonPrint(px, w, h, cx + dx, fy, 0.0085f) ?: continue
                val m = bestSpell(p, min = 0.50f, margin = 0.09f, includeBlessed = false, expected = expected) ?: continue
                if (bestMatch == null || m.second > bestMatch.second) bestMatch = m
            }
        }
        return bestMatch
    }

    // ---------------- 4) end-of-match scoreboard spell icon (for EndBoard spell locking) ----------------
    fun matchEndBoardSpell(px: IntArray, w: Int, h: Int, row: Int): Pair<String, Float>? {
        val rowY = EndBoard.ROWS.getOrNull(row) ?: return null
        val expected = GameStateTracker.spell()
        var bestMatch: Pair<String, Float>? = null
        for (dy in floatArrayOf(0.038f, 0.045f, 0.052f)) {
            for (dx in floatArrayOf(-0.008f, 0f, 0.008f)) {
                val p = HeroVerifier.buttonPrint(px, w, h, EndBoard.LEFT_X + dx, rowY + dy, 0.0075f) ?: continue
                val m = bestSpell(p, min = 0.50f, margin = 0.08f, includeBlessed = false, expected = expected) ?: continue
                if (bestMatch == null || m.second > bestMatch.second) bestMatch = m
            }
        }
        return bestMatch
    }

    // ---------------- the minimap (last resort) ----------------
    @Volatile var minimapStatus = "-"; private set
    private var mmAt = 0L
    // icon = centre x, y and radius as fractions of the screen WIDTH (MinimapAnalyzer.selfIcon)
    fun observeMinimap(px: IntArray, w: Int, h: Int, icon: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - mmAt < 5000) return
        mmAt = now
        val p = HeroVerifier.buttonPrint(px, w, h, icon[0], icon[1] * w / h, icon[2] * 0.6f) ?: return
        val m = best(p, 0.6f, 0.12f) { HeroKit.fitsScreen(it) }
        minimapStatus = m?.let { "${it.first} (%.2f)".format(it.second) } ?: "not clear"
        if (m != null && GameStateTracker.minimapWikiHero != m.first) {
            GameStateTracker.minimapWikiHero = m.first
            Diag.info("Minimap portrait vs the wiki pictures: ${m.first} (%.2f)".format(m.second))
        }
    }

    fun reset() {
        spellButtonStatus = "-"
        loadingSpellStatus = "-"
        scoreboardStatus = "-"
        minimapStatus = "-"
        spellCand = null; spellCandN = 0
        loadSpellCand = null; loadSpellCandN = 0
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
}
