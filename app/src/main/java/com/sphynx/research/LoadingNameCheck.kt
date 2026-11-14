package com.sphynx.research

import com.sphynx.game.PlayerName
import org.json.JSONObject

// THE LOADING SCREEN CHECK (owner, 2026-11-10): the loading screen already shows whether the other 9
// players are "Computer", before the match starts. The strategic model's line-up read of the loading
// screen (StrategicAdvisor.runLineup: every card's player name, top row and bottom row) is checked here.
// Pure logic, unit tested. It can only verify a practice match: anything else is NOT_PROVEN and the
// scoreboard check decides (a misread name must never lock a practice match, nor open a real one).
//  - your card: the GREEN-name card (PlayerName.greenCard), else the card with your player name, else -
//    when exactly one of the 10 cards is not "Computer" - that card;
//  - ALL_COMPUTER only when all 10 cards were read and the 9 others are exactly the word "Computer";
//    or, in SOLO PRACTICE (one card per row: you and one enemy), when the other card is "Computer".
object LoadingNameCheck {

    enum class Verdict { ALL_COMPUTER, NOT_PROVEN }

    data class Result(val verdict: Verdict, val note: String)

    // exactly the word "Computer" (any letter case / spacing): no reading slips are allowed, because a
    // real player called "Computers" or "Computor" must never verify a match
    fun isComputer(name: String): Boolean = PlayerName.norm(name) == "computer"

    fun check(reply: String?, green: Pair<Int, Int>?, myName: String): Result {
        if (reply.isNullOrBlank()) return Result(Verdict.NOT_PROVEN, "no reading")
        val o = try { JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)) }
            catch (e: Exception) { return Result(Verdict.NOT_PROVEN, "reading not understood") }
        fun row(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optJSONObject(it)?.optString("player").orEmpty() }
        }
        val rows = listOf(row("top_row"), row("bottom_row"))
        // SOLO PRACTICE (owner, 2026-11-10: only you and one Computer enemy): one card per row
        // (exactly one entry per row: a 5v5 read with blanks must never pass as solo - its unread cards may be players)
        val solo = rows.all { it.size == 1 }
        if (!solo && rows.any { it.size != 5 }) return Result(Verdict.NOT_PROVEN, "not all 10 cards read")
        val cards = if (solo) rows.mapIndexed { r, names -> Triple(r, 0, names[0]) }
            else rows.flatMapIndexed { r, names -> names.mapIndexed { i, n -> Triple(r, i, n) } }
        if (cards.any { it.third.isBlank() }) return Result(Verdict.NOT_PROVEN, "a card's name was not read")
        val notComputer = cards.filter { !isComputer(it.third) }
        // (solo: the green name's ROW is yours - its place in the row is not used)
        val self = green?.let { g -> cards.firstOrNull { it.first == g.first && (solo || it.second == g.second) } }
            ?: myName.takeIf { it.isNotBlank() }?.let { n -> cards.firstOrNull { PlayerName.same(it.third, n) && !isComputer(it.third) } }
            ?: notComputer.singleOrNull()
            ?: return Result(Verdict.NOT_PROVEN, "${notComputer.size} cards are not 'Computer'")
        val others = notComputer.filter { it !== self }
        return if (others.isEmpty()) Result(Verdict.ALL_COMPUTER,
            if (solo) "solo practice: the 1 other player is 'Computer'" else "all 9 other players are 'Computer'")
        else Result(Verdict.NOT_PROVEN, "${others.size} other card(s) are not 'Computer'")
    }

    // ---- from the PIXELS (no Gemma): the "Computer" picture's scores at the cards' name spots ----
    // Measured on the owner's 2712x1220 loading screenshots (2026-11-10), at 0.8x the scoreboard size:
    //  - 5v5 practice vs Computer: your card 0.22, the 9 others 0.90..0.94;
    //  - 5v5 with real players (two screenshots): 0.30 at most on every spot;
    //  - solo practice: the enemy card 0.94, your card 0.27.
    //  At the app's 800x359 capture (device test 2026-11-10; screenshots shrunk to that size): 5v5 practice
    //  0.84..0.89, solo enemy card 0.81, real players 0.50 at most - so the line sits at 0.75.
    const val PIXEL_COMPUTER = 0.75f

    // 5v5: ten spots (top row then bottom row); at least 9 must read "Computer" (yours is the 10th)
    fun fromGrid(scores: List<Float>): Result {
        val n = scores.count { it >= PIXEL_COMPUTER }
        val shown = scores.joinToString(" ") { "%.2f".format(java.util.Locale.US, it) }
        return if (scores.size == 10 && n >= 9) Result(Verdict.ALL_COMPUTER, "5v5 loading cards: $n of 10 read 'Computer' ($shown)")
        else Result(Verdict.NOT_PROVEN, "5v5 loading cards: $n of 10 read 'Computer' ($shown)")
    }

    // solo practice: the enemy card's one spot
    fun fromSolo(score: Float): Result = Result(if (score >= PIXEL_COMPUTER) Verdict.ALL_COMPUTER else Verdict.NOT_PROVEN,
        "solo practice loading card: 'Computer' %.2f".format(java.util.Locale.US, score))
}
