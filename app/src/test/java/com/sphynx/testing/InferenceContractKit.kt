package com.sphynx.testing

import com.sphynx.inference.EventCollector
import com.sphynx.inference.InferenceBackend
import com.sphynx.inference.InferenceEvent
import com.sphynx.inference.InferenceRequest
import com.sphynx.inference.ModelConfiguration
import com.sphynx.inference.RuntimeState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// THE INFERENCE CONTRACT KIT (Phase 6, step S2; ADR 0002). Every runtime (LiteRT-LM, llama.cpp, any later one) runs it
// from its own test with a fake engine:
//     assertEquals(emptyList<String>(), InferenceContractKit.check(myHarness))
// It returns plain sentences for every rule of InferenceBackend the runtime breaks: legal state changes, exactly one
// end event per request, invalid requests refused, cancel, unload, and a failed load reported (never thrown).
object InferenceContractKit {

    /** What the kit needs from a runtime's test. */
    interface Harness {
        /** A fresh runtime over a fake engine. */
        fun newBackend(): InferenceBackend
        /** A configuration the fake engine loads. */
        fun goodConfig(): ModelConfiguration
        /** A configuration the fake engine refuses. */
        fun badConfig(): ModelConfiguration
        /** The next answer waits until it is cancelled. */
        fun holdNextAnswer()
    }

    class Answer(val events: List<InferenceEvent>, val text: String?)

    fun check(h: Harness): List<String> {
        val out = ArrayList<String>()
        fun ask(be: InferenceBackend, req: InferenceRequest, during: () -> Unit = {}): Answer {
            val c = EventCollector(req.id)
            val events = java.util.Collections.synchronizedList(ArrayList<InferenceEvent>())
            val end = CountDownLatch(1)
            be.generate(req) { e -> events.add(e); c.accept(e); if (e.terminal) end.countDown() }
            during()
            if (!end.await(5, TimeUnit.SECONDS)) out.add("request ${req.id} did not end within 5 s")
            c.violations.forEach { out.add("request ${req.id}: $it") }
            return Answer(ArrayList(events), c.text)
        }

        // 1. a failed load is a FAILED status with an error, never an exception
        val bad = h.newBackend()
        val failed = try { bad.load(h.badConfig()) } catch (t: Throwable) { out.add("load threw $t instead of reporting FAILED"); null }
        if (failed != null && (failed.state != RuntimeState.FAILED || failed.error == null)) out.add("a refused load is ${failed.state}, not FAILED with an error")
        // 2. asking before a model is loaded ends with Failed
        val notReady = ask(bad, InferenceRequest("r0", "hello"))
        if (notReady.events.lastOrNull() !is InferenceEvent.Failed) out.add("a request with no model loaded did not end with Failed")

        // 3. a good load is READY with the configuration
        val be = h.newBackend()
        val st = be.load(h.goodConfig())
        if (st.state != RuntimeState.READY) out.add("a good load is ${st.state}, not READY (${st.error})")
        if (st.config?.id != h.goodConfig().id) out.add("the READY status does not carry the loaded configuration")
        if (st.backend != be.type) out.add("the status names ${st.backend}, the runtime is ${be.type}")

        // 4. an answer: Started first, one Completed, back to READY, the call counted
        val ok = ask(be, InferenceRequest("r1", "What first?"))
        if (ok.events.firstOrNull() !is InferenceEvent.Started) out.add("an answer did not start with Started")
        if (ok.events.lastOrNull() !is InferenceEvent.Completed) out.add("a good request did not end with Completed")
        if (ok.text.isNullOrBlank()) out.add("a Completed answer has no text")
        if (be.status().state != RuntimeState.READY) out.add("after an answer the runtime is ${be.status().state}, not READY")
        if (be.status().calls < 1) out.add("the answer was not counted in the status")

        // 5. streaming: Token events before Completed, and they add up to the text
        val streamed = ask(be, InferenceRequest("r2", "What first?", stream = true))
        val tokens = streamed.events.filterIsInstance<InferenceEvent.Token>().joinToString("") { it.text }
        if (tokens.isEmpty()) out.add("a streaming request had no Token events")
        else if (tokens != streamed.text) out.add("streamed tokens '$tokens' differ from the final text '${streamed.text}'")

        // 6. an invalid request is refused with InvalidRequest, and the runtime stays READY
        val invalid = ask(be, InferenceRequest("r3", "x", temperature = 5f))
        val f = invalid.events.lastOrNull() as? InferenceEvent.Failed
        if (f == null || f.error.code != "invalid_request") out.add("an invalid request did not end with Failed(invalid_request)")
        if (be.status().state != RuntimeState.READY) out.add("after an invalid request the runtime is ${be.status().state}")

        // 7. an image to a text-only model is refused
        if (!be.status().visionOk) {
            val img = ask(be, InferenceRequest("r4", "what is on the screen?", image = byteArrayOf(1, 2, 3)))
            if ((img.events.lastOrNull() as? InferenceEvent.Failed)?.error?.code != "invalid_request")
                out.add("an image sent to a text-only model was not refused")
        }

        // 8. cancel while answering ends with Cancelled
        h.holdNextAnswer()
        val cancelled = ask(be, InferenceRequest("r5", "long question")) { Thread.sleep(100); be.cancel("r5") }
        if (cancelled.events.lastOrNull() !is InferenceEvent.Cancelled) out.add("a cancelled request did not end with Cancelled")
        if (be.status().state != RuntimeState.READY) out.add("after a cancel the runtime is ${be.status().state}, not READY")

        // 9. unload: UNLOADED, and a request afterwards fails
        be.unload()
        if (be.status().state != RuntimeState.UNLOADED) out.add("after unload the runtime is ${be.status().state}, not UNLOADED")
        val after = ask(be, InferenceRequest("r6", "still there?"))
        if (after.events.lastOrNull() !is InferenceEvent.Failed) out.add("a request after unload did not end with Failed")
        return out
    }
}
