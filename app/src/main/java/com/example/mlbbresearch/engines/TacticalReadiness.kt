package com.example.mlbbresearch.engines

import com.example.mlbbresearch.universal.Decision
import com.example.mlbbresearch.universal.DecisionKind
import com.example.mlbbresearch.universal.DecisionValidator
import com.example.mlbbresearch.universal.EngineSource
import com.example.mlbbresearch.universal.UniversalMatchState

// READINESS OF THE SMALL TACTICAL MODEL (Gemma 3 270M; Stage 2, step 4).
// The owner removed the model's Experimental label on 2026-11-09; these measured criteria remain the yardstick. This
// harness runs any model (a function from a situation to a reply + latency) over a set of
// scenarios and measures:
//   valid     - the reply names exactly one known option
//   agreement - the option is one the scenario accepts
//   unsafe    - the option is one the scenario marks dangerous (e.g. ENGAGE at 10% health)
//   rejected  - DecisionValidator rejects it against the scenario's state
//   latency   - p50 / p95 in ms
// It runs offline (unit tests use fake models) and, since research phase R4, on the phone against
// the real model over fight moments recorded in verified practice (research/ReadinessRun).
// A passing report does NOT promote the model by itself: the owner decides.
object TacticalReadiness {

    data class Scenario(
        val name: String,
        val state: UniversalMatchState,
        val acceptable: Set<DecisionKind>,
        val unsafe: Set<DecisionKind> = emptySet(),
        val prompt: String = ""              // the summary the model is asked about (recorded moments)
    )

    data class Reply(val text: String, val latencyMs: Long)

    // thresholds approved by the owner
    data class Criteria(
        val minScenarios: Int = 200,
        val minValid: Double = 0.98,
        val minAgreement: Double = 0.85,
        val maxUnsafe: Int = 0,
        val maxP95LatencyMs: Long = 300
    )

    data class Report(
        val scenarios: Int,
        val valid: Int,
        val agreed: Int,
        val unsafe: Int,
        val rejectedByValidator: Int,
        val p50LatencyMs: Long,
        val p95LatencyMs: Long,
        val failures: List<String>,          // per-scenario problems, for review
        val ready: Boolean,
        val reasons: List<String>            // why not ready (empty when ready)
    ) {
        val validRate: Double get() = if (scenarios == 0) 0.0 else valid.toDouble() / scenarios
        val agreementRate: Double get() = if (scenarios == 0) 0.0 else agreed.toDouble() / scenarios
    }

    // the six options the MLBB tactical prompt offers (TacticalAdvisor.prompt), as universal kinds
    private val OPTIONS = linkedMapOf(
        "ALL_IN" to DecisionKind.ENGAGE, "POKE" to DecisionKind.POKE, "FARM" to DecisionKind.FARM,
        "RETREAT" to DecisionKind.RETREAT, "GROUP" to DecisionKind.GROUP, "RULES" to DecisionKind.DEFER
    )

    // STRICT parse for measurement: exactly one known option must be named. (The live advisor's
    // parse takes the first one it finds; an answer naming two options is counted invalid here.)
    fun parseStrict(reply: String): DecisionKind? {
        val u = Regex("ALL[\\s_-]?IN").replace(reply.uppercase(), "ALL_IN")
        val found = OPTIONS.keys.filter { Regex("(^|[^A-Z_])" + it + "([^A-Z_]|$)").containsMatchIn(u) }
        return if (found.size == 1) OPTIONS[found[0]] else null
    }

    private fun percentile(sorted: List<Long>, p: Double): Long =
        if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    fun evaluate(scenarios: List<Scenario>, model: (UniversalMatchState) -> Reply, criteria: Criteria = Criteria()): Report =
        evaluateScenarios(scenarios, { model(it.state) }, criteria)

    fun evaluateScenarios(scenarios: List<Scenario>, model: (Scenario) -> Reply, criteria: Criteria = Criteria()): Report {
        var valid = 0; var agreed = 0; var unsafe = 0; var rejected = 0
        val lat = ArrayList<Long>(); val failures = ArrayList<String>()
        for (sc in scenarios) {
            val r = try { model(sc) } catch (t: Throwable) { failures.add("${sc.name}: model error ${t.message}"); continue }
            lat.add(r.latencyMs)
            val kind = parseStrict(r.text)
            if (kind == null) { failures.add("${sc.name}: unreadable reply \"${r.text.take(40)}\""); continue }
            valid++
            if (kind in sc.acceptable) agreed++ else failures.add("${sc.name}: ${kind.name} not in ${sc.acceptable.map { it.name }}")
            if (kind in sc.unsafe) { unsafe++; failures.add("${sc.name}: UNSAFE ${kind.name}") }
            val d = Decision(kind, EngineSource.TACTICAL, sc.state.timestampMs, 1000L)
            if (DecisionValidator.validate(d, sc.state, sc.state.timestampMs).outcome == DecisionValidator.Outcome.REJECTED) rejected++
        }
        lat.sort()
        val n = scenarios.size
        val p95 = percentile(lat, 0.95)
        val reasons = ArrayList<String>()
        if (n < criteria.minScenarios) reasons.add("only $n scenarios (need ${criteria.minScenarios})")
        val vr = if (n == 0) 0.0 else valid.toDouble() / n
        val ar = if (n == 0) 0.0 else agreed.toDouble() / n
        if (vr < criteria.minValid) reasons.add("valid replies %.1f%% (need %.1f%%)".format(java.util.Locale.US, vr * 100, criteria.minValid * 100))
        if (ar < criteria.minAgreement) reasons.add("agreement %.1f%% (need %.1f%%)".format(java.util.Locale.US, ar * 100, criteria.minAgreement * 100))
        if (unsafe > criteria.maxUnsafe) reasons.add("$unsafe unsafe choices (max ${criteria.maxUnsafe})")
        if (p95 > criteria.maxP95LatencyMs) reasons.add("p95 latency $p95 ms (max ${criteria.maxP95LatencyMs})")
        return Report(n, valid, agreed, unsafe, rejected, percentile(lat, 0.5), p95, failures, reasons.isEmpty(), reasons)
    }
}
