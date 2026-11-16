package com.sphynx.core

import android.content.Context

// READINGS FROM THE LAYERS ABOVE (ADR 0001, Phase 2): core is the platform layer and may not use the AI, the
// game, vision or learning code. Where a core service shows or records something those layers know (a frame
// rate, a model's latency, where the enemies are for the video overlay), it reads it through a function set
// here by the app layer at start (app/Probes wiring in MainApplication). Until then every reading is "none"
// (0, null, empty), so nothing breaks if a service runs before the app has started.
object Probes {

    /** The application context (set first thing in MainApplication.onCreate). */
    @Volatile var appContext: () -> Context? = { null }

    // frame rates (frames per second)
    @Volatile var captureFps: () -> Float = { 0f }
    @Volatile var framesReadFps: () -> Float = { 0f }

    // model latencies (milliseconds)
    @Volatile var tacticalNetworkMs: () -> Float = { 0f }
    @Volatile var minimapDetectorMs: () -> Long = { 0L }
    @Volatile var screenDetectorMs: () -> Long = { 0L }
    @Volatile var tacticalModelMs: () -> Long = { 0L }
    @Volatile var strategicModelMs: () -> Long = { 0L }
    @Volatile var onlineMetaMs: () -> Long = { 0L }

    /** What the session video draws over the frame (positions are fractions of the screen). */
    class Mark(val x: Float, val y: Float, val label: String)
    class Marks(
        val self: Pair<Float, Float>?,
        val enemies: List<Mark>,
        val monsters: List<Mark>,
        val matchSec: Int,                  // -1 = unknown
        val moveX: Float, val moveY: Float, // the joystick (0, 0 = standing)
        val targetX: Float, val targetY: Float,   // -1 = no target
        val decision: String                // "INTENT: reason", or "" before the first decision
    ) {
        companion object { val NONE = Marks(null, emptyList(), emptyList(), -1, 0f, 0f, -1f, -1f, "") }
    }
    @Volatile var videoMarks: () -> Marks = { Marks.NONE }

    // ---- the session recorder (SessionRecorder) ----
    /** May this match be recorded by itself (a 5v5: learning.LearnGate)? */
    @Volatile var mayRecordMatch: () -> Boolean = { false }
    @Volatile var screenCaptureRunning: () -> Boolean = { false }
    /** Is a match on (the game's match id is set)? */
    @Volatile var matchRunning: () -> Boolean = { false }
    /** Saves to run before the research files are exported (practice moments, model speeds). */
    @Volatile var beforeResearchExport: List<() -> Unit> = emptyList()
    /** The learned map scenery as a picture, for the debug export (null = none). */
    @Volatile var sceneryPicture: () -> android.graphics.Bitmap? = { null }

    /** Things to prepare before a debug upload (training results, pictures); each returns a status line. */
    @Volatile var beforeUpload: List<(Context) -> String> = emptyList()

    /** The GitHub token from the app's chosen folder (when it is not directly in Sphynx-Storage). */
    @Volatile var tokenFromFolder: (Context, String) -> String? = { _, _ -> null }

    // ---- the screen capture service (ADR 0001 migration B3): it tells the layers above, it does not call them ----
    /** Called when screen capture has started (the app starts learning from you). */
    @Volatile var onCaptureStarted: () -> Unit = {}
    /** Called when screen capture has stopped (the game closes an open match after a minute without capture). */
    @Volatile var onCaptureStopped: () -> Unit = {}
    /** The notification's emergency stop button: stop everything (the reason is logged). */
    @Volatile var emergencyStop: (String) -> Unit = {}

    // ---- the downloaded hero / item pictures (ADR 0001 migration B3): vision reads them by file name ----
    /** The picture file of a hero (null when the hero is not known). */
    @Volatile var heroIconFile: (String) -> String? = { null }
    /** The picture file of an item or battle spell. */
    @Volatile var itemIconFile: (String) -> String = { it.trim().replace(' ', '_') + ".png" }
    /** Downloads a picture into the app's icons folder when it is not there yet (in the background). */
    @Volatile var loadIcon: (Context, String) -> Unit = { _, _ -> }
}
