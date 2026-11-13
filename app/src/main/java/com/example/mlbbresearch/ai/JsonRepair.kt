package com.example.mlbbresearch.ai

import org.json.JSONObject

// A JSON ANSWER THAT WAS CUT OFF (owner's 2026-11-13 log: "General meta update failed: answer was not JSON:
// {"patch":"2.2.23 ...","bans":[..." - the model ran out of answer length in the middle of a list). The complete
// part is kept: the text is cut back to the last finished value and the open lists / objects are closed.
// Pure, unit tested.
object JsonRepair {

    // the first JSON object in the text: as it is when complete, repaired when cut off; null when there is none
    fun parse(text: String): JSONObject? {
        val clean = text.replace("```json", "").replace("```", "")
        val a = clean.indexOf('{')
        if (a < 0) return null
        val b = clean.lastIndexOf('}')
        if (b > a) try { return JSONObject(clean.substring(a, b + 1)) } catch (e: Exception) { }
        val fixed = close(clean.substring(a)) ?: return null
        return try { JSONObject(fixed) } catch (e: Exception) { null }
    }

    // cut back to the last comma outside a string, then close what is still open; null = nothing usable
    fun close(s: String): String? {
        val stack = ArrayList<Char>()
        var inStr = false; var esc = false
        var cut = -1; var cutDepth: List<Char> = emptyList()
        for (i in s.indices) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> stack.add(c)
                '}', ']' -> { if (stack.isEmpty()) return null; stack.removeAt(stack.size - 1); if (stack.isEmpty()) return s.substring(0, i + 1) }
                ',' -> { cut = i; cutDepth = ArrayList(stack) }
            }
        }
        if (cut < 0 || cutDepth.isEmpty()) return null
        return s.substring(0, cut) + cutDepth.reversed().joinToString("") { if (it == '{') "}" else "]" }
    }
}
