package com.sphynx.inference

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * PHASE 6, step S2 (ADR 0002): the LiteRT-LM runtime behind the shared [InferenceBackend] contract.
 *
 * It wraps one existing LiteRT-LM slot (`ai/LlmSlot`) through [LiteRtSlot], so `inference` (L0) holds no LiteRT-LM or
 * app types, and LiteRT-LM keeps running exactly as before: the slot's own single thread, its load order (image + text
 * on the GPU, image + text on the CPU, text only on the CPU), its sampler and its speed records.
 *
 *  - One answer at a time; events arrive on this backend's thread.
 *  - LiteRT-LM answers in one piece here: a streaming request gets the whole text as one Token, then Completed.
 *  - Phase 7 L4: an answer that fails on the GPU reloads the model on the CPU once per app start; a second failure
 *    unloads it ([GpuFallback]). L5: failures are reported in the shared error words ([LiteRtErrorWords]).
 *  - Nothing here acts on the game: it only produces text.
 */
class LiteRtLmBackend(private val slot: LiteRtSlot) : InferenceBackend {

    override val type = BackendType.LITERT_LM

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "litertlm-backend").apply { isDaemon = true } }

    @Volatile private var st = RuntimeStatus(BackendType.LITERT_LM)
    @Volatile private var runningId: String? = null
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun status(): RuntimeStatus = st

    /** A line for the panel and the log when the model moved to the CPU or was unloaded (L4). Set by the app. */
    @Volatile var onNotice: (String) -> Unit = {}
    /** The GPU failed once since the app started, and the model was moved to the CPU (L4). */
    @Volatile var fellBack = false
        private set

    override fun check(config: ModelConfiguration): InferenceError? {
        if (config.backend != BackendType.LITERT_LM) return InferenceError.UnsupportedFormat("${config.displayName} is a ${config.backend.label} model")
        val p = config.problems()
        if (p.isNotEmpty()) return InferenceError.LoadFailed(p.joinToString("; "))
        if (!slot.fileExists(config.location)) return InferenceError.ModelNotFound(config.location)
        return null
    }

    override fun load(config: ModelConfiguration): RuntimeStatus = onWorker {
        st = when (st.state) {
            RuntimeState.UNLOADED, RuntimeState.FAILED, RuntimeState.READY -> st.moveTo(RuntimeState.LOADING, config = config)
            else -> RuntimeStatus(BackendType.LITERT_LM).moveTo(RuntimeState.LOADING, config = config)
        }
        val problem = check(config)
        st = if (problem != null) st.moveTo(RuntimeState.FAILED, error = problem) else {
            // the slot's own order: GPU first when the configuration lists it before the CPU; text only when no images
            val preferGpu = preferGpu(config.accelerators)
            val err = try { slot.load(config.location, preferGpu, textOnly = !config.wantsVision) } catch (t: Throwable) { t.message ?: t.toString() }
            if (err != null || !slot.ready) st.moveTo(RuntimeState.FAILED, error = LiteRtErrorWords.forLoadMessage(err ?: "unknown reason"))
            else st.moveTo(RuntimeState.READY, accelerator = acceleratorOf(slot.where), visionOk = slot.visionOk)
        }
        st
    }

    override fun generate(request: InferenceRequest, onEvent: (InferenceEvent) -> Unit) {
        worker.execute { answer(request, onEvent) }
    }

    private fun answer(request: InferenceRequest, onEvent: (InferenceEvent) -> Unit) {
        val id = request.id
        fun emit(e: InferenceEvent) { try { onEvent(e) } catch (t: Throwable) { } }
        emit(InferenceEvent.Started(id))
        if (cancelled.remove(id)) { emit(InferenceEvent.Cancelled(id)); return }
        val problems = request.problems(st)
        if (problems.isNotEmpty()) { emit(InferenceEvent.Failed(id, InferenceError.InvalidRequest(problems))); return }
        st = st.moveTo(RuntimeState.BUSY)
        runningId = id
        val t0 = System.currentTimeMillis()
        var text: String? = null
        var failure: Throwable? = null
        try {
            text = slot.ask(request.prompt, request.image, request.temperature.toDouble(), request.purpose, request.timeoutMs, request.stopWhen)
        } catch (t: Throwable) { failure = t }
        runningId = null
        val total = System.currentTimeMillis() - t0
        val wasCancelled = cancelled.remove(id)
        val on = st.accelerator
        st = st.moveTo(RuntimeState.READY).copy(lastLatencyMs = total, calls = st.calls + 1)
        when {
            wasCancelled -> emit(InferenceEvent.Cancelled(id))
            failure is java.util.concurrent.TimeoutException -> emit(InferenceEvent.Failed(id, InferenceError.Timeout(request.timeoutMs)))
            failure != null -> {
                val err = LiteRtErrorWords.classify(failure, loading = false, on = on)
                afterFailure(on, err)
                emit(InferenceEvent.Failed(id, err))
            }
            else -> {
                val t = text ?: ""
                if (request.stream && t.isNotEmpty()) emit(InferenceEvent.Token(id, t))
                emit(InferenceEvent.Completed(id, t, GenerationStats(timeToFirstTokenMs = total, totalMs = total)))
            }
        }
    }

    // L4, on this backend's thread: reload on the CPU once, or unload after a second failure
    private fun afterFailure(on: Accelerator?, err: InferenceError) {
        val config = st.config ?: return
        val job = config.role?.name?.lowercase() ?: "language"
        when (GpuFallback.decide(on, fellBack, err)) {
            GpuFallback.Action.NONE -> {}
            GpuFallback.Action.RELOAD_ON_CPU -> {
                fellBack = true
                st = st.moveTo(RuntimeState.LOADING, config = config)
                val loadErr = try { slot.load(config.location, preferGpu = false, textOnly = !config.wantsVision) } catch (t: Throwable) { t.message ?: t.toString() }
                val ok = loadErr == null && slot.ready
                st = if (ok) st.moveTo(RuntimeState.READY, accelerator = acceleratorOf(slot.where), visionOk = slot.visionOk)
                     else st.moveTo(RuntimeState.FAILED, error = LiteRtErrorWords.forLoadMessage(loadErr ?: "unknown reason"))
                notice(GpuFallback.reloadedNotice(job, ok, err))
            }
            GpuFallback.Action.UNLOAD -> {
                try { slot.unload() } catch (t: Throwable) { }
                st = st.moveTo(RuntimeState.UNLOADED)
                notice(GpuFallback.unloadedNotice(job, err))
            }
        }
    }

    private fun notice(line: String) { try { onNotice(line) } catch (t: Throwable) { } }

    override fun cancel(requestId: String) {
        cancelled.add(requestId)
        if (runningId == requestId) slot.cancel()
    }

    override fun unload() {
        if (runningId != null) slot.cancel()
        onWorker {
            try { slot.unload() } catch (t: Throwable) { }
            cancelled.clear()
            if (st.state != RuntimeState.UNLOADED) st = st.moveTo(RuntimeState.UNLOADED)
        }
    }

    private fun <T> onWorker(f: () -> T): T = worker.submit(Callable { f() }).get()

    companion object {
        /** GPU first when the configuration lists the GPU before the CPU (or the CPU not at all). */
        fun preferGpu(accs: List<Accelerator>): Boolean {
            val g = accs.indexOf(Accelerator.GPU); val c = accs.indexOf(Accelerator.CPU)
            return g >= 0 && (c < 0 || g < c)
        }

        /** The slot reports where it loaded as a label ("GPU", "CPU, text only", ...). */
        fun acceleratorOf(where: String): Accelerator = when {
            where.startsWith("GPU", ignoreCase = true) -> Accelerator.GPU
            where.startsWith("NPU", ignoreCase = true) -> Accelerator.NPU
            else -> Accelerator.CPU
        }
    }
}

/**
 * One LiteRT-LM model slot as [LiteRtLmBackend] sees it. The app implements it over `ai/LlmSlot` (ADR 0001: inference
 * may not call ai). Every method blocks until done; the slot keeps its own thread.
 */
interface LiteRtSlot {
    val ready: Boolean
    val visionOk: Boolean
    /** Where the model loaded ("GPU", "CPU", "CPU, text only"...), "-" when not loaded. */
    val where: String
    fun fileExists(location: String): Boolean
    /** Loads the model; null when it loaded, else why not. */
    fun load(location: String, preferGpu: Boolean, textOnly: Boolean): String?
    /** One answer; [imageJpeg] is used only when the model loaded with images. Throws TimeoutException after [timeoutMs]. */
    fun ask(prompt: String, imageJpeg: ByteArray?, temperature: Double, purpose: String, timeoutMs: Long,
            stopWhen: ((String) -> Boolean)?): String
    fun cancel()
    fun unload()
}
