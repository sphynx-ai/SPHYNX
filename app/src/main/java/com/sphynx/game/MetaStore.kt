package com.sphynx.game

import com.sphynx.core.Cfg
import org.json.JSONArray
import org.json.JSONObject

// What the online meta update (Groq + web search) found, saved on the phone so the
// Player, Builder and Drafter can use it offline.
class HeroMeta(
    val hero: String,
    val patch: String,
    val updatedAt: Long,
    val build: List<String>,
    val emblem: String,
    val spell: String?,
    val lane: Lane?,
    val combo: String?,
    val dash: String?,
    val laning: List<String>,
    val jungle: List<String>,
    val rotation: List<String>,
    val counters: List<String>,
    val changes: String,
    val sources: List<String>,
    val skillOrder: List<String> = emptyList(),   // upgrade priority from the guides (s1 / s2 / ult)
    val playstyle: String = "",                    // how to play it effectively, in a sentence or two
    val skillUse: Map<String, String> = emptyMap(),  // s1 / s2 / ult -> when and how to use it
    val powerSpike: String = "",
    val lane2: Lane? = null,                       // the second recommended lane (HeroLanes)
    val flex: Boolean? = null                      // a flex pick (any lane); null = the guide did not say
)

class GeneralMeta(
    val patch: String,
    val updatedAt: Long,
    val bans: List<String>,
    val picks: Map<Role, List<String>>,
    val notes: String,
    val sources: List<String>
)

object MetaStore {

    private fun key(hero: String) = "meta_hero_" + hero.lowercase().filter { it.isLetterOrDigit() }
    // an online guide is saved for this hero (whether or not the online meta is switched on)
    fun has(hero: String) = Cfg.raw(key(hero)) != null

    private fun list(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    // parsed once per saved answer (the Player asks every frame)
    private val cache = HashMap<String, Pair<String, HeroMeta?>>()

    @Synchronized
    fun hero(hero: String?): HeroMeta? {
        if (hero == null || !Cfg.useOnlineMeta) return null
        val txt = Cfg.raw(key(hero)) ?: return null
        cache[hero]?.let { if (it.first == txt) return it.second }
        val m = parseHero(hero, txt)
        cache[hero] = txt to m
        return m
    }

    private fun parseHero(hero: String, txt: String): HeroMeta? {
        return try {
            val o = JSONObject(txt)
            if (isAdvanced(o.optString("patch", ""))) return null
            val dash = o.optString("dash", "").lowercase().trim().takeIf { it in setOf("s1", "s2", "ult", "none") }
            // Online combo updates are disabled (v37.12): pure lookup of Manual Input or Built-In burst (no recursion)
            val combo = Cfg.manualCombo(hero) ?: ComboBook.builtInBurst(hero)
            HeroMeta(hero, o.optString("patch", "?"), o.optLong("at"), ItemClass.advancedOnly(list(o, "build")), o.optString("emblem", ""),
                SpellDb.match(o.optString("spell", "")), Lane.of(o.optString("lane", "").lowercase().trim()),
                combo, dash, list(o, "laning"), list(o, "jungle"), list(o, "rotation"),
                list(o, "counters").mapNotNull { HeroDb.match(it) }, o.optString("recent_changes", ""), list(o, "sources"),
                skillOrder = Regex("ult|s1|s2").findAll(o.optString("skill_order", "").lowercase().replace("skill 1", "s1").replace("skill 2", "s2")
                    .replace("ultimate", "ult")).map { it.value }.distinct().toList(),
                playstyle = o.optString("playstyle", "").trim(),
                skillUse = o.optJSONObject("skill_use")?.let { j -> listOf("s1", "s2", "ult").mapNotNull { k -> j.optString(k, "").trim().takeIf { it.isNotEmpty() }?.let { k to it } }.toMap() } ?: emptyMap(),
                powerSpike = o.optString("power_spike", "").trim(),
                lane2 = Lane.of(o.optString("lane2", "").lowercase().trim()),
                flex = if (o.has("flex")) o.optBoolean("flex") else null)
        } catch (e: Exception) {
            null
        }
    }

    fun saveHero(hero: String, o: JSONObject, sources: List<String>) {
        o.put("at", System.currentTimeMillis())
        o.put("sources", JSONArray(sources.take(6)))
        Cfg.setRaw(key(hero), o.toString())
        com.sphynx.learning.OnlineBackup.changed()      // also kept in the folder
    }

    fun general(): GeneralMeta? {
        if (!Cfg.useOnlineMeta) return null
        val txt = Cfg.raw("meta_general") ?: return null
        return try {
            val o = JSONObject(txt)
            if (isAdvanced(o.optString("patch", ""))) return null       // saved before: Advanced Server, not used
            val picks = HashMap<Role, List<String>>()
            val p = o.optJSONObject("picks")
            if (p != null) for (r in Role.values()) {
                val a = p.optJSONArray(r.name.lowercase()) ?: continue
                picks[r] = (0 until a.length()).mapNotNull { HeroDb.match(a.optString(it)) }
            }
            GeneralMeta(o.optString("patch", "?"), o.optLong("at"), list(o, "bans").mapNotNull { HeroDb.match(it) },
                picks, o.optString("notes", ""), list(o, "sources"))
        } catch (e: Exception) {
            null
        }
    }

    fun saveGeneral(o: JSONObject, sources: List<String>) {
        o.put("at", System.currentTimeMillis())
        o.put("sources", JSONArray(sources.take(8)))
        Cfg.setRaw("meta_general", o.toString())
        com.sphynx.learning.OnlineBackup.changed()      // also kept in the folder
    }

    fun clear() {
        for (k in listOf("meta_general")) Cfg.setRaw(k, null)
        for (h in HeroDb.allNames) Cfg.setRaw(key(h), null)
        synchronized(this) { cache.clear() }
    }

    // ---- original server only: the Advanced Server (test server) patch is never used ----
    // its changes are not live yet (and are often changed again before release)
    private val ADVANCED = Regex("\\b(advanced?|adv\\.?)\\s*(server|patch|srv)\\b|\\btest\\s*server\\b|\\bpublic\\s*test\\b|\\bnot\\s*yet\\s*(live|released)\\b",
        RegexOption.IGNORE_CASE)
    fun isAdvanced(text: String?): Boolean = text != null && ADVANCED.containsMatchIn(text)

    // Cleans an answer in place: sentences / list items / sources about the Advanced Server are removed.
    // Returns false when the answer's patch itself is an Advanced Server patch (then it is not used at all).
    fun keepOriginalServerOnly(o: JSONObject): Boolean {
        if (isAdvanced(o.optString("patch", ""))) return false
        for (k in o.keys().asSequence().toList()) {
            when (val v = o.opt(k)) {
                is String -> if (isAdvanced(v)) o.put(k, v.split(Regex("(?<=[.!?;])\\s+")).filterNot { isAdvanced(it) }.joinToString(" "))
                is JSONArray -> {
                    val kept = JSONArray()
                    for (i in 0 until v.length()) { val x = v.opt(i); if (!(x is String && isAdvanced(x))) kept.put(x) }
                    o.put(k, kept)
                }
                is JSONObject -> keepOriginalServerOnly(v)
            }
        }
        return true
    }

    fun ageDays(at: Long): Float = (System.currentTimeMillis() - at) / 86_400_000f
}
