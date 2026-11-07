package com.example.mlbbresearch.research

// THE PRACTICE-ONLY GATE (owner-approved research mode, phase R1).
// Research experiments may act on the game ONLY while this gate says VERIFIED: the app has proven,
// from the scoreboard, that all 9 other players in this match are "Computer".
// Rules (no overrides, by design - there is no setting or flag that forces it open):
//  - every new match starts UNVERIFIED (blocked);
//  - ALL_COMPUTER on a scoreboard reading -> VERIFIED;
//  - HUMAN_PRESENT at any time -> REVOKED for the rest of the match (sticky, cannot be re-verified);
//  - UNREADABLE changes nothing (an UNVERIFIED match stays blocked).
// Not connected to any touch output in R1.
class PracticeGate {

    enum class State { UNVERIFIED, VERIFIED, REVOKED }

    var state = State.UNVERIFIED; private set
    var reason = "new match: open the scoreboard once to verify practice"; private set
    var matchId = 0L; private set

    @Synchronized fun newMatch(id: Long) {
        matchId = id; state = State.UNVERIFIED; reason = "new match: open the scoreboard once to verify practice"
    }

    @Synchronized fun observe(r: ScoreboardNameCheck.Result) {
        when (r.verdict) {
            ScoreboardNameCheck.Verdict.HUMAN_PRESENT -> { state = State.REVOKED; reason = "human player detected: ${r.note}" }
            ScoreboardNameCheck.Verdict.ALL_COMPUTER -> if (state == State.UNVERIFIED) { state = State.VERIFIED; reason = r.note }
            ScoreboardNameCheck.Verdict.UNREADABLE -> if (state == State.UNVERIFIED) reason = "not verified yet: ${r.note}"
        }
    }

    fun allowsAutonomy(): Boolean = state == State.VERIFIED
}
