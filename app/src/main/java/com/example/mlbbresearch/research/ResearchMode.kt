package com.example.mlbbresearch.research

import com.example.mlbbresearch.adapters.mlbb.MlbbAdapter
import com.example.mlbbresearch.ai.StrategicAdvisor
import com.example.mlbbresearch.ai.TacticalAdvisor
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.engines.DecisionBridge
import com.example.mlbbresearch.learning.FightOption
import com.example.mlbbresearch.universal.Decision
import com.example.mlbbresearch.universal.EngineSource
import com.example.mlbbresearch.game.GameStateTracker

// Research mode: the "research" stage (AiRuntime). While it is on, in-match scoreboard frames are
// checked for "all 9 other players are Computer" (ScoreboardNameCheck -> PracticeGate). It does not
// need the accessibility service and never touches the game by itself.
// R3 experiment: in a verified practice match, while Player mode plays, the DecisionArbiter picks
// the fight option (ArbiterFightExperiment). Anywhere else the normal choice is played untouched.
object ResearchMode {

    const val EVERY_MS = 1500L
    @Volatile var on = false; private set
    val session = ResearchSession()
    @Volatile private var matcher: ComputerNameMatcher? = null
    @Volatile private var lastAt = 0L
    @Volatile private var loadFailed = false

    val experiment = ArbiterFightExperiment()
    @Volatile private var experimentFailed = false

    fun start() { on = true; experiment.reset(); experimentFailed = false; Diag.info("Research mode on (practice-only gate; R3 arbiter experiment in verified practice only)") }
    fun stop() { on = false }

    private fun matcher(): ComputerNameMatcher? = matcher ?: if (loadFailed) null else try {
        val ctx = com.example.mlbbresearch.vision.HeroVerifier.appContext() ?: return null
        ComputerNameMatcher(ctx.assets.open(ComputerNameMatcher.ASSET).use { it.readBytes() }).also { matcher = it }
    } catch (e: Exception) { loadFailed = true; Diag.error("Research: template not loaded", e); null }

    // called from FrameProcessor on in-match scoreboard frames
    fun observeScoreboard(px: IntArray, w: Int, h: Int, nowMs: Long = System.currentTimeMillis()) {
        if (!on || nowMs - lastAt < EVERY_MS) return
        lastAt = nowMs
        val m = matcher() ?: return
        val id = GameStateTracker.matchId
        val before = session.gate.state
        session.observe(id, ScoreboardNameCheck.check(px, w, h, m), nowMs)
        if (session.gate.state != before) Diag.info(session.status(true))
    }

    // for R3 experiments: true only in a verified practice match, while research mode is on
    fun allowsAutonomy(): Boolean = on && session.allowsAutonomy(GameStateTracker.matchId)

    // R3: called by the play loop once the normal fight option is final. Returns the option to play.
    // Fail-closed: not allowed, or any error (which also stops the experiment for this run) -> normal.
    fun fightChoice(s: com.example.mlbbresearch.game.GameState, normal: FightOption?, brainChoice: FightOption?, netAdvice: Pair<FightOption, Float>?): FightOption? {
        if (normal == null || experimentFailed || !allowsAutonomy()) return normal
        return try {
            val now = System.currentTimeMillis()
            val advice = ArrayList<Decision>()
            advice.add(DecisionBridge.fromFight(normal, EngineSource.RULES, now))            // what the app would play
            brainChoice?.let { advice.add(DecisionBridge.fromFight(it, EngineSource.LEARNED, now)) }
            netAdvice?.let { (o, c) -> advice.add(DecisionBridge.fromFight(o, EngineSource.LEARNED, now, c.coerceIn(0f, 1f))) }
            TacticalAdvisor.latestAdvice()?.let { (o, at) -> advice.add(DecisionBridge.fromFight(o, EngineSource.TACTICAL, at)) }
            DecisionBridge.fromPlan(StrategicAdvisor.plan)?.let { advice.add(it) }
            val r = experiment.choose(allowsAutonomy(), normal, advice, MlbbAdapter.toUniversal(s), now) { TacticalAdvisor.check(it, s) }
            if (r.changed) Diag.info("Research R3: ${normal.label} -> ${r.option?.label} (${r.note})")
            r.option
        } catch (t: Throwable) {
            experimentFailed = true
            Diag.error("Research R3 experiment stopped", t)
            normal
        }
    }

    fun status(): String {
        if (on) session.sync(GameStateTracker.matchId, System.currentTimeMillis())
        val base = session.status(on)
        if (!allowsAutonomy()) return base
        return if (experimentFailed) "$base - R3 experiment stopped after an error"
        else "Research: verified practice - R3 active: arbiter picks the fight option while Player mode plays (${experiment.summary()})"
    }
}
