package com.sphynx.models

import android.content.Context
import android.net.Uri
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.inference.Accelerator
import com.sphynx.inference.BackendType
import com.sphynx.inference.GgufFile
import com.sphynx.inference.LlamaCpu
import com.sphynx.inference.LlamaCppBackend
import com.sphynx.inference.LlamaJni
import com.sphynx.inference.ModelConfiguration
import com.sphynx.inference.StorageMode
import java.io.File

// PHASE 8: the GGUF models the app holds, and the one GGUF backend that runs them.
// A model is added by copying the picked .gguf into the app's own storage (files/gguf): on the owner's
// phone, reading a picked file in place failed while the same file copied into the app loaded
// (2026-11-14 spike test). Nothing is loaded by itself: the owner loads a model on the Model page.
object GgufLibrary {

    const val CONTEXT_TOKENS = 2048
    const val MAX_OUTPUT_TOKENS = 512

    @Volatile var busy = false; private set
    @Volatile var status = ""; private set

    @Volatile private var backendInstance: LlamaCppBackend? = null

    /** The GGUF backend (one per app). */
    fun backend(ctx: Context): LlamaCppBackend = backendInstance ?: synchronized(this) {
        backendInstance ?: LlamaCppBackend(LlamaJni) { freeMemory(ctx.applicationContext) }.also { backendInstance = it }
    }

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "gguf").apply { mkdirs() }

    /** The added models, by name. */
    /** Phase 8 G2: a model's description for the Model page ("llama · Q4_K_M · 1B · ..."), or why it can't be read. */
    fun describe(f: File): String = com.sphynx.inference.GgufInfo.read(f).fold({ it.summary() }, { "can't be read: ${it.message}" })

    fun models(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !com.sphynx.inference.MmprojPairing.isProjector(f.name) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Phase 6 S8: the multimodal projector files (mmproj) in the library. */
    fun projectors(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && com.sphynx.inference.MmprojPairing.isProjector(f.name) }?.toList() ?: emptyList()

    /** The model's projector file in the library (its mmproj), or null. */
    fun projectorFor(file: File): File? {
        val dir = file.parentFile ?: return null
        val all = projectors(dir)
        return com.sphynx.inference.MmprojPairing.pairFor(file.name, all.map { it.name })?.let { n -> all.firstOrNull { it.name == n } }
    }

    fun config(file: File): ModelConfiguration = ModelConfiguration(
        id = "gguf:" + file.name,
        displayName = file.name,
        backend = BackendType.LLAMA_CPP,
        location = file.absolutePath,
        storage = StorageMode.APP_COPY,
        sizeBytes = file.length(),
        accelerators = listOf(Accelerator.CPU),     // CPU only in this build
        contextTokens = CONTEXT_TOKENS,
        maxOutputTokens = MAX_OUTPUT_TOKENS,
        // Phase 8 G5: the thread count the benchmark found best for this file on this phone, else the default
        threads = ModelRegistry.fact(file.name, file.length(), ModelRecord.Fact.THREADS)?.toIntOrNull()?.takeIf { it in 1..16 }
            ?: LlamaCpu.threads(),
        projector = projectorFor(file)?.absolutePath
    )

    /** Copies the picked .gguf into the app (checked first); [done] gets a message for the owner. */
    fun add(ctx: Context, uri: Uri, done: (String) -> Unit) {
        if (busy) { done("A model is already being added"); return }
        busy = true
        status = "Checking the file..."
        val app = ctx.applicationContext
        Thread {
            val msg = try { copyIn(app, uri) } catch (t: Throwable) { "Could not add the model: ${t.message ?: t}" }
            status = msg
            busy = false
            Diag.info("GGUF: $msg")
            done(msg)
        }.apply { name = "gguf-add" }.start()
    }

    /** Phase 9 M9: adds a file the app already has (a download in Sphynx-Storage/Input); the message, as [add] gives it. */
    fun addFile(ctx: Context, file: File): String {
        if (busy) return "A model is already being added"
        busy = true
        val msg = try { copyIn(ctx.applicationContext, file.name, file.length()) { file.inputStream() } }
            catch (t: Throwable) { "Could not add the model: ${t.message ?: t}" }
        status = msg; busy = false; Diag.info("GGUF: $msg")
        return msg
    }

    private fun copyIn(ctx: Context, uri: Uri): String {
        val cr = ctx.contentResolver
        var name = "model.gguf"
        var size = -1L
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                if (!it.isNull(0)) name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        return copyIn(ctx, name, size) { cr.openInputStream(uri) }
    }

    private fun copyIn(ctx: Context, name0: String, size: Long, open: () -> java.io.InputStream?): String {
        val name = safeName(name0)
        if (!name.endsWith(".gguf", ignoreCase = true)) return "$name is not a .gguf file"
        val head = ByteArray(16)
        val got = open()?.use { s -> var n = 0; while (n < head.size) { val r = s.read(head, n, head.size - n); if (r < 0) break; n += r }; n } ?: 0
        GgufFile.checkHeader(head.copyOf(got), size)?.let { return "$name: $it" }
        // Phase 8 G2: the file's own description, read before anything is copied; a broken file or a family the built-in
        // llama.cpp doesn't know is refused here (a projector is fine: it goes next to its model)
        val info = open()?.use { com.sphynx.inference.GgufInfo.read(it, size) }
        info?.exceptionOrNull()?.let { return "$name: ${it.message}" }
        info?.getOrNull()?.let { i ->
            if (!i.isProjector) i.problemAsModel()?.let { return "$name: $it" }
            com.sphynx.core.Diag.info("GGUF $name: ${i.summary()}")
        }
        val dir = dir(ctx)
        ModelStorage.copyProblem(size, dir.usableSpace)?.let { return "Not enough room for $name: $it" }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            // Phase 9 M2: the SHA-256 is worked out during the copy
            val sha = (open() ?: throw IllegalStateException("cannot open $name")).use { input ->
                part.outputStream().use { out ->
                    com.sphynx.core.FileHash.copyAndHash(input, out) { copied ->
                        status = if (size > 0) "Copying $name: ${copied * 100 / size}%" else "Copying $name: ${mb(copied)} MB"
                    }
                }
            }
            // the same model already in the library under another name: not kept twice
            ModelRegistry.records.firstOrNull { it.sha256 == sha && it.fileName != name }?.let { dup ->
                return "$name is the same file as ${dup.fileName}, which is already added"
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) return "Could not save $name"
            Cfg.setRaw(com.sphynx.core.FileHash.key(target.name, target.length()), sha)
        } finally {
            if (part.exists()) part.delete()
        }
        try { ModelRegistry.refresh(ctx) } catch (t: Throwable) { }     // Phase 9 M1
        return "Added $name (${mb(target.length())} MB)"
    }

    /** The added model that is loaded now, if any. */
    fun loadedFile(ctx: Context): File? {
        val cfg = backend(ctx).status().config ?: return null
        return models(ctx).firstOrNull { it.absolutePath == cfg.location }
    }

    /** Loads [file] (the one loaded before is freed first); [done] gets a message for the owner. */
    fun load(ctx: Context, file: File, done: (String) -> Unit) {
        if (busy) { done("Wait: ${status.ifBlank { "busy" }}"); return }
        busy = true
        status = "Loading ${file.name}..."
        val be = backend(ctx)
        Thread {
            val t0 = System.currentTimeMillis()
            val st = be.load(config(file))
            val msg = if (st.ready) {
                val info = be.modelInfo
                "Loaded ${file.name} in %.1f s".format(java.util.Locale.US, (System.currentTimeMillis() - t0) / 1000.0) +
                    (info?.optString("model")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            } else "${file.name} did not load: ${st.error?.message ?: "unknown reason"}"
            status = msg
            busy = false
            Diag.info("GGUF: " + msg.replace('\n', ' '))
            done(msg)
        }.apply { name = "gguf-load" }.start()
    }

    fun unload(ctx: Context) {
        backendInstance?.unload()
        status = "No GGUF model loaded"
    }

    /** Removes an added model (unloaded first if it is the loaded one); true when it is gone. */
    fun remove(ctx: Context, file: File): Boolean {
        if (loadedFile(ctx) == file) unload(ctx)
        val gone = file.parentFile == dir(ctx) && file.delete()
        if (gone) try { ModelRegistry.refresh(ctx) } catch (t: Throwable) { }     // Phase 9 M1
        return gone
    }

    // ---- the test question on the Model page (same question as the 2026-11-14 spike runs) ----

    const val TEST_PROMPT = "In two short sentences: what should a jungler do in the first two minutes of a MOBA match?"
    @Volatile var test: com.sphynx.inference.EventCollector? = null; private set
    @Volatile var testLine = ""; private set

    /** Asks the loaded model the test question, streaming; null when it started, else why not. */
    fun askTest(ctx: Context): String? {
        val be = backend(ctx)
        if (!be.status().ready) return "Load a GGUF model first"
        if (test?.done == false) return "Still answering"
        val id = "test-" + System.currentTimeMillis()
        val c = com.sphynx.inference.EventCollector(id)
        test = c
        testLine = "Answering..."
        val model = be.status().config?.displayName ?: "?"
        val req = com.sphynx.inference.InferenceRequest(id, TEST_PROMPT, temperature = 0f,
            maxOutputTokens = 128, stream = true, timeoutMs = 120_000L)
        be.generate(req) { e ->
            c.accept(e)
            if (e.terminal) {
                testLine = when (e) {
                    is com.sphynx.inference.InferenceEvent.Completed ->
                        LlamaCppBackend.speedLine(be.lastRun) + " · total %.1f s".format(java.util.Locale.US, e.stats.totalMs / 1000.0)
                    is com.sphynx.inference.InferenceEvent.Failed -> "Failed: " + e.error.message
                    else -> "Stopped"
                }
                Diag.info("GGUF answer ($model): $testLine · " + c.partial().replace('\n', ' ').take(160))
            }
        }
        return null
    }

    fun stopTest(ctx: Context) {
        val c = test ?: return
        if (!c.done) backend(ctx).cancel(c.requestId)
    }

    /** "Abc.gguf" stays; anything that could reach another folder or confuse a path becomes "_". */
    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("").trimStart('.')
        return clean.ifEmpty { "model.gguf" }.take(120)
    }

    private fun freeMemory(ctx: Context): Long = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); mi.availMem
    } catch (e: Exception) { -1L }

    private fun mb(b: Long) = b / (1024 * 1024)
}
