package com.example.mlbbresearch.ai

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.MetaStore
import com.example.mlbbresearch.game.Role
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Online meta through Groq (openai/gpt-oss-120b with browser search): the latest patch, hero adjustments, builds,
// emblems, combos, laning / jungle / rotation tips and draft picks, from current web sources.
// Results are saved (MetaStore) and used offline by the Player, Builder and Drafter.
object MetaAdvisor {

    @Volatile var busy = ""
        private set
    @Volatile var lastMessage = ""
        private set
    private val tried = HashMap<String, Long>()

    private fun today() = SimpleDateFormat("d MMMM yyyy", Locale.US).format(Date())
    // what the last patch-meta search found, given to the questions asked without web search
    private fun patchContext(): String {
        val g = MetaStore.general() ?: return ""
        return " Current patch (found online): ${g.patch}. Strongest / most banned: ${g.bans.joinToString()}. ${g.notes}".take(700)
    }
    // every question: only the original server's latest released patch
    const val ORIGINAL_ONLY = "Use ONLY the latest patch released on the ORIGINAL (live, global) server. IGNORE the Advanced Server " +
        "(test server): do not use its patch, its hero adjustments, or any change that is not yet released on the original server. "
    // asks, and keeps only an original-server answer (asks once more, more firmly, if it came back with the Advanced Server)
    private suspend fun askOriginal(prompt: String, search: Boolean): Pair<org.json.JSONObject, List<String>> {
        for (attempt in 0..2) {
            val p = when (attempt) {
                0 -> prompt
                1 -> "IMPORTANT: your last answer used the Advanced Server patch. Give the ORIGINAL server's current live patch instead. " + prompt
                // (a hero being revamped on the Advanced Server, e.g. Aulus: the live, pre-revamp kit and its guide)
                else -> "IMPORTANT: this hero may be getting a revamp that is ONLY on the Advanced Server. Ignore that revamp completely: " +
                    "give the guide for the hero as it is RIGHT NOW on the original live server (its current, pre-revamp kit), and write " +
                    "the original server's live patch number in \"patch\" (never the word Advanced). " + prompt
            }
            val (text, sources) = GroqClient.ask(p, search)
            val o = GroqClient.json(text) ?: throw IllegalStateException("answer was not JSON: ${text.take(100)}")
            if (MetaStore.keepOriginalServerOnly(o)) return o to sources.filterNot { MetaStore.isAdvanced(it) }
            Diag.info("Groq answered with the Advanced Server patch (${o.optString("patch")}): not used")
        }
        throw IllegalStateException("Groq only found the Advanced Server patch: not saved (only the original server's patch is used)")
    }

    private fun searchHint(search: Boolean) = if (search) "Do at most ONE short web search. " else
        "Answer from your own knowledge (no web search); prefer what fits the current patch below.${patchContext()} "

    // ---------------- one hero ----------------

    fun updateHero(hero: String, done: ((String) -> Unit)? = null) {
        if (busy.isNotEmpty()) { done?.invoke("Busy: $busy"); return }
        busy = "meta for $hero"
        tried[hero] = System.currentTimeMillis()
        AiRuntime.scope.launch {
            val msg = try {
                learnHeroNow(hero)
            } catch (e: Exception) {
                Diag.error("Meta update for $hero failed", e)
                "Meta update failed: ${e.message}"
            } finally {
                busy = ""
            }
            lastMessage = msg
            done?.invoke(msg)
        }
    }

    // one hero's online guide (build, emblem, combo, how to play...): saved; returns a short line
    suspend fun learnHeroNow(hero: String): String {
                val search = Cfg.heroWebSearch
                val prompt = "You are a Mobile Legends: Bang Bang expert. Today is ${today()}. Give the current meta guide for the hero $hero " +
                    "(build, emblem, combo, how to play). $ORIGINAL_ONLY${searchHint(search)}Reply with ONLY one JSON object, no other words:\n" +
                    "{\"patch\":\"current ORIGINAL-server patch version and date\",\"build\":[\"the 6 items in buying order\"]," +
                    "\"emblem\":\"emblem set and talents\",\"spell\":\"best battle spell\"," +
                    "\"lane\":\"exp|gold|mid|roam|jungle - its primary (most played) lane\",\"lane2\":\"exp|gold|mid|roam|jungle|none - its secondary lane\"," +
                    "\"flex\":true or false - true if it is a flex pick that can be played in almost any lane,\"combo\":\"skill order using s1, s2, ult and a (basic attack), e.g. s2 > s1 > a > ult\"," +
                    "\"dash\":\"s1|s2|ult|none - the skill that dashes, leaps or blinks\"," +
                    "\"skill_order\":\"skill upgrade priority from the guides, e.g. ult > s1 > s2\"," +
                    "\"playstyle\":\"how to play $hero effectively in 1-2 short sentences (its job in fights, positioning)\"," +
                    "\"skill_use\":{\"s1\":\"when and how to use skill 1, one short sentence\",\"s2\":\"same for skill 2\",\"ult\":\"same for the ultimate\"}," +
                    "\"power_spike\":\"when it is strongest (level / items), a few words\"," +
                    "\"laning\":[\"3 short early-game laning / farming tips\"],\"jungle\":[\"3 short jungle clear route tips\"]," +
                    "\"rotation\":[\"3 short rotation and objective timing tips (turtle, lord, towers)\"]," +
                    "\"counters\":[\"heroes strong against $hero\"],\"recent_changes\":\"its buffs / nerfs in recent patches, one sentence\"}"
                val (o, sources) = askOriginal(prompt, search)
                MetaStore.saveHero(hero, o, if (GroqClient.lastSearched) sources else listOf("model knowledge, no web search"))
                val m = MetaStore.hero(hero)
                Diag.info("Online meta for $hero updated (${m?.patch})")
                return "Updated $hero (${m?.patch ?: "?"}): ${m?.build?.size ?: 0} items, combo ${m?.combo ?: "-"}"
    }

