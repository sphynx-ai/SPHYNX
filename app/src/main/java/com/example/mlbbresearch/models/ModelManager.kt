package com.example.mlbbresearch.models

import android.content.Context
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.ai.GemmaEngine
import com.example.mlbbresearch.ai.TacticalLlmEngine
import com.example.mlbbresearch.core.Cfg
import kotlinx.coroutines.launch
import java.io.File

// The two language-model slots and their .litertlm files (copied in from the Sphynx
// folder by AutoFolder):
//   STRATEGIC: Gemma 4 E2B (recommended) or Gemma 3n E2B - big, GPU first, asked now and then
//   TACTICAL:  a small Gemma (Gemma 3 270M, or 1B) - CPU, text only, asked ~3 times a second
//              in fights; its answer is only advice that the Kotlin scores check first
// Which slot a file goes to: "270m" / "1b" / "tactic" in its name, or under 1 GB = tactical;
// anything else = strategic (a Gemma 4 file wins over an older one when both are there).
object ModelManager {

    const val MODEL_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
    const val SMALL_MODEL_PAGE = "https://huggingface.co/litert-community?search_models=gemma-3-270m"

    fun modelFile(ctx: Context): File {
        val dir = ctx.applicationContext.filesDir
        val f = File(dir, "gemma-strategic.litertlm")
        // (older versions kept the file under this name)
        val old = File(dir, "gemma-3n-e2b.litertlm")
        if (!f.exists() && old.exists()) { old.renameTo(f); if (Cfg.raw("llm_name_strategic") == null) Cfg.setRaw("llm_name_strategic", "Gemma 3n E2B") }
        return f
    }
    fun tacticalFile(ctx: Context) = File(ctx.applicationContext.filesDir, "gemma-tactical.litertlm")

    // which slot a .litertlm file belongs to
    fun isTactical(name: String, size: Long): Boolean {
        val n = name.lowercase()
        if (n.contains("e2b") || n.contains("e4b") || n.contains("3n")) return false
        return n.contains("270m") || n.contains("tactic") || Regex("(^|[^0-9])1b").containsMatchIn(n) || size in 1..1_000_000_000L
    }
    // a newer strategic model is preferred over an older one
    fun strategicRank(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("gemma-4") || n.contains("gemma4") || n.contains("gemma_4") -> 3
            n.contains("3n") -> 2
            else -> 1
        }
    }
    fun prettyName(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("gemma-4") || n.contains("gemma4") || n.contains("gemma_4") -> if (n.contains("e4b")) "Gemma 4 E4B" else "Gemma 4 E2B"
            n.contains("3n") -> if (n.contains("e4b")) "Gemma 3n E4B" else "Gemma 3n E2B"
            n.contains("270m") -> "Gemma 3 270M"
            Regex("(^|[^0-9])1b").containsMatchIn(n) -> "Gemma 3 1B"
            else -> name.substringBeforeLast('.')
        }
    }
    fun strategicName(): String = Cfg.raw("llm_name_strategic") ?: "Gemma"
    fun tacticalName(): String = Cfg.raw("llm_name_tactical") ?: "small Gemma"

    fun status(ctx: Context): String {
        val f = modelFile(ctx)
        return if (f.exists()) "${strategicName()} (${f.length() / 1048576} MB)" else "no model file yet"
    }
    fun tacticalStatus(ctx: Context): String {
        val f = tacticalFile(ctx)
        return if (f.exists()) "${tacticalName()} (${f.length() / 1048576} MB)" else "no small model file yet"
    }

    fun load(ctx: Context, done: (String?) -> Unit) {
        val f = modelFile(ctx)
        if (!f.exists()) { done("No model file yet (put the .litertlm file in the Sphynx-Storage folder)"); return }
        if (GemmaEngine.loading) return
        AiRuntime.scope.launch {
            val err = GemmaEngine.load(ctx.applicationContext, f.absolutePath, Cfg.useGpu)
            done(err)
        }
    }

    // the small tactical model: CPU, text only (the GPU is left to the game and the big model)
    fun loadTactical(ctx: Context, done: (String?) -> Unit) {
        val f = tacticalFile(ctx)
        if (!f.exists()) { done("No small model file yet (put e.g. the Gemma 3 270M .litertlm file in the Sphynx-Storage folder)"); return }
        if (TacticalLlmEngine.loading || TacticalLlmEngine.ready) { done(null); return }
        AiRuntime.scope.launch {
            val err = TacticalLlmEngine.load(ctx.applicationContext, f.absolutePath, preferGpu = false, textOnly = true)
            done(err)
        }
    }

    fun unloadTactical() { AiRuntime.scope.launch { TacticalLlmEngine.unload() } }
}
