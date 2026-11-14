package com.sphynx.inference

/**
 * One model runtime (LiteRT-LM today, llama.cpp later). Rules every implementation keeps:
 *  - every call runs on the runtime's own single thread, one at a time (as LlmSlot does today);
 *  - load() never throws: a failure is a FAILED status with an [InferenceError];
 *  - generate() always ends the request with exactly one Completed / Failed / Cancelled event
 *    ([EventCollector] checks this in tests);
 *  - nothing here sends touches or acts on the game: a runtime only produces text.
 */
interface InferenceBackend {
    val type: BackendType

    /** The current status (cheap; safe from any thread). */
    fun status(): RuntimeStatus

    /** Why this runtime cannot load [config], or null when it can try. Does not load anything. */
    fun check(config: ModelConfiguration): InferenceError?

    /** Loads [config], trying [LoadPlan] attempts in order. Returns the final status (READY or FAILED). */
    fun load(config: ModelConfiguration): RuntimeStatus

    /** Answers [request]; events arrive on the runtime's thread. */
    fun generate(request: InferenceRequest, onEvent: (InferenceEvent) -> Unit)

    /** Stops the request with this id if it is running (it then ends with Cancelled). */
    fun cancel(requestId: String)

    /** Frees the model; status becomes UNLOADED. */
    fun unload()
}

/**
 * The load attempts, in order, from the model's accelerator list and whether it wants images.
 * Today's LiteRT-LM order (LlmSlot.load) is: image+text GPU, image+text CPU, then text only on CPU
 * (and text only on GPU first for a text-only model). This reproduces it exactly so Phase Two can
 * switch to it with identical behaviour.
 */
object LoadPlan {
    data class Attempt(val accelerator: Accelerator, val vision: Boolean) {
        val label: String get() = accelerator.name + if (vision) "" else ", text only"
    }

    fun attempts(config: ModelConfiguration, npuAvailable: Boolean = false): List<Attempt> {
        val accs = config.accelerators.filter { it != Accelerator.NPU || npuAvailable }
        val out = ArrayList<Attempt>()
        if (config.wantsVision && config.backend == BackendType.LITERT_LM) {
            accs.forEach { out.add(Attempt(it, true)) }
            if (Accelerator.CPU !in accs) out.add(Attempt(Accelerator.CPU, true))
            out.add(Attempt(Accelerator.CPU, false))           // last resort: text only on the CPU
        } else {
            accs.forEach { out.add(Attempt(it, false)) }
            if (Accelerator.CPU !in accs) out.add(Attempt(Accelerator.CPU, false))
        }
        return out.distinct()
    }
}
