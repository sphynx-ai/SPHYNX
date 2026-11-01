package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Groq API (online, OpenAI-style chat completions). The default model, openai/gpt-oss-120b,
// gets Groq's built-in browser search tool, so answers come from current sources (patch notes,
// meta sites, guides).
// Needs your own API key from https://console.groq.com/keys. Only used by the online meta
// features; the Player keeps playing offline, using what was downloaded.
object GroqClient {

    @Volatile var lastError = ""
        private set
    @Volatile var calls = 0
        private set

    val ready: Boolean get() = Cfg.groqKey.isNotBlank()

    // Groq's OpenAI-compatible endpoint (a test can point it at a local stand-in)
    @Volatile var baseUrl = "https://api.groq.com/openai/v1"

    // set after each answer: did it come with a web search?
    @Volatile var lastSearched = false
        private set

    // Returns the model's text, and the web pages it used (when the model reports them).
    // gpt-oss models sometimes write output Groq cannot parse (mostly while using the search
    // tool): Groq then answers 400 with the raw output in "failed_generation". In that case the
    // JSON is taken from the raw output; if there is none it retries once, then asks once more
    // WITHOUT web search (the answer then comes from the model's own knowledge).
    suspend fun ask(prompt: String): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val key = Cfg.groqKey.trim()
        if (key.isEmpty()) throw IllegalStateException("No Groq API key (put groq_key.txt in the MLBB-Research folder)")
        val model = Cfg.groqModel.trim().ifEmpty { Cfg.DEFAULT_GROQ_MODEL }
        val canSearch = model.startsWith("openai/gpt-oss")
        val attempts = if (canSearch) listOf(true, true, false) else listOf(false)
        var last: Exception? = null
        for (search in attempts) {
            try {
                val (code, text) = post(key, model, prompt, search)
                if (code in 200..299) {
                    calls++
                    lastError = ""
                    lastSearched = search
                    try { return@withContext parse(text) } catch (e: EmptyAnswer) {
                        // an empty answer (often after a web search): try again, the last try without search
                        lastError = "Groq gave an empty answer"; last = e; continue
                    }
                }
                val err = try { JSONObject(text).getJSONObject("error") } catch (e: Exception) { null }
                val msg = err?.optString("message") ?: text.take(200)
                lastError = "Groq $code: $msg"
                // the model did answer, Groq just could not parse it: use the raw output if it holds our JSON
                val raw = err?.optString("failed_generation", "") ?: ""
                if (raw.isNotBlank() && json(raw) != null) {
                    calls++
                    lastSearched = search
                    return@withContext raw to emptyList<String>()
                }
                // wrong key / no access / too many requests: retrying will not help
                if (code == 401 || code == 403 || code == 404 || code == 429) throw IllegalStateException(lastError)
                last = IllegalStateException(lastError)
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Groq did not answer")
    }

    private fun post(key: String, model: String, prompt: String, search: Boolean): Pair<Int, String> {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        // the gpt-oss models can search the web through Groq's built-in browser search tool
        if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
        val c = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    class EmptyAnswer : IllegalStateException("Groq gave an empty answer (tried again, also without web search)")

    // chat completion -> (answer text, sources from the web searches it ran)
    fun parse(json: String): Pair<String, List<String>> {
        val o = JSONObject(json)
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("Groq gave no answer")
        var text = msg.optString("content", "")
        // gpt-oss sometimes leaves "content" empty and writes the answer into its reasoning
        if (text.isBlank()) {
            val r = msg.optString("reasoning", "")
            if (json(r) != null) text = r
        }
        if (text.isBlank()) throw EmptyAnswer()
        val sources = ArrayList<String>()
        val tools = msg.optJSONArray("executed_tools") ?: JSONArray()
        for (i in 0 until tools.length()) {
            val results = tools.optJSONObject(i)?.optJSONObject("search_results")?.optJSONArray("results")
                ?: tools.optJSONObject(i)?.optJSONArray("search_results") ?: continue
            for (j in 0 until results.length()) {
                val r = results.optJSONObject(j) ?: continue
                sources.add(r.optString("title", "").ifEmpty { r.optString("url", "") })
            }
        }
        return text to sources.filter { it.isNotBlank() }.distinct()
    }

    // first {...} block of a reply
    fun json(text: String): JSONObject? {
        val clean = text.replace("```json", "").replace("```", "")
        val a = clean.indexOf('{')
        val b = clean.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(clean.substring(a, b + 1)) } catch (e: Exception) { null }
    }
}
