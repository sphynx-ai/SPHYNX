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

    // ---- Phase 12 C4: shadow mode and the audit log ----

    const val AUDIT = "coach_audit.jsonl"
    private const val EVERY_MS = 1_000L
    @Volatile private var shadowAt = 0L
    /** The coach's newest output (for the panel and supervised mode); null before the first. */
    @Volatile var last: com.sphynx.universal.coach.Coordinator.Output? = null; private set
    @Volatile var coachAt = 0L; private set

    /** The engines' advice right now, in the universal form (the same sources DecisionLogging compares). */
    fun advice(brainChoice: com.sphynx.learning.FightOption?, nowMs: Long): List<com.sphynx.universal.Decision> {
        val out = ArrayList<com.sphynx.universal.Decision>()
        brainChoice?.let { out.add(DecisionBridge.fromFight(it, com.sphynx.universal.EngineSource.LEARNED, nowMs)) }
        TacticalAdvisor.latestAdvice()?.let { (o, at) -> out.add(DecisionBridge.fromFight(o, com.sphynx.universal.EngineSource.TACTICAL, at)) }
        DecisionBridge.fromPlan(StrategicAdvisor.plan)?.let { out.add(it) }
        return out
    }

    /** The coach's advice for this moment (always computed: it is advice only). */
    fun coach(s: GameState, brainChoice: com.sphynx.learning.FightOption?, nowMs: Long = System.currentTimeMillis()): com.sphynx.universal.coach.Coordinator.Output? = try {
        com.sphynx.universal.coach.Coordinator.coordinate(advice(brainChoice, nowMs), com.sphynx.adapters.mlbb.MlbbAdapter.toUniversal(s), nowMs)
            .also { last = it; coachAt = nowMs }
    } catch (t: Throwable) { null }

    /** The panel's coach lines (budget 2), or empty when the coach has nothing fresh. */
    fun lines(nowMs: Long = System.currentTimeMillis()): List<String> =
        last?.takeIf { nowMs - coachAt < 3_000 }?.lines() ?: emptyList()

    /**
     * Shadow mode: once a second, the coach's tactical advice next to what the play loop [played], counted for the gates and
     * written to the audit log. It never changes [played].
     */
    fun shadow(s: GameState, brainChoice: com.sphynx.learning.FightOption?, played: com.sphynx.learning.FightOption?,
               nowMs: Long = System.currentTimeMillis()) {
        if (failed || played == null || nowMs - shadowAt < EVERY_MS) return
        val ctx = com.sphynx.core.Probes.appContext() ?: return
        shadowAt = nowMs
        try {
            val out = coach(s, brainChoice, nowMs) ?: return
            val playedKind = DecisionBridge.kind(played)
            val rec = out.actionable
            val agree = rec != null && rec.kind == playedKind
            val unsafe = rec != null && rec.kind.aggressive && playedKind == com.sphynx.universal.DecisionKind.RETREAT
            if (rec != null) Shadow.count(modelKey(ctx), agree, unsafe)
            val line = org.json.JSONObject().put("at", nowMs).put("matchSec", s.matchSec).put("played", playedKind.name)
                .putOpt("coach", rec?.toJson()).put("agree", agree).put("unsafe", unsafe).putOpt("conflict", out.conflict)
                .put("noAdvice", org.json.JSONArray(out.noAdvice)).put("context", context.summary(nowMs))
                .put("supervised", supervisedActive)
            audit(ctx, line.toString())
        } catch (t: Throwable) {
            failed = true
            Diag.error("Coach shadow mode stopped", t)
        }
    }

    /** The tactical job's model, the key its shadow counts are kept under (a new model starts again). */
    fun modelKey(ctx: android.content.Context): String {
        val f = com.sphynx.models.ModelManager.fileFor(ctx, Roles.TACTICAL)
        return f.name + ":" + f.length()
    }

    @Volatile var supervisedActive = false; private set

    private fun auditFile(ctx: android.content.Context) =
        java.io.File(java.io.File(ctx.applicationContext.filesDir, "research").apply { mkdirs() }, AUDIT)

    @Synchronized private fun audit(ctx: android.content.Context, line: String) {
        val f = auditFile(ctx)
        if (f.length() > 4L shl 20) { java.io.File(f.parentFile, AUDIT + ".old").let { it.delete(); f.renameTo(it) } }
        f.appendText(line + "\n")
    }

    /** The audit log into Recordings (the Debug data page's button), uploaded by the debug-data rule. */
    fun exportAudit(ctx: android.content.Context): String {
        val f = auditFile(ctx)
        if (!f.isFile || f.length() == 0L) return "No coach audit yet"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val out = java.io.File(ctx.cacheDir, "diagnostics-coach-audit-$stamp.jsonl")
        f.copyTo(out, overwrite = true)
        return try { com.sphynx.core.SessionRecorder.exportFile(ctx, out, "application/json") } finally { out.delete() }
    }

    /** The shadow counts per tactical model, kept in the settings (they survive a restart). */
    object Shadow {
        private fun key(m: String) = "coach_shadow_" + m
        fun counts(m: String): Triple<Int, Int, Int> = com.sphynx.core.Cfg.raw(key(m))?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) } ?: Triple(0, 0, 0)
        @Synchronized fun count(m: String, agree: Boolean, unsafe: Boolean) {
            val (n, a, u) = counts(m)
            com.sphynx.core.Cfg.setRaw(key(m), "${n + 1},${a + if (agree) 1 else 0},${u + if (unsafe) 1 else 0}")
        }
    }
}
