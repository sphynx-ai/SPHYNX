package com.sphynx.app

import com.sphynx.core.Probes
import kotlinx.coroutines.launch

// THE APP LAYER PLUGS ITS READINGS INTO core (ADR 0001): what the core services show or record from the AI,
// the game, vision and learning. Called first thing in MainApplication.onCreate. Each reading is the same
// one the core service used to read directly (no behaviour change).
object ProbesWiring {

    fun install() {
        Probes.appContext = { com.sphynx.vision.HeroVerifier.appContext() }
        Probes.captureFps = { com.sphynx.capture.FrameHub.fps }
        Probes.framesReadFps = { com.sphynx.vision.FrameProcessor.processedFps }
        Probes.tacticalNetworkMs = { com.sphynx.learning.PolicyNet.lastMs }
        Probes.minimapDetectorMs = { com.sphynx.vision.ObjectDetector.lastMs }
        Probes.screenDetectorMs = { com.sphynx.vision.ScreenDetector.lastMs }
        Probes.tacticalModelMs = { com.sphynx.ai.TacticalLlmEngine.lastLatencyMs }
        Probes.strategicModelMs = { com.sphynx.ai.GemmaEngine.lastLatencyMs }
        Probes.onlineMetaMs = { com.sphynx.ai.GroqClient.lastMs }

        Probes.videoMarks = {
            val s = com.sphynx.game.GameStateTracker.state
            val d = com.sphynx.ai.SessionRows.lastDecision
            Probes.Marks(
                self = s.self?.let { it.x to it.y },
                enemies = s.enemies.map { Probes.Mark(it.x, it.y, "#${it.id} ${(it.hp * 100).toInt()}%") },
                monsters = s.monsters.map { Probes.Mark(it.x, it.y, "${(it.hp * 100).toInt()}%") },
                matchSec = s.matchSec,
                moveX = d?.moveX ?: 0f, moveY = d?.moveY ?: 0f,
                targetX = d?.targetX ?: -1f, targetY = d?.targetY ?: -1f,
                decision = d?.let { it.intent.name + ": " + it.reason.take(70) } ?: ""
            )
        }

        // B3: the capture service and vision no longer call the layers above directly
        Probes.onCaptureStarted = {
            // learning from you runs whenever the screen is captured, Player on or off
            if (!com.sphynx.ai.AiRuntime.learnOn) com.sphynx.ai.AiRuntime.start("learn")
        }
        Probes.onCaptureStopped = { com.sphynx.game.GameStateTracker.captureStopped() }
        Probes.emergencyStop = { why -> com.sphynx.ai.AiRuntime.emergencyStop(why) }
        Probes.heroIconFile = { h -> IconCache.heroFile(h) }
        Probes.itemIconFile = { i -> IconCache.itemFile(i) }
        Probes.loadIcon = { ctx, f -> IconCache.load(ctx, f) { } }

        // B4: the models layer finds and copies the model files; the engines that run them are plugged in here
        wireModels()

        Probes.mayRecordMatch = { com.sphynx.learning.LearnGate.on() }
        Probes.screenCaptureRunning = { com.sphynx.capture.ScreenCaptureService.running }
        Probes.matchRunning = { com.sphynx.game.GameStateTracker.matchId != 0L }
        Probes.beforeResearchExport = listOf({ com.sphynx.research.ResearchMode.saveMoments() }, { com.sphynx.ai.ModelSpeed.save(sync = true) })
        Probes.sceneryPicture = { com.sphynx.game.SceneryMap.renderPicture() }

        // the training results and the new training pictures go up with the debug data (owner, 2026-11-12)
        Probes.beforeUpload = listOf(
            { ctx: android.content.Context -> try { com.sphynx.learning.TrainingResults.refresh(ctx); com.sphynx.learning.TrainingResults.export(ctx) }
                catch (e: Exception) { "training results failed: ${e.message?.take(80)}" } },
            { ctx: android.content.Context -> try { com.sphynx.learning.PictureExport.export(ctx) } catch (e: Exception) { "pictures failed: ${e.message?.take(80)}" } }
        )
        Probes.tokenFromFolder = { ctx, name ->
            com.sphynx.models.AutoFolder.files(ctx).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.open?.invoke()?.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    // ADR 0001 migration B4: ModelManager and AutoFolder reach the engines through ModelHooks (same calls as before)
    private fun wireModels() {
        val h = com.sphynx.models.ModelHooks
        val ai = com.sphynx.ai.AiRuntime
        val gemma = com.sphynx.ai.GemmaEngine
        val small = com.sphynx.ai.TacticalLlmEngine
        h.strategicReady = { gemma.ready }
        h.strategicLoading = { gemma.loading }
        h.loadStrategic = { ctx, path, gpu, done -> ai.scope.launch { done(gemma.load(ctx, path, gpu)) } }
        h.unloadStrategicNow = { kotlinx.coroutines.runBlocking { gemma.unload() } }
        h.tacticalReady = { small.ready }
        h.tacticalLoading = { small.loading }
        h.loadTactical = { ctx, path, done -> ai.scope.launch { done(small.load(ctx, path, preferGpu = false, textOnly = true)) } }
        h.unloadTactical = { ai.scope.launch { small.unload() } }

        val pn = com.sphynx.learning.PolicyNet
        val fn = com.sphynx.learning.FightNet
        h.policyKey = { stem -> pn.key(stem) }
        h.policyFileFor = { ctx, k -> pn.fileFor(ctx, k) }
        h.policyModelFile = { ctx -> pn.modelFile(ctx) }
        h.policyInfoFor = { k -> pn.infoFor(k) }
        h.policySaveInfo = { k, json -> pn.saveInfo(k, json) }
        h.policyLoad = { ctx -> pn.load(ctx) }
        h.policyReady = { pn.ready }
        h.policyQuality = { pn.qualityText }
        h.fightNetFile = { ctx -> fn.file(ctx) }
        h.fightNetLoad = { ctx -> fn.load(ctx) }
        h.fightNetSaveInfo = { json -> fn.saveInfo(json) }

        val sd = com.sphynx.vision.ScreenDetector
        val od = com.sphynx.vision.ObjectDetector
        h.screenDetectorFile = { ctx -> sd.modelFile(ctx) }
        h.screenDetectorLoad = { ctx -> sd.load(ctx) }
        h.screenDetectorReady = { sd.ready }
        h.minimapDetectorFile = { ctx -> od.modelFile(ctx) }
        h.minimapDetectorLoad = { ctx -> od.load(ctx) }
        h.minimapDetectorReady = { od.ready }

        h.isOnlineBackup = { name -> com.sphynx.learning.OnlineBackup.isBackup(name) }
        h.restoreOnlineBackup = { name, text -> com.sphynx.learning.OnlineBackup.restore(name, text) }
    }
}
