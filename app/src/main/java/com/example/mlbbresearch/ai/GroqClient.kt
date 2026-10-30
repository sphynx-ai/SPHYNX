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

    // Returns the model's text, and the web pages it used (when the model reports them)
    suspend fun ask(prompt: String): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val key = Cfg.groqKey.trim()
        if (key.isEmpty()) throw IllegalStateException("No Groq API key (Meta page)")
        val model = Cfg.groqModel.trim().ifEmpty { Cfg.DEFAULT_GROQ_MODEL }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        // the gpt-oss models can search the web through Groq's built-in browser search tool
        if (model.startsWith("openai/gpt-oss")) body.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
        val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
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
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (e: Exception) { text.take(200) }
                lastError = "Groq $code: $msg"
                throw IllegalStateException(lastError)
            }
            calls++
            lastError = ""
            parse(text)
        } finally {
            c.disconnect()
        }
    }

    // chat completion -> (answer text, sources from the web searches it ran)
    fun parse(json: String): Pair<String, List<String>> {
        val o = JSONObject(json)
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("Groq gave no answer")
        val text = msg.optString("content", "")
        if (text.isBlank()) throw IllegalStateException("Groq gave an empty answer")
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
