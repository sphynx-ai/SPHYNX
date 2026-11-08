package com.example.mlbbresearch.research

import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.GameStateTracker

// Research mode, phase R2: the "research" stage (AiRuntime). While it is on, in-match scoreboard
// frames are checked for "all 9 other players are Computer" (ScoreboardNameCheck -> PracticeGate).
// It never touches the game: no experiment is connected yet (R3). It does not need the
// accessibility service.
object ResearchMode {

    const val EVERY_MS = 1500L
    @Volatile var on = false; private set
    val session = ResearchSession()
    @Volatile private var matcher: ComputerNameMatcher? = null
    @Volatile private var lastAt = 0L
    @Volatile private var loadFailed = false

    fun start() { on = true; Diag.info("Research mode on (practice-only gate; no experiments active)") }
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

    fun status(): String {
        if (on) session.sync(GameStateTracker.matchId, System.currentTimeMillis())
        return session.status(on)
    }
}
