package com.sphynx.universal.pairing

import java.util.Locale

/**
 * PHASE 11, step P2: the pair recommender of the pairing method (docs/PHASE_11_PAIRING_METHOD.md). Pure and game-free:
 * the app hands it the candidates with their evidence; it removes pairs that can't work, keeps the Pareto front
 * (quality, speed, memory, never combined into one number), labels the trade-offs, explains each pair and gives its
 * confidence. It never chooses for the owner. Unit-tested.
 */
object PairRecommender {

    enum class Confidence(val label: String) { HIGH("high"), MEDIUM("medium"), LOW("low") }

    /** What was measured for a model in one job (Phase 10), or null when nothing comparable was measured. */
    data class Measured(val meanScore: Double, val scoreLow: Double, val scoreHigh: Double, val answers: Int,
                        val usableP90Ms: Double, val wholeMedianMs: Double, val setReviewed: Boolean,
                        val heatSlowdown: Double? = null)

    /** A model that could do [job]. [id] names the file (the same file can be a candidate for both jobs). */
    data class Candidate(val id: String, val label: String, val job: Workloads.Job, val sizeMb: Long, val pictures: Boolean?,
                         val gguf: Boolean, val onPhone: Boolean, val measured: Measured?)

    data class Pair(val strategic: Candidate, val tactical: Candidate, val confidence: Confidence, val labels: List<String>,
                    val reasons: List<String>, val misses: List<String>, val memoryMb: Long)

    const val MIN_ANSWERS = 30

    fun confidence(c: Candidate): Confidence = when {
        c.measured == null -> Confidence.LOW
        c.measured.setReviewed && c.measured.answers >= MIN_ANSWERS -> Confidence.HIGH
        else -> Confidence.MEDIUM
    }

    /** The budget a candidate misses for its job, or null. Unmeasured: unknown, not a miss. */
    fun budgetMiss(c: Candidate): String? {
        val m = c.measured ?: return null
        val w = Workloads.of(c.job)
        val v = if (w.budgetOn == Workloads.Budget.USABLE_P90) m.usableP90Ms else m.wholeMedianMs
        return if (v > w.budgetMs) String.format(Locale.US, "%s: %s %.2f s, over the %.1f s budget", c.label, w.budgetOn.label,
            v / 1000, w.budgetMs / 1000.0) else null
    }

    /** One model's memory when both jobs run: the same file for both is loaded twice by today's two slots. */
    fun memoryMb(s: Candidate, t: Candidate): Long = Workloads.neededMb(s.sizeMb) + Workloads.neededMb(t.sizeMb)

    /**
     * The recommended pairs: every strategic × tactical candidate that [allowed] accepts and that fits in [roomMb] (room for
     * models next to the game; below 0 = unknown, nothing removed), Pareto-filtered, labelled and explained, best
     * confidence first. [allowed] carries the app's job rules (one GGUF at a time).
     */
    fun recommend(cands: List<Candidate>, roomMb: Long, allowed: (Candidate, Candidate) -> Boolean): List<Pair> {
        val s = cands.filter { it.job == Workloads.Job.STRATEGIC }
        val t = cands.filter { it.job == Workloads.Job.TACTICAL }
        val all = s.flatMap { a -> t.map { b -> a to b } }
            .filter { (a, b) -> allowed(a, b) }
            .filter { (a, b) -> roomMb < 0 || memoryMb(a, b) <= roomMb }
        val front = all.filter { p -> all.none { q -> q !== p && dominates(q, p) } }
        val pairs = front.map { (a, b) -> describe(a, b) }
        val labelled = label(pairs)
        return labelled.sortedWith(compareBy<Pair> { it.confidence.ordinal }.thenBy { it.misses.size }
            .thenByDescending { quality(it.strategic) + quality(it.tactical) }.thenBy { it.memoryMb })
    }

    // quality: measured mean (unmeasured = unknown, counted as 0 so it never wins on quality); speed: lower is better
    private fun quality(c: Candidate) = c.measured?.meanScore ?: 0.0
    private fun speed(p: kotlin.Pair<Candidate, Candidate>) =
        (p.second.measured?.usableP90Ms ?: Double.MAX_VALUE) to (p.first.measured?.wholeMedianMs ?: Double.MAX_VALUE)

