package com.sphynx.adapters.mlbb

import org.json.JSONObject

/**
 * PHASE 13, step D1 (roadmap 13.1, 13.11): MLBB's emblem knowledge, **only from the Mobile Legends Wiki Emblems page**
 * (assets/mlbb/emblems/emblems.json: the owner's capture of the page, icons fetched from the Wiki's image server at the
 * addresses the capture names). Wiki facts are kept apart from the app's presentation. When the Wiki gives no
 * recommendation, [recommendationFor] says so and invents nothing. Pure (org.json), unit-tested.
 */
object MlbbEmblems {

    const val ASSET_DIR = "mlbb/emblems"
    const val ASSET = "$ASSET_DIR/emblems.json"
    const val NO_RECOMMENDATION = "No Wiki-supported recommendation: the Wiki Emblems page does not recommend a set or talents per hero."

    data class Talent(val name: String, val core: Boolean, val tier: Int?, val description: String, val icon: String)
    data class EmblemSet(val name: String, val attributes: List<String>, val talents: List<Talent>, val icon: String) {
        val standard: List<Talent> get() = talents.filter { !it.core }
        val core: List<Talent> get() = talents.filter { it.core }
    }
    data class Source(val page: String, val site: String, val license: String, val captured: String, val iconsFetched: String)
    data class Library(val source: Source, val sets: List<EmblemSet>, val notes: List<String>, val recommendationNote: String) {
        /** Every icon file the data names (sets and talents). */
        val icons: List<String> get() = sets.flatMap { s -> listOf(s.icon) + s.talents.map { it.icon } }
        fun set(name: String) = sets.firstOrNull { it.name.equals(name, true) }
        /** "Mobile Legends: Bang Bang Wiki (Fandom), Emblems page, captured 2026-11-10; icons fetched 2026-11-28; CC BY-SA". */
        fun attribution() = "${source.site}, Emblems page (${source.page}), captured ${source.captured}; icons fetched ${source.iconsFetched}; ${source.license}"
    }

    fun parse(text: String): Library {
        val o = JSONObject(text)
        require(o.optInt("schema") == 1) { "unknown emblem schema" }
        val src = o.getJSONObject("source")
        val a = o.getJSONArray("sets")
        val sets = (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            val ta = s.getJSONArray("talents")
            EmblemSet(s.getString("name"), (0 until s.getJSONArray("attributes").length()).map { s.getJSONArray("attributes").getString(it) },
                (0 until ta.length()).map { k -> val t = ta.getJSONObject(k)
                    Talent(t.getString("name"), t.getString("type") == "core", if (t.isNull("tier")) null else t.getInt("tier"),
                        t.getString("description"), t.getString("icon")) }, s.getString("icon"))
        }
        val notes = o.optJSONArray("notes")?.let { n -> (0 until n.length()).map { n.getString(it) } } ?: emptyList()
        return Library(Source(src.getString("page"), src.getString("site"), src.getString("license"), src.getString("captured"),
            src.getString("iconsFetched")), sets, notes, o.getJSONObject("recommendations").getString("note"))
    }

    /** The set the Wiki names after a role ("tank" → Custom Tank Emblem): a Wiki fact, not a recommendation. Null when none. */
    fun setNamedFor(lib: Library, role: String): EmblemSet? =
        lib.sets.firstOrNull { it.name.equals("Custom ${role.trim()} Emblem", ignoreCase = true) }

    /**
     * What the app may say about a hero's emblem: the Wiki gives no per-hero recommendation, so this is always the
     * unavailable statement, followed by the Wiki fact of the role's named set when there is one.
     */
    fun recommendationFor(lib: Library?, role: String?): String {
        if (lib == null) return "Emblem data not available"
        val named = role?.let { setNamedFor(lib, it) }
        return NO_RECOMMENDATION + (named?.let { " (The Wiki names a set after this role: ${it.name}.)" } ?: "")
    }
}