    // Called by the runtime: refresh the hero you are playing when its meta is old (Auto update)
    fun autoUpdate(hero: String?) {
        if (hero == null || !Cfg.autoOnline || !Cfg.onlineAuto || !GroqClient.ready || busy.isNotEmpty()) return
        val m = MetaStore.hero(hero)
        if (m != null && MetaStore.ageDays(m.updatedAt) < Cfg.metaMaxAgeDays) return
        if (System.currentTimeMillis() - (tried[hero] ?: 0L) < 15 * 60_000L) return
        updateHero(hero)
    }

    // ---------------- the whole meta ----------------

    // the patch meta refreshes by itself when it is older than a week (no button needed)
    private var generalTried = 0L
    fun autoGeneral() {
        if (!Cfg.autoOnline || !Cfg.onlineAuto || !GroqClient.ready || busy.isNotEmpty()) return
        val g = MetaStore.general()
        if (g != null && MetaStore.ageDays(g.updatedAt) < Cfg.metaMaxAgeDays) return
        if (System.currentTimeMillis() - generalTried < 60 * 60_000L) return
        generalTried = System.currentTimeMillis()
        updateGeneral()
    }

    fun updateGeneral(done: ((String) -> Unit)? = null) {
        if (busy.isNotEmpty()) { done?.invoke("Busy: $busy"); return }
        busy = "general meta"
        AiRuntime.scope.launch {
            val msg = try {
                val prompt = "You are a Mobile Legends: Bang Bang expert. Today is ${today()}. Search the web for the LATEST " +
                    "Mobile Legends patch notes and the current ranked meta (tier lists, pick and ban rates, hero adjustments). " +
                    "Do at most TWO short searches. $ORIGINAL_ONLY" +
                    "Reply with ONLY one JSON object, no other words:\n" +
                    "{\"patch\":\"current ORIGINAL-server patch version and date\",\"bans\":[\"8 most banned / strongest heroes\"]," +
                    "\"picks\":{\"tank\":[\"5 heroes\"],\"fighter\":[\"5\"],\"assassin\":[\"5\"],\"mage\":[\"5\"],\"marksman\":[\"5\"],\"support\":[\"5\"]}," +
                    "\"notes\":\"the main changes of this patch and what they mean for the meta, max 3 sentences\"}"
                val (o, sources) = askOriginal(prompt, true)
                MetaStore.saveGeneral(o, if (GroqClient.lastSearched) sources else listOf("model knowledge, no web search"))
                val g = MetaStore.general()
                "Meta updated: ${g?.patch ?: "?"}, ${g?.bans?.size ?: 0} top bans"
            } catch (e: Exception) {
                Diag.error("General meta update failed", e)
                "Meta update failed: ${e.message}"
            } finally {
                busy = ""
            }
            lastMessage = msg
            done?.invoke(msg)
        }
    }

    // ---------------- draft ----------------

    class Draft(val ban: List<String>, val pick: List<String>, val reason: String)

    // Ranked: bans and picks for my team from the current meta. Called from the Drafter.
    suspend fun draft(stage: String, myTeam: List<String>, enemyTeam: List<String>, solo: Boolean): Draft? {
        if (!GroqClient.ready || !Cfg.onlineDraft) return null
        val missing = Role.values().filter { r -> r != Role.UNKNOWN && myTeam.none { HeroDb.role(it) == r } }
            .joinToString { it.name.lowercase() }
        val prompt = "You are a Mobile Legends: Bang Bang ranked drafting expert. Today is ${today()}. " +
            "$ORIGINAL_ONLY${searchHint(false)}Draft stage: $stage. " +
            "My team so far: ${myTeam.ifEmpty { listOf("none") }.joinToString()}. Enemy team so far: ${enemyTeam.ifEmpty { listOf("none") }.joinToString()}. " +
            "Roles my team still needs: $missing. ${if (solo) "I play solo queue: prefer heroes that can carry alone." else "I play with a team."} " +
            "Reply with ONLY one JSON object, no other words: {\"ban\":[\"3 heroes to ban now\"],\"pick\":[\"3 best picks for me now, " +
            "countering the enemy and filling my team\"],\"reason\":\"one short sentence\"}"
        return try {
            busy = "draft"
            val (text, _) = GroqClient.ask(prompt, false)
            val o = GroqClient.json(text) ?: return null
            fun heroes(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { HeroDb.match(a.optString(it)) } } ?: emptyList()
            Draft(heroes("ban"), heroes("pick"), o.optString("reason", ""))
        } catch (e: Exception) {
            Diag.error("Online draft failed", e)
            null
        } finally {
            busy = ""
        }
    }
}