    /** [q] is at least as good as [p] on quality, speed and memory, and better on one. */
    private fun dominates(q: kotlin.Pair<Candidate, Candidate>, p: kotlin.Pair<Candidate, Candidate>): Boolean {
        val ql = quality(q.first) + quality(q.second); val pl = quality(p.first) + quality(p.second)
        val qs = speed(q); val ps = speed(p)
        val qm = memoryMb(q.first, q.second); val pm = memoryMb(p.first, p.second)
        // unmeasured speeds can't be compared: only measured pairs can push another pair off the front on speed
        val speedKnown = qs.first != Double.MAX_VALUE && ps.first != Double.MAX_VALUE
        val geSpeed = !speedKnown || (qs.first <= ps.first && qs.second <= ps.second)
        val gtSpeed = speedKnown && (qs.first < ps.first || qs.second < ps.second)
        val ge = ql >= pl && geSpeed && qm <= pm
        val gt = ql > pl || gtSpeed || qm < pm
        // a measured pair never removes an unmeasured one on quality alone: unknown is not worse
        val bothMeasured = listOf(q.first, q.second, p.first, p.second).all { it.measured != null }
        return ge && gt && (bothMeasured || qm < pm)
    }

    private fun describe(a: Candidate, b: Candidate): Pair {
        val reasons = ArrayList<String>()
        for (c in listOf(a, b)) {
            val job = c.job.name.lowercase()
            reasons.add(c.measured?.let { m -> String.format(Locale.US,
                "%s (%s): measured — mean score %.2f (%.2f–%.2f, %d answers%s), usable 90%% under %.2f s, whole median %.2f s%s",
                c.label, job, m.meanScore, m.scoreLow, m.scoreHigh, m.answers, if (m.setReviewed) "" else ", set not yet reviewed",
                m.usableP90Ms / 1000, m.wholeMedianMs / 1000, m.heatSlowdown?.let { String.format(Locale.US, ", %+.0f%% after 10 min of fights", it * 100) } ?: "") }
                ?: "${c.label} ($job): not measured on this phone yet — declared only: ${c.sizeMb} MB, " +
                    when (c.pictures) { true -> "reads pictures"; false -> "text only"; null -> "pictures unknown" } +
                    ", for ${Workloads.declaredClass(c.sizeMb).label} phones and up")
        }
        if (a.job == Workloads.Job.STRATEGIC && a.pictures == false) reasons.add("${a.label} reads text only: the draft and screen reading need pictures")
        if (!a.onPhone || !b.onPhone) reasons.add("not on the phone yet: " + listOf(a, b).filter { !it.onPhone }.joinToString { it.label } + " (download list)")
        val misses = listOfNotNull(budgetMiss(a), budgetMiss(b))
        val conf = listOf(confidence(a), confidence(b)).maxByOrNull { it.ordinal }!!     // the lower of the two
        return Pair(a, b, conf, emptyList(), reasons, misses, memoryMb(a, b))
    }

    private fun label(ps: List<Pair>): List<Pair> {
        if (ps.isEmpty()) return ps
        val measured = ps.filter { it.strategic.measured != null && it.tactical.measured != null }
        val bestQ = measured.maxByOrNull { quality(it.strategic) + quality(it.tactical) }
        val fastest = measured.minByOrNull { it.tactical.measured!!.usableP90Ms }
        val lightest = ps.minByOrNull { it.memoryMb }
        return ps.map { p ->
            val l = listOfNotNull("best measured quality".takeIf { p === bestQ }, "fastest".takeIf { p === fastest },
                "lightest".takeIf { p === lightest })
            p.copy(labels = l.ifEmpty { listOf("balanced") })
        }
    }

    /** The pair's card text. */
    fun text(p: Pair): String = listOfNotNull(
        "${p.strategic.label} + ${p.tactical.label} · ${p.labels.joinToString(", ")} · confidence ${p.confidence.label} · about ${p.memoryMb} MB",
        p.reasons.joinToString("\n"),
        if (p.misses.isEmpty()) null else "Misses: " + p.misses.joinToString("; "),
    ).joinToString("\n")
}
