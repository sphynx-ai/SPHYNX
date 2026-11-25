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
    @Volatile private var sentinelChecked = false
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!sentinelChecked) {
            sentinelChecked = true
            for ((role, name) in sentinel.checkAtStart())
                Diag.error("$name was loading for the ${role.name.lowercase()} job when the app stopped: it is not loaded by itself again (load it by hand to try again)")
            // Phase 7 L9: the processor that was being tried when the app stopped is skipped from now on
            for (r in ModelRole.values()) com.sphynx.core.Cfg.raw(tryingKey(r))?.let { v ->
                val model = v.substringBeforeLast('|')
                val acc = try { Accelerator.valueOf(v.substringAfterLast('|')) } catch (e: Exception) { null }
                if (acc != null && acc != Accelerator.CPU) {
                    processors.failed(model, acc)
                    Diag.error("Processor choice: ${acc.name} is skipped for $model: the app stopped while it was loading on it")
                }
                com.sphynx.core.Cfg.setRawNow(tryingKey(r), null)
            }
        }
    }

    // Phase 6 S6: a model that was loading when the app stopped is not loaded again by itself (owner, 2026-11-22)
    private val sentinel = com.sphynx.inference.roles.LoadSentinel(object : com.sphynx.inference.roles.LoadSentinel.Store {
        override fun get(key: String) = com.sphynx.core.Cfg.raw(key)
        override fun setNow(key: String, value: String?) = com.sphynx.core.Cfg.setRawNow(key, value)
    })
    /** The next load is the owner's own (a button), so a model suspected of a crash may be tried again. */
    @Volatile private var byOwner = false
    fun loadByOwner() { byOwner = true; sentinel.forgiveAll() }
    /** Why [role] has no model although one is set, after a crash while loading (null = no such problem). */
    fun crashNote(role: ModelRole): String? = sentinel.suspected(role)?.let {
        "$it stopped the app while loading last time, so it is not loaded by itself; load it by hand to try again" }

    private val strategicLite by lazy { LiteRtLmBackend(LlmSlotPort(GemmaEngine) { appCtx }).apply { onNotice = ::fallbackNotice } }
    private val tacticalLite by lazy { LiteRtLmBackend(LlmSlotPort(TacticalLlmEngine) { appCtx }).apply { onNotice = ::fallbackNotice } }

    // Phase 7 L4: a GPU failure mid-match moved a model to the CPU (or unloaded it): the log and, for 2 minutes, the panel
    @Volatile private var fallbackLine = ""
    @Volatile private var fallbackUntil = 0L
    private fun fallbackNotice(line: String) {
        Diag.error(line)
        fallbackLine = line; fallbackUntil = System.currentTimeMillis() + 2 * 60_000L
    }
    /** The floating panel's line about a GPU fallback ("" when there is nothing to say). */
    fun panelLine(nowMs: Long = System.currentTimeMillis()): String = if (nowMs < fallbackUntil) fallbackLine else ""

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
    fun state(role: ModelRole): String = (if (!ready(role)) crashNote(role) else null)
        ?: if (onSlot(role)) slot(role).state else router.status(role).describe()
    /** Where the model runs ("GPU", "CPU, text only", "-" when not loaded). */
    fun where(role: ModelRole): String = if (onSlot(role)) slot(role).backendName
        else router.status(role).takeIf { it.ready }?.accelerator?.name ?: "-"
    fun lastLatencyMs(role: ModelRole): Long = if (onSlot(role)) slot(role).lastLatencyMs else router.status(role).lastLatencyMs
    fun calls(role: ModelRole): Int = if (onSlot(role)) slot(role).calls else router.status(role).calls
    /** The display name of the model serving [role] (null = the slot's usual name). */
    fun modelName(role: ModelRole): String? = router.assignment(role)?.takeIf { it.backend != BackendType.LITERT_LM }?.model?.displayName

    // ---- loading ----

    // ---- Phase 7 L9: the best processor per model, from this phone's own results (inference.roles.ProcessorMemory) ----

    /** The LiteRT-LM version in app/build.gradle.kts: a new one tries every processor again. */
    const val LITERT_LM_VERSION = "0.19.1"
    private val processors by lazy {
        com.sphynx.inference.roles.ProcessorMemory(object : com.sphynx.inference.roles.LoadSentinel.Store {
            override fun get(key: String) = com.sphynx.core.Cfg.raw(key)
            override fun setNow(key: String, value: String?) = com.sphynx.core.Cfg.setRaw(key, value)
        }, "app ${com.sphynx.core.AppVersion.VERSION}, litertlm $LITERT_LM_VERSION") {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        }
    }
    private fun tryingKey(role: ModelRole) = "proc_trying_" + role.name.lowercase()
    private val loadedKey = ConcurrentHashMap<ModelRole, String>()

    /** The processors the switches allow for [role], in the usual order. */
    fun allowedProcessors(role: ModelRole, preferGpu: Boolean = com.sphynx.core.Cfg.useGpu): List<Accelerator> = when {
        // Phase 7 L8: the experimental NPU first (owner's switch, off by default), then the GPU when allowed, then the CPU
        role == ModelRole.STRATEGIC && com.sphynx.core.Cfg.useNpu ->
            if (preferGpu) listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU) else listOf(Accelerator.NPU, Accelerator.CPU)
        role == ModelRole.STRATEGIC && preferGpu -> listOf(Accelerator.GPU, Accelerator.CPU)
        else -> listOf(Accelerator.CPU)
    }

    /** The Model page's line: which processor is tried first for [file], and why. */
    fun processorLine(role: ModelRole, file: File): String =
        processors.explain(com.sphynx.inference.roles.ProcessorMemory.modelKey(file.name, file.length()), allowedProcessors(role))

    /**
     * Loads a LiteRT-LM model file for [role] (what ModelManager did through the slots before): the strategic model GPU
     * first (when [preferGpu]) with images, the tactical model on the CPU, text only. Returns null when it loaded.
     * Phase 7 L9: the order comes from this phone's results for this file (the best first, failed ones skipped).
     */
    fun loadLiteRt(role: ModelRole, path: String, preferGpu: Boolean): String? {
        val f = File(path)
        val key = com.sphynx.inference.roles.ProcessorMemory.modelKey(f.name, if (f.isFile) f.length() else -1L)
        val allowed = allowedProcessors(role, preferGpu)
        val order = processors.order(key, allowed)
        if (order != allowed) Diag.info("Processor choice for ${f.name}: " + processors.explain(key, allowed))
        val cfg = ModelConfiguration(
            id = "litertlm:" + role.name.lowercase() + ":" + f.name, displayName = f.name, backend = BackendType.LITERT_LM,
            location = path, sizeBytes = if (f.isFile) f.length() else -1L, role = role,
            accelerators = order,
            // Phase 8 G11: the tactical job reads pictures too when the owner's switch is on (and the model can)
            wantsVision = role == ModelRole.STRATEGIC || (role == ModelRole.TACTICAL && com.sphynx.core.Cfg.tacticalSightOn &&
                com.sphynx.core.Cfg.raw("vision_" + f.name) != "0"))
        // what the slot will really try, in its order (NPU only when first; GPU when before the CPU; then the CPU)
        val tried = listOfNotNull(Accelerator.NPU.takeIf { LiteRtLmBackend.preferNpu(order) },
            Accelerator.GPU.takeIf { LiteRtLmBackend.preferGpu(order) }, Accelerator.CPU)
        com.sphynx.core.Cfg.setRawNow(tryingKey(role), key + "|" + tried.first().name)
        val t0 = System.currentTimeMillis()
        val err = try { load(Assignment(role, cfg)) } finally { com.sphynx.core.Cfg.setRawNow(tryingKey(role), null) }
        val st = router.status(role)
        val landed = st.accelerator
        if (err == null && st.ready && landed != null) {
            for (a in tried.takeWhile { it != landed }) processors.failed(key, a)
            processors.loaded(key, landed, System.currentTimeMillis() - t0)
            loadedKey[role] = key
        } else loadedKey.remove(role)
        return err
    }

    /** Assigns and loads [a]; null when it loaded, else why not. */
    fun load(a: Assignment): String? {
        val owner = byOwner.also { byOwner = false }
        if (a.role == ModelRole.TACTICAL) TacticalSight.resetFailures()     // a new load may read pictures again
        sentinel.refusal(a.role, a.model.displayName, owner)?.let { e -> Diag.info("Not loaded: " + e.message); return e.message }
        router.assign(a)?.let { return it }
        sentinel.loading(a.role, a.model.displayName)
        val st = try { router.load(a.role) } finally { sentinel.loaded(a.role) }
        if (st.ready && a.backend == BackendType.LITERT_LM && a.model.wantsVision)   // both jobs (G11)
            com.sphynx.core.Cfg.setRaw("vision_" + a.model.displayName, if (st.visionOk) "1" else "0")
        // Phase 7 L5: the error and what to do about it
        return if (st.ready) null else (st.error?.let { com.sphynx.inference.LiteRtErrorWords.words(it) } ?: "not loaded (${st.state.label})")
    }

    fun unload(role: ModelRole) = router.unload(role)

    // ---- stepping down to a smaller model (owner decision 2026-11-22; only in "Step down gently", Phase 5 P5) ----

    private val original = ConcurrentHashMap<ModelRole, Assignment>()
    /** What a stepped-down role runs now, for the floating panel ("" when nothing was stepped down). */
    @Volatile var stepDownLine = ""; private set

    /** Does this model read pictures? Known from an earlier load on this phone, else from its name (Gemma 3n / 4). */
    fun readsPictures(c: ModelConfiguration): Boolean {
        if (c.backend != BackendType.LITERT_LM) return c.projector != null    // GGUF: through its mmproj file (S8)
        com.sphynx.core.Cfg.raw("vision_" + c.displayName)?.let { return it == "1" }
        val n = c.displayName.lowercase()
        return n.contains("3n") || n.contains("gemma-4") || n.contains("gemma4") || n.contains("gemma_4") || c.id.startsWith("litertlm:strategic")
    }

    /** Every model on the phone that a role could step down to (LiteRT-LM files and the GGUF library). */
    fun candidates(role: ModelRole): List<ModelConfiguration> {
        val c = appCtx ?: return emptyList()
        val out = ArrayList<ModelConfiguration>()
        val lite = ArrayList<File>()
        lite.add(com.sphynx.models.ModelManager.modelFile(c)); lite.add(com.sphynx.models.ModelManager.tacticalFile(c))
        com.sphynx.core.Storage.dir(com.sphynx.core.StorageLayout.INPUT)?.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".litertlm", true) || it.name.endsWith(".task", true)) }?.let { lite.addAll(it) }
        for (f in lite.distinctBy { it.absolutePath }) if (f.isFile) out.add(ModelConfiguration(
            id = "litertlm:" + f.absolutePath, displayName = f.name, backend = BackendType.LITERT_LM, location = f.absolutePath,
            sizeBytes = f.length(),
            accelerators = if (role == ModelRole.STRATEGIC && com.sphynx.core.Cfg.useGpu) listOf(Accelerator.GPU, Accelerator.CPU) else listOf(Accelerator.CPU)))
        try { com.sphynx.models.GgufLibrary.models(c).forEach { out.add(com.sphynx.models.GgufLibrary.config(it)) } } catch (t: Throwable) { }
        return out
    }

    /**
     * Frees [role]'s model and, when a smaller compatible model is on the phone, loads that one instead (strategic: only
     * one that reads pictures). The owner's model is kept to come back in [restore].
     */
    fun stepDown(role: ModelRole) {
        val now = router.assignment(role) ?: return
        original.putIfAbsent(role, now)
        router.unload(role)
        val pick = com.sphynx.inference.roles.StepDown.choose(role, now.model, candidates(role), ::readsPictures) { cand ->
            router.problemWith(Assignment(role, cand)) }
        if (pick == null) {
            Diag.info("Pressure: the ${role.name.lowercase()} model is unloaded (no smaller model to step down to)")
            return
        }
        val cfg = com.sphynx.inference.roles.StepDown.configFor(role, pick, readsPictures(pick), com.sphynx.core.Cfg.tacticalSightOn)
        val err = load(Assignment(role, cfg))
        if (err == null && role == ModelRole.STRATEGIC && !images(role)) {
            Diag.info("Pressure: ${pick.displayName} loaded without pictures, so the strategic job stays unloaded")
            router.unload(role)
            return
        }
        stepDownLine = if (err == null) com.sphynx.inference.roles.StepDown.notice(role, pick) else ""
        Diag.info(if (err == null) "Pressure: ${role.name.lowercase()} job stepped down to ${pick.displayName}" else "Pressure: stepping down to ${pick.displayName} failed: $err")
    }

    /** Brings back the owner's model after the pressure is over (the policy's cool-down, never in a fight). */
    fun restore(role: ModelRole): Boolean {
        val back = original.remove(role) ?: return false
        if (original.isEmpty()) stepDownLine = ""
        val err = load(back)
        Diag.info(if (err == null) "Pressure over: ${back.model.displayName} is back for the ${role.name.lowercase()} job" else "Pressure over: ${back.model.displayName} did not load again: $err")
        return true
    }

    // ---- asking ----

    private val ids = AtomicLong()
    private val running = ConcurrentHashMap<String, ModelRole>()

    /**
     * Asks [role] one question, optionally with a screenshot, and returns the reply (what LlmSlot.ask did). As before, a
     * picture is sent only when the role's model reads pictures; callers check [images] first. Throws when the role has
     * no model loaded or the answer fails or is cancelled.
     */
    suspend fun ask(role: ModelRole, prompt: String, image: Bitmap? = null, temperature: Double = 0.15, purpose: String = "other",
                    stopWhen: ((String) -> Boolean)? = null): String {
        val id = role.name.lowercase() + "-" + ids.incrementAndGet()
        val jpeg = if (image != null && images(role)) LlmSlot.jpegForModel(image) else null
        val maxOut = router.status(role).config?.maxOutputTokens ?: 256
        val req = InferenceRequest(id = id, prompt = prompt, image = jpeg, temperature = temperature.toFloat().coerceIn(0f, 2f),
            maxOutputTokens = minOf(512, maxOut).coerceAtLeast(1), timeoutMs = 600_000L, purpose = purpose, stopWhen = stopWhen)
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
                // Phase 7 L9: this processor's answer speed for this model
                else loadedKey[role]?.let { k -> router.status(role).accelerator?.let { a ->
                    processors.answered(k, a, reply.length, System.currentTimeMillis() - t0) } }
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
