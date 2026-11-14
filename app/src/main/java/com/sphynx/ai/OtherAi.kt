package com.sphynx.ai

import com.sphynx.core.Cfg
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// MORE ONLINE MODELS that share Groq's work (each with its own free limits):
//   Gemini 3.8 Flash       (Google, gemini_key.txt)  - can search the web itself (Google Search)
//   Gemini 3.6 Flash       (Google, same key)        - between the two, can search too
//   Gemini 3.5 Flash-Lite  (Google, same key)        - cheaper / lighter, can search too
//   (Google counts its free limits per model: one key, three separate allowances)
//   Cerebras gpt-oss-120b  (cerebras_key.txt)        - very fast, no web search
//   Groq, a SECOND model   (groq_key.txt, openai/gpt-oss-20b) - Groq's limits are per model, so a
//                          second one roughly doubles what Groq can answer; it can web-search too
// Keys from aistudio.google.com (API keys) and cloud.cerebras.ai. Model names can be changed with
// gemini_flash_model.txt / gemini_mid_model.txt / gemini_lite_model.txt / cerebras_model.txt / groq_model2.txt in the folder.
// A provider that answers "too many requests" rests until it says it is ready again; the next one
// takes the question (GroqClient.ask decides the order).
object OtherAi {

    class Provider(val id: String, val label: String, val gemini: Boolean, val canSearch: Boolean,
                   val key: () -> String, val model: () -> String)

    class RateLimited(val provider: Provider, val seconds: Double, msg: String) : Exception(msg)

    val GEMINI_FLASH = Provider("gemini_flash", "Gemini 3.8 Flash", true, true, { Cfg.geminiKey },
        { Cfg.raw("gemini_flash_model")?.takeIf { it.isNotBlank() } ?: "gemini-3.8-flash" })
    val GEMINI_MID = Provider("gemini_mid", "Gemini 3.6 Flash", true, true, { Cfg.geminiKey },
        { Cfg.raw("gemini_mid_model")?.takeIf { it.isNotBlank() } ?: "gemini-3.6-flash" })
    val GEMINI_LITE = Provider("gemini_lite", "Gemini 3.5 Flash-Lite", true, true, { Cfg.geminiKey },
        { Cfg.raw("gemini_lite_model")?.takeIf { it.isNotBlank() } ?: "gemini-3.5-flash-lite" })
    val CEREBRAS = Provider("cerebras", "Cerebras", false, false, { Cfg.cerebrasKey },
        { Cfg.raw("cerebras_model")?.takeIf { it.isNotBlank() } ?: "gpt-oss-120b" })
    val GROQ2 = Provider("groq2", "Groq (2nd model)", false, true, { Cfg.groqKey },
        { Cfg.raw("groq_model2")?.takeIf { it.isNotBlank() } ?: "openai/gpt-oss-20b" })
    val all = listOf(GEMINI_FLASH, GEMINI_MID, GEMINI_LITE, CEREBRAS, GROQ2)

    @Volatile var baseGemini = "https://generativelanguage.googleapis.com/v1beta"   // (tests point these elsewhere)
    @Volatile var baseCerebras = "https://api.cerebras.ai/v1"
    val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun until(p: Provider): Long = Cfg.raw("lim_${p.id}")?.toLongOrNull() ?: 0L
    fun hasKey(p: Provider) = p.key().isNotBlank()
    fun usable(p: Provider) = hasKey(p) && System.currentTimeMillis() >= until(p)
    fun rest(p: Provider, seconds: Double) {
        Cfg.setRaw("lim_${p.id}", (System.currentTimeMillis() + (seconds * 1000).toLong() + 1000).toString())
        GroqClient.schedule()
    }

