package com.example.mlbbresearch.inference

/** Timing of one answer. Token counts are -1 when the runtime does not report them. */
data class GenerationStats(val timeToFirstTokenMs: Long, val totalMs: Long, val promptTokens: Int = -1, val outputTokens: Int = -1)

/**
 * What a runtime reports while answering one request, in this order:
 * Started, then zero or more Token (only when streaming), then exactly one of Completed / Failed / Cancelled.
 */
sealed class InferenceEvent(val requestId: String) {
    class Started(requestId: String) : InferenceEvent(requestId)
    class Token(requestId: String, val text: String) : InferenceEvent(requestId)
    class Completed(requestId: String, val text: String, val stats: GenerationStats) : InferenceEvent(requestId)
    class Failed(requestId: String, val error: InferenceError) : InferenceEvent(requestId)
    class Cancelled(requestId: String) : InferenceEvent(requestId)

    val terminal: Boolean get() = this is Completed || this is Failed || this is Cancelled
}

/**
 * Collects the events of one request and checks that the runtime kept the order above. Callers that
 * want the whole answer (today's blocking ask) read [text] once [done].
 */
class EventCollector(val requestId: String) {
    private val sb = StringBuilder()
    var started = false; private set
    var done = false; private set
    var text: String? = null; private set
    var error: InferenceError? = null; private set
    var stats: GenerationStats? = null; private set
    val violations = ArrayList<String>()

    @Synchronized fun accept(e: InferenceEvent) {
        if (e.requestId != requestId) { violations.add("event for another request (${e.requestId})"); return }
        if (done) { violations.add("${e.javaClass.simpleName} after the answer ended"); return }
        if (e !is InferenceEvent.Started && !started) violations.add("${e.javaClass.simpleName} before Started")
        when (e) {
            is InferenceEvent.Started -> { if (started) violations.add("Started twice"); started = true }
            is InferenceEvent.Token -> sb.append(e.text)
            is InferenceEvent.Completed -> {
                // streamed tokens and the final text must agree; the final text wins
                if (sb.isNotEmpty() && sb.toString() != e.text) violations.add("final text differs from the streamed tokens")
                text = e.text; stats = e.stats; done = true
            }
            is InferenceEvent.Failed -> { error = e.error; done = true }
            is InferenceEvent.Cancelled -> { error = InferenceError.Cancelled; done = true }
        }
    }

    /** The text so far (streamed tokens before the end, the final text after). */
    @Synchronized fun partial(): String = text ?: sb.toString()
}
