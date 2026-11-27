package com.sphynx.universal.eval

import org.json.JSONArray
import org.json.JSONObject

/**
 * PHASE 10, step E2: an evaluation set, game-free. The questions and expected answers belong to a game adapter (MLBB:
 * assets/eval/mlbb_*.json); this is only their form. Pure (org.json), unit-tested.
 *
 * Three kinds of item:
 *  - CHOICE: the answer names one of [options] (the tactical model's fight options);
 *  - PLAN: the answer is JSON whose [field] holds one of [options] (the strategic plan);
 *  - RUBRIC: free text, scored by the points it must name ([must]: each inner list is alternatives) and the claims it
 *    must not make ([mustNot]).
 * For CHOICE and PLAN, [best] is the expected answer, [ok] is also acceptable and [unsafe] could get the hero killed.
 */
class EvalSet(val id: String, val version: Int, val game: String, val role: String, val reviewed: Boolean,
              val maxTokens: Int, val about: String, val items: List<Item>) {

    enum class Kind { CHOICE, PLAN, RUBRIC }

    class Item(val id: String, val kind: Kind, val prompt: String, val options: List<String>, val field: String?,
               val best: List<String>, val ok: List<String>, val unsafe: List<String>,
               val must: List<List<String>>, val mustNot: List<String>, val why: String)

    companion object {
        const val SCHEMA = 1

        private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()

        /** The set in [text], or the reason it is refused. */
        fun parse(text: String): Result<EvalSet> = try {
            val o = JSONObject(text)
            require(o.optInt("schema", 0) == SCHEMA) { "schema ${o.optInt("schema", 0)} is not $SCHEMA" }
            val a = o.getJSONArray("items")
            val items = (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                val m = j.optJSONArray("must")
                Item(j.getString("id"), Kind.valueOf(j.getString("kind").uppercase()), j.getString("prompt"),
                    strings(j.optJSONArray("options")), j.optString("field").ifEmpty { null }, strings(j.optJSONArray("best")),
                    strings(j.optJSONArray("ok")), strings(j.optJSONArray("unsafe")),
                    m?.let { (0 until it.length()).map { k -> strings(it.getJSONArray(k)) } } ?: emptyList(),
                    strings(j.optJSONArray("mustNot")), j.optString("why"))
            }
            val s = EvalSet(o.getString("id"), o.getInt("version"), o.optString("game"), o.getString("role"),
                o.optBoolean("reviewed"), o.optInt("maxTokens", 64), o.optString("about"), items)
            problems(s).takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException(it.joinToString("; ")) }
            Result.success(s)
        } catch (e: Exception) { Result.failure(IllegalArgumentException("evaluation set refused: ${e.message}")) }

        /** What is wrong with a set (empty = nothing): every item must be complete and its expected answers possible. */
        fun problems(s: EvalSet): List<String> {
            val out = ArrayList<String>()
            if (s.items.isEmpty()) out.add("no items")
            if (s.version < 1) out.add("version must be 1 or more")
            val dup = s.items.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dup.isNotEmpty()) out.add("item ids twice: " + dup.joinToString())
            for (it in s.items) {
                if (it.prompt.isBlank()) out.add("${it.id}: no prompt")
                when (it.kind) {
                    Kind.CHOICE, Kind.PLAN -> {
                        if (it.best.isEmpty()) out.add("${it.id}: no best answer")
                        val all = it.best + it.ok + it.unsafe
                        (all - it.options.toSet()).takeIf { x -> x.isNotEmpty() }?.let { x -> out.add("${it.id}: not an option: ${x.joinToString()}") }
                        if (all.size != all.toSet().size) out.add("${it.id}: an answer is in two lists")
                        if (it.kind == Kind.PLAN && it.field == null) out.add("${it.id}: no field")
                    }
                    Kind.RUBRIC -> if (it.must.isEmpty() || it.must.any { g -> g.isEmpty() }) out.add("${it.id}: empty rubric")
                }
            }
            return out
        }
    }
}
