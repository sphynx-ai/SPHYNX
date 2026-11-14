package com.example.mlbbresearch.ai

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.mlbbresearch.capture.FrameHub
import com.example.mlbbresearch.capture.ScreenCaptureService
import com.example.mlbbresearch.controller.AccessibilityController
import com.example.mlbbresearch.controller.ActionExecutor
import com.example.mlbbresearch.controller.QuickBuySwap
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.CooldownTracker
import com.example.mlbbresearch.game.GameStateTracker
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.learning.Brain
import com.example.mlbbresearch.learning.DemoRecorder
import com.example.mlbbresearch.learning.ExperienceRecorder
import com.example.mlbbresearch.learning.FightOption
import com.example.mlbbresearch.learning.ImitationPolicy
import com.example.mlbbresearch.learning.PerformanceEvaluator
import com.example.mlbbresearch.vision.FrameProcessor
import com.example.mlbbresearch.game.JungleNav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Runs the three speeds of the hybrid AI with Kotlin coroutines:
//   vision        10-15 fps   (FrameProcessor -> GameStateTracker)
//   tactics       5 per sec   (TacticalEngine -> ActionExecutor)
//   strategy      event-based (StrategicAdvisor -> Gemma 4 E2B / 3n E2B)
//   tactical LLM  in fights, ~3 per second, background (TacticalAdvisor -> small Gemma; its answer passes the Kotlin checks first)
object AiRuntime {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var playerOn = false
        private set
    @Volatile var drafterOn = false
        private set
    @Volatile var builderOn = false
        private set
    @Volatile var learnOn = false        // "Learn from me": watches you play, never touches
        private set
    @Volatile var lastError = ""
        private set

    private var visionJob: Job? = null
    private var tacticJob: Job? = null
    private var strategyJob: Job? = null

    // standard match: hero / spell / emblem chosen once per hero-select screen
    @Volatile private var stdSelectAt = 0L
    @Volatile private var stdSelectTries = 0
    @Volatile private var lastActive: TacticalDecision? = null
    @Volatile private var lastActiveAt = 0L
    // stages that were on when the floating icon paused the AI
    private var paused: List<String> = emptyList()

    init {
        GameStateTracker.onNewMatch = { stdSelectAt = 0L; stdSelectTries = 0 }
        // a 5v5 match was recognised: the automatic recording starts here (never in Solo practice), and the app
        // learns from you by itself (owner, 2026-11-13)
        com.example.mlbbresearch.game.MatchKind.onFiveVFive = {
            try {
                com.example.mlbbresearch.core.SessionRecorder.matchStarted()
                if (!learnOn && anyOn()) start("learn")
            } catch (t: Throwable) { Diag.error("Starting to learn in a 5v5 match", t) }
        }
    }

    fun anyOn() = playerOn || drafterOn || builderOn || learnOn || com.example.mlbbresearch.research.ResearchMode.on

    // Returns an error message, or null when started
    fun start(stage: String): String? {
        // learning only watches the screen: it needs no accessibility service (no touches)
        val svc = AccessibilityController.instance
        // research mode (R2) only reads the scoreboard: it needs no touches either
        if (svc == null && stage != "learn" && stage != "research") return "Turn on the accessibility service first (Setup page)"
        if (needsGemma(stage) && !GemmaEngine.ready) return "The Gemma model is not loaded (put the .litertlm file in the Sphynx-Storage folder)"
        when (stage) {
            "player" -> {
                playerOn = true
                FrameProcessor.trackMotion = true
                // ONE SWITCH (owner, 2026-11-10): the Player and research mode are started and stopped together
                if (!com.example.mlbbresearch.research.ResearchMode.on) com.example.mlbbresearch.research.ResearchMode.start()
                if (Cfg.playLikeMe) ImitationPolicy.ensureLoaded(GameStateTracker.ownHero())
            }
            "draft" -> drafterOn = true
            "build" -> builderOn = true
            "research" -> com.example.mlbbresearch.research.ResearchMode.start()
            "learn" -> {
                learnOn = true
                DemoRecorder.resetSession()
                DemoRecorder.refreshTotal()
                com.example.mlbbresearch.learning.LiveRecorder.resetSession(); com.example.mlbbresearch.learning.LiveRecorder.refreshTotal()
                FrameProcessor.trackPlayer = true
            }
            else -> return "unknown stage"
        }
        // "Learn from me" is on by default: it runs alongside whatever else is started (it
        // records only while you play, and the heroes you watch while dead)
        if (stage != "learn" && Cfg.learnFromMe && !learnOn) {
            learnOn = true
            DemoRecorder.resetSession()
            DemoRecorder.refreshTotal()
            com.example.mlbbresearch.learning.LiveRecorder.resetSession(); com.example.mlbbresearch.learning.LiveRecorder.refreshTotal()
            FrameProcessor.trackPlayer = true
        }
        lastError = ""
        svc?.overlay?.let { if (!it.userHidden) it.show() }
        ensureLoops()
        Diag.info("$stage started" + if (stage == "player" && !GemmaEngine.ready) " (rules only, no Gemma)" else "")
        ActionExecutor.note("$stage started")
        return null
    }

