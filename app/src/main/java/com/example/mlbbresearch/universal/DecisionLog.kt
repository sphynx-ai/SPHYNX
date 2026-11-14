package com.example.mlbbresearch.universal

// LOGGING MODE (owner approved 2026-11-07): what every engine advised at a moment, what the
// arbiter would have chosen, and what the app actually played - recorded side by side, never
// acted on. It is the evidence for later steps (how often the engines agree, how often advice is
// rejected and why, how the 270M compares) and the source of real scenarios for the readiness
// harness. Pure Kotlin: a bounded in-memory log plus running counters.
class DecisionLog(private val capacity: Int = 500) {

    data class Entry(
        val timestampMs: Long,
        val gameId: String,
        val advice: List<Decision>,
        val arbiterChoice: DecisionKind?,
        val played: DecisionKind?,
        val rejected: List<Pair<EngineSource, String>>,
        val advisoryOnly: List<EngineSource>,
        val schemaVersion: Int = UniversalMatchState.SCHEMA_VERSION   // of the state the advice was judged on
    ) {
        val agrees: Boolean? get() = if (arbiterChoice == null || played == null) null else arbiterChoice == played
    }

    private val entries = ArrayDeque<Entry>()
    var total = 0; private set
    var compared = 0; private set           // entries where both an arbiter choice and a played choice exist
    var agreed = 0; private set
    val rejectionsByReason = LinkedHashMap<String, Int>()
    val adviceBySource = LinkedHashMap<EngineSource, Int>()

    @Synchronized
    fun record(state: UniversalMatchState, advice: List<Decision>, played: DecisionKind?, nowMs: Long): Entry {
        val r = DecisionArbiter.resolve(advice, state, nowMs)
        val e = Entry(nowMs, state.gameId, advice, r.chosen?.kind, played,
            r.rejected.map { (d, why) -> d.source to why.joinToString("; ") }, r.advisory.map { it.source },
            state.schemaVersion)
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(e)
        total++
        e.agrees?.let { compared++; if (it) agreed++ }
        for ((_, why) in e.rejected) rejectionsByReason[why] = (rejectionsByReason[why] ?: 0) + 1
        for (d in advice) adviceBySource[d.source] = (adviceBySource[d.source] ?: 0) + 1
        return e
    }

    @Synchronized fun recent(n: Int = 20): List<Entry> = entries.toList().takeLast(n)

    @Synchronized
    fun summary(): String {
        if (total == 0) return "Decision log: nothing recorded yet"
        val agree = if (compared == 0) "-" else "%d%%".format(agreed * 100 / compared)
        val topReject = rejectionsByReason.maxByOrNull { it.value }?.let { " · most rejected: ${it.key} (${it.value})" } ?: ""
        return "Decision log: $total moments, arbiter agrees with play $agree of $compared$topReject"
    }

    @Synchronized
    fun clear() { entries.clear(); total = 0; compared = 0; agreed = 0; rejectionsByReason.clear(); adviceBySource.clear() }
}
