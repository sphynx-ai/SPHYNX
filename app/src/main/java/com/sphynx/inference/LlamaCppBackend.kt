package com.sphynx.inference

import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PHASE 8: the GGUF / llama.cpp runtime behind the shared [InferenceBackend] contract.
 *
 *  - One model at a time, kept loaded until [unload]; every call runs on this backend's own thread.
 *  - CPU only in this build (no GPU / Vulkan backend is compiled in): a configuration that lists GPU
 *    first still runs, on the CPU, and the status says so.
 *  - The model must be a copy in the app's storage (StorageMode.APP_COPY): on the owner's phone,
 *    reading a picked file in place failed while the same file copied into the app loaded (2026-11-14).
 *  - Each question starts from an empty context. Answers stream as Token events when asked to.
 *  - Nothing here acts on the game: it only produces text.
 */
class LlamaCppBackend(
    private val native: LlamaNative,
    private val freeMemoryBytes: () -> Long = { -1L }
) : InferenceBackend {

    override val type = BackendType.LLAMA_CPP

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-backend").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "llama-timeout").apply { isDaemon = true } }

    @Volatile private var st = RuntimeStatus(BackendType.LLAMA_CPP)
    @Volatile private var handle = 0L
    @Volatile private var runningId: String? = null
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** What llama.cpp says about the loaded model (model, sizeBytes, params, nCtx, threads, templated). */
    @Volatile var modelInfo: JSONObject? = null; private set
    /** Why the model's mmproj file did not load (the model then reads text only), or null. */
    @Volatile var projectorError: String? = null; private set
    /** llama.cpp's report of the last answer (timings, stop reason). */
    @Volatile var lastRun: JSONObject? = null; private set

    override fun status(): RuntimeStatus = st

    override fun check(config: ModelConfiguration): InferenceError? {
        if (config.backend != BackendType.LLAMA_CPP) return InferenceError.UnsupportedFormat("${config.displayName} is a ${config.backend.label} model")
        native.unavailableReason?.let { return InferenceError.LoadFailed(it) }
        val p = config.problems()
        if (p.isNotEmpty()) return InferenceError.LoadFailed(p.joinToString("; "))
        if (config.storage != StorageMode.APP_COPY)
            return InferenceError.LoadFailed("add the model to the app first (reading a picked file in place is not reliable on Android)")
        val f = File(config.location)
        if (!f.isFile) return InferenceError.ModelNotFound(config.location)
        val head = try { f.inputStream().use { s -> ByteArray(16).let { b -> b.copyOf(maxOf(0, s.read(b))) } } } catch (e: Exception) { ByteArray(0) }
        GgufFile.checkHeader(head, f.length())?.let { return InferenceError.UnsupportedFormat(it) }
        // Phase 8 G2: the file's own description, checked before native code opens it
        val info = GgufInfo.read(f).getOrElse { return InferenceError.UnsupportedFormat(it.message ?: "the file can't be read") }
        info.problemAsModel()?.let { return InferenceError.UnsupportedFormat(it) }
        val need = GgufFile.estimatedMemoryBytes(f.length(), config.contextTokens)
        val free = freeMemoryBytes()
        if (free in 0 until need) return InferenceError.NotEnoughMemory(need, free)
        return null
    }

    override fun load(config: ModelConfiguration): RuntimeStatus = onWorker {
        if (handle != 0L) { native.free(handle); handle = 0L; modelInfo = null }
        st = when (st.state) {
            RuntimeState.UNLOADED, RuntimeState.FAILED, RuntimeState.READY -> st.moveTo(RuntimeState.LOADING, config = config)
            else -> RuntimeStatus(BackendType.LLAMA_CPP).moveTo(RuntimeState.LOADING, config = config)
        }
        val problem = check(config)
        if (problem != null) {
            st = st.moveTo(RuntimeState.FAILED, error = problem)
        } else {
            val threads = if (config.threads > 0) config.threads else LlamaCpu.threads()
            val h = try { native.load(config.location, config.contextTokens, threads) } catch (t: Throwable) { 0L }
            if (h == 0L) {
                st = st.moveTo(RuntimeState.FAILED, error = InferenceError.LoadFailed(native.loadError().ifBlank { "unknown reason" }))
            } else {
                handle = h
                // Phase 6 S8: the picture part, when the model has an mmproj file and pictures are wanted
                var vision = false
                val proj = config.projector
                if (config.wantsVision && proj != null) {
                    val why = try { native.loadProjector(h, proj, threads) } catch (t: Throwable) { t.message ?: t.toString() }
                    vision = why.isEmpty()
                    projectorError = why.ifEmpty { null }
                } else projectorError = null
                modelInfo = try { native.describe(h) } catch (t: Throwable) { null }
                st = st.moveTo(RuntimeState.READY, accelerator = Accelerator.CPU, visionOk = vision)
            }
        }
        st
    }

    override fun generate(request: InferenceRequest, onEvent: (InferenceEvent) -> Unit) {
        worker.execute { answer(request, onEvent) }
    }

    private fun answer(request: InferenceRequest, onEvent: (InferenceEvent) -> Unit) {
        val id = request.id
        // a listener that throws must not break the runtime or skip the request's end event
        fun emit(e: InferenceEvent) { try { onEvent(e) } catch (t: Throwable) { } }
        emit(InferenceEvent.Started(id))
        if (cancelled.remove(id)) { emit(InferenceEvent.Cancelled(id)); return }
        val problems = request.problems(st)
        if (problems.isNotEmpty()) { emit(InferenceEvent.Failed(id, InferenceError.InvalidRequest(problems))); return }

        st = st.moveTo(RuntimeState.BUSY)
        runningId = id
        val timedOut = AtomicBoolean(false)
        val earlyStop = AtomicBoolean(false)
        val h = handle
        val watchdog = timer.schedule(Runnable { timedOut.set(true); native.cancel(h) }, request.timeoutMs, TimeUnit.MILLISECONDS)
        val utf = Utf8Stream()
        val t0 = System.currentTimeMillis()
        val res = try {
            val onPiece: (ByteArray) -> Unit = { bytes ->
                // a cancel that came just before the engine started is caught on the first piece
                if (id in cancelled) native.cancel(h)
                val s = utf.push(bytes)
                if (request.stream && s.isNotEmpty()) emit(InferenceEvent.Token(id, s))
                // Phase 7 (L2): the caller has what it needs (the text so far), so the model stops here
                val stop = request.stopWhen
                if (stop != null && !earlyStop.get() && try { stop(utf.text()) } catch (t: Throwable) { false }) { earlyStop.set(true); native.cancel(h) }
            }
            val img = request.image
            if (img != null) native.generateWithImage(h, request.prompt, img, request.maxOutputTokens, request.temperature, id.hashCode(), onPiece)
            else native.generate(h, request.prompt, request.maxOutputTokens, request.temperature, id.hashCode(), onPiece)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: t.toString()).put("stop", "error")
        }
        watchdog.cancel(false)
        runningId = null
        val wasCancelled = cancelled.remove(id)
        val rest = utf.finish()
        if (request.stream && rest.isNotEmpty()) emit(InferenceEvent.Token(id, rest))
        val total = System.currentTimeMillis() - t0
        lastRun = res
        st = st.moveTo(RuntimeState.READY).copy(lastLatencyMs = total, calls = st.calls + 1)
        val stop = res.optString("stop")
        when {
            timedOut.get() -> emit(InferenceEvent.Failed(id, InferenceError.Timeout(request.timeoutMs)))
            earlyStop.get() && !wasCancelled -> emit(InferenceEvent.Completed(id, utf.text(), GenerationStats(
                timeToFirstTokenMs = res.optLong("firstTokenMs", -1L), totalMs = total,
                promptTokens = res.optInt("promptTokens", -1), outputTokens = res.optInt("genTokens", -1))))
            stop == "cancelled" || (wasCancelled && !res.optBoolean("ok")) -> emit(InferenceEvent.Cancelled(id))
            !res.optBoolean("ok") -> emit(InferenceEvent.Failed(id, InferenceError.Internal(res.optString("error").ifBlank { "unknown error" })))
            else -> emit(InferenceEvent.Completed(id, utf.text(), GenerationStats(
                timeToFirstTokenMs = res.optLong("firstTokenMs", -1L), totalMs = total,
                promptTokens = res.optInt("promptTokens", -1), outputTokens = res.optInt("genTokens", -1))))
        }
    }

    override fun cancel(requestId: String) {
        cancelled.add(requestId)
        if (runningId == requestId) native.cancel(handle)
    }

    override fun unload() {
        runningId?.let { native.cancel(handle) }      // stop a running answer first, then free
        onWorker {
            if (handle != 0L) { native.free(handle); handle = 0L }
            modelInfo = null
            cancelled.clear()
            if (st.state != RuntimeState.UNLOADED) st = st.moveTo(RuntimeState.UNLOADED)
        }
    }

    private fun <T> onWorker(f: () -> T): T = worker.submit(Callable { f() }).get()

    companion object {
        /** One line about an answer's speed, from llama.cpp's report. */
        fun speedLine(res: JSONObject?): String {
            if (res == null) return ""
            fun rate(n: Int, ms: Long) = if (ms <= 0 || n <= 0) "-" else "%.1f tok/s".format(Locale.US, n * 1000.0 / ms)
            val pt = res.optInt("promptTokens"); val pm = res.optLong("promptMs")
            val gt = res.optInt("genTokens"); val gm = res.optLong("genMs")
            val first = res.optLong("firstTokenMs", -1L)
            return "Prompt: $pt tokens (${rate(pt, pm)}) · Answer: $gt tokens (${rate(gt, gm)})" +
                (if (first >= 0) " · first word after %.1f s".format(Locale.US, first / 1000.0) else "")
        }
    }
}
