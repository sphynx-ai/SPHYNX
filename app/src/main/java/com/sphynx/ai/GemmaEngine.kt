package com.sphynx.ai

import android.content.Context
import android.graphics.Bitmap
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

// A language model running on the phone through LiteRT-LM (.litertlm file). LiteRT-LM applies
// each model's own prompt format, so any Gemma .litertlm works: Gemma 4 E2B, Gemma 3n E2B,
// Gemma 3 270M / 1B. All calls of one slot run one at a time on its own thread.
//   GemmaEngine       = the STRATEGIC brain (Gemma 4 E2B or 3n E2B, GPU first): asked now and then
//   TacticalLlmEngine = the small TACTICAL adviser (e.g. Gemma 3 270M, CPU, text only)
open class LlmSlot(private val label: String, threadPriority: Int) {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, label).apply { priority = threadPriority } }
    private val dispatcher = thread.asCoroutineDispatcher()

    @Volatile private var engine: Engine? = null
    @Volatile private var active: Conversation? = null

    @Volatile var state: String = "not loaded"
        private set
    @Volatile var backendName: String = "-"
        private set
    @Volatile var loading = false
        private set
    @Volatile var lastLatencyMs = 0L
        private set
    @Volatile var calls = 0
        private set

    val ready: Boolean get() = engine != null && !loading

    @Volatile var visionOk = false
        private set

    // for the model speed recorder (ModelSpeed): which engine this is, whether the detailed
    // LiteRT-LM figures were on at load, and whether the next call is the first after loading
    val role: String get() = if (label == "gemma") "strategic" else "tactical"
    @Volatile private var detailedActive = false
    @Volatile private var coldNext = true

    // Loads the .litertlm model with Google's recommended fallback order:
    //   1. image + text on the GPU   2. image + text on the CPU   3. text only on the CPU
    // Phase 7 L8 (owner, 2026-11-23, experimental, off by default): with [preferNpu], the NPU is tried first. LiteRT-LM
    // compiles the ordinary .litertlm for this phone's NPU while loading (its generic compiler-plugin path; the first
    // time can take over a minute; the result is cached in cacheDir). The picture part stays on the GPU / CPU.
    suspend fun load(ctx: Context, modelPath: String, preferGpu: Boolean, textOnly: Boolean = false,
                     preferNpu: Boolean = false): String? = withContext(dispatcher) {
        loading = true
        state = "loading..."
        try { engine?.close() } catch (e: Throwable) { }
        engine = null
        class Attempt(val label: String, val gpu: Boolean, val vision: Boolean, val npu: Boolean = false)
        val attempts = ArrayList<Attempt>()
        if (preferNpu) attempts.add(Attempt(if (textOnly) "NPU, text only" else "NPU", preferGpu, !textOnly, npu = true))
        if (!textOnly) {
            if (preferGpu) attempts.add(Attempt("GPU", true, true))
            attempts.add(Attempt("CPU", false, true))
        } else if (preferGpu) attempts.add(Attempt("GPU, text only", true, false))
        attempts.add(Attempt("CPU, text only", false, false))
        var lastErr: Throwable? = null
        val tLoad = System.currentTimeMillis()
        // detailed speed figures (owner's switch): LiteRT-LM reads this flag when an engine starts. If no
        // attempt loads with it on, every attempt is tried once more with it off.
        val passes = if (Cfg.modelSpeedDetailed) listOf(true, false) else listOf(false)
        var tried = 0
        for (detailed in passes) for (a in attempts) {
            try {
                tried++
                setDetailed(detailed)
                state = if (a.npu) "compiling for the NPU (the first time can take over a minute)..."
                        else "loading on ${a.label}${if (detailed) " (detailed speed on)" else ""}..."
                val main = when {
                    a.npu -> Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir)
                    a.gpu -> Backend.GPU()
                    else -> Backend.CPU()
                }
                val cfg = if (a.vision) {
                    EngineConfig(
                        modelPath = modelPath,
                        backend = main,
                        visionBackend = if (a.gpu) Backend.GPU() else Backend.CPU(),
                        audioBackend = Backend.CPU(),
                        maxNumImages = 1,
                        cacheDir = ctx.cacheDir.path
                    )
                } else {
                    EngineConfig(modelPath = modelPath, backend = main, cacheDir = ctx.cacheDir.path)
                }
                val e = Engine(cfg)
                e.initialize()
                engine = e
                visionOk = a.vision
                backendName = a.label
                state = "ready on ${a.label}"
                loading = false
                detailedActive = detailed
                coldNext = true
                Diag.info("$label model loaded on ${a.label}" + if (detailed) " (detailed speed on)" else "")
                ModelSpeed.load(role, a.label, System.currentTimeMillis() - tLoad, true, "", detailed, tried)
                return@withContext null
            } catch (t: Throwable) {
                lastErr = t
                Diag.error("$label model load on ${a.label} failed", t)
            }
        }
        state = "load failed (see Logs)"
        loading = false
        setDetailed(false)
        ModelSpeed.load(role, "-", System.currentTimeMillis() - tLoad, false, lastErr?.message ?: "unknown error", false, tried)
        lastErr?.message ?: "unknown error"
    }

    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun setDetailed(on: Boolean) {
        try { com.google.ai.edge.litertlm.ExperimentalFlags.enableBenchmark = on } catch (t: Throwable) { }
    }

    // Asks Gemma one question, optionally with a screenshot. Returns the text reply.
    // v37.01 workload reduction: fast sampler (topK=16, topP=0.85), no-thinking instruction, and 512px JPEG at quality 70.
    // purpose: what asked, only for the model speed recorder (draft, plan, combo, fight advice ...)
    suspend fun ask(prompt: String, image: Bitmap? = null, temperature: Double = 0.15, purpose: String = "other"): String =
        askJpeg(prompt, image, null, temperature, purpose)

    // Phase 6 (S2): the same question with the picture already as JPEG bytes (the shared InferenceRequest carries bytes).
    // Exactly one of image / jpeg is used; the reply, sampler and speed record are the same as ask().
    // Phase 7 (L2): the answer is streamed (sendMessageAsync); [stopWhen] sees the text so far and may end the answer early
    // (the tactical adviser stops once it has read its choice). The returned text is the same cleaned reply as before.
    suspend fun askJpeg(prompt: String, image: Bitmap?, jpegBytes: ByteArray?, temperature: Double = 0.15, purpose: String = "other",
                        stopWhen: ((String) -> Boolean)? = null): String = withContext(dispatcher) {
        val e = engine ?: throw IllegalStateException("the $label model is not loaded")
        val t0 = System.currentTimeMillis()
        val conv = e.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(topK = 16, topP = 0.85, temperature = temperature))
        )
        active = conv
        val cold = coldNext
        coldNext = false
        val withImage = (image != null || jpegBytes != null) && visionOk
        var promptChars = prompt.length
        try {
            val fastPrompt = if ("Do not think" in prompt) prompt else "$prompt Do not think; answer directly."
            // text first, then the image (the order LiteRT-LM's guide asks for)
            val contents = if (withImage) {
                Contents.of(Content.Text(fastPrompt), Content.ImageBytes(jpegBytes ?: jpeg(image!!)))
            } else {
                Contents.of(Content.Text(fastPrompt))
            }
            promptChars = fastPrompt.length
            val streamed = stream(conv, contents, t0, stopWhen)
            val reply = clean(streamed.text)
            lastLatencyMs = System.currentTimeMillis() - t0
            calls++
            record(conv, purpose, withImage, promptChars, reply.length, lastLatencyMs, true, "", cold, streamed.firstPieceMs)
            reply
        } catch (t: Throwable) {
            record(null, purpose, withImage, promptChars, 0, System.currentTimeMillis() - t0, false, t.message ?: t.toString(), cold, null)
            throw t
        } finally {
            active = null
            try { conv.close() } catch (t: Throwable) { }
        }
    }

    private class Streamed(val text: String, val firstPieceMs: Long?)

    // Phase 7 (L2): one answer through LiteRT-LM's streaming call; waits on this slot's thread (as sendMessage did) until
    // the model is done, fails, or [stopWhen] says the text so far is enough (then the rest is cancelled)
    private fun stream(conv: Conversation, contents: Contents, t0: Long, stopWhen: ((String) -> Boolean)?): Streamed {
        val sb = StringBuilder()
        val done = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        val stoppedEarly = java.util.concurrent.atomic.AtomicBoolean(false)
        var first: Long? = null
        conv.sendMessageAsync(contents, object : MessageCallback {
            override fun onMessage(message: Message) {
                val piece = message.toString()
                synchronized(sb) {
                    if (stoppedEarly.get()) return
                    if (first == null && piece.isNotEmpty()) first = System.currentTimeMillis() - t0
                    sb.append(piece)
                    if (stopWhen != null && try { stopWhen(sb.toString()) } catch (t: Throwable) { false }) {
                        stoppedEarly.set(true)
                        try { conv.cancelProcess() } catch (t: Throwable) { }
                        done.countDown()
                    }
                }
            }
            override fun onDone() { done.countDown() }
            override fun onError(error: Throwable) { if (!stoppedEarly.get()) failure = error; done.countDown() }
        })
        // the same overall limit as a request's longest timeout (an emergency stop ends it sooner, through onError)
        if (!done.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
            try { conv.cancelProcess() } catch (t: Throwable) { }
            throw java.util.concurrent.TimeoutException("no answer within 10 minutes")
        }
        failure?.let { throw it }
        return synchronized(sb) { Streamed(sb.toString(), first) }
    }

    // the speed recorder never breaks a call: anything it cannot read is left empty
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun record(conv: Conversation?, purpose: String, image: Boolean, promptChars: Int, replyChars: Int, ms: Long,
                       ok: Boolean, error: String, cold: Boolean, firstPieceMs: Long?) {
        try {
            val b: com.google.ai.edge.litertlm.BenchmarkInfo? =
                if (detailedActive && conv != null) try { conv.getBenchmarkInfo() } catch (t: Throwable) { null } else null
            var ttft: Long? = null; var pt: Int? = null; var ptps: Double? = null; var dt: Int? = null; var dtps: Double? = null
            if (b != null) {
                val f = (b.timeToFirstTokenInSecond * 1000).toLong()
                if (f > 0L) ttft = f
                if (b.lastPrefillTokenCount > 0) pt = b.lastPrefillTokenCount
                if (b.lastPrefillTokensPerSecond > 0.0) ptps = b.lastPrefillTokensPerSecond
                if (b.lastDecodeTokenCount > 0) dt = b.lastDecodeTokenCount
                if (b.lastDecodeTokensPerSecond > 0.0) dtps = b.lastDecodeTokensPerSecond
            }
            // Phase 7 (L2): the time to the first word comes from the stream on every call (the benchmark figure wins when on)
            ModelSpeed.call(role, purpose, backendName, image, promptChars, replyChars, ms, ok, error, cold, ttft ?: firstPieceMs, pt, ptps, dt, dtps)
        } catch (t: Throwable) { }
    }

    // Emergency stop: interrupt a reply that is being generated
    fun cancel() {
        try { active?.cancelProcess() } catch (t: Throwable) { }
    }

    suspend fun unload() = withContext(dispatcher) {
        try { engine?.close() } catch (t: Throwable) { }
        engine = null
        state = "not loaded"
        backendName = "-"
    }

    // newer models (Gemma 4) may think out loud before answering: keep only the answer
    private fun clean(r: String): String {
        var t = r.replace(Regex("(?s)<think>.*?</think>"), "")
        if (t.contains("<channel|>")) t = t.substringAfterLast("<channel|>")
        return t.trim()
    }

    private fun jpeg(b: Bitmap): ByteArray = jpegForModel(b)

    companion object {
        /** The picture as the models get it (v37.01): at most 512 px wide, JPEG quality 70. */
        fun jpegForModel(b: Bitmap): ByteArray {
        val w = 512
        val small = if (b.width > w) Bitmap.createScaledBitmap(b, w, (b.height * w / b.width).coerceAtLeast(1), true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray()
        }
    }
}

object GemmaEngine : LlmSlot("gemma", Thread.NORM_PRIORITY - 1)

// low priority: it must never slow down the screen reading or the touches
object TacticalLlmEngine : LlmSlot("tactical-llm", Thread.MIN_PRIORITY + 1)
