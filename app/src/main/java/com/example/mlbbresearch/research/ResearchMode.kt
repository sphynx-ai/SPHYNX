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
// R4: in verified practice, fight moments are recorded (PracticeMoments) so the small tactical model
// can be measured on them later (ReadinessRun). Recording never changes what is played.
object ResearchMode {

    const val EVERY_MS = 1500L
    @Volatile var on = false; private set
    val session = ResearchSession()
    @Volatile private var matcher: ComputerNameMatcher? = null
    @Volatile private var lastAt = 0L
    @Volatile private var loadFailed = false

    val experiment = ArbiterFightExperiment()
    @Volatile private var experimentFailed = false

    val moments = PracticeMomentSet()
    @Volatile private var momentsLoaded = false
    @Volatile private var unsaved = 0
    @Volatile private var recordFailed = false

    private fun momentsFile(): java.io.File? = com.example.mlbbresearch.vision.HeroVerifier.appContext()?.let {
        java.io.File(java.io.File(it.filesDir, "research").apply { mkdirs() }, "practice_moments.jsonl") }

    fun loadMoments() {
        if (momentsLoaded) return
        momentsLoaded = true
        try { momentsFile()?.takeIf { it.exists() }?.let { moments.loadJsonLines(it.readText()) } }
        catch (e: Exception) { Diag.error("Research: practice moments not loaded", e) }
    }

    fun saveMoments() {
        if (unsaved == 0) return
        try { momentsFile()?.writeText(moments.toJsonLines()); unsaved = 0 }
        catch (e: Exception) { Diag.error("Research: practice moments not saved", e) }
    }

    fun clearMoments() { moments.clear(); unsaved = 1; saveMoments() }

    // R4: called by the play loop after the fight option is final (verified practice only).
    // Any error stops recording for the run; it never affects play.
    fun recordMoment(s: com.example.mlbbresearch.game.GameState, p: com.example.mlbbresearch.game.HeroProfile, played: FightOption?) {
        if (played == null || recordFailed || !allowsAutonomy()) return
        try {
            loadMoments()
            val unsafe = HashSet<com.example.mlbbresearch.universal.DecisionKind>()
            if (TacticalAdvisor.check(FightOption.ALL_IN, s) != null) unsafe.add(com.example.mlbbresearch.universal.DecisionKind.ENGAGE)
            com.example.mlbbresearch.ai.Deliberation.last?.let { if (it.r > 0.5f) unsafe.add(com.example.mlbbresearch.universal.DecisionKind.FARM) }
            val m = PracticeMoment(GameStateTracker.matchId, s.ts, TacticalAdvisor.summary(s, p), DecisionBridge.kind(played), unsafe,
                s.selfHp.takeIf { it in 0f..1f }, s.enemiesWithin(0.9f).size, s.allies.count { it.hero }, s.enemyMinions.size, s.monsters.size)
            if (moments.add(m) && ++unsaved >= 20) saveMoments()
        } catch (t: Throwable) {
            recordFailed = true
            Diag.error("Research R4 recording stopped", t)
        }
    }

    // R5: the controlled comparison (arbiter vs normal) inside verified practice
    val ab = ResearchAB()
    @Volatile private var abLoaded = false

    private fun abFile(): java.io.File? = com.example.mlbbresearch.vision.HeroVerifier.appContext()?.let {
        java.io.File(java.io.File(it.filesDir, "research").apply { mkdirs() }, "ab_log.json") }

    private fun loadAb() {
        if (abLoaded) return
        abLoaded = true
        try { abFile()?.takeIf { it.exists() }?.let { ab.load(it.readText()) } } catch (e: Exception) { Diag.error("Research: comparison not loaded", e) }
    }

    fun clearAb() { loadAb(); ab.clear(); try { abFile()?.writeText(ab.toJson()) } catch (e: Exception) {} }

    // the arm of the current match: decided by its id, so it never changes during a match
    fun arm(): ResearchAB.Arm = ResearchAB.armOf(GameStateTracker.matchId)

