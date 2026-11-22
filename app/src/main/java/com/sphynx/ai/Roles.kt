package com.sphynx.ai

import android.content.Context
import android.graphics.Bitmap
import com.sphynx.core.Diag
import com.sphynx.inference.Accelerator
import com.sphynx.inference.BackendType
import com.sphynx.inference.InferenceBackend
import com.sphynx.inference.InferenceEvent
import com.sphynx.inference.InferenceRequest
import com.sphynx.inference.LiteRtLmBackend
import com.sphynx.inference.ModelConfiguration
import com.sphynx.inference.ModelRole
import com.sphynx.inference.RuntimeState
import com.sphynx.inference.roles.Assignment
import com.sphynx.inference.roles.RoleRouter
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// PHASE 6, step S4 (ADR 0002): the app's ONE way to a language model. Every caller asks a ROLE (strategic, tactical)
// here; only this file, ai/GemmaEngine.kt (the LiteRT-LM slots) and ai/LlmSlotPort.kt name the slots
// (RolesOnlyTest). Behind it, inference.roles.RoleRouter holds which model serves each role on which runtime.
//
// With the default assignment (today's: the strategic LiteRT-LM model in the GemmaEngine slot, the small model in the
// TacticalLlmEngine slot) every answer, load, picture and speed record is the same as before: the LiteRT-LM runtime
// asks the same slot, on the same thread, with the same sampler. The status words shown on the pages come from the slot
// for a LiteRT-LM role (unchanged text) and from the runtime's status for a GGUF role.
object Roles {

    val STRATEGIC = ModelRole.STRATEGIC
    val TACTICAL = ModelRole.TACTICAL

    @Volatile private var appCtx: Context? = null
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    private val strategicLite by lazy { LiteRtLmBackend(LlmSlotPort(GemmaEngine) { appCtx }) }
    private val tacticalLite by lazy { LiteRtLmBackend(LlmSlotPort(TacticalLlmEngine) { appCtx }) }

    /** The GGUF runtime (one per app, shared with the Model page's test question). Set by the app at start. */
    @Volatile var gguf: () -> InferenceBackend? = { null }

    val router = RoleRouter { role, type ->
        when (type) {
            BackendType.LITERT_LM -> if (role == ModelRole.STRATEGIC) strategicLite else tacticalLite
            BackendType.LLAMA_CPP -> gguf()
        }
    }

    private fun slot(role: ModelRole): LlmSlot = if (role == ModelRole.STRATEGIC) GemmaEngine else TacticalLlmEngine

    /** The runtime serving [role] now (LiteRT-LM when nothing else is assigned, as before). */
    fun backend(role: ModelRole): BackendType = router.assignment(role)?.backend ?: BackendType.LITERT_LM
    private fun onSlot(role: ModelRole) = backend(role) == BackendType.LITERT_LM

    // ---- what the pages and the advisers read (same meaning as the slot's fields before) ----

    fun ready(role: ModelRole): Boolean = if (onSlot(role)) slot(role).ready else router.status(role).ready
    fun loading(role: ModelRole): Boolean = if (onSlot(role)) slot(role).loading else router.status(role).state == RuntimeState.LOADING
    /** Does the role's model read pictures? */
    fun images(role: ModelRole): Boolean = if (onSlot(role)) slot(role).visionOk else router.capabilities(role).images
    /** The status words ("ready on GPU", "loading...", "not loaded"...). */
    fun state(role: ModelRole): String = if (onSlot(role)) slot(role).state else router.status(role).describe()
    /** Where the model runs ("GPU", "CPU, text only", "-" when not loaded). */
    fun where(role: ModelRole): String = if (onSlot(role)) slot(role).backendName
        else router.status(role).takeIf { it.ready }?.accelerator?.name ?: "-"
    fun lastLatencyMs(role: ModelRole): Long = if (onSlot(role)) slot(role).lastLatencyMs else router.status(role).lastLatencyMs
    fun calls(role: ModelRole): Int = if (onSlot(role)) slot(role).calls else router.status(role).calls
    /** The display name of the model serving [role] (null = the slot's usual name). */
    fun modelName(role: ModelRole): String? = router.assignment(role)?.takeIf { it.backend != BackendType.LITERT_LM }?.model?.displayName

    // ---- loading ----