    // one question to one provider: (answer, web pages used); throws RateLimited on "too many requests"
    fun ask(p: Provider, prompt: String, search: Boolean): Pair<String, List<String>> =
        if (p.gemini) gemini(p, prompt, search && p.canSearch)
        else if (p === GROQ2) openAi(p, GroqClient.baseUrl, prompt, search && p.model().startsWith("openai/gpt-oss"))
        else openAi(p, baseCerebras, prompt, false)

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Triple<Int, String, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 20_000; c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            return Triple(code, text, c.getHeaderField("retry-after"))
        } finally { c.disconnect() }
    }

    private fun limitSeconds(text: String, header: String?): Double {
        header?.trim()?.toDoubleOrNull()?.let { return it }
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(text)?.let { return it.groupValues[1].toDouble() }
        GroqClient.retrySeconds(null, text)?.let { return it }
        Regex("retry in (\\d+(?:\\.\\d+)?)\\s*s", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1].toDouble() }
        return 60.0
    }

    private fun gemini(p: Provider, prompt: String, search: Boolean): Pair<String, List<String>> {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 4096))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val (code, text, retry) = post("$baseGemini/models/${p.model()}:generateContent", mapOf("x-goog-api-key" to p.key().trim()), body)
        if (code == 429) { val s = limitSeconds(text, retry); rest(p, s); throw RateLimited(p, s, "${p.label}: too many requests") }
        if (code !in 200..299) throw IllegalStateException("${p.label} $code: " + (try { JSONObject(text).getJSONObject("error").optString("message") } catch (e: Exception) { text.take(200) }))
        val o = JSONObject(text)
        val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: throw IllegalStateException("${p.label} gave no answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val answer = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }.joinToString("")
        if (answer.isBlank()) throw IllegalStateException("${p.label} gave an empty answer")
        val sources = ArrayList<String>()
        cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("web")?.let { w -> sources.add(w.optString("title").ifEmpty { w.optString("uri") }) }
        }
        calls.merge(p.id, 1, Int::plus)
        return answer to sources.filter { it.isNotBlank() }.distinct()
    }

    // OpenAI-style chat completions (Cerebras; Groq's second model, with Groq's browser search when asked)
    private fun openAi(p: Provider, base: String, prompt: String, search: Boolean): Pair<String, List<String>> {
        val body = JSONObject().put("model", p.model())
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_completion_tokens", 4096)
        if (p === GROQ2) {
            if (p.model().startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")
            if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
        }
        val (code, text, retry) = post("$base/chat/completions", mapOf("Authorization" to "Bearer ${p.key().trim()}"), body)
        // (Groq: a 400 "failed_generation" often still holds the JSON answer)
        if (code == 400) try {
            val raw = JSONObject(text).getJSONObject("error").optString("failed_generation", "")
            if (raw.isNotBlank() && GroqClient.json(raw) != null) { calls.merge(p.id, 1, Int::plus); return raw to emptyList() }
        } catch (e: Exception) { }
        if (code == 429) { val s = limitSeconds(text, retry); rest(p, s); throw RateLimited(p, s, "${p.label}: too many requests") }
        if (code !in 200..299) throw IllegalStateException("${p.label} $code: " + (try { JSONObject(text).optJSONObject("error")?.optString("message") ?: JSONObject(text).optString("message") } catch (e: Exception) { text.take(200) }))
        val msg = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw IllegalStateException("${p.label} gave no answer")
        var answer = msg.optString("content", "")
        if (answer.isBlank()) msg.optString("reasoning", "").takeIf { GroqClient.json(it) != null }?.let { answer = it }
        if (answer.isBlank()) throw IllegalStateException("${p.label} gave an empty answer")
        calls.merge(p.id, 1, Int::plus)
        // the web pages Groq's search used
        val srcs = ArrayList<String>()
        msg.optJSONArray("executed_tools")?.let { tl -> for (i in 0 until tl.length()) {
            val rs = tl.optJSONObject(i)?.optJSONObject("search_results")?.optJSONArray("results") ?: continue
            for (j in 0 until rs.length()) rs.optJSONObject(j)?.let { r -> srcs.add(r.optString("title").ifEmpty { r.optString("url") }) }
        } }
        return answer to srcs.filter { it.isNotBlank() }.distinct()
    }
}
