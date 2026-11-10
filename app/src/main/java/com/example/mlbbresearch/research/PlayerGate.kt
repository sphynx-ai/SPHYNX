package com.example.mlbbresearch.research

// PLAYER MODE IS PRACTICE-ONLY (owner decision, 2026-11-10): "I don't want to use it to play live
// matches against real players anymore." The Player plays the hero only in a match that the practice
// gate has verified - all 9 other players are "Computer" (scoreboard check, research/PracticeGate) -
// so it can be trained to match or beat the game's own AI. Pure logic, unit tested.
//  - PLAY:    verified practice match (this match id)          -> the tactical loop runs
//  - WAIT:    not verified yet (open the scoreboard once)       -> nothing is sent, the thumb is lifted
//  - LOCKED:  a human player was seen in this match            -> nothing is sent for the rest of it
//  - UNKNOWN: the match is not identified (id 0)               -> nothing is sent
// In every verdict but PLAY the Player becomes a COMPANION: strategist + tactical advisor keep working
// and their advice is shown, nothing is touched (AiRuntime.advise).
// Drafter and Builder are NOT gated (owner decision): they do not play against other players.
object PlayerGate {

    enum class Verdict(val label: String) {
        PLAY("verified practice vs Computer"),
        WAIT("waiting: open the scoreboard once to confirm a practice match vs Computer"),
        LOCKED("LOCKED for this match: a real player was detected"),
        UNKNOWN("waiting: match not identified yet")
    }

    /** The Builder presses item actives itself unless the Player is really playing (it presses them then). */
    fun builderPressesActives(enabled: Boolean, playerOn: Boolean, gate: Verdict?): Boolean =
        enabled && !(playerOn && gate == Verdict.PLAY)

    fun verdict(matchId: Long, gateMatchId: Long, state: PracticeGate.State): Verdict = when {
        matchId == 0L -> Verdict.UNKNOWN
        gateMatchId != matchId -> Verdict.WAIT
        state == PracticeGate.State.VERIFIED -> Verdict.PLAY
        state == PracticeGate.State.REVOKED -> Verdict.LOCKED
        else -> Verdict.WAIT
    }
}