    // R5: a fight choice scored by the learning brain (4 s later). Only the AI's own choices in a
    // verified practice match while Player mode plays are counted.
    fun onFightScored(reward: Float, died: Boolean, byYou: Boolean) {
        if (byYou || !com.example.mlbbresearch.ai.AiRuntime.playerOn || !allowsAutonomy()) return
        try {
            loadAb(); ab.fight(GameStateTracker.matchId, reward, died)
            com.example.mlbbresearch.core.SessionRecorder.event(org.json.JSONObject().put("k", "fight")
                .put("arm", arm().name).put("reward", com.example.mlbbresearch.core.SessionRecorder.r3(reward)).put("died", died))
        } catch (t: Throwable) { Diag.error("Research R5", t) }
    }

    // R5: a match ended (GameStateTracker.logMatch)
    fun onMatchEnd(k: Int, d: Int, a: Int, seconds: Int, win: Boolean?) {
        try {
            loadAb()
            if (ab.matchEnd(k, d, a, seconds, win)) {
                abFile()?.writeText(ab.toJson())
                Diag.info("Research R5: practice match saved - " + ab.report().verdict)
            }
        } catch (t: Throwable) { Diag.error("Research R5", t) }
    }

    fun abText(): String { loadAb(); return ab.text() }

    // PRACTICE BENCHMARK: one scorecard per verified practice match the Player played (PracticeBenchmark).
    // Runs whenever Player mode is on, research mode or not. Any error stops it for the run; it never affects play.
    val benchmark = PracticeBenchmark()
    @Volatile private var benchLoaded = false
    @Volatile private var benchFailed = false

    private fun benchFile(): java.io.File? = com.example.mlbbresearch.vision.HeroVerifier.appContext()?.let {
        java.io.File(java.io.File(it.filesDir, "research").apply { mkdirs() }, "practice_benchmark.json") }

    private fun loadBench() {
        if (benchLoaded) return
        benchLoaded = true
        try { benchFile()?.takeIf { it.exists() }?.let { benchmark.load(it.readText()) } } catch (e: Exception) { Diag.error("Benchmark not loaded", e) }
    }

    fun benchTick(matchId: Long, nowMs: Long, verdict: PlayerGate.Verdict?, inGame: Boolean, playing: Boolean, intent: String?,
                  idle: Boolean, stuck: Boolean, matchSec: Int, level: Int, deathAt: Long, outnumbered: Boolean) {
        if (benchFailed) return
        try { benchmark.tick(matchId, nowMs, verdict, inGame, playing, intent, idle, stuck, matchSec, level, deathAt, outnumbered) }
        catch (t: Throwable) { benchFailed = true; Diag.error("Benchmark stopped", t) }
    }

    // a match ended (GameStateTracker.logMatch)
    fun onBenchmarkEnd(hero: String?, lane: String, k: Int, d: Int, a: Int, level: Int, seconds: Int, win: Boolean?) {
        if (benchFailed) return
        try {
            loadBench()
            val row = benchmark.matchEnd(hero, lane, k, d, a, level, seconds, win, System.currentTimeMillis()) ?: return
            benchFile()?.writeText(benchmark.toJson())
            Diag.info("Practice scorecard saved: ${row.hero}, ${if (row.win == true) "win" else if (row.win == false) "loss" else "result unknown"}, " +
                "${row.k}/${row.d}/${row.a}, level ${row.level}")
        } catch (t: Throwable) { Diag.error("Benchmark", t) }
    }

    fun benchText(): String { loadBench(); return "Now: " + benchmark.live() + "\n" + benchmark.text() }
    fun benchCsv(): String { loadBench(); return benchmark.csv() }
    fun clearBench() { loadBench(); benchmark.clear(); try { benchFile()?.writeText(benchmark.toJson()) } catch (e: Exception) {} }

    fun start() { on = true; experiment.reset(); experimentFailed = false; recordFailed = false; loadMoments(); Diag.info("Research mode on (practice-only gate; R3 arbiter experiment in verified practice only)") }
    fun stop() { on = false; saveMoments() }

    private fun matcher(): ComputerNameMatcher? = matcher ?: if (loadFailed) null else try {
        val ctx = com.example.mlbbresearch.vision.HeroVerifier.appContext() ?: return null
        ComputerNameMatcher(ctx.assets.open(ComputerNameMatcher.ASSET).use { it.readBytes() }).also { matcher = it }
    } catch (e: Exception) { loadFailed = true; Diag.error("Research: template not loaded", e); null }