    // Ranked drafting reads the ban & pick screen with Gemma; standard hero select only taps
    // calibrated positions, so it works without the model.
    fun needsGemma(stage: String) = stage == "build" || stage == "draft" && Cfg.ranked

    fun stop(stage: String) {
        when (stage) {
            "player" -> {
                playerOn = false; FrameProcessor.trackMotion = false; ActionExecutor.release()
                com.example.mlbbresearch.research.ResearchMode.stop()      // one switch with the Player
            }
            "draft" -> drafterOn = false
            "build" -> builderOn = false
            "learn" -> { learnOn = false; FrameProcessor.trackPlayer = false }
            "research" -> com.example.mlbbresearch.research.ResearchMode.stop()
        }
        ActionExecutor.note("$stage stopped")
        if (!anyOn()) stopLoops()
    }

    // EMERGENCY STOP: everything off immediately, queued touches dropped, Gemma interrupted
    fun emergencyStop(reason: String) {
        val was = anyOn()
        playerOn = false
        drafterOn = false
        builderOn = false
        learnOn = false
        com.example.mlbbresearch.research.ResearchMode.stop()
        paused = emptyList()
        FrameProcessor.trackPlayer = false
        FrameProcessor.trackMotion = false
        stopLoops()
        ActionExecutor.cancelAll()
        GemmaEngine.cancel()
        if (was) {
            Diag.info("EMERGENCY STOP ($reason)")
            ActionExecutor.note("EMERGENCY STOP ($reason)")
            main.post {
                AccessibilityController.instance?.let {
                    Toast.makeText(it, "AI stopped", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Floating icon's on/off button: pause everything that runs, or resume it (Player by default)
    fun toggleFromOverlay(): String {
        if (anyOn()) {
            paused = listOfNotNull(
                if (playerOn) "player" else null, if (drafterOn) "draft" else null,
                if (builderOn) "build" else null, if (learnOn) "learn" else null,
                if (com.example.mlbbresearch.research.ResearchMode.on && !playerOn) "research" else null
            )
            for (st in paused) stop(st)
            ActionExecutor.cancelAll()
            return "AI paused"
        }
        val list = paused.ifEmpty { listOf("player") }
        val errors = list.mapNotNull { start(it) }
        paused = emptyList()
        return if (errors.isEmpty()) "AI on: " + list.joinToString(" + ") else errors.first()
    }

    private fun stopLoops() {
        Brain.saveAll()
        com.example.mlbbresearch.learning.OnlineBackup.flushLearned()
        visionJob?.cancel(); tacticJob?.cancel(); strategyJob?.cancel()
        visionJob = null; tacticJob = null; strategyJob = null
        TacticalEngine.reset()
        ComboRunner.reset()
        StrategicAdvisor.reset()
    }

    private fun ensureLoops() {
        if (visionJob?.isActive != true) visionJob = scope.launch { visionLoop() }
        if (tacticJob?.isActive != true) tacticJob = scope.launch { tacticLoop() }
        if (strategyJob?.isActive != true) strategyJob = scope.launch { strategyLoop() }
    }

    // ---------- fast: 10-15 fps ----------
    private suspend fun visionLoop() {
        var lastShot = 0L
        while (anyOn()) {
            try {
                if (!ScreenCaptureService.running) {
                    val now = System.currentTimeMillis()
                    if (now - lastShot >= 1000) {
                        lastShot = now
                        main.post { AccessibilityController.instance?.grabScreenshot() }
                    }
                }
                val v = FrameProcessor.processNext()
                com.example.mlbbresearch.core.SpeedLog.tick()
                com.example.mlbbresearch.core.TouchSpeed.tick()
                if (v != null) {
                    GameStateTracker.update(v)
                    if (learnOn && com.example.mlbbresearch.learning.LearnGate.on() && v.hint != com.example.mlbbresearch.vision.ScreenHint.LIVE && v.hint != com.example.mlbbresearch.vision.ScreenHint.LIVE_DEAD) DemoRecorder.observe(v, aiPlaying = playerOn, s = GameStateTracker.state)
                    if (learnOn && Cfg.liveLearn && com.example.mlbbresearch.learning.LearnGate.on()) com.example.mlbbresearch.learning.LiveRecorder.observe(v)
                    // Zero-latency reaction (v37.09): run the tactical decision immediately on the fresh vision frame!
                    if (playerOn && System.currentTimeMillis() - lastFastTacticAt >= 28L) {
                        lastFastTacticAt = System.currentTimeMillis()
                        runTacticOnce()
                    }
                    kotlinx.coroutines.yield()
                } else {
                    delay(8)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Diag.error("Vision loop", t)
                delay(500)
            }
        }
    }

    // ---------- medium: 5 per second ----------
    private var prevPhase = Phase.UNKNOWN
    private var lastDeathMark = 0L
    @Volatile private var lastFastTacticAt = 0L
    private val tacticLock = Any()

    private fun runTacticOnce() {
        synchronized(tacticLock) {
            try {
                val s = GameStateTracker.state
                val stale = System.currentTimeMillis() - s.ts > 1500
                // you just died: the moments you recorded in the 6 s before led to it
                if (learnOn && com.example.mlbbresearch.learning.LearnGate.on() && !stale && s.phase == Phase.DEAD && prevPhase != Phase.DEAD && prevPhase.inMatch &&
                    System.currentTimeMillis() - lastDeathMark > 10_000) {
                    lastDeathMark = System.currentTimeMillis()
                    DemoRecorder.markDeath(lastDeathMark)
                }
                if (!stale) prevPhase = s.phase
                // the buttons are found on the screen by themselves the first time (no calibration to do)
                if (!stale && (s.phase == Phase.IN_GAME && s.self != null || s.phase == Phase.DRAFT || s.phase == Phase.HERO_LOCKED))
                    com.example.mlbbresearch.game.AutoTune.autoCalibrate(s.phase)
                // the hero still plays with the big map open; shop / scoreboard pause it
                val inGame = !stale && (s.phase == Phase.IN_GAME || s.phase == Phase.MAP)
                // Player mode is practice-only (owner, 2026-11-10): it acts only in a verified practice
                // match vs Computer; otherwise nothing is sent (the thumb is lifted once)
                val gate = if (playerOn && inGame) com.example.mlbbresearch.research.ResearchMode.playerVerdict() else null
                playerGate = gate
                if (gate != null && gate != com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY) {
                    if (!playerHeld) { ActionExecutor.release(); playerHeld = true; Diag.info("Player advises only (you play): ${gate.label}") }
                    // COMPANION ADVICE (owner, 2026-11-10): in Ranked / Classic / any unverified match the strategist and
                    // the tactical advisor keep working and their advice is shown - nothing is touched
                    advise(s)
                } else if (playerOn && inGame) {
                    advice = "-"
                    if (playerHeld) { playerHeld = false; Diag.info("Player plays: verified practice vs Computer") }
                    val tDecide = System.currentTimeMillis()
                    val hero = s.ownHero
                    // what Coach learned from you (e.g. your wave-clear skill) on top of the hero's profile
                    val profile = com.example.mlbbresearch.learning.Coach.adjust(HeroDb.profile(hero), hero)
                    if (Cfg.coachMode && com.example.mlbbresearch.learning.LearnGate.on()) com.example.mlbbresearch.learning.Coach.tick(s)
                    val plan = StrategicAdvisor.plan
                    val bias = Cfg.learnedBias(hero ?: "unknown")
                    // SCENERY CALIBRATION (owner, 2026-11-11): while the tour runs the Player does not fight -
                    // no fight choice, no combos, no skills: it only walks the tour (TacticalEngine.calibrationStep)
                    val calibrating = com.example.mlbbresearch.game.SceneryMap.roamActive
                    // the learning brain picks how to handle a nearby enemy; the engine plays it
                    var option = if (calibrating) null else Brain.choose(s)
                    val brainOption = option
                    // the small tactical model (if on): its advice, once the Kotlin checks allow it
                    TacticalAdvisor.tick(s, profile)
                    // the fight-choice network (if on) advises first - it answers at once; the small
                    // model's advice is used when the network is off. Both pass the same Kotlin checks.
                    val netRaw = if (option != null && Cfg.fightNetOn && com.example.mlbbresearch.learning.FightNet.usable)
                        com.example.mlbbresearch.learning.FightNet.advise(TacticalAdvisor.summary(s, profile))?.takeIf { it.second >= 0.4f }
                            ?.takeIf { TacticalAdvisor.check(it.first, s) == null } else null
                    val netAdvice = netRaw?.first
                    if (netAdvice != null) { if (netAdvice != option) { Brain.adopt(s, netAdvice); option = netAdvice } }
                    else if (option != null) TacticalAdvisor.validated(s, profile)?.let { a ->
                        if (a != option) { Brain.adopt(s, a); option = a }
                    }
                    // research R3 (verified practice vs Computer only, research mode on): the decision
                    // arbiter picks the fight option; anywhere else this returns the option unchanged
                    com.example.mlbbresearch.research.ResearchMode.fightChoice(s, option, brainOption, netRaw)?.let { r ->
                        if (r != option) { Brain.adopt(s, r); option = r }
                    }
                    // logging mode only: records every engine's advice next to what is played (never changes it)
                    com.example.mlbbresearch.engines.DecisionLogging.observe(s, brainOption, option)
                    // research R4 (verified practice only): records this fight moment for the small-model test
                    if (com.example.mlbbresearch.learning.LearnGate.on()) com.example.mlbbresearch.research.ResearchMode.recordMoment(s, profile, option)
                    var d = if (calibrating) TacticalEngine.calibrationStep(s, profile)
                        else TacticalEngine.decide(s, profile, plan, bias, option)
                    // fights: play the hero's combo in order instead of pressing everything at once
                    if (!calibrating) d = ComboRunner.apply(d, s, profile)
                    // "Play like me": your style mixed into the AI's decision (it keeps control of the
                    // joystick and skills); the survival rules still come first
                    // the trained tactical network (if loaded) learned your play; without it, the
                    // nearest recorded moments of yours are used
                    // (a network trained for another hero is not used: that hero's recorded moments are)
                    if (!calibrating && !ComboRunner.running) {
                        if (Cfg.policyOn && com.example.mlbbresearch.learning.PolicyNet.usableFor(s.ownHero)) {
                            d = com.example.mlbbresearch.learning.PolicyNet.blend(d, s, profile)
                        } else if (Cfg.playLikeMe) {
                            ImitationPolicy.ensureLoaded(hero)
                            val brainFight = option != null && option != FightOption.RULES
                            if (!brainFight && d.intent != Intent.RETREAT && d.intent != Intent.RECALL && d.intent != Intent.REGEN) {
                                d = ImitationPolicy.blend(d, s)
                            }
                        }
                    }
                    d = TacticalEngine.filterCasts(d, s, profile)
                    // no freezing: your HP bar can vanish for a moment (effects, animations); keep the
                    // last movement going for up to 0.6 s instead of stopping
                    val tNow = System.currentTimeMillis()
                    benchIntent = d.intent; benchIdle = d.intent == Intent.IDLE
                    // NO IDLE in a match: when the engine has nothing (your bar hidden for a moment),
                    // the hero keeps walking the last way for up to 3 s, then heads for the enemy base
                    if (d.intent == Intent.IDLE) {
                        val l = lastActive
                        d = if (l != null && tNow - lastActiveAt < 3000 && (l.moveX != 0f || l.moveY != 0f))
                            TacticalDecision(l.intent, l.moveX, l.moveY, emptyList(), -1f, -1f, "-", -1f, -1, l.reason + " (continuing)")
                        else TacticalEngine.fallbackMove(s)
                    } else {
                        lastActive = d; lastActiveAt = tNow
                    }
                    // think ahead: the next task is worked out while this one runs
                    TacticalEngine.planAhead(s, d, profile)
                    com.example.mlbbresearch.core.SessionRecorder.frame(s, d)
                    benchMoving = d.moveX != 0f || d.moveY != 0f
                    // never into the enemy fountain, whatever the mode (owner, 2026-11-11) - applied last
                    d = FountainGuard.apply(s, d)
                    ActionExecutor.execute(d)
                    // screen-to-touch recorder: from the start of reading this frame to its touches being queued
                    com.example.mlbbresearch.core.TouchSpeed.log.frameToDecision(System.currentTimeMillis() - s.ts)
                    com.example.mlbbresearch.core.Speed.decided((tDecide - s.ts - com.example.mlbbresearch.core.Speed.vision.toLong()).coerceAtLeast(0L), System.currentTimeMillis() - tDecide)
                    if (com.example.mlbbresearch.learning.LearnGate.on()) ExperienceRecorder.record(s, d, plan.plan, plan.aggression + bias)
                    ActionExecutor.maybeUpgrade(s)
                    if (Cfg.playerQuickBuy) ActionExecutor.maybeQuickBuy(s)
                } else if (playerOn && !stale && !s.phase.inMatch) {
                    // out of the match only (lobby, result): lift the thumb. Shop / scoreboard /
                    // dead / a late frame: nothing is sent, the thumb stays where it is
                    ActionExecutor.execute(TacticalDecision.IDLE)
                } else if (playerOn && !stale && (s.phase == Phase.SHOP || s.phase == Phase.SCOREBOARD || s.phase == Phase.DEAD)) {
                    ActionExecutor.release()
                }
                // settings the app learns by itself (tower range, base-to-lane time)
                if (inGame) com.example.mlbbresearch.game.AutoTune.observe(s)
                // Player off and you play: everything you do is yours (combos, cast distance, joystick)
                // (no learning at all in Solo practice - learning.LearnGate)
                val learns = learnOn && com.example.mlbbresearch.learning.LearnGate.on()
                if (!playerOn && learns && inGame) com.example.mlbbresearch.learning.Coach.tick(s, youPlay = true)
                // your moments: whenever you play yourself, also while helping the AI (coach)
                if (learns && inGame && (!playerOn || com.example.mlbbresearch.learning.Coach.userActive())) DemoRecorder.record(s)
                // (learning from the heroes you watch while dead was removed at your request)
                // watching another player's live match: their play, recorded like a match of yours
                if (learns && Cfg.liveLearn && !stale && s.phase == Phase.LIVE) com.example.mlbbresearch.learning.LiveRecorder.record(s)
                if (builderOn && inGame) {
                    if (Cfg.qbOn) QuickBuySwap.watch(s)
                    // item actives: the Builder presses them in every match the Player does not play (owner, 2026-11-10)
                    if (!stale && com.example.mlbbresearch.research.PlayerGate.builderPressesActives(Cfg.builderActives, playerOn, playerGate)) {
                        val d = adviceDecision?.takeIf { System.currentTimeMillis() - adviceDecisionAt < 1500 }
                        ActionExecutor.builderActive(s, d)
                    }
                    // the Builder leaves buying to the Player only while the Player really plays (verified practice);
                    // in Ranked / Classic the Player only advises, so the Builder keeps buying (owner, 2026-11-10)
                    val playerBuys = playerOn && Cfg.playerQuickBuy && playerGate == com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY
                    if (Cfg.autonomous && drafterOn && !playerBuys) ActionExecutor.maybeQuickBuy(s, blind = true)
                }
                if (drafterOn && !Cfg.ranked && Cfg.autonomous && !stale) standardSelect(s)
                // PRACTICE BENCHMARK: measures the Player in verified practice (reads only, never acts)
                if (playerOn && !stale && s.phase.inMatch) {
                    val playing = inGame && gate == com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY
                    val nowB = System.currentTimeMillis()
                    val enemiesNear = s.enemiesWithin(0.9f).size
                    val alliesNear = 1 + s.allies.count { it.hero && s.distToSelf(it.x, it.y) <= 0.9f }
                    if (com.example.mlbbresearch.learning.LearnGate.on()) com.example.mlbbresearch.research.ResearchMode.benchTick(com.example.mlbbresearch.game.GameStateTracker.matchId, nowB,
                        gate ?: com.example.mlbbresearch.research.ResearchMode.playerVerdict(nowB), inGame, playing,
                        if (playing) benchIntent?.name else null, playing && benchIdle,
                        playing && benchMoving && nowB - com.example.mlbbresearch.game.GameStateTracker.lastMotionAt > 1000,
                        s.matchSec, s.level, com.example.mlbbresearch.game.GameStateTracker.lastDeathAt, enemiesNear > alliesNear)
                    if (!playing) { benchIntent = null; benchIdle = false; benchMoving = false }
                }
                PerformanceEvaluator.evaluateDue()
                if (!stale) Brain.evaluateDue(s)
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Diag.error("Tactical loop", t)
            }
        }
    }

    private suspend fun tacticLoop() {
        while (anyOn()) {
            try {
                if (System.currentTimeMillis() - lastFastTacticAt >= 45L) {
                    lastFastTacticAt = System.currentTimeMillis()
                    runTacticOnce()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {}
            delay(25)
        }
    }

    // Standard match: no bans, so just pick the hero (and optionally spell / emblem) and confirm,
    // once the hero-select screen has been up for 2.5 s. One retry if it is still there 20 s later.
    private fun standardSelect(s: com.example.mlbbresearch.game.GameState) {
        if (s.phase != Phase.DRAFT) return
        val now = System.currentTimeMillis()
        if (now - GameStateTracker.phaseSince < 2500) return
        if (stdSelectTries >= 2 || stdSelectAt != 0L && now - stdSelectAt < 20_000) return
        stdSelectAt = now
        stdSelectTries++
        val hero = HeroDb.match(Cfg.myHero)
        ActionExecutor.standardSelect(hero)
        // (the hero the AI picks is not taken as known any more: the loading screen tells it - v36.82)
    }

    // ---------- slow: Gemma, event-driven ----------
    private suspend fun strategyLoop() {
        while (anyOn()) {
            try {
                // keep the patch meta and the online meta of the hero you play fresh (Groq, in the background)
                MetaAdvisor.autoGeneral()
                MetaAdvisor.autoUpdate(GameStateTracker.ownHero())
                // and learn its skills and passive once (descriptions from the web)
                com.example.mlbbresearch.game.SkillBook.autoFetch(GameStateTracker.ownHero())
                // and the enemy heroes' skills (what they can do to you), one a minute
                com.example.mlbbresearch.game.EnemyIntel.learnEnemies()
                StrategicAdvisor.tick(GameStateTracker.state, playerOn, drafterOn, builderOn)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Diag.error("Strategy loop", t)
            }
            delay(500)
        }
    }

    // ---------- status for the screens ----------

    @Volatile var playerGate: com.example.mlbbresearch.research.PlayerGate.Verdict? = null
        private set
    // the advice shown while you play yourself (any match that is not verified practice)
    @Volatile var advice: String = "-"
        private set
    private var adviceAt = 0L

    // Advice only: the same Kotlin engine decision the Player would play, plus the small tactical model's
    // advice and Gemma's plan - shown, never executed. The learning parts that assume the AI played the
    // move (Brain choice credit, combos, recorded AI moments) are left out, so your own play is not
    // mixed into the AI's learning.
    private fun advise(s: com.example.mlbbresearch.game.GameState) {
        val now = System.currentTimeMillis()
        if (now - adviceAt < 400L) return
        adviceAt = now
        try {
            val hero = s.ownHero
            val profile = com.example.mlbbresearch.learning.Coach.adjust(HeroDb.profile(hero), hero)
            TacticalAdvisor.tick(s, profile)
            val d = TacticalEngine.decide(s, profile, StrategicAdvisor.plan, Cfg.learnedBias(hero ?: "unknown"), null)
            val small = TacticalAdvisor.latestAdvice()?.takeIf { now - it.second < 1500 }?.first
            // skill leveling is advice only here: no tap (maybeUpgrade stays in the practice play loop)
            val skills = com.example.mlbbresearch.game.SkillAdvice.line(
                com.example.mlbbresearch.game.SkillOrder.of(hero), s.hud.upgradeReady)
            adviceDecision = d; adviceDecisionAt = now
            advice = d.describe() + (small?.let { " | small model: ${it.label}" } ?: "") + "\n  " + skills
        } catch (t: Throwable) {
            advice = "-"
            Diag.error("Advice", t)
        }
    }
    @Volatile private var playerHeld = false
    // what the play loop decided this tick, for the practice benchmark
    @Volatile private var benchIntent: Intent? = null
    @Volatile private var benchIdle = false
    @Volatile private var benchMoving = false
    @Volatile private var adviceDecision: TacticalDecision? = null
    @Volatile private var adviceDecisionAt = 0L

    // what runs, in one line (owner, 2026-11-11): the Player and research mode are ONE switch, "Research
    // player"; "Learning from you" is named only while coach mode is on
    fun statusLine(): String = when {
        !anyOn() -> "Stopped"
        else -> "Running: " + listOfNotNull(
            if (playerOn) "Research player" else null,
            if (!playerOn && com.example.mlbbresearch.research.ResearchMode.on) "Research (practice check)" else null,
            if (drafterOn) (if (Cfg.ranked) "Drafter (ranked)" else "Drafter (standard)") else null,
            if (builderOn) "Builder" else null,
            if (learnOn && com.example.mlbbresearch.learning.LearnGate.on()) "Learning from you" else null
        ).joinToString(" + ") + if (Cfg.autonomous && drafterOn && builderOn) " (autonomous${if (Cfg.dryRun) ", dry run" else ""})" else ""
    }

    // THE FLOATING PANEL (owner, 2026-11-11: "optimize what information is shown"): what runs, the practice
    // check, what the Player does now, the match in one line, then only the sections that are on.
    // Every line is one row of the panel; long ones are cut at its edge.
    fun panelText(): String {
        val s = GameStateTracker.state
        val sb = StringBuilder()
        sb.append(statusLine()).append('\n')
        val calib = com.example.mlbbresearch.game.SceneryMap
        if (playerOn || com.example.mlbbresearch.research.ResearchMode.on) {
            // (the play loop reads the verdict only in play: on the loading screen / scoreboard it is read here)
            val g = playerGate ?: if (GameStateTracker.matchId != 0L) com.example.mlbbresearch.research.ResearchMode.playerVerdict() else null
            sb.append("Practice check: ").append(when (g) {
                null -> "waiting for the match"
                com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY -> "✓ unlocked - the Player plays"
                else -> g.label
            }).append('\n')
            sb.append("  last reading: ").append(com.example.mlbbresearch.research.ResearchMode.lastReadingShort()).append('\n')
            if (calib.roamActive) sb.append("Scenery calibration: ").append(calib.status).append(" (no fighting)\n")
            if (playerOn) {
                if (g == com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY) {
                    sb.append("Now: ").append(ActionExecutor.lastDecision).append('\n')
                    sb.append("Target: ").append(ActionExecutor.lastTarget).append(" | next: ").append(TacticalEngine.nextPlan).append('\n')
                } else if (g != null) sb.append("Advice (you play): ").append(advice.replace('\n', ' ')).append('\n')
            }
        }
        // the match, in one line each
        sb.append("Match: ").append(s.phase.label)
        if (s.selfHp >= 0) sb.append(" | HP ").append((s.selfHp * 100).toInt()).append('%')
        if (s.self != null) sb.append(" | level ").append(s.level)
        sb.append(" | ").append(s.lane.label)
        com.example.mlbbresearch.game.MapSide.goldTop?.let { sb.append(if (it) " (Gold lane on top)" else " (EXP lane on top)") }
        com.example.mlbbresearch.game.MatchKind.current().takeIf { it != com.example.mlbbresearch.game.MatchKind.Kind.UNKNOWN }?.let { sb.append(" | ").append(it.label) }
        sb.append("\nHero: ").append(GameStateTracker.verifiedHeroPanel()).append(" | spell: ").append(GameStateTracker.verifiedSpellPanel())
        if (s.phase.inMatch) sb.append("\nBuffs held: ").append(com.example.mlbbresearch.vision.BuffEmblems.text())
        sb.append("\nGemma: ").append(GemmaEngine.state)
        if (StrategicAdvisor.busyWith.isNotEmpty()) sb.append(" (").append(StrategicAdvisor.busyWith).append(")")
        sb.append(" | capture ").append("%.0f".format(FrameProcessor.processedFps)).append(" fps")
        com.example.mlbbresearch.core.PhoneState.read().let { ph ->
            ph.batteryC?.let { sb.append(" | ").append("%.0f".format(it)).append("°C") }
            ph.thermal?.takeIf { it >= 2 }?.let { sb.append(" HOT") }
        }
        if (playerOn && playerGate == com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY) {
            val p = StrategicAdvisor.plan
            sb.append("\nPlan: ").append(p.plan).append(" | state: ").append(Utility.state.name.lowercase())
                .append(" (survival ").append(Utility.lastSurvival.toInt()).append(")")
            if (Cfg.useCombos) sb.append("\nCombo: ").append(ComboRunner.status)
        }
        // LEARNING FROM YOU (owner, 2026-11-13): by itself in a 5v5 match while the Research player is off or only
        // advises; never in Solo practice. Always shown, with the reason when nothing is learned.
        if (learnOn) {
            val kind = com.example.mlbbresearch.game.MatchKind.current()
            val plays = playerOn && playerGate == com.example.mlbbresearch.research.PlayerGate.Verdict.PLAY
            sb.append("\n\n").append(com.example.mlbbresearch.learning.LearnGate.panelTitle(kind, s.phase.inMatch, plays))
            if (com.example.mlbbresearch.learning.LearnGate.learns(kind)) {
                sb.append("\nYou: ").append(DemoRecorder.lastAction)
                sb.append("\nSaved: ").append(DemoRecorder.recorded).append(" this session, ").append(DemoRecorder.total).append(" total")
                sb.append("\nCoach: ").append(com.example.mlbbresearch.learning.Coach.summary(s.ownHero))
            }
        }
        if (drafterOn && StrategicAdvisor.draftText.isNotEmpty()) sb.append("\n\nDRAFT\n").append(StrategicAdvisor.draftText)
        if (builderOn) {
            sb.append("\n\nBUILD\n").append(StrategicAdvisor.buildSummary().take(600))
            if (Cfg.qbOn) sb.append("\nQuick buy: ").append(QuickBuySwap.status)
        }
        if (lastError.isNotEmpty()) sb.append("\n\nError: ").append(lastError)
        return sb.toString()
    }

    fun resetCooldowns() = CooldownTracker.reset()
}
