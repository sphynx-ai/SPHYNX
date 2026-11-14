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
                val size = fileSize(ctx, uri)
                val head = ByteArray(16)
                val got = ctx.contentResolver.openInputStream(uri)!!.use { readFully(it, head) }
                val bad = checkHeader(head.copyOf(got), size)
                if (bad != null) "$name: FAILED - $bad"
                else {
                    val t0 = System.currentTimeMillis()
                    // first read in place through the file's descriptor (no copy of a large model)
                    var json = ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                        JSONObject(String(nativeRun("/proc/self/fd/${pfd.fd}", PROMPT, MAX_TOKENS, threads(), CONTEXT), Charsets.UTF_8))
                    }
                    var how = "read in place"
                    // some phones hand over the file in a form llama.cpp cannot open or map: copy it
                    // into the app's own storage and try once more (deleted right after)
                    if (!json.optBoolean("ok") && json.optString("error").startsWith("the model could not be loaded")) {
                        val tmp = java.io.File(ctx.cacheDir, "gguf-test.gguf")
                        if (size > 0 && ctx.cacheDir.usableSpace < size + 64L * 1024 * 1024) {
                            how = "read in place (no room to copy it: ${ctx.cacheDir.usableSpace / (1024 * 1024)} MB free)"
                        } else try {
                            last = "Running... (copying the model into the app, then loading it)"
                            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                            val first = json
                            json = JSONObject(String(nativeRun(tmp.absolutePath, PROMPT, MAX_TOKENS, threads(), CONTEXT), Charsets.UTF_8))
                            how = if (json.optBoolean("ok")) "copied into the app (reading in place failed)" else "read in place and copied: both failed"
                            if (!json.optBoolean("ok") && json.optString("log").isBlank()) json.put("log", first.optString("log"))
                        } finally { tmp.delete() }
                    }
                    format(name, json, System.currentTimeMillis() - t0, how, freeMemoryMb(ctx))
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

    // what is wrong with a picked file before llama.cpp is asked to load it (null = looks like a GGUF)
    fun checkHeader(head: ByteArray, size: Long): String? {
        val magic = "GGUF".toByteArray(Charsets.US_ASCII)
        if (head.size >= 4 && head.copyOf(4).contentEquals(magic)) {
            return if (size in 0 until 1024L * 1024) "only ${size / 1024} KB: the download did not finish. Download the model again." else null
        }
        if (head.isEmpty()) return "the file is empty. Download the model again."
        val start = String(head, Charsets.ISO_8859_1).trimStart()
        if (start.startsWith("<")) return "this is a web page saved as .gguf, not the model. Download it again with the file's download link."
        if (start.startsWith("version https://git-lfs")) return "this is a Git LFS pointer, not the model. Download it with the file's download link."
        val shown = head.take(8).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return "not a GGUF file (it starts with $shown instead of GGUF). Pick the .gguf model file itself."
    }

    // llama.cpp's last few warnings and errors, short enough to read on the phone
    fun lastLogLines(log: String, max: Int = 4): List<String> =
        log.lines().map { it.trim() }.filter { it.isNotEmpty() && it.any { c -> c.isLetter() } }.takeLast(max).map { it.take(220) }

    fun format(file: String, j: JSONObject, wallMs: Long, how: String = "", freeMb: Long = -1): String {
        if (!j.optBoolean("ok")) return buildString {
            append("$file: FAILED - ${j.optString("error")}")
            if (how.isNotEmpty()) append("\nFile: $how")
            if (freeMb >= 0) append("\nFree memory: $freeMb MB")
            val lines = lastLogLines(j.optString("log"))
            if (lines.isNotEmpty()) append("\nllama.cpp says:\n").append(lines.joinToString("\n"))
        }
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
            if (how.isNotEmpty()) append("File: $how\n")
            append("Text: ").append(j.optString("text").trim().ifEmpty { "(empty)" })
        }
    }

    private fun readFully(i: java.io.InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = i.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun fileSize(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) { -1L }

    private fun freeMemoryMb(ctx: Context): Long = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); mi.availMem / (1024 * 1024)
    } catch (e: Exception) { -1L }

    private fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "model"
    } catch (e: Exception) { uri.lastPathSegment ?: "model" }
}
