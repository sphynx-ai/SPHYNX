package com.example.mlbbresearch.ai

// COMMIT TO THE OBJECTIVE (owner, 2026-11-12 - the 2026-11-11 recording: "turtle is up: rotating there" from 2:07
// to 7:18, five minutes, and the Player never reached the pit - pulled away again and again). Once a rotation to
// the Turtle / Lord starts, it goes first (before the jungle route, ganks, waves...) until the Player arrives,
// unless it is in danger (enemies near, or HP under 40%). Not there after 45 s: it gives up on the objective for
// 60 s instead of flip-flopping. Pure logic, unit tested.
class ObjectiveCommit {

    companion object {
        const val COMMIT_MS = 45_000L
        const val GIVE_UP_MS = 60_000L
        const val SAFE_HP = 0.4f
    }

    var since = 0L; private set
    var giveUpUntil = 0L; private set

    // a rotation decision was made this moment (start the clock if it is not running)
    fun rotating(now: Long) { if (since == 0L) since = now }

    // arrived, the objective is gone, or the match changed: the commitment ends
    fun done() { since = 0L }

    fun gaveUp(now: Long): Boolean = now < giveUpUntil

    // should the rotation go first now? (also ends it, with the 60 s give-up, once 45 s are over)
    fun first(now: Long, inDanger: Boolean, hp: Float): Boolean {
        if (since == 0L) return false
        if (now - since > COMMIT_MS) { since = 0L; giveUpUntil = now + GIVE_UP_MS; return false }
        return !inDanger && hp >= SAFE_HP
    }

    fun reset() { since = 0L; giveUpUntil = 0L }
}
