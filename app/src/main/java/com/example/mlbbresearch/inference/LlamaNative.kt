package com.example.mlbbresearch.inference

import org.json.JSONObject

/**
 * PHASE 8: what LlamaCppBackend needs from llama.cpp. The real one is [LlamaJni]; the unit tests use a
 * fake, so the backend's contract (states, events, cancel, timeout) is checked without a phone.
 * A handle is one loaded model (0 = none). Everything except [cancel] is called from one thread.
 */
interface LlamaNative {
    /** Null when llama.cpp can run here, else why not (shown to the owner). */
    val unavailableReason: String?

    /** "llama.cpp <tag> (<commit>) | <CPU features>". */
    fun info(): String

    /** Loads a GGUF; 0 on failure, then [loadError] says why. */
    fun load(path: String, contextTokens: Int, threads: Int): Long
    fun loadError(): String

    /** model, sizeBytes, params, nCtx, threads, templated. */
    fun describe(handle: Long): JSONObject

    /**
     * One answer; [onPiece] gets UTF-8 bytes as they are made. Returns ok, error, stop
     * ("eos" | "length" | "cancelled" | "error"), promptTokens, promptMs, genTokens, genMs, firstTokenMs.
     */
    fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, seed: Int, onPiece: (ByteArray) -> Unit): JSONObject

    /** Stops the running answer; safe from any thread. */
    fun cancel(handle: Long)

    fun free(handle: Long)
}

/** The JNI bridge to libsphynx_llama.so (app/src/main/cpp/llama_jni.cpp). */
object LlamaJni : LlamaNative {

    /** What JNI calls with each piece of the answer (a method named onPiece taking byte[]). */
    fun interface PieceSink { fun onPiece(bytes: ByteArray) }

    private val loadFailure: String? by lazy {
        val cpuinfo = try { java.io.File("/proc/cpuinfo").readText() } catch (e: Exception) { null }
        LlamaCpu.unsupportedReason(android.os.Build.SUPPORTED_ABIS.toList(), cpuinfo)
            ?: try { System.loadLibrary("sphynx_llama"); null }
            catch (t: Throwable) { "the GGUF engine library did not load: ${t.message ?: t}" }
    }

    override val unavailableReason: String? get() = loadFailure

    private external fun nativeInfo(): ByteArray
    private external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long
    private external fun nativeLoadError(): ByteArray
    private external fun nativeDescribe(handle: Long): ByteArray
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, seed: Int, sink: PieceSink?): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    override fun info(): String = unavailableReason ?: utf8(nativeInfo())
    override fun load(path: String, contextTokens: Int, threads: Int): Long = if (unavailableReason != null) 0L else nativeLoad(path, contextTokens, threads)
    override fun loadError(): String = unavailableReason ?: utf8(nativeLoadError())
    override fun describe(handle: Long): JSONObject = JSONObject(utf8(nativeDescribe(handle)))
    override fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, seed: Int, onPiece: (ByteArray) -> Unit): JSONObject =
        JSONObject(utf8(nativeGenerate(handle, prompt.toByteArray(Charsets.UTF_8), maxTokens, temperature, seed, PieceSink { onPiece(it) })))
    override fun cancel(handle: Long) { if (handle != 0L) nativeCancel(handle) }
    override fun free(handle: Long) { if (handle != 0L) nativeFree(handle) }
}
