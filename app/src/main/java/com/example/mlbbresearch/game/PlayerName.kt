package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import org.json.JSONObject

// YOUR IN-GAME NAME (v36.84, Player page "My player name"): on the LOADING screen and the SCOREBOARD
// Gemma writes down every card's player name with its hero; the app then finds YOUR card by your name
// (not only by its place - first card / top row). Small reading slips are allowed ("Grifflth" =
// "Griffith"), since names are read off a picture.
object PlayerName {

    fun get(): String = Cfg.raw("player_name")?.trim().orEmpty()
    fun set(v: String) = Cfg.setRaw("player_name", v.trim().ifBlank { null })

    class Card(val player: String, val hero: String?, val spell: String?, val level: Int = -1)
    class Lineup(val mine: List<Card>, val theirs: List<Card>, val me: Card?, val meByName: Boolean)

    // letters and digits only, lower case (decorations like ">ᴗ<" and spaces dropped)
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // the same name, allowing small reading slips: one wrong letter per 5 letters (at least one)
    fun same(read: String, mine: String): Boolean {
        val a = norm(read); val b = norm(mine)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (b.length >= 4 && (a.contains(b) || (a.length >= 4 && b.contains(a)))) return true
        return lev(a, b) <= maxOf(1, b.length / 5)
    }
    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    // YOUR CARD ON THE LOADING SCREEN BY ITS GREEN NAME (v36.92, your observation): your player name is
    // written in mint green, everyone else's in white / grey. Measured on your loading screenshot: 173
    // green pixels on the top row, all on its first card (Karina, "100%KO"); none on the bottom row.
    // Works without a typed name and without Gemma. Returns (row: 0 top / 1 bottom, card 0..4).
    private val TOP_X = floatArrayOf(0.11f, 0.235f, 0.355f, 0.48f, 0.605f)
    private val BOTTOM_X = floatArrayOf(0.40f, 0.52f, 0.645f, 0.77f, 0.89f)
    fun greenCard(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        fun band(y0: Float, y1: Float, x0: Float, x1: Float): Pair<Int, Float> {
            var n = 0; var sx = 0L
            var y = (y0 * h).toInt()
            while (y < (y1 * h).toInt()) {
                var x = (x0 * w).toInt()
                while (x < (x1 * w).toInt()) {
                    val p = px[y * w + x]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                    if (g >= 140 && g - r >= 50 && g - b >= 20 && b >= 90) { n++; sx += x }
                    x += 2
                }
                y += 2
            }
            return n to (if (n > 0) sx.toFloat() / n / w else 0f)
        }
        val top = band(0.355f, 0.40f, 0.05f, 0.68f)
        val bottom = band(0.88f, 0.925f, 0.32f, 0.96f)
        val (row, b) = if (top.first >= bottom.first) 0 to top else 1 to bottom
        if (b.first < 40) return null
        val xs = if (row == 0) TOP_X else BOTTOM_X
        val idx = xs.indices.minByOrNull { kotlin.math.abs(xs[it] - b.second) } ?: return null
        return row to idx
    }

    // the question for Gemma (loading screen or scoreboard)
    // v36.85 (your word): on the LOADING screen the cards are in random places and the two teams' rows
    // can swap - so nothing is taken from positions: Gemma reports the TOP and BOTTOM rows as they are,
    // and the row holding your name is your team, the card with your name is you.
    fun lineupPrompt(loading: Boolean, name: String = get()): String {
        val me = (if (name.isNotBlank()) " My player name is \"$name\"." else "") +
            (if (loading) " My player name is the one written in GREEN (all others are white)." else "")
        val spells = " Battle spells: Execute, Retribution, Inspire, Sprint, Revitalize, Aegis, Petrify, Purify, Flameshot, " +
            "Flicker, Arrival, Vengeance. Copy every player's name exactly as written."
        return if (loading)
            "Mobile Legends: Bang Bang screenshot. This is the LOADING screen: 10 hero cards in two rows of 5. On each card the " +
                "hero's name is written above the player's name (the skin name, if any, is above the hero name - do not use it); " +
                "the battle spell icon is at the bottom left of the card.$me$spells Reply with ONLY one JSON object, no other " +
                "words: {\"top_row\":[{\"player\":\"\",\"hero\":\"\",\"spell\":\"\"}],\"bottom_row\":[{\"player\":\"\",\"hero\":\"\",\"spell\":\"\"}]} " +
                "with 5 entries each, left to right."
        else
            "Mobile Legends: Bang Bang screenshot. This should be the IN-GAME scoreboard (opened during the match - not the " +
                "end-of-match result screen, which looks different: if it is the end-of-match screen, reply {} only). On the in-game " +
                "SCOREBOARD MY team is on the LEFT, the ENEMY team on the " +
                "RIGHT. Next to each portrait the player's name is written above the hero's name; the small icon under the " +
                "portrait is the battle spell; the small number at the bottom left of each portrait is the hero's LEVEL.$me$spells " +
                "Reply with ONLY one JSON object, no other words: " +
                "{\"my_team\":[{\"player\":\"\",\"hero\":\"\",\"spell\":\"\",\"level\":0}],\"enemy_team\":[{\"player\":\"\",\"hero\":\"\",\"spell\":\"\",\"level\":0}]} " +
                "with 5 entries each, top to bottom."
    }

    // Gemma's answer -> my team, the enemies and me - found ONLY by my name (null when it cannot be told:
    // no name set, or the name is not on the screen)
    fun parseLineup(reply: String, loading: Boolean, name: String = get(), green: Pair<Int, Int>? = null): Lineup? {
        val o = try { JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)) } catch (e: Exception) { return null }
        fun side(k: String): List<Card> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                Card(e.optString("player"), HeroDb.match(e.optString("hero")), SpellDb.match(e.optString("spell")), e.optInt("level", -1))
            }
        }
        val a = side(if (loading) "top_row" else "my_team"); val b = side(if (loading) "bottom_row" else "enemy_team")
        if (a.none { it.hero != null } && b.none { it.hero != null }) return null
        if (name.isNotBlank()) {
            a.firstOrNull { same(it.player, name) }?.let { return Lineup(a, b, it, true) }
            b.firstOrNull { same(it.player, name) }?.let { return Lineup(b, a, it, true) }
        }
        // the GREEN name's card (loading screen): its row is my team, its card is me
        if (loading && green != null) {
            val (mine, theirs) = if (green.first == 0) a to b else b to a
            mine.getOrNull(green.second)?.takeIf { mine.size == 5 }?.let { return Lineup(mine, theirs, it, true) }
        }
        return null
    }
}
