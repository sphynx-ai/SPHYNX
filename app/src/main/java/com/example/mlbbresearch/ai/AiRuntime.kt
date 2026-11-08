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
//   tactical LLM  in fights, ~3 per second, background (TacticalAdvisor -> small Gemma; advice only)
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
    }

    fun anyOn() = playerOn || drafterOn || builderOn || learnOn || com.example.mlbbresearch.research.ResearchMode.on

    // Returns an error message, or null when started
    fun start(stage: String): String? {
        // learning only watches the screen: it needs no accessibility service (no touches)
        val svc = AccessibilityController.instance
        // research mode (R2) only reads the scoreboard: it needs no touches either
        if (svc == null && stage != "learn" && stage != "research") return "Turn on the accessibility service first (Setup page)"
        if (needsGemma(stage) && !GemmaEngine.ready) return "The Gemma model is not loaded (put the .litertlm file in the Sphynx-Auto-Pilot folder)"
        when (stage) {
            "player" -> {
                playerOn = true
                FrameProcessor.trackMotion = true
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
            "player" -> { playerOn = false; FrameProcessor.trackMotion = false; ActionExecutor.release() }
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
                if (com.example.mlbbresearch.research.ResearchMode.on) "research" else null
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
                if (v != null) {
                    GameStateTracker.update(v)
                    if (learnOn && v.hint != com.example.mlbbresearch.vision.ScreenHint.LIVE && v.hint != com.example.mlbbresearch.vision.ScreenHint.LIVE_DEAD) DemoRecorder.observe(v, aiPlaying = playerOn, s = GameStateTracker.state)
                    if (learnOn && Cfg.liveLearn) com.example.mlbbresearch.learning.LiveRecorder.observe(v)
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
                if (learnOn && !stale && s.phase == Phase.DEAD && prevPhase != Phase.DEAD && prevPhase.inMatch &&
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
                if (playerOn && inGame) {
                    val tDecide = System.currentTimeMillis()
                    val hero = s.ownHero
                    // what Coach learned from you (e.g. your wave-clear skill) on top of the hero's profile
                    val profile = com.example.mlbbresearch.learning.Coach.adjust(HeroDb.profile(hero), hero)
                    if (Cfg.coachMode) com.example.mlbbresearch.learning.Coach.tick(s)
                    val plan = StrategicAdvisor.plan
                    val bias = Cfg.learnedBias(hero ?: "unknown")
                    // the learning brain picks how to handle a nearby enemy; the engine plays it
                    var option = Brain.choose(s)
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
                    com.example.mlbbresearch.research.ResearchMode.recordMoment(s, profile, option)
                    var d = TacticalEngine.decide(s, profile, plan, bias, option)
                    // fights: play the hero's combo in order instead of pressing everything at once
                    d = ComboRunner.apply(d, s, profile)
                    // "Play like me": your style mixed into the AI's decision (it keeps control of the
                    // joystick and skills); the survival rules still come first
                    // the trained tactical network (if loaded) learned your play; without it, the
                    // nearest recorded moments of yours are used
                    // (a network trained for another hero is not used: that hero's recorded moments are)
                    if (!ComboRunner.running) {
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
                    ActionExecutor.execute(d)
                    com.example.mlbbresearch.core.Speed.decided((tDecide - s.ts - com.example.mlbbresearch.core.Speed.vision.toLong()).coerceAtLeast(0L), System.currentTimeMillis() - tDecide)
                    ExperienceRecorder.record(s, d, plan.plan, plan.aggression + bias)
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
                if (!playerOn && learnOn && inGame) com.example.mlbbresearch.learning.Coach.tick(s, youPlay = true)
                // your moments: whenever you play yourself, also while helping the AI (coach)
                if (learnOn && inGame && (!playerOn || com.example.mlbbresearch.learning.Coach.userActive())) DemoRecorder.record(s)
                // (learning from the heroes you watch while dead was removed at your request)
                // watching another player's live match: their play, recorded like a match of yours
                if (learnOn && Cfg.liveLearn && !stale && s.phase == Phase.LIVE) com.example.mlbbresearch.learning.LiveRecorder.record(s)
                if (builderOn && inGame) {
                    if (Cfg.qbOn) QuickBuySwap.watch(s)
                    if (Cfg.autonomous && drafterOn && !(playerOn && Cfg.playerQuickBuy)) ActionExecutor.maybeQuickBuy(s, blind = true)
                }
                if (drafterOn && !Cfg.ranked && Cfg.autonomous && !stale) standardSelect(s)
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

    fun statusLine(): String = when {
        !anyOn() -> "Stopped"
        else -> "Running: " + listOfNotNull(
            if (playerOn) (if (Cfg.coachMode) "Player (coach)" else "Player") else null,
            if (drafterOn) (if (Cfg.ranked) "Drafter (ranked)" else "Drafter (standard)") else null,
            if (builderOn) "Builder" else null,
            if (learnOn) "Learning from you" else null,
            if (com.example.mlbbresearch.research.ResearchMode.on) "Research (practice gate)" else null
        ).joinToString(" + ") + if (Cfg.autonomous && drafterOn && builderOn) " (autonomous${if (Cfg.dryRun) ", dry run" else ""})" else ""
    }

    fun panelText(): String {
        val s = GameStateTracker.state
        val sb = StringBuilder()
        sb.append(statusLine()).append('\n')
        if (com.example.mlbbresearch.research.ResearchMode.on) sb.append(com.example.mlbbresearch.research.ResearchMode.status()).append('\n')
        sb.append("Gemma: ").append(GemmaEngine.state)
        if (StrategicAdvisor.busyWith.isNotEmpty()) sb.append(" (thinking: ").append(StrategicAdvisor.busyWith).append(")")
        sb.append('\n')
        sb.append("Capture: ").append(FrameHub.source).append(", ").append("%.0f".format(FrameProcessor.processedFps)).append(" fps\n")
        sb.append("Match: ").append(if (Cfg.ranked) "ranked" else "standard")
        sb.append(" | Phase: ").append(s.phase.label)
        if (s.selfHp >= 0) sb.append(" | my HP ").append((s.selfHp * 100).toInt()).append('%')
        if (s.self != null) sb.append(" | level ").append(s.level).append(if (s.xp >= 0f) " (EXP ${(s.xp * 100).toInt()}%)" else "")
        // Exact sequence requested in v37.01, with ✓ when verified:
        sb.append("\nName: ").append(GameStateTracker.verifiedNamePanel())
        sb.append("\nHero: ").append(GameStateTracker.verifiedHeroPanel())
        sb.append("\nSpell: ").append(GameStateTracker.verifiedSpellPanel())
        val loadName = GameStateTracker.loadingCardName?.let { "✓ $it" }
            ?: (if (GameStateTracker.loadingGreen != null) "✓ ${com.example.mlbbresearch.game.PlayerName.get().ifBlank { "green name" }}" else "-")
        val loadHero = GameStateTracker.loadingHero?.let { "✓ $it" } ?: "-"
        val loadSpell = GameStateTracker.loadingSpell?.let { "✓ $it" }
            ?: GameStateTracker.gemmaLoadingSpell
            ?: "-"
        val loadHeader = if (GameStateTracker.loadingCardName != null || GameStateTracker.loadingHero != null || GameStateTracker.loadingSpell != null || GameStateTracker.gemmaLoadingSpell != null)
            "✓ card read"
        else if (com.example.mlbbresearch.game.PlayerName.get().isBlank() && GameStateTracker.loadingGreen == null)
            "enter your player name to read it"
        else "not read yet"
        sb.append("\nLoading screen: ").append(loadHeader)
        sb.append("\n     1. Name- ").append(loadName)
        sb.append("\n     2. Hero- ").append(loadHero)
        sb.append("\n     3. Spell- ").append(loadSpell)
        sb.append("\nDead: ").append(com.example.mlbbresearch.vision.DeathTimers.text())
        sb.append("\n").append(com.example.mlbbresearch.engines.DecisionLogging.log.summary())
        sb.append("\nPortraits: scoreboard ").append(com.example.mlbbresearch.vision.PortraitMatch.scoreboardStatus)
            .append(" · minimap ").append(com.example.mlbbresearch.vision.PortraitMatch.minimapStatus)
        sb.append("\nMatch end: ").append(com.example.mlbbresearch.vision.EndBoard.status)
        if (playerOn) {
            sb.append("\n\nDecision: ").append(ActionExecutor.lastDecision)
            sb.append("\nTarget: ").append(ActionExecutor.lastTarget)
            sb.append("\nNext: ").append(TacticalEngine.nextPlan)
            sb.append("\nGame: ").append(com.example.mlbbresearch.game.MatchSense.status).append(" | role: ")
                .append(s.lane.label).append(" - ").append(com.example.mlbbresearch.game.RoleProfile.of(s.lane).focus)
            sb.append("\nTimeline: ").append(com.example.mlbbresearch.game.MatchEvents.status(s.matchSec))
            sb.append("\nMap: ").append(com.example.mlbbresearch.game.MapIntel.status)
            sb.append("\nNetwork: ").append(if (!com.example.mlbbresearch.learning.PolicyNet.ready) "-" else com.example.mlbbresearch.learning.PolicyNet.last +
                " | agrees with the rules " + (com.example.mlbbresearch.learning.PolicyNet.agreeRate * 100).toInt() + "%")
            sb.append("\nState: ").append(Utility.state.name.lowercase()).append(" | survival ").append(Utility.lastSurvival.toInt())
                .append(" | engage ").append(Utility.lastEngage.toInt()).append(" (").append(Utility.lastWhy).append(")")
            val p = StrategicAdvisor.plan
            sb.append("\nPlan: ").append(p.plan).append(" (").append("%.1f".format(p.aggression)).append(")")
            sb.append("\nLane: ").append(s.lane.label).append(" | spell: ").append(GameStateTracker.spellDisplay() ?: s.spell ?: "?")
            sb.append("\nCombo: ").append(if (Cfg.useCombos) ComboRunner.status else "-")
            sb.append("\nBrain: ").append(if (Cfg.smartFights) Brain.lastChoice else "-")
            sb.append("\nObjective in sight: ").append(s.boss?.let { "HP ${(it.hp * 100).toInt()}%" } ?: "-")
            sb.append("\nCan upgrade: ").append(s.hud.upgradeReady.joinToString().ifBlank { "-" })
            sb.append("\nCoach: ").append(if (Cfg.coachMode) com.example.mlbbresearch.learning.Coach.summary(s.ownHero) + " | like you: " + ImitationPolicy.status else "-")
        }
        if (learnOn) {
            sb.append("\n\nLEARNING FROM YOU").append(if (playerOn) " (paused: Player is on)" else "")
            sb.append("\nYou: ").append(DemoRecorder.lastAction)
            sb.append("\nSaved: ").append(DemoRecorder.recorded).append(" this session, ").append(DemoRecorder.total).append(" total")
            sb.append(" | from watched heroes: ").append(DemoRecorder.watched)
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
