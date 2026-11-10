package com.example.mlbbresearch.research

import com.example.mlbbresearch.game.PlayerName
import org.json.JSONObject

// THE LOADING SCREEN CHECK (owner, 2026-11-10): the loading screen already shows whether the other 9
// players are "Computer", before the match starts. The strategic model's line-up read of the loading
// screen (StrategicAdvisor.runLineup: every card's player name, top row and bottom row) is checked here.
// Pure logic, unit tested. It can only verify a practice match: anything else is NOT_PROVEN and the
// scoreboard check decides (a misread name must never lock a practice match, nor open a real one).
//  - your card: the GREEN-name card (PlayerName.greenCard), else the card with your player name, else -
//    when exactly one of the 10 cards is not "Computer" - that card;
//  - ALL_COMPUTER only when all 10 cards were read and the 9 others are exactly the word "Computer".
object LoadingNameCheck {

    enum class Verdict { ALL_COMPUTER, NOT_PROVEN }

    data class Result(val verdict: Verdict, val note: String)

    // "Computer", allowing one reading slip ("Cornputer"), but not a longer name containing it ("ComputerKing")
    fun isComputer(name: String): Boolean {
        val n = PlayerName.norm(name)
        return n.length in 7..9 && PlayerName.same(n, "computer")
    }

    fun check(reply: String?, green: Pair<Int, Int>?, myName: String): Result {
        if (reply.isNullOrBlank()) return Result(Verdict.NOT_PROVEN, "no reading")
        val o = try { JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)) }
            catch (e: Exception) { return Result(Verdict.NOT_PROVEN, "reading not understood") }
        fun row(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optJSONObject(it)?.optString("player").orEmpty() }
        }
        val rows = listOf(row("top_row"), row("bottom_row"))
        if (rows.any { it.size != 5 }) return Result(Verdict.NOT_PROVEN, "not all 10 cards read")
        val cards = rows.flatMapIndexed { r, names -> names.mapIndexed { i, n -> Triple(r, i, n) } }
        if (cards.any { it.third.isBlank() }) return Result(Verdict.NOT_PROVEN, "a card's name was not read")
        val notComputer = cards.filter { !isComputer(it.third) }
        val self = green?.let { g -> cards.firstOrNull { it.first == g.first && it.second == g.second } }
            ?: myName.takeIf { it.isNotBlank() }?.let { n -> cards.firstOrNull { PlayerName.same(it.third, n) && !isComputer(it.third) } }
            ?: notComputer.singleOrNull()
            ?: return Result(Verdict.NOT_PROVEN, "${notComputer.size} cards are not 'Computer'")
        val others = notComputer.filter { it !== self }
        return if (others.isEmpty()) Result(Verdict.ALL_COMPUTER, "all 9 other players are 'Computer'")
        else Result(Verdict.NOT_PROVEN, "${others.size} other card(s) are not 'Computer'")
    }
}
