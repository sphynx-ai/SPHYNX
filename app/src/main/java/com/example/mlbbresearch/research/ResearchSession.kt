package com.example.mlbbresearch.research

// Research mode, phase R2 (pure logic, unit tested): ties the PracticeGate to the current match and
// keeps a verification log. Nothing here acts on the game; R3 experiments will ask allowsAutonomy().
//  - The gate follows the tracker's match id: a different id always resets it to UNVERIFIED.
//  - Match id 0 (match not identified) can NEVER verify, so two unidentified matches can never
//    share one verification.
class ResearchSession(private val maxLog: Int = 30) {

    data class LogEntry(val matchId: Long, val atMs: Long, val event: String)

    val gate = PracticeGate()
    private val log = ArrayDeque<LogEntry>()
    var readings = 0; private set

    @Synchronized fun sync(matchId: Long, nowMs: Long) {
        if (matchId != gate.matchId) {
            gate.newMatch(matchId)
            readings = 0
            if (matchId != 0L) add(matchId, nowMs, "new match: blocked until the scoreboard proves practice")
        }
    }

    @Synchronized fun observe(matchId: Long, r: ScoreboardNameCheck.Result, nowMs: Long) {
        sync(matchId, nowMs)
        if (matchId == 0L) return                          // unidentified match: never verifies
        readings++
        val before = gate.state
        gate.observe(r)
        if (gate.state != before) {
            val rows = "ally " + r.ally.joinToString(" ") { "%.2f".format(java.util.Locale.US, it) } +
                " | enemy " + r.enemy.joinToString(" ") { "%.2f".format(java.util.Locale.US, it) }
            add(matchId, nowMs, "${gate.state}: ${r.note} (your row ${r.selfRow + 1}; scores $rows)")
        }
    }

    @Synchronized fun allowsAutonomy(matchId: Long): Boolean = matchId != 0L && matchId == gate.matchId && gate.allowsAutonomy()

    fun status(on: Boolean): String = when {
        !on -> "Research: off"
        gate.matchId == 0L -> "Research: blocked - match not identified yet"
        gate.state == PracticeGate.State.VERIFIED -> "Research: verified practice (all 9 others are Computer) - no experiment until Player mode plays"
        gate.state == PracticeGate.State.REVOKED -> "Research: LOCKED for this match - ${gate.reason}"
        else -> "Research: blocked - open the scoreboard once to verify practice"
    }

    @Synchronized fun recent(): List<LogEntry> = log.toList()

    private fun add(matchId: Long, atMs: Long, event: String) {
        if (log.size >= maxLog) log.removeFirst()
        log.addLast(LogEntry(matchId, atMs, event))
    }
}