    // called from FrameProcessor on in-match scoreboard frames
    // the practice gate also watches the scoreboard while Player mode is on (Player is practice-only)
    fun gateWatching(): Boolean = on || com.example.mlbbresearch.ai.AiRuntime.playerOn

    // THE GATE'S LAST READING, shown on the floating panel (device test 2026-11-10: "blocked" alone did not
    // tell whether the scoreboard was read at all, or read and found unclear)
    @Volatile var lastReading = "no loading screen / scoreboard reading yet"; private set
    @Volatile var lastReadingAt = 0L; private set
    private fun reading(text: String, nowMs: Long) { lastReading = text; lastReadingAt = nowMs }
    private fun f2(v: Float) = "%.2f".format(java.util.Locale.US, v)

    fun observeScoreboard(px: IntArray, w: Int, h: Int, nowMs: Long = System.currentTimeMillis()) {
        if (!gateWatching() || nowMs - lastAt < EVERY_MS) return
        // the app's floating window / boxes are hidden on the scoreboard; read only once they are gone
        // (screen capture records them over the names - device test 2026-11-10)
        if (!com.example.mlbbresearch.controller.OverlayManager.clearOfOverlay(nowMs)) {
            reading("scoreboard seen - waiting for the floating window to hide", nowMs); return
        }
        lastAt = nowMs
        val m = matcher() ?: run { reading("scoreboard seen - the 'Computer' picture could not be loaded", nowMs); return }
        val id = GameStateTracker.matchId
        if (id == 0L) { reading("scoreboard seen - match not identified yet", nowMs); return }
        val before = session.gate.state
        val r = ScoreboardNameCheck.check(px, w, h, m)
        session.observe(id, r, nowMs)
        reading("scoreboard ${w}x$h: ${r.verdict.name.lowercase().replace('_', ' ')} - ${r.note}" +
            (if (r.selfRow >= 0) " (your row ${r.selfRow + 1})" else "") +
            "; ally " + r.ally.joinToString(" ") { f2(it) } + " | enemy " + r.enemy.joinToString(" ") { f2(it) }, nowMs)
        if (session.gate.state != before) { Diag.info(session.status(true)); GateFeedback.changed(session.gate.state) }
    }

    // THE LOADING SCREEN FROM THE PIXELS ALONE (no Gemma needed - owner 2026-11-10), at 0.8x the
    // scoreboard's word size (measured on the owner's 2712x1220 screenshots - see LoadingNameCheck):
    //  - solo practice: the enemy card's name at x 0.581, y 0.889;
    //  - 5v5: a fixed grid, one card every 0.1073 of the width; name tops at y 0.349 (top row, from
    //    x 0.0796) and y 0.889 (bottom row, from x 0.3894).
    // Only verifies; anything else leaves the match to the scoreboard.
    const val SOLO_NAME_X = 0.5808f
    const val SOLO_NAME_Y = 0.8893f
    const val NAME_SIZE = 0.8f
    private val GRID = (0 until 5).map { (0.0796f + 0.1073f * it) to 0.3492f } + (0 until 5).map { (0.3894f + 0.1073f * it) to 0.8893f }
    @Volatile private var lastLoadAt = 0L

    fun observeLoadingFrame(px: IntArray, w: Int, h: Int, nowMs: Long = System.currentTimeMillis()) {
        if (!gateWatching() || nowMs - lastLoadAt < EVERY_MS) return
        if (!com.example.mlbbresearch.controller.OverlayManager.clearOfOverlay(nowMs)) return
        lastLoadAt = nowMs
        val m = matcher() ?: return
        val id = GameStateTracker.matchId
        if (id == 0L) { reading("loading screen - match not identified yet", nowMs); return }
        if (session.gate.matchId == id && session.gate.state != PracticeGate.State.UNVERIFIED) return   // already decided
        val r = if (com.example.mlbbresearch.vision.ScreenReader.soloLoading(px, w, h))
            LoadingNameCheck.fromSolo(m.score(px, w, h, SOLO_NAME_X, SOLO_NAME_Y, sizeMul = NAME_SIZE))
        else LoadingNameCheck.fromGrid(GRID.map { (x, y) -> m.score(px, w, h, x, y, dx = 40, sizeMul = NAME_SIZE) })
        val before = session.gate.state
        session.observeLoading(id, r, nowMs)
        reading("loading screen ${w}x$h: ${r.note}", nowMs)
        if (session.gate.state != before) { Diag.info(session.status(true)); GateFeedback.changed(session.gate.state) }
    }

