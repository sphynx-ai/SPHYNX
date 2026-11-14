package com.sphynx.learning

import com.sphynx.game.MatchKind

// WHEN THE APP LEARNS (owner, 2026-11-13):
//  - Solo practice vs Computer is for calibration only (skill buttons, scenery): NO learning at all there - no
//    recorded moments, no practice benchmark, no minimap / screen training pictures, no automatic recording.
//  - A 5v5 match (vs Computer or real players) is where everything is learned; with the Research player off (or
//    only advising) the app learns from YOU by itself, and the floating panel says so.
//  - Until the kind of match is known (the loading screen or the scoreboard tells), nothing is learned.
// Pure logic, unit tested.
object LearnGate {

    fun learns(kind: MatchKind.Kind): Boolean = kind == MatchKind.Kind.FIVE_V_FIVE

    // the current match
    fun on(): Boolean = learns(MatchKind.current())

    // the floating panel's heading for the learning section
    fun panelTitle(kind: MatchKind.Kind, inMatch: Boolean, playerPlays: Boolean): String = when {
        kind == MatchKind.Kind.SOLO -> "NOT LEARNING: Solo practice (calibration only)"
        kind == MatchKind.Kind.FIVE_V_FIVE -> if (playerPlays) "LEARNING FROM YOU (5v5, while you take over)" else "LEARNING FROM YOU (5v5)"
        inMatch -> "NOT LEARNING YET: open the scoreboard once (5v5 or Solo not known)"
        else -> "Learning from you starts by itself in a 5v5 match"
    }
}
