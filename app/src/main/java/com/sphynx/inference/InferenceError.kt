package com.sphynx.inference

/**
 * Why loading or answering failed, in words the owner can act on. [retryable] says whether trying
 * again later may work without the owner changing anything.
 */
sealed class InferenceError(val code: String, val message: String, val retryable: Boolean) {
    class ModelNotFound(location: String) : InferenceError("model_not_found", "model file not found: $location", false)
    class UnsupportedFormat(detail: String) : InferenceError("unsupported_format", "this model cannot be read: $detail", false)
    class BackendUnavailable(backend: BackendType) :
        InferenceError("backend_unavailable", "${backend.label} is not included in this build", false)
    class NotEnoughMemory(val neededBytes: Long, val availableBytes: Long) : InferenceError("not_enough_memory",
        "needs about ${mb(neededBytes)} MB of memory, ${mb(availableBytes)} MB free", true)
    class LoadFailed(detail: String) : InferenceError("load_failed", "loading failed: $detail", true)
    class InvalidRequest(val reasons: List<String>) : InferenceError("invalid_request", "invalid request: " + reasons.joinToString("; "), false)
    class Timeout(ms: Long) : InferenceError("timeout", "no answer within ${ms / 1000.0} s", true)
    object Cancelled : InferenceError("cancelled", "cancelled", true)
    /** The app restarted while this model was loading: it is not loaded again by itself (load sentinel, Phase Two). */
    class CrashSuspected(modelId: String) : InferenceError("crash_suspected",
        "the app stopped while loading $modelId last time; load it by hand to try again", false)
    class Internal(detail: String) : InferenceError("internal", "runtime error: $detail", true)

    override fun toString(): String = "$code: $message"

    companion object { private fun mb(b: Long) = if (b < 0) "?" else (b / (1024 * 1024)).toString() }
}
