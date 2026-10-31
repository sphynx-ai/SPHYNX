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
//   strategy      event-based (StrategicAdvisor -> Gemma 3n E2B)
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

    fun anyOn() = playerOn || drafterOn || builderOn || learnOn

    // Returns an error message, or null when started
    fun start(stage: String): String? {
        // learning only watches the screen: it needs no accessibility service (no touches)
        val svc = AccessibilityController.instance
        if (svc == null && stage != "learn") return "Turn on the accessibility service first (Setup page)"
        if (needsGemma(stage) && !GemmaEngine.ready) return "Load the Gemma model first (Model page)"
        when (stage) {
            "player" -> {
                playerOn = true
                FrameProcessor.trackMotion = true
                if (Cfg.playLikeMe) ImitationPolicy.ensureLoaded(GameStateTracker.ownHero())
            }
            "draft" -> drafterOn = true
            "build" -> builderOn = true
            "learn" -> {
                learnOn = true
                DemoRecorder.resetSession()
                DemoRecorder.refreshTotal()
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
            FrameProcessor.trackPlayer = true
        }
        lastError = ""
        svc?.overlay?.show()
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
                if (builderOn) "build" else null, if (learnOn) "learn" else null
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
                if (v != null) {
                    GameStateTracker.update(v)
                    if (learnOn) DemoRecorder.observe(v, aiPlaying = playerOn, s = GameStateTracker.state)
                    kotlinx.coroutines.yield()
                } else {
                    delay(15)
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
    private suspend fun tacticLoop() {
        while (anyOn()) {
            try {
                val s = GameStateTracker.state
                val stale = System.currentTimeMillis() - s.ts > 1500
                // the hero still plays with the big map open; shop / scoreboard pause it
                val inGame = !stale && (s.phase == Phase.IN_GAME || s.phase == Phase.MAP)
                if (playerOn && inGame) {
                    val hero = s.ownHero
                    // what Coach learned from you (e.g. your wave-clear skill) on top of the hero's profile
                    val profile = com.example.mlbbresearch.learning.Coach.adjust(HeroDb.profile(hero), hero)
                    if (Cfg.coachMode) com.example.mlbbresearch.learning.Coach.tick(s)
                    val plan = StrategicAdvisor.plan
                    val bias = Cfg.learnedBias(hero ?: "unknown")
                    // the learning brain picks how to handle a nearby enemy; the engine plays it
                    val option = Brain.choose(s)
                    var d = TacticalEngine.decide(s, profile, plan, bias, option)
                    // fights: play the hero's combo in order instead of pressing everything at once
                    d = ComboRunner.apply(d, s, profile)
                    // "Play like me": your style mixed into the AI's decision (it keeps control of the
                    // joystick and skills); the survival rules still come first
                    if (Cfg.playLikeMe) {
                        ImitationPolicy.ensureLoaded(hero)
                        val brainFight = option != null && option != FightOption.RULES
                        if (!brainFight && d.intent != Intent.RETREAT && d.intent != Intent.RECALL && d.intent != Intent.REGEN) {
                            d = ImitationPolicy.blend(d, s)
                        }
                    }
                    d = TacticalEngine.filterCasts(d, s, profile)
                    // no freezing: your HP bar can vanish for a moment (effects, animations); keep the
                    // last movement going for up to 0.6 s instead of stopping
                    val tNow = System.currentTimeMillis()
                    if (d.intent == Intent.IDLE && lastActive != null && tNow - lastActiveAt < 600) {
                        val l = lastActive!!
                        d = TacticalDecision(l.intent, l.moveX, l.moveY, emptyList(), -1f, -1f, "-", -1f, -1, l.reason + " (continuing)")
                    } else if (d.intent != Intent.IDLE) {
                        lastActive = d; lastActiveAt = tNow
                    }
                    // think ahead: the next task is worked out while this one runs
                    TacticalEngine.planAhead(s, d, profile)
                    ActionExecutor.execute(d)
                    ExperienceRecorder.record(s, d, plan.plan, plan.aggression + bias)
                    ActionExecutor.maybeUpgrade(s)
                    if (Cfg.playerQuickBuy) ActionExecutor.maybeQuickBuy(s)
                } else if (playerOn) {
                    ActionExecutor.execute(TacticalDecision.IDLE)
                }
                // Player off and you play: everything you do is yours (combos, cast distance, joystick)
                if (!playerOn && learnOn && inGame) com.example.mlbbresearch.learning.Coach.tick(s, youPlay = true)
                // your moments: whenever you play yourself, also while helping the AI (coach)
                if (learnOn && inGame && (!playerOn || com.example.mlbbresearch.learning.Coach.userActive())) DemoRecorder.record(s)
                // learn from other heroes too: the ally you watch while dead, enemies on screen
                if (learnOn && !stale && s.phase != Phase.SHOP && s.phase != Phase.SCOREBOARD && s.phase != Phase.MAP &&
                    s.phase != Phase.DRAFT && s.phase != Phase.LOADING) DemoRecorder.recordWatched(s)
                if (builderOn && inGame) {
                    if (Cfg.qbOn) QuickBuySwap.watch(s)
                    if (Cfg.autonomous && drafterOn && !(playerOn && Cfg.playerQuickBuy)) ActionExecutor.maybeQuickBuy(s, blind = true)
                }
                if (drafterOn && !Cfg.ranked && Cfg.autonomous && !stale) standardSelect(s)
                PerformanceEvaluator.evaluateDue()
                if (!stale) Brain.evaluateDue(s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Diag.error("Tactical loop", t)
            }
            // Fast mode: decide again as soon as vision has a new frame (up to ~25 per second),
            // instead of every 200 ms. Balanced mode keeps 5 per second (less battery).
            if (Cfg.fastMode) {
                val seenTs = GameStateTracker.state.ts
                val start = System.currentTimeMillis()
                delay(40)
                while (GameStateTracker.state.ts == seenTs && System.currentTimeMillis() - start < 150) delay(5)
            } else delay(200)
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
        // the AI picked it, so this match's hero is known (and its look gets learned)
        if (!Cfg.dryRun && Cfg.stdPickHero && hero != null) GameStateTracker.draftedHero = hero
    }

    // ---------- slow: Gemma, event-driven ----------
    private suspend fun strategyLoop() {
        while (anyOn()) {
            try {
                // keep the online meta of the hero you play fresh (Groq, in the background)
                MetaAdvisor.autoUpdate(GameStateTracker.ownHero())
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
            if (learnOn) "Learning from you" else null
        ).joinToString(" + ") + if (Cfg.autonomous && drafterOn && builderOn) " (autonomous${if (Cfg.dryRun) ", dry run" else ""})" else ""
    }

    fun panelText(): String {
        val s = GameStateTracker.state
        val sb = StringBuilder()
        sb.append(statusLine()).append('\n')
        sb.append("Gemma: ").append(GemmaEngine.state)
        if (StrategicAdvisor.busyWith.isNotEmpty()) sb.append(" (thinking: ").append(StrategicAdvisor.busyWith).append(")")
        sb.append('\n')
        sb.append("Capture: ").append(FrameHub.source).append(", ").append("%.0f".format(FrameProcessor.processedFps)).append(" fps\n")
        sb.append("Match: ").append(if (Cfg.ranked) "ranked" else "standard")
        sb.append(" | Phase: ").append(s.phase.label)
        if (s.selfHp >= 0) sb.append(" | my HP ").append((s.selfHp * 100).toInt()).append('%')
        if (s.self != null) sb.append(" | level ").append(s.level).append(if (s.xp >= 0f) " (EXP ${(s.xp * 100).toInt()}%)" else "")
        sb.append("\nHero: ").append(GameStateTracker.ownHero() ?: "unknown").append(" (").append(GameStateTracker.ownHeroSource()).append(")")
        sb.append(" | enemies ").append(s.enemies.size).append(" | minions ").append(s.enemyMinions.size)
        if (playerOn) {
            sb.append("\n\nDecision: ").append(ActionExecutor.lastDecision)
            sb.append("\nTarget: ").append(ActionExecutor.lastTarget)
            sb.append("\nNext: ").append(TacticalEngine.nextPlan)
            val p = StrategicAdvisor.plan
            sb.append("\nPlan: ").append(p.plan).append(" (").append("%.1f".format(p.aggression)).append(")")
            sb.append("\nLane: ").append(s.lane.label).append(" | spell: ").append(s.spell ?: "?")
            if (Cfg.useCombos) sb.append("\nCombo: ").append(ComboRunner.status)
            if (Cfg.smartFights) sb.append("\nBrain: ").append(Brain.lastChoice)
            s.boss?.let { sb.append("\nObjective in sight: HP ").append((it.hp * 100).toInt()).append('%') }
        }
        if (playerOn && s.hud.upgradeReady.isNotEmpty()) sb.append("\nCan upgrade: ").append(s.hud.upgradeReady.joinToString())
        if (playerOn && Cfg.coachMode) sb.append("\nCoach: ").append(com.example.mlbbresearch.learning.Coach.summary(s.ownHero)).append(" | like you: ").append(ImitationPolicy.status)
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
