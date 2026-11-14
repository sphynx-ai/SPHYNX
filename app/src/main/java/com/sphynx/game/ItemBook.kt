package com.sphynx.game

import com.sphynx.ai.GroqClient
import com.sphynx.core.Cfg
import org.json.JSONArray
import org.json.JSONObject

// THE EQUIPMENT the app knows: every current item (ItemDb) with what it does - type, price, stats
// and its passive / active effect - learned online (Groq, 12 items per question, no web search)
// and kept on the phone and in mlbb-online-meta.json (survives a reinstall).
object ItemBook {

    class Item(val name: String, val type: String, val price: Int, val stats: String, val effect: String)

    fun key(item: String) = "item_" + item.lowercase().filter { it.isLetterOrDigit() }
    fun known(item: String) = Cfg.raw(key(item)) != null

    fun get(item: String): Item? = Cfg.raw(key(item))?.let {
        try {
            val o = JSONObject(it)
            Item(item, o.optString("type"), o.optInt("price", -1), o.optString("stats"), o.optString("effect"))
        } catch (e: Exception) { null }
    }

    // a batch of items in one question; returns how many were learned
    suspend fun learnBatch(items: List<String>): Int {
        val prompt = "You are a Mobile Legends: Bang Bang expert. For each of these CURRENT equipment items (original server, " +
            "latest patch), give its type, gold price, stats and its passive / active effect. Items: ${items.joinToString("; ")}. " +
            "Reply with ONLY one JSON object, no other words: {\"items\":[{\"name\":\"\",\"type\":\"attack|magic|defense|movement|" +
            "jungle|roam\",\"price\":0,\"stats\":\"e.g. +60 Physical Attack, +10% Crit\",\"effect\":\"the passive / active in one sentence\"}]}"
        val (text, _) = GroqClient.ask(prompt, false)
        val o = GroqClient.json(text) ?: return 0
        val a = o.optJSONArray("items") ?: JSONArray()
        var n = 0
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            val name = ItemDb.canonical(e.optString("name")) ?: continue
            Cfg.setRaw(key(name), JSONObject().put("type", e.optString("type")).put("price", e.optInt("price", -1))
                .put("stats", e.optString("stats")).put("effect", e.optString("effect")).put("at", System.currentTimeMillis()).toString())
            n++
        }
        if (n > 0) com.sphynx.learning.OnlineBackup.changed()
        return n
    }
}
