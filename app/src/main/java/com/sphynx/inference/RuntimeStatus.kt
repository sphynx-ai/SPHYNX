package com.sphynx.inference

/** The life of one loaded runtime. Only the transitions in [canMoveTo] are legal. */
enum class RuntimeState(val label: String) {
    UNLOADED("not loaded"), LOADING("loading"), READY("ready"), BUSY("answering"), FAILED("failed");

    fun canMoveTo(next: RuntimeState): Boolean = when (this) {
        UNLOADED -> next == LOADING
        LOADING -> next == READY || next == FAILED || next == UNLOADED
        READY -> next == BUSY || next == UNLOADED || next == LOADING
        BUSY -> next == READY || next == FAILED || next == UNLOADED
        FAILED -> next == LOADING || next == UNLOADED
    }
}

/** What a runtime reports about itself: shown on the AI page and used by callers (ready? images?). */
data class RuntimeStatus(
    val backend: BackendType,
    val state: RuntimeState = RuntimeState.UNLOADED,
    val config: ModelConfiguration? = null,
    val accelerator: Accelerator? = null,  // where it actually runs once loaded
    val visionOk: Boolean = false,
    val error: InferenceError? = null,
    val lastLatencyMs: Long = 0L,
    val calls: Int = 0
) {
    val ready: Boolean get() = state == RuntimeState.READY

    /** The next status, or IllegalStateException for an illegal transition (a runtime bug). */
    fun moveTo(next: RuntimeState, accelerator: Accelerator? = this.accelerator, visionOk: Boolean = this.visionOk,
               error: InferenceError? = null, config: ModelConfiguration? = this.config): RuntimeStatus {
        check(state.canMoveTo(next)) { "illegal runtime transition ${state.name} -> ${next.name}" }
        val gone = next == RuntimeState.UNLOADED || next == RuntimeState.FAILED || next == RuntimeState.LOADING
        return copy(state = next, accelerator = if (gone) null else accelerator, visionOk = if (gone) false else visionOk,
            error = if (next == RuntimeState.FAILED) error else null, config = if (next == RuntimeState.UNLOADED) null else config)
    }

    fun describe(): String = buildString {
        append(backend.label).append(": ").append(state.label)
        accelerator?.let { append(" on ").append(it.name) }
        if (state == RuntimeState.READY || state == RuntimeState.BUSY) append(if (visionOk) ", images yes" else ", text only")
        error?.let { append(" - ").append(it.message) }
    }
}
