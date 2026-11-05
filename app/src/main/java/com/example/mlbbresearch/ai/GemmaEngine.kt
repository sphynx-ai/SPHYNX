package com.example.mlbbresearch.ai

import android.content.Context
import android.graphics.Bitmap
import com.example.mlbbresearch.core.Diag
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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

    // Loads the .litertlm model with Google's recommended fallback order:
    //   1. image + text on the GPU   2. image + text on the CPU   3. text only on the CPU
    suspend fun load(ctx: Context, modelPath: String, preferGpu: Boolean, textOnly: Boolean = false): String? = withContext(dispatcher) {
        loading = true
        state = "loading..."
        try { engine?.close() } catch (e: Throwable) { }
        engine = null
        class Attempt(val label: String, val gpu: Boolean, val vision: Boolean)
        val attempts = ArrayList<Attempt>()
        if (!textOnly) {
            if (preferGpu) attempts.add(Attempt("GPU", true, true))
            attempts.add(Attempt("CPU", false, true))
        } else if (preferGpu) attempts.add(Attempt("GPU, text only", true, false))
        attempts.add(Attempt("CPU, text only", false, false))
        var lastErr: Throwable? = null
        for (a in attempts) {
            try {
                state = "loading on ${a.label}..."
                val cfg = if (a.vision) {
                    EngineConfig(
                        modelPath = modelPath,
                        backend = if (a.gpu) Backend.GPU() else Backend.CPU(),
                        visionBackend = if (a.gpu) Backend.GPU() else Backend.CPU(),
                        audioBackend = Backend.CPU(),
                        maxNumImages = 1,
                        cacheDir = ctx.cacheDir.path
                    )
                } else {
                    EngineConfig(modelPath = modelPath, backend = if (a.gpu) Backend.GPU() else Backend.CPU(), cacheDir = ctx.cacheDir.path)
                }
                val e = Engine(cfg)
                e.initialize()
                engine = e
                visionOk = a.vision
                backendName = a.label
                state = "ready on ${a.label}"
                loading = false
                Diag.info("$label model loaded on ${a.label}")
                return@withContext null
            } catch (t: Throwable) {
                lastErr = t
                Diag.error("$label model load on ${a.label} failed", t)
            }
        }
        state = "load failed (see Logs)"
        loading = false
        lastErr?.message ?: "unknown error"
    }

    // Asks Gemma one question, optionally with a screenshot. Returns the text reply.
    // v37.01 workload reduction: fast sampler (topK=16, topP=0.85), no-thinking instruction, and 512px JPEG at quality 70.
    suspend fun ask(prompt: String, image: Bitmap? = null, temperature: Double = 0.15): String = withContext(dispatcher) {
        val e = engine ?: throw IllegalStateException("the $label model is not loaded")
        val t0 = System.currentTimeMillis()
        val conv = e.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(topK = 16, topP = 0.85, temperature = temperature))
        )
        active = conv
        try {
            val fastPrompt = if ("Do not think" in prompt) prompt else "$prompt Do not think; answer directly."
            // text first, then the image (the order LiteRT-LM's guide asks for)
            val contents = if (image != null && visionOk) {
                Contents.of(Content.Text(fastPrompt), Content.ImageBytes(jpeg(image)))
            } else {
                Contents.of(Content.Text(fastPrompt))
            }
            val reply = clean(conv.sendMessage(contents).toString())
            lastLatencyMs = System.currentTimeMillis() - t0
            calls++
            reply
        } finally {
            active = null
            try { conv.close() } catch (t: Throwable) { }
        }
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

    private fun jpeg(b: Bitmap): ByteArray {
        val w = 512
        val small = if (b.width > w) Bitmap.createScaledBitmap(b, w, (b.height * w / b.width).coerceAtLeast(1), true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray()
    }
}

object GemmaEngine : LlmSlot("gemma", Thread.NORM_PRIORITY - 1)

// low priority: it must never slow down the screen reading or the touches
object TacticalLlmEngine : LlmSlot("tactical-llm", Thread.MIN_PRIORITY + 1)
