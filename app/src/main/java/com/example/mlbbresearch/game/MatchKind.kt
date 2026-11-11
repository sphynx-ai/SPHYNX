package com.example.mlbbresearch.game

// SOLO PRACTICE OR 5v5 (owner, 2026-11-11): the owner uses Solo mode vs computer ONLY for the scenery
// calibration - there the Lord and the Turtle are both up from the start and the game's timers do not apply.
// 5v5 matches are where the real data comes from: the wiki's timers are right there (Turtle at 2:00, no Turtle
// from 9:00, the Lord...). Which one the current match is comes from what the practice gate already reads:
//  - the loading screen: the solo layout (ScreenReader.soloLoading) = solo; the 10-card grid read = 5v5;
//  - the scoreboard: 2 players = solo; 6 or more = 5v5.
// Tied to the match id, so a new match starts unknown. Pure logic, unit tested.
object MatchKind {

    enum class Kind(val label: String) { UNKNOWN("?"), SOLO("Solo practice"), FIVE_V_FIVE("5v5") }

    @Volatile private var id = 0L
    @Volatile private var kind = Kind.UNKNOWN

    @Synchronized fun set(matchId: Long, k: Kind) {
        if (matchId == 0L || k == Kind.UNKNOWN) return
        if (id == matchId && kind == k) return
        id = matchId; kind = k
        com.example.mlbbresearch.core.Diag.info("Match kind: ${k.label}")
    }

    // from the number of players the scoreboard shows (you included); 0 = not read
    fun fromPlayers(players: Int): Kind = when {
        players == 2 -> Kind.SOLO
        players >= 6 -> Kind.FIVE_V_FIVE
        else -> Kind.UNKNOWN
    }

    fun of(matchId: Long): Kind = if (matchId != 0L && matchId == id) kind else Kind.UNKNOWN
    fun current(): Kind = of(GameStateTracker.matchId)

    @Synchronized fun reset() { id = 0L; kind = Kind.UNKNOWN }
}
