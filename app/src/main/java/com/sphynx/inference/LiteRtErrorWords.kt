package com.sphynx.inference

/**
 * PHASE 7, step L5: LiteRT-LM's exceptions (mostly `LiteRtLmJniException` with a native message, and
 * `IllegalStateException` for a closed engine) in the shared [InferenceError] types, plus what the owner can do about each.
 * It reads only the class name and the message, so `inference` holds no LiteRT-LM types (ADR 0002). Pure, unit-tested.
 */
object LiteRtErrorWords {

    private val MEMORY = listOf("out of memory", "outofmemory", "bad_alloc", "enomem", "out_of_memory", "failed to allocate",
        "cannot allocate", "allocation failed", "insufficient memory", "not enough memory")
    private val FORMAT = listOf("unsupported model", "not supported", "unsupported", "invalid model", "flatbuffer",
        "bad magic", "invalid magic", "unknown model type", "failed to parse", "corrupt")
    private val GPU = listOf("opencl", "open cl", "gpu", "vulkan", "delegate", "egl", "clbuild", "cl_", "mali")
    private val NPU = listOf("npu", "neuron", "neuropilot", "qnn", "dispatch")
    private val CANCEL = listOf("cancelled", "canceled", "aborted by user")

    /** The shared error for a failure, [during] loading or answering, on [on] (the processor the model was using). */
    fun classify(className: String, message: String?, loading: Boolean, on: Accelerator? = null): InferenceError {
        val m = (message ?: "").lowercase()
        val detail = (message ?: className).take(300)
        fun has(words: List<String>) = words.any { m.contains(it) }
        return when {
            className.contains("OutOfMemory") || has(MEMORY) -> InferenceError.NotEnoughMemory(-1, -1)
            has(CANCEL) -> InferenceError.Cancelled
            loading && has(FORMAT) -> InferenceError.UnsupportedFormat(detail)
            !loading && on == Accelerator.NPU && has(NPU + GPU) -> InferenceError.AcceleratorFailed(Accelerator.NPU, detail)
            !loading && on == Accelerator.GPU && has(GPU) -> InferenceError.AcceleratorFailed(Accelerator.GPU, detail)
            // an answer that fails on the GPU for any native reason is treated as the GPU's failure (L4 falls back)
            !loading && on == Accelerator.GPU && className.contains("Jni") -> InferenceError.AcceleratorFailed(Accelerator.GPU, detail)
            loading -> InferenceError.LoadFailed(detail)
            else -> InferenceError.Internal(detail)
        }
    }

    fun classify(t: Throwable, loading: Boolean, on: Accelerator? = null): InferenceError =
        classify(t.javaClass.simpleName, t.message, loading, on)

    /** A load message from the slot (it reports only text). */
    fun forLoadMessage(message: String): InferenceError = classify("", message, loading = true)

    /** What the owner can do, for the Model page and the log. */
    fun advice(e: InferenceError): String = when (e) {
        is InferenceError.NotEnoughMemory -> "Close other apps, or use a smaller model (the Model page lists the files)."
        is InferenceError.UnsupportedFormat -> "This file is not a LiteRT-LM model this app can read; the supported model is Gemma 4 E2B."
        is InferenceError.AcceleratorFailed -> "Turn the GPU switch off on the Model page to stay on the CPU, or restart the app."
        is InferenceError.LoadFailed -> "Try again; if it keeps failing, turn the GPU switch off or get the file again."
        is InferenceError.ModelNotFound -> "Put the .litertlm file in the Sphynx-Storage/Input folder."
        is InferenceError.Timeout -> "The phone is busy or hot; the next question is tried normally."
        is InferenceError.CrashSuspected -> "Load it by hand on the Model page to try again."
        else -> ""
    }

    /** The error and its advice in one line. */
    fun words(e: InferenceError): String = advice(e).let { if (it.isEmpty()) e.message else "${e.message}. $it" }
}
