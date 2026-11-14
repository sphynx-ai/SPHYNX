package com.sphynx.inference

/** One question to a loaded model. [problems] lists what is wrong with it (empty = fine). */
class InferenceRequest(
    val id: String,
    val prompt: String,
    val image: ByteArray? = null,         // encoded PNG / JPEG; only for a vision-capable model
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 256,
    val stream: Boolean = false,          // true: Token events as text arrives; false: one Completed
    val timeoutMs: Long = 30_000L
) {
    fun problems(status: RuntimeStatus? = null): List<String> {
        val p = ArrayList<String>()
        if (id.isBlank()) p.add("no request id")
        if (prompt.isBlank() && image == null) p.add("empty prompt")
        if (temperature.isNaN() || temperature !in 0f..2f) p.add("temperature $temperature is out of range (0-2)")
        if (maxOutputTokens < 1) p.add("max output must be at least 1")
        if (timeoutMs !in 100L..600_000L) p.add("timeout $timeoutMs ms is out of range")
        if (status != null) {
            if (status.state != RuntimeState.READY) p.add("model is ${status.state.label}, not ready")
            if (image != null && !status.visionOk) p.add("this model has no image input")
            if (status.config != null && maxOutputTokens > status.config.maxOutputTokens)
                p.add("max output $maxOutputTokens is above the model's ${status.config.maxOutputTokens}")
        }
        return p
    }
}
