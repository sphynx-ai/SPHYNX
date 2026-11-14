package com.sphynx.app

import com.sphynx.core.Probes

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
            val d = com.sphynx.core.SessionRecorder.lastDecision
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
}
