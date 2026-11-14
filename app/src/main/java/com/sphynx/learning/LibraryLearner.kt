package com.sphynx.learning

import com.sphynx.ai.AiRuntime
import com.sphynx.ai.GroqClient
import com.sphynx.ai.MetaAdvisor
import com.sphynx.core.Diag
import com.sphynx.game.HeroDb
import com.sphynx.game.ItemBook
import com.sphynx.game.ItemDb
import com.sphynx.game.MetaStore
import com.sphynx.game.SkillBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// LEARN THE WHOLE DATABASE ONLINE (Library page): every hero's skills and online guide (build,
// emblem, combo, how to play), then every equipment item - one question at a time, skipping what is
// already known, waiting out Groq's rate limit (the free tier allows roughly 200,000 tokens a day:
// all of it takes a few days of the app being open; it carries on by itself after a wait).
object LibraryLearner {

    @Volatile var running = false
        private set
    @Volatile var progress = "-"
        private set

    fun start(done: (String) -> Unit) {
        if (running) { done("Already learning: $progress"); return }
        if (!GroqClient.anyKey) { done("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Storage/Input folder"); return }
        running = true
        AiRuntime.scope.launch {
            var learned = 0
            try {
                val heroes = HeroDb.allNames
                for ((i, h) in heroes.withIndex()) {
                    if (!running) break
                    // the real skill pictures and the kit from the wiki page (no tokens)
                    if (!com.sphynx.game.WikiSkills.iconsKnown(h)) {
                        progress = "${i + 1}/${heroes.size}: $h - skill pictures"
                        try { com.sphynx.game.WikiSkills.fetchIcons(h) } catch (e: Exception) { }
                    }
                    if (!SkillBook.known(h)) {
                        waitForGroq("${i + 1}/${heroes.size} $h skills")
                        if (!running) break
                        progress = "${i + 1}/${heroes.size}: $h - skills"
                        try { SkillBook.fetchNow(h); learned++; failed(h, "skills", null) } catch (e: Exception) { Diag.error("Library: skills of $h", e); failed(h, "skills", e.message) }
                        delay(4000)
                    }
                    if (!MetaStore.has(h)) {
                        waitForGroq("${i + 1}/${heroes.size} $h guide")
                        if (!running) break
                        progress = "${i + 1}/${heroes.size}: $h - online guide"
                        try { MetaAdvisor.learnHeroNow(h); learned++; failed(h, "guide", null) } catch (e: Exception) { Diag.error("Library: guide of $h", e); failed(h, "guide", e.message) }
                        delay(4000)
                    }
                }
                // the equipment's wiki pages (tier, stats - no tokens) and the Blessed Retributions' wiki text
                val ic = com.sphynx.game.ItemClass
                for ((i, it0) in ItemDb.current.filter { !ic.isBlessing(it) && !ic.wikiKnown(it) }.withIndex()) {
                    if (!running) break
                    progress = "equipment wiki pages: ${i + 1} ($it0)"
                    try { ic.fetchWiki(it0) } catch (e: Exception) { }
                }
                for (r in ItemDb.retributions) if (running && ic.retributionText(r) == null) {
                    progress = "wiki: $r"
                    try { ic.fetchRetribution(r) } catch (e: Exception) { }
                }
                val todo = ItemDb.learnable.filter { !ItemBook.known(it) }
                for ((i, batch) in todo.chunked(12).withIndex()) {
                    if (!running) break
                    waitForGroq("equipment ${i + 1}")
                    if (!running) break
                    progress = "equipment: batch ${i + 1} of ${(todo.size + 11) / 12}"
                    try { learned += ItemBook.learnBatch(batch) } catch (e: Exception) { Diag.error("Library: equipment", e) }
                    delay(4000)
                }
            } finally {
                progress = (if (running) "done" else "stopped") + ": $learned learned this time · " + counts()
                running = false
                done(progress)
            }
        }
    }

    fun stop() { running = false }

    // why a hero's skills / guide could not be learned (shown in the Library)
    private fun errKey(h: String, what: String) = "learn_err_${what}_" + h.lowercase().filter { it.isLetterOrDigit() }
    private fun failed(h: String, what: String, msg: String?) = com.sphynx.core.Cfg.setRaw(errKey(h, what), msg?.take(160))
    fun lastError(h: String, what: String): String? = com.sphynx.core.Cfg.raw(errKey(h, what))

    // one hero now (Library: long-press a hero): skills from its wiki page, then its guide
    @Volatile var oneBusy = ""; private set
    fun learnOne(h: String, done: (String) -> Unit) {
        if (oneBusy.isNotEmpty()) { done("Already learning $oneBusy"); return }
        if (!GroqClient.anyKey) { done("No online model key: put groq_key.txt, gemini_key.txt or cerebras_key.txt in the Sphynx-Storage/Input folder"); return }
        oneBusy = h
        AiRuntime.scope.launch {
            val out = StringBuilder("$h - ")
            try {
                try { com.sphynx.game.WikiSkills.fetchIcons(h) } catch (e: Exception) { }
                com.sphynx.game.HeroKit.forgetCache()
                try { SkillBook.fetchNow(h); failed(h, "skills", null); out.append("skills: learned") }
                catch (e: Exception) { failed(h, "skills", e.message); out.append("skills: FAILED (${e.message?.take(120)})") }
                try { MetaAdvisor.learnHeroNow(h); failed(h, "guide", null); out.append(" · guide: learned") }
                catch (e: Exception) { failed(h, "guide", e.message); out.append(" · guide: FAILED (${e.message?.take(120)})") }
            } finally { oneBusy = "" }
            done(out.toString())
        }
    }

    private suspend fun waitForGroq(what: String) {
        while (running && GroqClient.limited) {
            progress = "$what: waiting for Groq (${GroqClient.waitText()})"
            delay(5000)
        }
    }

    fun counts(): String {
        val h = HeroDb.allNames
        return "skills ${h.count { SkillBook.known(it) }}/${h.size}, guides ${h.count { MetaStore.has(it) }}/${h.size}, " +
            "equipment ${ItemDb.learnable.count { ItemBook.known(it) }}/${ItemDb.learnable.size}"
    }
}
