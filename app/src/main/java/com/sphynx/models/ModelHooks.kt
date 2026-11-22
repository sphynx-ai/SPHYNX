package com.sphynx.models

import android.content.Context
import java.io.File

// THE MODELS LAYER CALLS NO CODE ABOVE IT (ADR 0001, migration B4). ModelManager and AutoFolder find, copy and
// load model files; the engines that run them (the language models in ai, the networks in learning, the
// detectors in vision) and the online backups are reached through these functions, plugged in by the app layer
// at start (app/ProbesWiring). Until then each does nothing / reports "not ready", so nothing breaks.
object ModelHooks {

    // ---- the language models (ai): the strategic Gemma model and the small tactical one ----
    @Volatile var strategicReady: () -> Boolean = { false }
    @Volatile var strategicLoading: () -> Boolean = { false }
    /** Loads the strategic model from [path] in the background; [done] gets the error, or null. */
    @Volatile var loadStrategic: (Context, String, Boolean, (String?) -> Unit) -> Unit = { _, _, _, done -> done("not available") }
    /** Unloads the strategic model and waits until it is unloaded. */
    @Volatile var unloadStrategicNow: () -> Unit = {}
    @Volatile var tacticalReady: () -> Boolean = { false }
    @Volatile var tacticalLoading: () -> Boolean = { false }
    @Volatile var loadTactical: (Context, String, (String?) -> Unit) -> Unit = { _, _, done -> done("not available") }
    @Volatile var unloadTactical: () -> Unit = {}
    /** The owner asked for this load (a button, a scan by hand): a model suspected of a crash may be tried again (Phase 6 S6). */
    @Volatile var loadByOwner: () -> Unit = {}

    // ---- the tactical networks (learning: PolicyNet, FightNet) ----
    @Volatile var policyKey: (String) -> String = { it.lowercase() }
    @Volatile var policyFileFor: (Context, String) -> File = { c, k -> File(c.filesDir, "policy_$k.tflite") }
    @Volatile var policyModelFile: (Context) -> File = { c -> File(c.filesDir, "policy.tflite") }
    @Volatile var policyInfoFor: (String) -> String? = { null }
    @Volatile var policySaveInfo: (String, String) -> Unit = { _, _ -> }
    /** Loads the tactical networks; the error, or null. */
    @Volatile var policyLoad: (Context) -> String? = { null }
    @Volatile var policyReady: () -> Boolean = { false }
    @Volatile var policyQuality: () -> String = { "" }
    @Volatile var fightNetFile: (Context) -> File = { c -> File(c.filesDir, "fight_choice.tflite") }
    @Volatile var fightNetLoad: (Context) -> Unit = {}
    @Volatile var fightNetSaveInfo: (String) -> Unit = {}

    // ---- the detectors (vision): the whole-screen one and the minimap one ----
    @Volatile var screenDetectorFile: (Context) -> File = { c -> File(c.filesDir, "screen_detector.tflite") }
    @Volatile var screenDetectorLoad: (Context) -> String? = { null }
    @Volatile var screenDetectorReady: () -> Boolean = { false }
    @Volatile var minimapDetectorFile: (Context) -> File = { c -> File(c.filesDir, "detector.tflite") }
    @Volatile var minimapDetectorLoad: (Context) -> String? = { null }
    @Volatile var minimapDetectorReady: () -> Boolean = { false }

    // ---- the online data backups (learning: OnlineBackup) ----
    @Volatile var isOnlineBackup: (String) -> Boolean = { false }
    /** Restores one backup file's text; how many entries came back. */
    @Volatile var restoreOnlineBackup: (String, String) -> Int = { _, _ -> 0 }
}
