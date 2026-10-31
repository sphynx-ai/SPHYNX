package com.example.mlbbresearch.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.ai.GemmaEngine
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import kotlinx.coroutines.launch
import java.io.File

// Keeps the Gemma 3n E2B model file (.litertlm) inside the app and loads it.
object ModelManager {

    const val MODEL_PAGE = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"

    @Volatile var copyStatus: String = ""
        private set
    @Volatile var copying = false
        private set

    fun modelFile(ctx: Context) = File(ctx.applicationContext.filesDir, "gemma-3n-e2b.litertlm")

    fun status(ctx: Context): String {
        val f = modelFile(ctx)
        return when {
            copying -> copyStatus
            f.exists() -> "model file ready (${f.length() / 1048576} MB)"
            else -> "no model file yet"
        }
    }

    // Copies the chosen file into the app (the model must be readable by path for LiteRT-LM)
    fun import(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        if (copying) return
        val app = ctx.applicationContext
        val name = queryName(app, uri)
        if (name != null && !name.endsWith(".litertlm", ignoreCase = true)) {
            done("That file is \"$name\". LiteRT-LM needs a .litertlm model file.")
            return
        }
        AiRuntime.emergencyStop("replacing the model")
        copying = true
        copyStatus = "copying..."
        Thread {
            var err: String? = null
            try {
                AiRuntime.scope.launch { GemmaEngine.unload() }
                val tmp = File(app.filesDir, "model.tmp")
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done2 = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done2 += n
                            copyStatus = "copying... ${done2 / 1048576} MB"
                        }
                    }
                }
                val dst = modelFile(app)
                dst.delete()
                if (!tmp.renameTo(dst)) throw IllegalStateException("could not save the model")
                // old MediaPipe model from the previous version is no longer used
                File(app.filesDir, "player_model.task").delete()
                Diag.info("Model copied (${dst.length() / 1048576} MB)")
            } catch (e: Exception) {
                err = e.message ?: e.toString()
                Diag.error("Model copy failed", e)
            }
            copying = false
            copyStatus = ""
            done(err)
        }.start()
    }

    fun load(ctx: Context, done: (String?) -> Unit) {
        val f = modelFile(ctx)
        if (!f.exists()) { done("Choose the model file first"); return }
        if (GemmaEngine.loading) return
        AiRuntime.scope.launch {
            val err = GemmaEngine.load(ctx.applicationContext, f.absolutePath, Cfg.useGpu)
            done(err)
        }
    }

    fun delete(ctx: Context) {
        AiRuntime.scope.launch { GemmaEngine.unload() }
        modelFile(ctx).delete()
    }

    // Optional trained detector (.tflite made by notebooks/train_mlbb_detector.ipynb)
    fun importDetector(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        val name = queryName(app, uri)
        if (name != null && !name.endsWith(".tflite", ignoreCase = true)) {
            done("That file is \"$name\". The detector must be a .tflite file.")
            return
        }
        Thread {
            var err: String? = null
            try {
                val dst = com.example.mlbbresearch.vision.ObjectDetector.modelFile(app)
                app.contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
                Diag.info("Detector copied (${dst.length() / 1024} KB)")
                err = com.example.mlbbresearch.vision.ObjectDetector.load(app)
            } catch (e: Exception) {
                err = e.message ?: e.toString()
                Diag.error("Detector copy failed", e)
            }
            done(err)
        }.start()
    }

    // Trained tactical network (.tflite made by notebooks/train_tactical_policy.ipynb)
    fun importPolicy(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        val name = queryName(app, uri)
        if (name != null && !name.endsWith(".tflite", ignoreCase = true)) {
            done("That file is \"$name\". The tactical network must be a .tflite file.")
            return
        }
        Thread {
            var err: String? = null
            try {
                val dst = com.example.mlbbresearch.learning.PolicyNet.modelFile(app)
                app.contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
                Diag.info("Tactical network copied (${dst.length() / 1024} KB)")
                err = com.example.mlbbresearch.learning.PolicyNet.load(app)
            } catch (e: Exception) {
                err = e.message ?: e.toString()
                Diag.error("Tactical network copy failed", e)
            }
            done(err)
        }.start()
    }

    private fun queryName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
