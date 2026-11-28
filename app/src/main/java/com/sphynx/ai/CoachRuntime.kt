package com.sphynx.ai

import com.sphynx.adapters.mlbb.MlbbEvents
import com.sphynx.core.Diag
import com.sphynx.game.GameState
import com.sphynx.universal.coach.MatchContext

// THE COACH IN A MATCH (Phase 12). Step C2: keeps the shared match context, fed four times a second from the screen
// reading through the MLBB adapter's events. It only reads: it never changes what the play loop does.
object CoachRuntime {

    val context = MatchContext()
    @Volatile private var prev: MlbbEvents.Reading? = null
    @Volatile private var lastAt = 0L
    @Volatile private var failed = false

    fun reading(s: GameState, nowMs: Long): MlbbEvents.Reading {
        val timers = com.sphynx.vision.DeathTimers.fresh()
        val obj = if (s.bossHp >= 0f) com.sphynx.game.LaneLogic.objectiveName(s.matchSec) else null
        return MlbbEvents.Reading(nowMs, s.phase.inMatch, s.phase == com.sphynx.game.Phase.DEAD, s.matchSec.takeIf { it >= 0 },
            if (timers) com.sphynx.vision.DeathTimers.enemiesDead else null, if (timers) com.sphynx.vision.DeathTimers.alliesDead else null,
            obj, s.bossHp.takeIf { it >= 0f })
    }

    /** Called with each fresh screen reading. */
    fun observe(s: GameState, nowMs: Long = System.currentTimeMillis()) {
        if (failed || nowMs - lastAt < 250) return
        lastAt = nowMs
        try {
            val r = reading(s, nowMs)
            val p = prev
            if ((p == null || !p.inMatch) && r.inMatch) context.reset()
            for (e in MlbbEvents.diff(p, r)) context.add(e)
            if (r.inMatch) context.observe(nowMs, r.matchSec)
            prev = r
        } catch (t: Throwable) {
            failed = true                       // stop for this run rather than risk the play loop
            Diag.error("Coach context stopped", t)
        }
    }
}