    fun observeLoading(reply: String?, green: Pair<Int, Int>?, myName: String, nowMs: Long = System.currentTimeMillis()) {
        if (!gateWatching()) return
        val id = GameStateTracker.matchId
        val before = session.gate.state
        val r = LoadingNameCheck.check(reply, green, myName)
        session.observeLoading(id, r, nowMs)
        reading("loading screen (Gemma): ${r.note}", nowMs)
        Diag.info(if (session.gate.state != before) session.status(true) else "Practice check, loading screen: ${r.note}")
        if (session.gate.state != before) GateFeedback.changed(session.gate.state)
    }

    // read the loading screen again while this match is not verified yet
    fun loadingCheckWanted(): Boolean {
        if (!gateWatching()) return false
        val id = GameStateTracker.matchId
        return id != 0L && (session.gate.matchId != id || session.gate.state == PracticeGate.State.UNVERIFIED)
    }

    // for R3 experiments: true only in a verified practice match, while research mode is on
    fun allowsAutonomy(): Boolean = on && session.allowsAutonomy(GameStateTracker.matchId)

    // Player mode (practice-only): may the Player act in the current match? (independent of research on/off)
    fun playerVerdict(nowMs: Long = System.currentTimeMillis()): PlayerGate.Verdict {
        val id = GameStateTracker.matchId
        session.sync(id, nowMs)
        return PlayerGate.verdict(id, session.gate.matchId, session.gate.state)
    }

    // R3: called by the play loop once the normal fight option is final. Returns the option to play.
    // Fail-closed: not allowed, or any error (which also stops the experiment for this run) -> normal.
    fun fightChoice(s: com.example.mlbbresearch.game.GameState, normal: FightOption?, brainChoice: FightOption?, netAdvice: Pair<FightOption, Float>?): FightOption? {
        if (normal == null || experimentFailed || !allowsAutonomy()) return normal
        if (arm() == ResearchAB.Arm.NORMAL) return normal       // R5: the comparison arm plays the usual order
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
            com.example.mlbbresearch.core.SessionRecorder.event(org.json.JSONObject().put("k", "arb")
                .put("normal", normal.name).put("played", r.option?.name ?: "-").put("changed", r.changed).put("note", r.note.take(100))
                .put("advice", org.json.JSONArray(advice.map { "${it.source.name.lowercase()}:${it.kind.name}" + (it.confidence?.let { c -> "@" + "%.2f".format(java.util.Locale.US, c) } ?: "") })))
            r.option
        } catch (t: Throwable) {
            experimentFailed = true
            Diag.error("Research R3 experiment stopped", t)
            normal
        }
    }

    fun status(): String {
        val now = System.currentTimeMillis()
        if (on) session.sync(GameStateTracker.matchId, now)
        val last = "\n  gate's last reading" + (if (lastReadingAt > 0) " (${(now - lastReadingAt) / 1000} s ago)" else "") + ": $lastReading"
        return statusBase() + last
    }

    private fun statusBase(): String {
        val base = session.status(on)
        if (!allowsAutonomy()) return base
        return if (experimentFailed) "$base - R3 experiment stopped after an error"
        else if (arm() == ResearchAB.Arm.NORMAL) "Research: verified practice - this match is the NORMAL arm of the comparison (usual fight choice)" +
            (if (recordFailed) "; R4 recording stopped after an error" else "; R4 recording practice moments")
        else "Research: verified practice - ARBITER arm: arbiter picks the fight option while Player mode plays (${experiment.summary()})" +
            (if (recordFailed) "; R4 recording stopped after an error" else "; R4 recording practice moments")
    }

    fun momentsText(): String {
        loadMoments()
        val mix = moments.mix().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" }
        return "Practice moments recorded: ${moments.size} from ${moments.matches} matches" + (if (mix.isNotEmpty()) " ($mix)" else "") +
            " - the readiness test needs at least 200"
    }
}
