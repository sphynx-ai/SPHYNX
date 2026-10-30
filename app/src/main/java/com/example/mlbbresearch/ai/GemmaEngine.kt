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

// Gemma 3n E2B running on the phone through LiteRT-LM.
// It is only the strategic brain: it is asked occasionally, never every frame.
// All calls run one at a time on a dedicated thread.
object GemmaEngine {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "gemma").apply { priority = Thread.NORM_PRIORITY - 1 } }
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
    suspend fun load(ctx: Context, modelPath: String, preferGpu: Boolean): String? = withContext(dispatcher) {
        loading = true
        state = "loading..."
        try { engine?.close() } catch (e: Throwable) { }
        engine = null
        class Attempt(val label: String, val gpu: Boolean, val vision: Boolean)
        val attempts = ArrayList<Attempt>()
        if (preferGpu) attempts.add(Attempt("GPU", true, true))
        attempts.add(Attempt("CPU", false, true))
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
                    EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path)
                }
                val e = Engine(cfg)
                e.initialize()
                engine = e
                visionOk = a.vision
                backendName = a.label
                state = "ready on ${a.label}"
                loading = false
                Diag.info("Gemma loaded on ${a.label}")
                return@withContext null
            } catch (t: Throwable) {
                lastErr = t
                Diag.error("Gemma load on ${a.label} failed", t)
            }
        }
        state = "load failed (see Logs)"
        loading = false
        lastErr?.message ?: "unknown error"
    }

    // Asks Gemma one question, optionally with a screenshot. Returns the text reply.
    suspend fun ask(prompt: String, image: Bitmap? = null, temperature: Double = 0.2): String = withContext(dispatcher) {
        val e = engine ?: throw IllegalStateException("Gemma is not loaded")
        val t0 = System.currentTimeMillis()
        val conv = e.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature))
        )
        active = conv
        try {
            // text first, then the image (the order LiteRT-LM's guide asks for)
            val contents = if (image != null && visionOk) {
                Contents.of(Content.Text(prompt), Content.ImageBytes(jpeg(image)))
            } else {
                Contents.of(Content.Text(prompt))
            }
            val reply = conv.sendMessage(contents).toString()
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

    private fun jpeg(b: Bitmap): ByteArray {
        val w = 768
        val small = if (b.width > w) Bitmap.createScaledBitmap(b, w, b.height * w / b.width, true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }
}
