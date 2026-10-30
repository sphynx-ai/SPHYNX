package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import org.json.JSONArray
import org.json.JSONObject

// What the online meta update (Gemini + web search) found, saved on the phone so the
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
    val sources: List<String>
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
            val dash = o.optString("dash", "").lowercase().trim().takeIf { it in setOf("s1", "s2", "ult", "none") }
            val combo = o.optString("combo", "").takeIf { ComboBook.parse(it).size >= 2 }
            HeroMeta(hero, o.optString("patch", "?"), o.optLong("at"), list(o, "build"), o.optString("emblem", ""),
                SpellDb.match(o.optString("spell", "")), Lane.of(o.optString("lane", "").lowercase().trim()),
                combo, dash, list(o, "laning"), list(o, "jungle"), list(o, "rotation"),
                list(o, "counters").mapNotNull { HeroDb.match(it) }, o.optString("recent_changes", ""), list(o, "sources"))
        } catch (e: Exception) {
            null
        }
    }

    fun saveHero(hero: String, o: JSONObject, sources: List<String>) {
        o.put("at", System.currentTimeMillis())
        o.put("sources", JSONArray(sources.take(6)))
        Cfg.setRaw(key(hero), o.toString())
    }

    fun general(): GeneralMeta? {
        if (!Cfg.useOnlineMeta) return null
        val txt = Cfg.raw("meta_general") ?: return null
        return try {
            val o = JSONObject(txt)
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
    }

    fun clear() {
        for (k in listOf("meta_general")) Cfg.setRaw(k, null)
        for (h in HeroDb.allNames) Cfg.setRaw(key(h), null)
        synchronized(this) { cache.clear() }
    }

    fun ageDays(at: Long): Float = (System.currentTimeMillis() - at) / 86_400_000f
}
