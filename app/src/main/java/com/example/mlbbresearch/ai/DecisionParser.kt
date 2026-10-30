package com.example.mlbbresearch.ai

import com.example.mlbbresearch.game.HeroDb
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.game.SpellDb
import org.json.JSONArray
import org.json.JSONObject

// Gemma's strategic plan. The tactical engine reads it on every tick.
class StrategyPlan(
    val plan: String,          // farm | push | fight | retreat | group | defend
    val aggression: Float,     // 0 careful .. 1 aggressive
    val focus: String,         // weakest | nearest
    val note: String,
    val at: Long
) {
    companion object {
        val DEFAULT = StrategyPlan("farm", 0.5f, "weakest", "default plan (no Gemma yet)", 0L)
    }
}

class DraftAdvice(
    val screen: Phase,
    val stage: String,             // ban | pick | waiting | none
    val myTurn: Boolean,
    val myTeam: List<String>,
    val enemyTeam: List<String>,
    val myHero: String?,
    val ban: List<String>,
    val pick: List<String>,
    val tapX: Float,               // -1 when Gemma gave no position
    val tapY: Float,
    val reason: String
)

class ScreenRead(val items: Int, val gold: Int, val hero: String?, val spell: String?)

// Reads Gemma's replies. Gemma is asked for JSON but small models sometimes add
// extra words, so this finds the {...} part and is forgiving about the rest.
object DecisionParser {

    private fun json(text: String): JSONObject? {
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(text.substring(a, b + 1)) } catch (e: Exception) { null }
    }

    private fun list(o: JSONObject, key: String): List<String> {
        val arr: JSONArray = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            val s = if (v is JSONObject) v.optString("hero", v.optString("name", "")) else v?.toString() ?: ""
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    private fun heroes(o: JSONObject, key: String): List<String> =
        list(o, key).mapNotNull { HeroDb.match(it) }.distinct()

    fun screen(s: String?): Phase = when (s?.lowercase()?.trim()) {
        "draft", "ban", "pick", "hero select", "selection" -> Phase.DRAFT
        "loading" -> Phase.LOADING
        "lobby", "menu", "home" -> Phase.LOBBY
        "game", "in game", "ingame", "battle" -> Phase.IN_GAME
        "shop", "equipment" -> Phase.SHOP
        "map", "minimap", "big map" -> Phase.MAP
        "scoreboard", "score" -> Phase.SCOREBOARD
        "result", "results", "victory", "defeat", "end" -> Phase.RESULT
        else -> Phase.UNKNOWN
    }

    fun strategy(text: String): StrategyPlan? {
        val o = json(text) ?: return null
        val plan = o.optString("plan", "farm").lowercase().trim()
        val allowed = setOf("farm", "push", "fight", "retreat", "group", "defend")
        val focus = o.optString("focus", "weakest").lowercase().trim()
        return StrategyPlan(
            plan = if (plan in allowed) plan else "farm",
            aggression = o.optDouble("aggression", 0.5).toFloat().coerceIn(0f, 1f),
            focus = if (focus == "nearest") "nearest" else "weakest",
            note = o.optString("note", "").take(80),
            at = System.currentTimeMillis()
        )
    }

    fun draft(text: String): DraftAdvice? {
        val o = json(text) ?: return null
        val tap = o.optJSONObject("tap")
        val tx = tap?.optDouble("x", -1.0)?.toFloat() ?: -1f
        val ty = tap?.optDouble("y", -1.0)?.toFloat() ?: -1f
        return DraftAdvice(
            screen = screen(o.optString("screen", "")),
            stage = o.optString("stage", "none").lowercase(),
            myTurn = o.optBoolean("my_turn", false),
            myTeam = heroes(o, "my_team"),
            enemyTeam = heroes(o, "enemy_team"),
            myHero = HeroDb.match(o.optString("my_hero", "")),
            ban = heroes(o, "ban").take(3),
            pick = heroes(o, "pick").take(3),
            tapX = pct(tx, ty),
            tapY = pct(ty, tx),
            reason = o.optString("reason", "").take(200)
        )
    }

    // Gemma may answer 0-100 (percent) or 0-1 (fraction); both become a fraction
    private fun pct(v: Float, other: Float): Float = when {
        v < 0f || v > 100f -> -1f
        v <= 1f && other <= 1f -> v
        else -> v / 100f
    }

    fun screenRead(text: String): ScreenRead? {
        val o = json(text) ?: return null
        fun num(k: String): Int {
            val v = o.opt(k) ?: return -1
            return v.toString().replace(",", "").trim().toIntOrNull() ?: -1
        }
        return ScreenRead(num("items").coerceIn(-1, 6), num("gold"), HeroDb.match(o.optString("hero", "")),
            SpellDb.match(o.optString("spell", "")))
    }
}
