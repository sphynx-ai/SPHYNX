package com.example.mlbbresearch.models

import android.content.Context
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.ai.GemmaEngine
import com.example.mlbbresearch.core.Cfg
import kotlinx.coroutines.launch
import java.io.File

// Keeps the Gemma 3n E2B model file (.litertlm) inside the app and loads it. The file itself is
// copied in from the MLBB-Research folder by AutoFolder.
object ModelManager {

    const val MODEL_PAGE = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"

    fun modelFile(ctx: Context) = File(ctx.applicationContext.filesDir, "gemma-3n-e2b.litertlm")

    fun status(ctx: Context): String {
        val f = modelFile(ctx)
        return if (f.exists()) "model file ready (${f.length() / 1048576} MB)" else "no model file yet"
    }

    fun load(ctx: Context, done: (String?) -> Unit) {
        val f = modelFile(ctx)
        if (!f.exists()) { done("No model file yet (put the .litertlm file in the MLBB-Research folder)"); return }
        if (GemmaEngine.loading) return
        AiRuntime.scope.launch {
            val err = GemmaEngine.load(ctx.applicationContext, f.absolutePath, Cfg.useGpu)
            done(err)
        }
    }
}
