package com.sphynx.ai

import android.content.Context
import com.sphynx.inference.LiteRtSlot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// PHASE 6, step S2 (ADR 0002): one existing LiteRT-LM slot (GemmaEngine or TacticalLlmEngine) seen through
// inference.LiteRtSlot, so inference.LiteRtLmBackend can run it behind the shared contract. Every call goes to the
// slot itself: the same thread, load order, sampler and speed records as today. Nothing calls this yet; the role
// router (S3) does.
class LlmSlotPort(private val slot: LlmSlot, private val ctx: () -> Context?) : LiteRtSlot {
    override val ready: Boolean get() = slot.ready
    override val visionOk: Boolean get() = slot.visionOk
    override val where: String get() = slot.backendName
    override fun fileExists(location: String): Boolean = java.io.File(location).isFile

    override fun load(location: String, preferGpu: Boolean, textOnly: Boolean, preferNpu: Boolean): String? {
        val c = ctx() ?: return "the app is not started yet"
        return runBlocking { slot.load(c, location, preferGpu, textOnly, preferNpu) }
    }

    override fun ask(prompt: String, imageJpeg: ByteArray?, temperature: Double, purpose: String, timeoutMs: Long,
                     stopWhen: ((String) -> Boolean)?): String = try {
        runBlocking { withTimeout(timeoutMs) { slot.askJpeg(prompt, null, imageJpeg, temperature, purpose, stopWhen) } }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        slot.cancel()
        throw java.util.concurrent.TimeoutException("no answer within $timeoutMs ms")
    }

    override fun cancel() = slot.cancel()
    override fun unload() { runBlocking { slot.unload() } }
}
