package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Google's Gemini API (online), with Google Search grounding so answers come from current
// web sources (patch notes, meta sites, guides). Needs your own API key from
// https://aistudio.google.com/apikey. Only used by the online meta features; the Player keeps
// playing offline, using what was downloaded.
object GeminiClient {

    @Volatile var lastError = ""
        private set
    @Volatile var calls = 0
        private set

    val ready: Boolean get() = Cfg.geminiKey.isNotBlank()

    // Returns the model's text, and the web pages it used
    suspend fun ask(prompt: String, search: Boolean = true): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val key = Cfg.geminiKey.trim()
        if (key.isEmpty()) throw IllegalStateException("No Gemini API key (Meta page)")
        val model = Cfg.geminiModel.trim().ifEmpty { "gemini-2.5-flash" }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (e: Exception) { text.take(200) }
                lastError = "Gemini $code: $msg"
                throw IllegalStateException(lastError)
            }
            calls++
            lastError = ""
            parse(text)
        } finally {
            c.disconnect()
        }
    }

    // generateContent reply -> (all text parts joined, source page titles / links)
    fun parse(json: String): Pair<String, List<String>> {
        val o = JSONObject(json)
        val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: throw IllegalStateException("Gemini gave no answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
        val sources = ArrayList<String>()
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            sources.add(web.optString("title", "").ifEmpty { web.optString("uri", "") })
        }
        return sb.toString() to sources.distinct()
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
