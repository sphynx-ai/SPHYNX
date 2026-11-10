package com.example.mlbbresearch.inference

import android.content.Context
import android.net.Uri
import com.example.mlbbresearch.core.Diag
import org.json.JSONObject
import java.util.Locale

// PHASE 3a SPIKE (owner-approved order One -> 3a -> Two): does llama.cpp build in Code on the Go,
// load a GGUF and answer on the phone, and how fast? One button on the Model page runs one fixed
// question through a GGUF the owner picks. It is not connected to the AI in any way: no advice,
// no touches, the model is freed right after. Phase 3b turns this into a real InferenceBackend.
object LlamaCppSpike {

    const val PROMPT = "In two short sentences: what should a jungler do in the first two minutes of a MOBA match?"
    const val MAX_TOKENS = 64
    const val CONTEXT = 512

    @Volatile var loadError: String? = null; private set
    @Volatile var running = false; private set
    @Volatile var last: String = "Not run yet."; private set

    /** The native library loaded (built for 64-bit ARM phones only). */
    val available: Boolean by lazy {
        try { System.loadLibrary("sphynx_llama"); true }
        catch (t: Throwable) { loadError = t.message ?: t.toString(); false }
    }

    private external fun nativeInfo(): String
    private external fun nativeRun(path: String, prompt: String, maxTokens: Int, threads: Int, nCtx: Int): ByteArray

    fun info(): String = if (available) try { nativeInfo() } catch (t: Throwable) { "error: ${t.message}" }
        else "native library not available on this phone: $loadError"

    // the big cores of most phones; llama.cpp gets slower when it also uses the small cores
    fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Runs the fixed question through the GGUF at [uri] on a background thread. */
    fun run(ctx: Context, uri: Uri, done: (String) -> Unit) {
        if (running) { done("A test is already running."); return }
        if (!available) { done(info()); return }
        running = true
        last = "Running... (loading the model can take a while)"
        Thread {
            val report = try {
                val name = displayName(ctx, uri)
                // the file is read in place through its descriptor: no copy of a large model
                ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    val t0 = System.currentTimeMillis()
                    val json = JSONObject(String(nativeRun("/proc/self/fd/${pfd.fd}", PROMPT, MAX_TOKENS, threads(), CONTEXT), Charsets.UTF_8))
                    format(name, json, System.currentTimeMillis() - t0)
                }
            } catch (t: Throwable) {
                "Test failed: ${t.message ?: t}"
            }
            last = report
            running = false
            Diag.info("GGUF test: " + report.replace('\n', ' ').take(300))
            done(report)
        }.apply { name = "llama-spike" }.start()
    }

    fun format(file: String, j: JSONObject, wallMs: Long): String {
        if (!j.optBoolean("ok")) return "$file: FAILED - ${j.optString("error")}"
        fun rate(n: Int, ms: Long) = if (ms <= 0) "-" else "%.1f tok/s".format(Locale.US, n * 1000.0 / ms)
        val pt = j.optInt("promptTokens"); val pm = j.optLong("promptMs")
        val gt = j.optInt("genTokens"); val gm = j.optLong("genMs")
        return buildString {
            append("$file: OK\n")
            append("Model: ${j.optString("model")} (${j.optLong("sizeBytes") / (1024 * 1024)} MB)\n")
            append("Load: %.1f s · threads %d · chat format %s\n".format(Locale.US, j.optLong("loadMs") / 1000.0, j.optInt("threads"),
                if (j.optBoolean("templated")) "yes" else "no (raw prompt)"))
            append("Prompt: $pt tokens in %.1f s (${rate(pt, pm)})\n".format(Locale.US, pm / 1000.0))
            append("Answer: $gt tokens in %.1f s (${rate(gt, gm)})\n".format(Locale.US, gm / 1000.0))
            append("Total: %.1f s\n".format(Locale.US, wallMs / 1000.0))
            append("Text: ").append(j.optString("text").trim().ifEmpty { "(empty)" })
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "model"
    } catch (e: Exception) { uri.lastPathSegment ?: "model" }
}
