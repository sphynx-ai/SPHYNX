package com.example.mlbbresearch.research

// THE PRACTICE-ONLY GATE (owner-approved research mode, phase R1).
// Research experiments may act on the game ONLY while this gate says VERIFIED: the app has proven,
// from the loading screen or the scoreboard, that all 9 other players in this match are "Computer".
// Rules (no overrides, by design - there is no setting or flag that forces it open):
//  - every new match starts UNVERIFIED (blocked);
//  - the LOADING SCREEN shows all 9 others as "Computer" -> VERIFIED before the match even starts
//    (owner, 2026-11-10: the loading screen already tells practice from a real match);
//  - ALL_COMPUTER on a scoreboard reading -> VERIFIED (checked again on every scoreboard look);
//  - HUMAN_PRESENT on HUMAN_READINGS scoreboard readings in a row -> REVOKED for the rest of the match
//    (sticky, cannot be re-verified). One reading is not enough: a single frame with something drawn
//    over the names (device test 2026-11-10: the app's own boxes) used to lock a practice match;
//    a real player's name is there on every reading, so it is still caught 1.5 s later;
//  - UNREADABLE changes nothing (an UNVERIFIED match stays blocked).
// A loading screen that does NOT show 9 "Computer" names only leaves the match unverified (a misread
// name never locks); the scoreboard decides then.
class PracticeGate {

    companion object { const val HUMAN_READINGS = 2 }

    enum class State { UNVERIFIED, VERIFIED, REVOKED }

    var state = State.UNVERIFIED; private set
    var reason = "new match: open the scoreboard once to verify practice"; private set
    var matchId = 0L; private set
    private var humanStreak = 0

    @Synchronized fun newMatch(id: Long) {
        matchId = id; state = State.UNVERIFIED; humanStreak = 0
        reason = "new match: open the scoreboard once to verify practice"
    }

    @Synchronized fun observe(r: ScoreboardNameCheck.Result) {
        if (r.verdict != ScoreboardNameCheck.Verdict.HUMAN_PRESENT) humanStreak = 0
        when (r.verdict) {
            ScoreboardNameCheck.Verdict.HUMAN_PRESENT -> {
                humanStreak++
                if (humanStreak >= HUMAN_READINGS) { state = State.REVOKED; reason = "human player detected: ${r.note}" }
                else if (state == State.UNVERIFIED) reason = "checking again: ${r.note}"
            }
            ScoreboardNameCheck.Verdict.ALL_COMPUTER -> if (state == State.UNVERIFIED) { state = State.VERIFIED; reason = r.note }
            ScoreboardNameCheck.Verdict.UNREADABLE -> if (state == State.UNVERIFIED) reason = "not verified yet: ${r.note}"
        }
    }

    // the loading screen's reading (LoadingNameCheck): only ever verifies, never locks
    @Synchronized fun observeLoading(r: LoadingNameCheck.Result) {
        if (state != State.UNVERIFIED) return
        if (r.verdict == LoadingNameCheck.Verdict.ALL_COMPUTER) { state = State.VERIFIED; reason = "loading screen: ${r.note}" }
        else reason = "loading screen not conclusive (${r.note}): open the scoreboard once"
    }

    fun allowsAutonomy(): Boolean = state == State.VERIFIED
}
