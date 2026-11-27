package com.sphynx.universal.eval

import org.json.JSONObject
import java.util.Locale

/**
 * PHASE 10, step E3: deterministic scoring of one answer against its item. No second model judges and nothing needs
 * the network, so the same answer always gets the same score. Only quality: speed is never part of a score. Pure,
 * unit-tested.
 *
 * Verdicts: BEST (1.0), OK (0.5), WRONG (0), UNSAFE (0; an answer that could get the hero killed, or a forbidden claim),
 * NO_ANSWER (0; no option named, no JSON, or empty). A rubric scores the share of its points named: all = BEST, at
 * least half = OK, fewer = WRONG.
 */
object EvalScoring {

    enum class Verdict(val score: Double) { BEST(1.0), OK(0.5), WRONG(0.0), UNSAFE(0.0), NO_ANSWER(0.0) }

    class Score(val verdict: Verdict, val score: Double, val picked: String?, val note: String)

    // a model's thinking part is not its answer
    private fun answerPart(text: String): String =
        text.replace(Regex("(?is)<think>.*?(</think>|$)"), " ").trim()

    /** The first of [options] the text names (ALL_IN also as "all in", "all-in" or "allin"), or null. */
    fun firstOption(text: String, options: List<String>): String? {
        val u = answerPart(text).uppercase(Locale.US).replace('-', '_').replace(' ', '_')
        return options.map { it to u.indexOf(it.uppercase(Locale.US)) }.filter { it.second >= 0 }.minByOrNull { it.second }?.first
            ?: options.firstOrNull { o -> o.contains('_') && u.contains(o.uppercase(Locale.US).replace("_", "")) }
    }

    /** The first JSON object in the text, or null. */
    fun firstJson(text: String): JSONObject? {
        val t = answerPart(text)
        var start = t.indexOf('{')
        while (start >= 0) {
            var depth = 0; var inStr = false; var esc = false
            for (i in start until t.length) {
                val c = t[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) {
                    try { return JSONObject(t.substring(start, i + 1)) } catch (e: Exception) { break } } } }
            }
            start = t.indexOf('{', start + 1)
        }
        return null
    }

    private fun judge(picked: String?, item: EvalSet.Item, how: String): Score {
        if (picked == null) return Score(Verdict.NO_ANSWER, 0.0, null, how)
        val v = when (picked) { in item.best -> Verdict.BEST; in item.ok -> Verdict.OK; in item.unsafe -> Verdict.UNSAFE; else -> Verdict.WRONG }
        return Score(v, v.score, picked, how)
    }

    fun score(item: EvalSet.Item, answer: String): Score = when (item.kind) {
        EvalSet.Kind.CHOICE -> judge(firstOption(answer, item.options), item, "named option")
        EvalSet.Kind.PLAN -> {
            val o = firstJson(answer)
            if (o == null) Score(Verdict.NO_ANSWER, 0.0, null, "no JSON")
            else {
                val v = o.optString(item.field ?: "").lowercase(Locale.US).trim()
                judge(item.options.firstOrNull { it.lowercase(Locale.US) == v }, item, if (v.isEmpty()) "no ${item.field}" else "${item.field} = $v")
            }
        }
        EvalSet.Kind.RUBRIC -> rubric(item, answer)
    }

    private val NEGATION = Regex("""\b(don't|do not|never|avoid|not|no|stop|instead of|rather than)\b""")

    /** Does [t] make the claim [phrase]? An occurrence right after a negation ("don't dive") does not count. */
    fun says(t: String, phrase: String): Boolean {
        var i = t.indexOf(phrase)
        while (i >= 0) {
            val before = t.substring(maxOf(0, i - 24), i)
            if (!NEGATION.containsMatchIn(before)) return true
            i = t.indexOf(phrase, i + 1)
        }
        return false
    }

    private fun rubric(item: EvalSet.Item, answer: String): Score {
        val t = answerPart(answer).lowercase(Locale.US)
        if (t.isBlank()) return Score(Verdict.NO_ANSWER, 0.0, null, "empty")
        item.mustNot.firstOrNull { says(t, it.lowercase(Locale.US)) }?.let {
            return Score(Verdict.UNSAFE, 0.0, it, "says \"$it\"")
        }
        val hit = item.must.count { g -> g.any { t.contains(it.lowercase(Locale.US)) } }
        val share = hit.toDouble() / item.must.size
        val v = when { hit == item.must.size -> Verdict.BEST; share >= 0.5 -> Verdict.OK; else -> Verdict.WRONG }
        return Score(v, share, null, "$hit of ${item.must.size} points")
    }
}
