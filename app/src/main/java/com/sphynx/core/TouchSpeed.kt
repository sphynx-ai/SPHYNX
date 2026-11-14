package com.sphynx.core

import java.io.File
import java.util.concurrent.Executors

// SCREEN TO TOUCH RECORDER, the Android side: Speed (screen reading, deciding, touch queue) and the
// play loop (frame -> decision) feed it; a row every 5 s while the Player plays. Kept in
// files/research/screen_to_touch.jsonl (copied out with every session recording) and exported as CSV
// from the Speed page together with the model speed. It only measures.
object TouchSpeed {

    val log = TouchSpeedLog()
    @Volatile private var loaded = false
    @Volatile private var unsaved = 0
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "touch-speed").apply { priority = Thread.MIN_PRIORITY } }

    private fun file(): File? = com.sphynx.vision.HeroVerifier.appContext()?.let {
        File(File(it.filesDir, "research").apply { mkdirs() }, "screen_to_touch.jsonl") }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try { file()?.takeIf { it.exists() }?.let { log.loadJsonLines(it.readText()) } } catch (e: Exception) { Diag.error("Screen to touch: not loaded", e) }
    }

    // sync: write now (before an export or a session recording copies the file)
    fun save(sync: Boolean = false) {
        if (unsaved == 0) return
        unsaved = 0
        val text = log.toJsonLines()
        val w = Runnable { try { file()?.writeText(text) } catch (e: Exception) { Diag.error("Screen to touch: not saved", e) } }
        if (sync) w.run() else io.execute(w)
    }

    // called by the play loop (cheap; a row at most every 5 s)
    fun tick() {
        try {
            ensureLoaded()
            val row = log.flush(System.currentTimeMillis(), com.sphynx.capture.FrameHub.fps,
                com.sphynx.vision.FrameProcessor.processedFps, PhoneState.read())
            if (row != null && ++unsaved >= 12) save()      // about once a minute while playing
        } catch (t: Throwable) { }
    }

    fun text(): String { ensureLoaded(); return log.text() }
    fun csv(): String { ensureLoaded(); return log.csv() }
    fun clear() { ensureLoaded(); log.clear(); unsaved = 1; save() }
}
