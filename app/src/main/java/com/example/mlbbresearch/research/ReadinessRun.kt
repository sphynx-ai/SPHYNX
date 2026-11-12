package com.example.mlbbresearch.research

import com.example.mlbbresearch.adapters.mlbb.MlbbAdapter
import com.example.mlbbresearch.ai.AiRuntime
import com.example.mlbbresearch.ai.TacticalAdvisor
import com.example.mlbbresearch.ai.TacticalLlmEngine
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.engines.TacticalReadiness
import kotlinx.coroutines.launch
import java.io.File

// Research mode, phase R4: measures the small tactical model (Gemma 3 270M) on the phone, over the
// fight moments recorded in verified practice, against the owner-approved criteria
// (>= 200 moments, >= 98% valid replies, >= 85% agreement, 0 unsafe, p95 <= 300 ms).
// It is a test bench only: it never touches the game, it runs only while Player mode is off (so it
// does not compete with play for the CPU and the latency is measured fairly), and a passing report
// measures the model on real practice moments (the owner removed its Experimental label on 2026-11-09).
object ReadinessRun {

    @Volatile var running = false; private set
    @Volatile var progress = ""; private set
    @Volatile var lastReport: TacticalReadiness.Report? = null; private set
    @Volatile var lastAtMs = 0L; private set
    @Volatile private var stopAsked = false

    private fun reportFile(): File? = com.example.mlbbresearch.vision.HeroVerifier.appContext()?.let {
        File(File(it.filesDir, "research").apply { mkdirs() }, "readiness_report.txt") }

    // why a run cannot start now, or null
    fun blocker(): String? = when {
        running -> "a test is already running"
        AiRuntime.playerOn -> "turn Player mode off first (the test must not compete with play)"
        !TacticalLlmEngine.ready -> "the small tactical model is not loaded (${TacticalLlmEngine.state})"
        ResearchMode.moments.size == 0 -> "no practice moments recorded yet"
        else -> null
    }

    fun start(onDone: (String) -> Unit) {
        blocker()?.let { onDone("Cannot test: $it"); return }
        running = true; stopAsked = false
        val moments = ResearchMode.moments.all()
        val caps = MlbbAdapter.capabilities
        AiRuntime.scope.launch {
            try {
                // ask the model about every moment first (suspending; nothing blocks a thread),
                // then score the replies with the shared harness
                val scenarios = moments.map { it.scenario(caps) }
                val replies = java.util.IdentityHashMap<TacticalReadiness.Scenario, TacticalReadiness.Reply>()
                for ((i, sc) in scenarios.withIndex()) {
                    if (stopAsked) break
                    progress = "${i + 1} of ${scenarios.size}"
                    val t0 = System.currentTimeMillis()
                    val reply = try { TacticalLlmEngine.ask(TacticalAdvisor.prompt(sc.prompt), null, 0.0, purpose = "readiness test") }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { null }
                    if (reply != null) replies[sc] = TacticalReadiness.Reply(reply, System.currentTimeMillis() - t0)
                }
                if (stopAsked) { onDone("Readiness test stopped (no report)"); return@launch }
                // a moment the model failed to answer counts as an invalid reply (model error)
                val report = TacticalReadiness.evaluateScenarios(scenarios, { replies[it] ?: throw IllegalStateException("no reply") })
                lastReport = report; lastAtMs = System.currentTimeMillis()
                val text = describe(report, moments.size)
                try { reportFile()?.writeText(text + "\n\nProblems:\n" + report.failures.take(200).joinToString("\n")) } catch (e: Exception) { Diag.error("Readiness report not saved", e) }
                Diag.info("Readiness: " + text.lineSequence().first())
                onDone(text.lineSequence().first())
            } catch (t: Throwable) {
                Diag.error("Readiness test stopped", t)
                onDone("Readiness test stopped: ${t.message}")
            } finally { running = false; progress = "" }
        }
    }

    fun stop() { stopAsked = true }

    fun describe(r: TacticalReadiness.Report, n: Int): String = buildString {
        append(if (r.ready) "Small model MEETS the criteria on $n practice moments"
        else "Small model NOT READY on $n practice moments: " + r.reasons.joinToString("; "))
        append("\nvalid %.1f%%, agreement %.1f%%, unsafe %d, rejected by the validator %d, latency p50 %d ms / p95 %d ms".format(
            java.util.Locale.US, r.validRate * 100, r.agreementRate * 100, r.unsafe, r.rejectedByValidator, r.p50LatencyMs, r.p95LatencyMs))
    }

    fun status(): String = when {
        running -> "Readiness test running: $progress"
        lastReport != null -> describe(lastReport!!, lastReport!!.scenarios)
        else -> "Readiness test: not run yet"
    }
}
