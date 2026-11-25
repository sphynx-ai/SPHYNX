package com.sphynx.models

import android.content.Context
import com.sphynx.core.Cfg
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
    /** A file name that can't reach another folder ("a/b c.litertlm" -> "b_c.litertlm"). */
    fun safeFileName(raw: String): String = GgufLibrary.safeName(raw)

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

    // ---- Phase 6 S7: the model the owner chose for each job (RoleChoice); a GGUF choice is a file in the GGUF library ----
    fun choice(role: com.sphynx.inference.ModelRole): String? = Cfg.raw(com.sphynx.inference.roles.RoleChoice.settingKey(role))
    /** The chosen GGUF model for [role], or null when the job uses its LiteRT-LM file (or the chosen file is gone). */
    fun chosenGguf(ctx: Context, role: com.sphynx.inference.ModelRole): File? =
        com.sphynx.inference.roles.RoleChoice.ggufFile(choice(role))?.let { File(GgufLibrary.dir(ctx), it) }?.takeIf { it.isFile }
    fun choiceLabel(ctx: Context, role: com.sphynx.inference.ModelRole): String {
        val lite = if (role == com.sphynx.inference.ModelRole.STRATEGIC) strategicName() else tacticalName()
        val g = chosenGguf(ctx, role)
        return com.sphynx.inference.roles.RoleChoice.label(role, g?.let { com.sphynx.inference.roles.RoleChoice.gguf(it.name) }, lite,
            withPictures = g != null && GgufLibrary.projectorFor(g) != null)
    }

    fun status(ctx: Context): String {
        chosenGguf(ctx, com.sphynx.inference.ModelRole.STRATEGIC)?.let { return "${it.name} (GGUF, ${it.length() / 1048576} MB)" }
        val f = modelFile(ctx)
        return if (f.exists()) "${strategicName()} (${f.length() / 1048576} MB)" else "no model file yet"
    }
    fun tacticalStatus(ctx: Context): String {
        chosenGguf(ctx, com.sphynx.inference.ModelRole.TACTICAL)?.let { return "${it.name} (GGUF, ${it.length() / 1048576} MB)" }
        val f = tacticalFile(ctx)
        return if (f.exists()) "${tacticalName()} (${f.length() / 1048576} MB)" else "no small model file yet"
    }

    // ---- Phase 7 L3: will the job's model fit next to the game? (core/MemoryFit; warn, never block) ----
    fun fileFor(ctx: Context, role: com.sphynx.inference.ModelRole): File =
        chosenGguf(ctx, role) ?: if (role == com.sphynx.inference.ModelRole.STRATEGIC) modelFile(ctx) else tacticalFile(ctx)

    /** A warning when the job's model will probably not fit; null when it fits or nothing is known. */
    fun fitWarning(ctx: Context, role: com.sphynx.inference.ModelRole, alreadyLoadedMb: Long = 0L): String? {
        val f = fileFor(ctx, role)
        if (!f.isFile) return null
        val avail = try {
            val mi = android.app.ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            mi.availMem / 1_048_576L
        } catch (e: Exception) { 0L }
        return com.sphynx.core.MemoryFit.warning(f.name, f.length(), avail, alreadyLoadedMb)
    }

    /** A load nobody pressed a button for (start, a new file): a risky load is only written in the log. */
    private fun logFit(ctx: Context, role: com.sphynx.inference.ModelRole, loaded: Boolean) {
        val ownMb = if (loaded) fileFor(ctx, role).length() / 1_048_576L else 0L
        fitWarning(ctx, role, ownMb)?.let { com.sphynx.core.Diag.log("W", "Memory check (${role.name.lowercase()}): $it") }
    }

    fun load(ctx: Context, done: (String?) -> Unit) {
        val f = chosenGguf(ctx, com.sphynx.inference.ModelRole.STRATEGIC) ?: modelFile(ctx)
        if (!f.exists()) { done("No model file yet (put the .litertlm file in the Sphynx-Storage/Input folder)"); return }
        logFit(ctx, com.sphynx.inference.ModelRole.STRATEGIC, ModelHooks.strategicReady())
        if (ModelHooks.strategicLoading()) return
        ModelHooks.loadStrategic(ctx.applicationContext, f.absolutePath, Cfg.useGpu, done)   // runs in the AI's scope
    }

    // the small tactical model: CPU, text only (the GPU is left to the game and the big model)
    fun loadTactical(ctx: Context, done: (String?) -> Unit) {
        val f = chosenGguf(ctx, com.sphynx.inference.ModelRole.TACTICAL) ?: tacticalFile(ctx)
        if (!f.exists()) { done("No small model file yet (put e.g. the Gemma 3 270M .litertlm file in the Sphynx-Storage/Input folder)"); return }
        if (ModelHooks.tacticalLoading() || ModelHooks.tacticalReady()) { done(null); return }
        logFit(ctx, com.sphynx.inference.ModelRole.TACTICAL, false)
        ModelHooks.loadTactical(ctx.applicationContext, f.absolutePath, done)   // CPU, text only
    }

    fun unloadTactical() = ModelHooks.unloadTactical()
}
