package com.sphynx.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

// MODEL SPEED RECORDER, the Android side: LlmSlot reports every load and every call here; the rows
// are kept in files/research/model_speed.jsonl (copied out with every session recording, like the
// other research files) and can be exported as CSV from the Speed page. It only measures: it never
// changes what the models are asked or how their answers are used.
object ModelSpeed {

    val log = ModelSpeedLog()
    @Volatile private var loaded = false
    @Volatile private var unsaved = 0
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "model-speed").apply { priority = Thread.MIN_PRIORITY } }

    @Volatile var testRunning = false; private set
    @Volatile var testStatus = "-"; private set

    private fun ctx(): Context? = com.sphynx.vision.HeroVerifier.appContext()
    private fun file(): File? = ctx()?.let { File(File(it.filesDir, "research").apply { mkdirs() }, "model_speed.jsonl") }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try { file()?.takeIf { it.exists() }?.let { log.loadJsonLines(it.readText()) } } catch (e: Exception) { Diag.error("Model speed: not loaded", e) }
    }

    // sync: write now (before an export or a session recording copies the file)
    fun save(sync: Boolean = false) {
        if (unsaved == 0) return
        unsaved = 0
        val text = log.toJsonLines()
        val w = Runnable { try { file()?.writeText(text) } catch (e: Exception) { Diag.error("Model speed: not saved", e) } }
        if (sync) w.run() else io.execute(w)
    }

    private fun add(r: ModelSpeedLog.Row) {
        try {
            ensureLoaded()
            log.add(r)
            // loads, speed tests and every 10th call are written at once
            if (++unsaved >= 10 || r.kind == "load" || r.purpose == "speed test") save()
        } catch (t: Throwable) { Diag.error("Model speed", t) }
    }

    fun modelName(slot: String): String = if (slot == "strategic") com.sphynx.models.ModelManager.strategicName()
        else com.sphynx.models.ModelManager.tacticalName()

    fun load(slot: String, backend: String, ms: Long, ok: Boolean, error: String, detailed: Boolean, attempts: Int) {
        val ph = com.sphynx.core.PhoneState.read()
        add(ModelSpeedLog.Row("load", System.currentTimeMillis(), slot, modelName(slot), backend, ms, ok, error,
            detailed = detailed, attempts = attempts, thermal = ph.thermal, batteryC = ph.batteryC, ramAvailMb = ph.ramAvailMb))
    }

    fun call(slot: String, purpose: String, backend: String, image: Boolean, promptChars: Int, replyChars: Int, ms: Long,
             ok: Boolean, error: String, cold: Boolean, ttftMs: Long?, prefillTokens: Int?, prefillTps: Double?,
             decodeTokens: Int?, decodeTps: Double?) {
        val ph = com.sphynx.core.PhoneState.read()
        add(ModelSpeedLog.Row("call", System.currentTimeMillis(), slot, modelName(slot), backend, ms, ok, error, purpose, image,
            promptChars, replyChars, cold, ttftMs, prefillTokens, prefillTps, decodeTokens, decodeTps,
            thermal = ph.thermal, batteryC = ph.batteryC, ramAvailMb = ph.ramAvailMb))
    }

    // ---- what the Speed page shows / exports ----
    fun text(): String { ensureLoaded(); return log.text() }
    fun csv(): String { ensureLoaded(); return log.csv() }
    fun clear() { ensureLoaded(); log.clear(); unsaved = 1; save() }

    // ---- the speed test: the same questions every time, so models, settings and phones compare ----
    val TEST_PROMPTS = listOf(
        "Answer with one word: at 30% health with two enemies close, should a hero fight or retreat?",
        "You are a MOBA coach. Minute 6, your team has 2 more kills, the enemy jungler was just seen bottom, the Turtle " +
            "spawns in 40 seconds and your mid laner is at full health. In three short lines, what should your team do next and why?",
        "List three items a mage usually buys first in a MOBA, one per line, no explanations."
    )
    const val RUNS = 2

    /** Asks every loaded model the test questions RUNS times (and the strategist once with a test picture). */
    fun runTest(done: (String) -> Unit) {
        if (testRunning) { done("The speed test is already running"); return }
        val slots = listOf(Roles.STRATEGIC, Roles.TACTICAL).filter { Roles.ready(it) }
        if (slots.isEmpty()) { done("No model is loaded: load the models first (Model page)"); return }
        testRunning = true
        AiRuntime.scope.launch {
            var n = 0; var failed = 0
            try {
                for (s in slots) for (run in 1..RUNS) for ((i, p) in TEST_PROMPTS.withIndex()) {
                    testStatus = "${s.name.lowercase()}: question ${i + 1} of ${TEST_PROMPTS.size}, run $run of $RUNS"
                    try { Roles.ask(s, p, null, 0.0, purpose = "speed test"); n++ } catch (t: Throwable) { failed++ }
                }
                if (Roles.ready(Roles.STRATEGIC) && Roles.images(Roles.STRATEGIC)) {
                    testStatus = "strategic: question with a picture"
                    try { Roles.ask(Roles.STRATEGIC, "Describe this picture in one short sentence.", testPicture(), 0.0, purpose = "speed test"); n++ }
                    catch (t: Throwable) { failed++ }
                }
            } finally {
                testRunning = false
                save()
            }
            testStatus = "done: $n answers" + if (failed > 0) ", $failed failed" else ""
            Diag.info("Model speed test $testStatus")
            done("Speed test $testStatus")
        }
    }

    // a plain 1280 x 720 test picture (bands of colour), so the picture cost is the same every time
    private fun testPicture(): Bitmap {
        val w = 1280; val h = 720
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; Color.rgb((x * 255) / w, (y * 255) / h, 128) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
