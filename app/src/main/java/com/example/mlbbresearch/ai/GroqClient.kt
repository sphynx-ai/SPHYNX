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

    // ONLINE QUESTIONS GO TO WHICHEVER MODEL IS FREE: Groq, Gemini 3.8 Flash, Gemini 3.5 Flash-Lite,
    // Cerebras (OtherAi) - every one with a key and not resting after its rate limit.
    // ready = at least one can be asked now
    val ready: Boolean get() = (Cfg.groqKey.isNotBlank() && !groqLimited) || OtherAi.all.any { OtherAi.usable(it) }
    val anyKey: Boolean get() = Cfg.groqKey.isNotBlank() || OtherAi.all.any { OtherAi.hasKey(it) }
    @Volatile var lastProvider = "-"
        private set

    // ---- rate limit: when Groq answers 429, the time it is available again ----
    // (from its "retry-after" header or "Please try again in 7m12.5s"; kept across app restarts)
    val groqUntil: Long get() = Cfg.raw("groq_limited_until")?.toLongOrNull() ?: 0L
    val groqLimited: Boolean get() = System.currentTimeMillis() < groqUntil
    // ALL the models you have keys for are resting (nothing can be asked); until when
    val limited: Boolean get() = anyKey && !ready
    val limitedUntil: Long get() {
        if (!limited) return 0L
        val t = ArrayList<Long>()
        if (Cfg.groqKey.isNotBlank()) t.add(groqUntil)
        for (p in OtherAi.all) if (OtherAi.hasKey(p)) t.add(OtherAi.until(p))
        return t.minOrNull() ?: 0L
    }
    // each model's state, for the Meta page
    fun providersText(): String {
        val now = System.currentTimeMillis()
        fun st(key: Boolean, until: Long) = when { !key -> "no key"; now < until -> "resting ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(until))}"; else -> "ready" }
        return "Groq: " + st(Cfg.groqKey.isNotBlank(), groqUntil) + (if (calls > 0) " ($calls answers)" else "") +
            OtherAi.all.joinToString("") { p -> " · ${p.label}: " + st(OtherAi.hasKey(p), OtherAi.until(p)) + (OtherAi.calls[p.id]?.let { " ($it)" } ?: "") }
    }
    @Volatile var limitReason = ""
        private set
    // what Groq said is left after the last answer (its x-ratelimit headers), -1 = unknown
    @Volatile var tokensLeft = -1L
        private set
    @Volatile var requestsLeft = -1L
        private set
    // called once when the wait is over (the app shows a notification)
    @Volatile var onAvailable: (() -> Unit)? = null
    private var timer: java.util.Timer? = null

    // "available in 6 m 40 s" / "ready"; for the indicators
    fun waitText(now: Long = System.currentTimeMillis()): String {
        val ms = limitedUntil - now
        if (ms <= 0) return "ready"
        val s = (ms + 999) / 1000
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "$s s"
        }
    }
    fun availableAtText(): String = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(limitedUntil))

    // seconds from Groq's reply: header "retry-after" first, else the text of its message
    fun retrySeconds(header: String?, message: String): Double? {
        header?.trim()?.toDoubleOrNull()?.let { return it }
        val m = Regex("try again in ((?:\\d+(?:\\.\\d+)?(?:ms|h|m|s))+)", RegexOption.IGNORE_CASE).find(message) ?: return null
        return durationSeconds(m.groupValues[1])
    }
    // "1h2m3.5s" / "7m12.5s" / "850ms" -> seconds
    fun durationSeconds(t: String): Double? {
        var total = 0.0; var any = false
        for (p in Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s)").findAll(t)) {
            val v = p.groupValues[1].toDouble(); any = true
            total += when (p.groupValues[2]) { "h" -> v * 3600; "m" -> v * 60; "ms" -> v / 1000; else -> v }
        }
        return if (any) total else null
    }

    fun markLimited(seconds: Double, reason: String) {
        val until = System.currentTimeMillis() + (seconds * 1000).toLong() + 1000
        if (until > groqUntil) Cfg.setRaw("groq_limited_until", until.toString())
        limitReason = reason
        schedule()
    }

    // after a restart: if a wait was going, the "available again" notice still comes
    fun schedule() {
        val until = limitedUntil
        if (until <= System.currentTimeMillis()) return
        synchronized(this) {
            timer?.cancel()
            timer = java.util.Timer("groq-limit", true).apply {
                schedule(object : java.util.TimerTask() {
                    override fun run() { if (!limited) { limitReason = ""; onAvailable?.invoke() } }
                }, java.util.Date(until + 500))
            }
        }
    }

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
    @Volatile var lastMs = 0L      // the last answer, start to end (Speed page)

    // search = false: no web search (the model's own knowledge; a small fraction of the tokens)
    // The order: questions WITH web search go to Gemini 3.8 Flash (Google Search) -> Groq (browser
    // search) -> Gemini 3.5 Flash-Lite -> Cerebras (no search: model knowledge); questions WITHOUT search
    // go to Cerebras -> Gemini 3.5 Flash-Lite -> Groq -> Gemini 3.8 Flash. A model without a key or
    // resting after its rate limit is skipped; one that fails hands the question to the next.
    suspend fun ask(prompt: String, search: Boolean = true): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val order: List<Any> = if (search) listOf(OtherAi.GEMINI_FLASH, "groq", OtherAi.GROQ2, OtherAi.GEMINI_MID, OtherAi.GEMINI_LITE, OtherAi.CEREBRAS)
            else listOf(OtherAi.CEREBRAS, OtherAi.GEMINI_LITE, OtherAi.GROQ2, OtherAi.GEMINI_MID, "groq", OtherAi.GEMINI_FLASH)
        var last: Exception? = null
        try {
            for (o in order) {
                if (o == "groq") {
                    if (Cfg.groqKey.isBlank() || groqLimited) continue
                    try { val r = askRaw(prompt, search); lastProvider = "Groq"; return@withContext r } catch (e: Exception) { last = e; continue }
                }
                val p = o as OtherAi.Provider
                if (!OtherAi.usable(p)) continue
                try {
                    val r = OtherAi.ask(p, prompt, search)
                    lastSearched = search && p.canSearch && (p !== OtherAi.GROQ2 || p.model().startsWith("openai/gpt-oss"))
                    lastProvider = p.label; lastError = ""
                    return@withContext r
                } catch (e: Exception) { last = e; lastError = e.message ?: "${p.label} failed" }
            }
            if (!anyKey) throw IllegalStateException("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Auto-Pilot-Storage folder")
            throw last ?: IllegalStateException("All online models are resting after their rate limits: available again in ${waitText()} (${availableAtText()})")
        } finally { lastMs = System.currentTimeMillis() - t0 }
    }

    private suspend fun askRaw(prompt: String, wantSearch: Boolean): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val key = Cfg.groqKey.trim()
        if (key.isEmpty()) throw IllegalStateException("No Groq API key (put groq_key.txt in the Sphynx-Auto-Pilot-Storage folder)")
        // still resting after the rate limit: asking now only gets another 429
        if (groqLimited) throw IllegalStateException("Groq rate limit reached: available again at ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(groqUntil))}")
        val model = Cfg.groqModel.trim().ifEmpty { Cfg.DEFAULT_GROQ_MODEL }
        val canSearch = wantSearch && model.startsWith("openai/gpt-oss")
        // a failed search is retried WITHOUT search (a second search would cost as much again)
        val attempts = if (canSearch) listOf(true, false) else listOf(false, false)
        var last: Exception? = null
        for (search in attempts) {
            try {
                val (code, text, retry) = post(key, model, prompt, search)
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
                if (code == 429) {
                    // per minute / per day token or request limit: wait as long as Groq says (1 min if it does not say)
                    val secs = retrySeconds(retry, msg) ?: 60.0
                    val kind = when {
                        msg.contains("per day", true) || msg.contains("TPD") || msg.contains("RPD") -> "daily limit"
                        else -> "per-minute limit"
                    }
                    markLimited(secs, kind)
                    lastError = "Groq rate limit reached ($kind): resting until ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(groqUntil))}"
                }
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

    private fun post(key: String, model: String, prompt: String, search: Boolean): Triple<Int, String, String?> {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        // the gpt-oss models can search the web through Groq's built-in browser search tool
        if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
        // gpt-oss: low reasoning effort = fewer searches and fewer tokens; the answers are short JSON
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")
        body.put("max_completion_tokens", 4096)
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
            c.getHeaderField("x-ratelimit-remaining-tokens")?.trim()?.toLongOrNull()?.let { tokensLeft = it }
            c.getHeaderField("x-ratelimit-remaining-requests")?.trim()?.toLongOrNull()?.let { requestsLeft = it }
            return Triple(code, text, c.getHeaderField("retry-after"))
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
