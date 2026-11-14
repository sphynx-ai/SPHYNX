package com.sphynx.ai

import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.game.ComboBook
import com.sphynx.game.ComboParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Gets a hero's skill combo from the internet (only when you tap the button: the rest of
// the app stays offline). It downloads a guide page, looks for a written combo
// ("S1 > S2 > Ult"), and if there is none, asks Gemma to work one out from the page
// (for example from the skill descriptions on a wiki page). The result is saved as YOUR combo.
object ComboFetcher {

    @Volatile var busy = false
        private set

    // url: a page address, or empty to use the template from the Player page ({hero} = hero name)
    fun fetch(hero: String, url: String, done: (String) -> Unit) {
        if (busy) { done("Already downloading"); return }
        busy = true
        AiRuntime.scope.launch {
            val msg = try {
                val address = url.ifBlank {
                    Cfg.comboUrl.replace("{hero}", URLEncoder.encode(hero.replace(' ', '_'), "UTF-8"))
                }
                val text = withContext(Dispatchers.IO) { ComboParser.htmlToText(download(address)) }
                fromText(hero, text, "downloaded from $address")
            } catch (e: Exception) {
                Diag.error("Combo download failed", e)
                "Download failed: ${e.message ?: e.toString()}"
            } finally {
                busy = false
            }
            done(msg)
        }
    }

    // pasted text works the same way
    fun fromPaste(hero: String, text: String, done: (String) -> Unit) {
        AiRuntime.scope.launch { done(fromText(hero, text, "from your text")) }
    }

    private suspend fun fromText(hero: String, text: String, where: String): String {
        ComboParser.find(text)?.let { combo ->
            Cfg.setHeroCombo(hero, combo)
            Diag.info("Combo for $hero $where: $combo")
            return "Saved $hero combo: $combo ($where)"
        }
        if (!GemmaEngine.ready) return "No written combo found ($where). Load Gemma to let it work one out, or type it."
        // keep the part about skills, small enough for the model
        val low = text.lowercase()
        val at = listOf("combo", "skill 1", "skills", "abilities").map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val part = text.substring(at, minOf(text.length, at + 3500))
        val prompt = "Mobile Legends hero $hero. Here is text about the hero's skills:\n\"\"\"$part\"\"\"\n" +
            "Write the best fighting combo using s1, s2, ult and a (basic attack). " +
            "Reply with ONLY one JSON object, no other words: {\"combo\":\"s1 > s2 > a > ult\"}"
        val reply = try { GemmaEngine.ask(prompt, null, 0.2, purpose = "combo") } catch (e: Exception) { return "Gemma failed: ${e.message}" }
        val combo = Regex("\"combo\"\\s*:\\s*\"([^\"]+)\"").find(reply)?.groupValues?.get(1) ?: ComboParser.find(reply)
        val steps = if (combo != null) ComboBook.parse(combo) else emptyList()
        if (steps.size < 2) return "Could not get a combo ($where). Gemma said: ${reply.take(120)}"
        val clean = ComboBook.format(steps)
        Cfg.setHeroCombo(hero, clean)
        Diag.info("Combo for $hero by Gemma $where: $clean")
        return "Saved $hero combo: $clean (worked out by Gemma, $where)"
    }

    private fun download(address: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MLBBResearch")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("server answered ${c.responseCode}")
            val bytes = c.inputStream.use { it.readNBytesCompat(3_000_000) }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
