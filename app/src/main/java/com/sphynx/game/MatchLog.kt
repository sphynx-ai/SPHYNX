package com.sphynx.game

import com.sphynx.core.Cfg
import org.json.JSONArray
import org.json.JSONObject

// EVALUATION across heroes and roles: one line per match (the last 200) - hero, role, who played
// (the AI or you), kills / deaths / assists, level reached, length. The Learn page sums them up per
// hero and per role, so you can see where the AI plays well and where it needs work.
// Win / loss comes from the match end screens (gold VICTORY letters = win).
object MatchLog {

    fun save(hero: String?, lane: Lane, k: Int, d: Int, a: Int, level: Int, seconds: Int, aiPlayed: Boolean, win: Boolean? = null) {
        if (seconds < 120) return                 // not a real match (a few seconds of testing)
        val arr = try { JSONArray(Cfg.raw("match_log") ?: "[]") } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("hero", hero ?: "unknown").put("lane", lane.name)
            .put("k", k).put("d", d).put("a", a).put("lvl", level).put("sec", seconds).put("ai", aiPlayed).apply { if (win != null) put("win", win) })
        val keep = JSONArray()
        for (i in maxOf(0, arr.length() - 200) until arr.length()) keep.put(arr.get(i))
        Cfg.setRaw("match_log", keep.toString())
    }

    // the end scoreboard locked the hero of the match just logged (v36.90)
    fun relabelLast(hero: String) {
        val arr = try { JSONArray(Cfg.raw("match_log") ?: "[]") } catch (e: Exception) { return }
        if (arr.length() == 0) return
        val o = arr.getJSONObject(arr.length() - 1)
        if (System.currentTimeMillis() - o.optLong("t") > 10 * 60_000) return
        o.put("hero", hero); Cfg.setRaw("match_log", arr.toString())
    }

    private class Row(val hero: String, val lane: String, val k: Int, val d: Int, val a: Int, val lvl: Int, val sec: Int, val ai: Boolean, val win: Boolean?)

    private fun rows(): List<Row> {
        val arr = try { JSONArray(Cfg.raw("match_log") ?: "[]") } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Row(it.optString("hero"), it.optString("lane"), it.optInt("k"), it.optInt("d"), it.optInt("a"), it.optInt("lvl"), it.optInt("sec"), it.optBoolean("ai"),
                if (it.has("win")) it.optBoolean("win") else null)
        }
    }

    // "Chou (EXP), AI: 4 games, KDA 3.1 (2.5/1.6/2.4), level 11, 14 min"
    fun summary(): String {
        val r = rows()
        if (r.isEmpty()) return "No matches recorded yet (a match counts after 2 minutes of play)."
        fun line(name: String, g: List<Row>): String {
            val k = g.map { it.k }.average(); val d = g.map { it.d }.average(); val a = g.map { it.a }.average()
            val kda = (k + a) / maxOf(1.0, d)
            val known = g.filter { it.win != null }
            val wins = if (known.isEmpty()) "" else ", won %d of %d (%d%%)".format(known.count { it.win == true }, known.size,
                known.count { it.win == true } * 100 / known.size)
            return "%s: %d games%s, KDA %.1f (%.1f/%.1f/%.1f), level %.0f, %.0f min".format(name, g.size, wins, kda, k, d, a,
                g.map { it.lvl }.average(), g.map { it.sec }.average() / 60)
        }
        val sb = StringBuilder()
        sb.append("BY ROLE\n")
        r.groupBy { it.lane to it.ai }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (k, g) ->
            sb.append("  ").append(line("${k.first} (${if (k.second) "AI" else "you"})", g)).append('\n')
        }
        sb.append("BY HERO\n")
        r.groupBy { it.hero to it.ai }.entries.sortedByDescending { it.value.size }.take(15).forEach { (k, g) ->
            sb.append("  ").append(line("${k.first} (${if (k.second) "AI" else "you"})", g)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun clear() = Cfg.setRaw("match_log", null)
}
