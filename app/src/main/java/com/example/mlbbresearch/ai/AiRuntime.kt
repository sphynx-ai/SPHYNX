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
import com.example.mlbbresearch.learning.DemoRecorder
import com.example.mlbbresearch.learning.ExperienceRecorder
import com.example.mlbbresearch.learning.ImitationPolicy
import com.example.mlbbresearch.learning.PerformanceEvaluator
import com.example.mlbbresearch.vision.FrameProcessor
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
    // stages that were on when the floating icon paused the AI
    private var paused: List<String> = emptyList()

    init {
        GameStateTracker.onNewMatch = { stdSelectAt = 0L; stdSelectTries = 0 }
    }

    fun anyOn() = playerOn || drafterOn || builderOn || learnOn

    // Returns an error message, or null when started
    fun start(stage: String): String? {
        val svc = AccessibilityController.instance ?: return "Turn on the accessibility service first (Setup page)"
        if (needsGemma(stage) && !GemmaEngine.ready) return "Load the Gemma model first (Model page)"
        when (stage) {
            "player" -> {
                playerOn = true
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
        lastError = ""
        svc.overlay?.show()
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
            "player" -> { playerOn = false; ActionExecutor.release() }
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
        visionJob?.cancel(); tacticJob?.cancel(); strategyJob?.cancel()
        visionJob = null; tacticJob = null; strategyJob = null
        TacticalEngine.reset()
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
                    if (learnOn) DemoRecorder.observe(v)
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
                    val profile = HeroDb.profile(hero)
                    val plan = StrategicAdvisor.plan
                    val bias = Cfg.learnedBias(hero ?: "unknown")
                    var d = TacticalEngine.decide(s, profile, plan, bias)
                    // "Play like me": copy your moves, but the survival rules still come first
                    if (Cfg.playLikeMe) {
                        ImitationPolicy.ensureLoaded(hero)
                        if (d.intent != Intent.RETREAT && d.intent != Intent.RECALL) ImitationPolicy.decide(s)?.let { d = it }
                    }
                    ActionExecutor.execute(d)
                    ExperienceRecorder.record(s, d, plan.plan, plan.aggression + bias)
                    ActionExecutor.maybeUpgrade(s)
                    if (Cfg.playerQuickBuy) ActionExecutor.maybeQuickBuy(s)
                } else if (playerOn) {
                    ActionExecutor.execute(TacticalDecision.IDLE)
                }
                if (learnOn && !playerOn && inGame) DemoRecorder.record(s)
                if (builderOn && inGame) {
                    if (Cfg.qbOn) QuickBuySwap.watch(s)
                    if (Cfg.autonomous && drafterOn && !(playerOn && Cfg.playerQuickBuy)) ActionExecutor.maybeQuickBuy(s, blind = true)
                }
                if (drafterOn && !Cfg.ranked && Cfg.autonomous && !stale) standardSelect(s)
                PerformanceEvaluator.evaluateDue()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Diag.error("Tactical loop", t)
            }
            delay(200)
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
        ActionExecutor.standardSelect(HeroDb.match(Cfg.myHero))
    }

    // ---------- slow: Gemma, event-driven ----------
    private suspend fun strategyLoop() {
        while (anyOn()) {
            try {
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
            if (playerOn) (if (Cfg.playLikeMe) "Player (like me)" else "Player") else null,
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
        sb.append("\nHero: ").append(GameStateTracker.ownHero() ?: "unknown")
        sb.append(" | enemies ").append(s.enemies.size).append(" | minions ").append(s.enemyMinions.size)
        if (playerOn) {
            sb.append("\n\nDecision: ").append(ActionExecutor.lastDecision)
            sb.append("\nTarget: ").append(ActionExecutor.lastTarget)
            val p = StrategicAdvisor.plan
            sb.append("\nPlan: ").append(p.plan).append(" (").append("%.1f".format(p.aggression)).append(")")
        }
        if (playerOn && s.hud.upgradeReady.isNotEmpty()) sb.append("\nCan upgrade: ").append(s.hud.upgradeReady.joinToString())
        if (playerOn && Cfg.playLikeMe) sb.append("\nLike me: ").append(ImitationPolicy.status)
        if (learnOn) {
            sb.append("\n\nLEARNING FROM YOU").append(if (playerOn) " (paused: Player is on)" else "")
            sb.append("\nYou: ").append(DemoRecorder.lastAction)
            sb.append("\nSaved: ").append(DemoRecorder.recorded).append(" this session, ").append(DemoRecorder.total).append(" total")
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
