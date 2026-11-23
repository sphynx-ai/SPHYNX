package com.sphynx.inference

/**
 * PHASE 7, step L4 (owner decision 2026-11-23: fall back to the CPU once). What to do after an answer failed:
 *  - on the GPU, the first time since the app started: reload the model on the CPU;
 *  - again after that reload: unload it, and play goes on with the rules;
 *  - a time-out, a cancel or a bad request is not the processor's fault: nothing.
 * The GPU is tried again at the next start (the flag lives only in memory). Pure, unit-tested.
 */
object GpuFallback {
    enum class Action { NONE, RELOAD_ON_CPU, UNLOAD }

    fun decide(on: Accelerator?, alreadyFellBack: Boolean, error: InferenceError): Action = when {
        error is InferenceError.Timeout || error is InferenceError.Cancelled || error is InferenceError.InvalidRequest -> Action.NONE
        on == Accelerator.GPU && !alreadyFellBack -> Action.RELOAD_ON_CPU
        alreadyFellBack -> Action.UNLOAD
        else -> Action.NONE
    }

    fun reloadedNotice(job: String, ok: Boolean, e: InferenceError): String =
        if (ok) "The $job model failed on the GPU (${e.code}) and now runs on the CPU; the GPU is tried again at the next start"
        else "The $job model failed on the GPU (${e.code}) and could not be loaded on the CPU; play goes on with the rules"

    fun unloadedNotice(job: String, e: InferenceError): String =
        "The $job model failed again on the CPU (${e.code}) and was unloaded; play goes on with the rules"
}