    /**
     * Loads a LiteRT-LM model file for [role] (what ModelManager did through the slots before): the strategic model GPU
     * first (when [preferGpu]) with images, the tactical model on the CPU, text only. Returns null when it loaded.
     */
    fun loadLiteRt(role: ModelRole, path: String, preferGpu: Boolean): String? {
        val f = File(path)
        val cfg = ModelConfiguration(
            id = "litertlm:" + role.name.lowercase() + ":" + f.name, displayName = f.name, backend = BackendType.LITERT_LM,
            location = path, sizeBytes = if (f.isFile) f.length() else -1L, role = role,
            accelerators = if (role == ModelRole.STRATEGIC && preferGpu) listOf(Accelerator.GPU, Accelerator.CPU) else listOf(Accelerator.CPU),
            wantsVision = role == ModelRole.STRATEGIC)
        return load(Assignment(role, cfg))
    }

    /** Assigns and loads [a]; null when it loaded, else why not. */
    fun load(a: Assignment): String? {
        router.assign(a)?.let { return it }
        val st = router.load(a.role)
        return if (st.ready) null else (st.error?.message ?: "not loaded (${st.state.label})")
    }

    fun unload(role: ModelRole) = router.unload(role)

    // ---- asking ----

    private val ids = AtomicLong()
    private val running = ConcurrentHashMap<String, ModelRole>()

    /**
     * Asks [role] one question, optionally with a screenshot, and returns the reply (what LlmSlot.ask did). As before, a
     * picture is sent only when the role's model reads pictures; callers check [images] first. Throws when the role has
     * no model loaded or the answer fails or is cancelled.
     */
    suspend fun ask(role: ModelRole, prompt: String, image: Bitmap? = null, temperature: Double = 0.15, purpose: String = "other"): String {
        val id = role.name.lowercase() + "-" + ids.incrementAndGet()
        val jpeg = if (image != null && images(role)) LlmSlot.jpegForModel(image) else null
        val maxOut = router.status(role).config?.maxOutputTokens ?: 256
        val req = InferenceRequest(id = id, prompt = prompt, image = jpeg, temperature = temperature.toFloat().coerceIn(0f, 2f),
            maxOutputTokens = minOf(512, maxOut).coerceAtLeast(1), timeoutMs = 600_000L, purpose = purpose)
        val t0 = System.currentTimeMillis()
        val gguf = !onSlot(role)
        running[id] = role
        try {
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { router.cancel(role, id) }
                router.ask(role, req) { e ->
                    when (e) {
                        is InferenceEvent.Completed -> { if (cont.isActive) cont.resume(e.text) }
                        is InferenceEvent.Failed -> { if (cont.isActive) cont.resumeWithException(IllegalStateException(e.error.message)) }
                        is InferenceEvent.Cancelled -> { if (cont.isActive) cont.resumeWithException(IllegalStateException("the ${role.name.lowercase()} answer was cancelled")) }
                        else -> {}
                    }
                }
            }.also { reply ->
                // the LiteRT-LM slot keeps its own speed records; a GGUF role is recorded here (S5)
                if (gguf) recordSpeed(role, purpose, jpeg != null, prompt.length, reply.length, System.currentTimeMillis() - t0, true, "")
            }
        } catch (t: Throwable) {
            if (gguf && t !is kotlinx.coroutines.CancellationException)
                recordSpeed(role, purpose, jpeg != null, prompt.length, 0, System.currentTimeMillis() - t0, false, t.message ?: t.toString())
            throw t
        } finally {
            running.remove(id)
        }
    }

    /** Emergency stop: interrupts the role's answer (what GemmaEngine.cancel() did). */
    fun cancel(role: ModelRole) {
        for ((id, r) in running) if (r == role) router.cancel(role, id)
        if (onSlot(role)) slot(role).cancel()
    }

    private fun recordSpeed(role: ModelRole, purpose: String, image: Boolean, promptChars: Int, replyChars: Int, ms: Long, ok: Boolean, error: String) {
        try {
            ModelSpeed.call(role.name.lowercase(), purpose, "GGUF " + where(role), image, promptChars, replyChars, ms, ok, error,
                false, null, null, null, null, null)
        } catch (t: Throwable) { Diag.error("Speed record failed", t) }
    }
}
