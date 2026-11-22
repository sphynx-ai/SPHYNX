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
        Probes.tacticalModelMs = { com.sphynx.ai.Roles.lastLatencyMs(com.sphynx.ai.Roles.TACTICAL) }
        Probes.strategicModelMs = { com.sphynx.ai.Roles.lastLatencyMs(com.sphynx.ai.Roles.STRATEGIC) }
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

        // Phase 2 adapter split: the tactical engine asks Mobile Legends' own rules (heroes, spells, jungle...)
        com.sphynx.ai.TacticalEngine.rules = com.sphynx.adapters.mlbb.MlbbTacticalRules
        // ...and the frame pipeline reads the screen with Mobile Legends' readers
        com.sphynx.vision.FrameProcessor.reader = com.sphynx.vision.MlbbScreenReading
        // ...and the strategic model is asked Mobile Legends' questions
        com.sphynx.ai.StrategicAdvisor.game = com.sphynx.adapters.mlbb.MlbbStrategy

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
        // Phase 6 S4 (ADR 0002): the models load and unload through the role router (ai/Roles), same slots and order
        val roles = com.sphynx.ai.Roles
        roles.gguf = { Probes.appContext()?.let { com.sphynx.models.GgufLibrary.backend(it) } }
        h.strategicReady = { roles.ready(roles.STRATEGIC) }
        h.strategicLoading = { roles.loading(roles.STRATEGIC) }
        // a .gguf path is the owner's GGUF choice for the job (Phase 6 S7), anything else the job's LiteRT-LM file
        fun loadFor(role: com.sphynx.inference.ModelRole, path: String, gpu: Boolean): String? =
            if (path.endsWith(".gguf", ignoreCase = true))
                com.sphynx.models.GgufLibrary.config(java.io.File(path)).let { c ->
                    // Phase 6 S8: the strategic job reads pictures through the model's mmproj file, when there is one
                    roles.load(com.sphynx.inference.roles.Assignment(role,
                        c.copy(role = role, wantsVision = role == roles.STRATEGIC && c.projector != null)))
                }
            else roles.loadLiteRt(role, path, gpu)
        h.loadStrategic = { ctx, path, gpu, done -> roles.init(ctx); ai.scope.launch { done(loadFor(roles.STRATEGIC, path, gpu)) } }
        h.unloadStrategicNow = { roles.unload(roles.STRATEGIC) }
        h.tacticalReady = { roles.ready(roles.TACTICAL) }
        h.tacticalLoading = { roles.loading(roles.TACTICAL) }
        h.loadTactical = { ctx, path, done -> roles.init(ctx); ai.scope.launch { done(loadFor(roles.TACTICAL, path, false)) } }
        h.unloadTactical = { ai.scope.launch { roles.unload(roles.TACTICAL) } }
        h.loadByOwner = { roles.loadByOwner() }

        // Phase 5 (P5): the models the low-memory / heat policy may set aside and bring back (core.PressurePolicy)
        val pp = com.sphynx.core.PressurePolicy
        val mm = com.sphynx.models.ModelManager
        val gg = com.sphynx.models.GgufLibrary
        fun ctx() = Probes.appContext()
        pp.mode = { com.sphynx.core.PressurePolicy.Mode.of(com.sphynx.core.Cfg.pressureMode) }
        pp.inFight = { com.sphynx.game.GameStateTracker.state.enemies.isNotEmpty() }     // an enemy on screen = a fight may be on
        // Phase 6 S5: the slots are the roles; "Step down gently" swaps in the next smaller model when there is one
        // (owner, 2026-11-22) and brings the owner's model back after the cool-down; "Only warn me" never calls these
        fun reloadByManager(role: com.sphynx.inference.ModelRole) = ctx()?.let { c ->
            val done: (String?) -> Unit = { err -> if (err != null) com.sphynx.core.Diag.info("Pressure: ${role.name.lowercase()} model not reloaded: $err") }
            if (role == roles.STRATEGIC) mm.load(c, done) else mm.loadTactical(c, done)
        }
        pp.tactical = com.sphynx.core.PressurePolicy.Slot("the tactical model", { roles.ready(roles.TACTICAL) },
            { roles.stepDown(roles.TACTICAL) }, { if (!roles.restore(roles.TACTICAL)) reloadByManager(roles.TACTICAL) }) { roles.stepDownLine.ifEmpty { null } }
        // the GGUF test model of the Model page, when no role uses the GGUF engine (a role's GGUF model is its role's slot)
        var ggufFile: java.io.File? = null
        fun ggufForRole() = listOf(roles.STRATEGIC, roles.TACTICAL).any { roles.backend(it) == com.sphynx.inference.BackendType.LLAMA_CPP }
        pp.gguf = com.sphynx.core.PressurePolicy.Slot("the GGUF model", { !ggufForRole() && ctx()?.let { gg.loadedFile(it) } != null },
            { ctx()?.let { c -> ggufFile = gg.loadedFile(c); gg.unload(c) } }, { val c = ctx(); val f = ggufFile; if (c != null && f != null && f.isFile) gg.load(c, f) { } })
        pp.strategic = com.sphynx.core.PressurePolicy.Slot("the strategic model", { roles.ready(roles.STRATEGIC) },
            { roles.stepDown(roles.STRATEGIC) }, { if (!roles.restore(roles.STRATEGIC)) reloadByManager(roles.STRATEGIC) }) { roles.stepDownLine.ifEmpty { null } }

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
