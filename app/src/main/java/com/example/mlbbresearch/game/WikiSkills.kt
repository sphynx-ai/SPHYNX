package com.example.mlbbresearch.game

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// REAL SKILLS ONLY (v36.53): a hero's passive and skills come from the hero's page on the Mobile
// Legends wiki (Fandom), not from an AI's memory (which invents skills). The page is downloaded
// on the phone, the skills part cut out and cleaned; the online model then only COPIES each skill
// out of that text into the app's format, and every skill name it gives must appear in the text
// (SkillBook.fetchNow) - an invented one is thrown away.
object WikiSkills {
    @Volatile internal var BASE = "https://mobile-legends.fandom.com/wiki/"     // (overridable for tests)

    // the page's wiki text (following one redirect), null if the wiki cannot be reached
    fun page(hero: String): String? {
        var title = hero
        repeat(2) {
            val raw = get(BASE + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/") + "?action=raw") ?: return null
            val redirect = Regex("(?i)^\\s*#REDIRECT\\s*\\[\\[([^\\]|#]+)").find(raw)?.groupValues?.get(1)?.trim()
            if (redirect == null || redirect.equals(title, true)) return raw
            title = redirect
        }
        return null
    }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "MLBB-Research")
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    } catch (e: Exception) { null }

    // the skills part of the page, cleaned to readable text (at most ~9000 characters)
    fun skillsPart(raw: String): String {
        val heads = Regex("(?m)^(=+)\\s*([^=]+?)\\s*\\1\\s*$").findAll(raw).toList()
        // (a hero with a revamp being tested shows a second skills section for the Advanced Server /
        // the upcoming version: the live one is used - v36.62, Aulus)
        val notLive = Regex("(?i)advanced|upcoming|test server|revamp(ed)? \\(|new version|old version|legacy")
        // the section that really holds the skills: the one with Passive / Skill 1 / Ultimate in it (your
        // Aulus page has an "Abilities" heading in his STORY first - his lore - and the skills later)
        fun sectionOf(h: MatchResult): String {
            val level = h.groupValues[1].length
            val end = heads.firstOrNull { it.range.first > h.range.first && it.groupValues[1].length <= level }
            return raw.substring(h.range.first, end?.range?.first ?: raw.length)
        }
        fun skillScore(t: String): Int = listOf("(?i)passive", "(?i)skill\\s*1", "(?i)skill\\s*2", "(?i)ultimate").count { Regex(it).containsMatchIn(t) }
        val cands = heads.filter { Regex("(?i)skill|abilit").containsMatchIn(it.groupValues[2]) }
        val start = cands.filter { !notLive.containsMatchIn(it.groupValues[2]) }.maxByOrNull { skillScore(sectionOf(it)) * 10 - it.groupValues[1].length }
            ?: cands.firstOrNull()
        val part = if (start != null) {
            val level = start.groupValues[1].length
            val end = heads.firstOrNull { it.range.first > start.range.first && it.groupValues[1].length <= level }
            raw.substring(start.range.first, end?.range?.first ?: raw.length)
        } else {
            val i = raw.indexOf("passive", ignoreCase = true)
            if (i < 0) raw else raw.substring(maxOf(0, i - 600))
        }
        // sub-sections about the Advanced Server / upcoming changes inside it are cut out
        val sub = Regex("(?m)^(=+)\\s*([^=]+?)\\s*\\1\\s*$").findAll(part).toList()
        var live = part
        for ((i, hd) in sub.withIndex().reversed()) if (notLive.containsMatchIn(hd.groupValues[2])) {
            val lvl = hd.groupValues[1].length
            val end = sub.drop(i + 1).firstOrNull { it.groupValues[1].length <= lvl }?.range?.first ?: part.length
            live = live.removeRange(hd.range.first, minOf(end, live.length))
        }
        return clean(live).take(9000)
    }

    fun clean(t: String): String = t
        .replace(Regex("(?s)<!--.*?-->"), "")
        .replace(Regex("(?s)<ref[^>]*/>|<ref[^>]*>.*?</ref>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("\\[\\[(?:File|Image|Category):[^\\]]*\\]\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[\\[[^\\]|]*\\|([^\\]]*)\\]\\]"), "$1")
        .replace(Regex("\\[\\[([^\\]]*)\\]\\]"), "$1")
        .replace(Regex("<[^>]+>"), "")
        .replace("'''", "").replace("''", "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ---- the real skill pictures (v36.66) ----
    // Guessing a picture from the skill's name failed when a skill shares its name with an item or a
    // spell (Layla's Malefic Gun, Arlott's Vengeance) or has ":" / quotes in it (Hayabusa, Aldous,
    // Barats). The hero's rendered wiki page shows the real file under each heading (Passive, Skill 1,
    // Skill 2, Skill 3, Ultimate): read it there. Only the page is downloaded (no online-model tokens).
    private fun iconKey(h: String) = "skillicons_" + norm(h)
    fun icons(hero: String): Map<String, String> {
        val o = try { org.json.JSONObject(com.example.mlbbresearch.core.Cfg.raw(iconKey(hero)) ?: return emptyMap()) } catch (e: Exception) { return emptyMap() }
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    }
    fun iconsDebug(hero: String) = com.example.mlbbresearch.core.Cfg.raw("skillicons_dbg_" + norm(hero))
    fun iconsKnown(hero: String) = com.example.mlbbresearch.core.Cfg.raw(iconKey(hero)) != null

    // rendered page -> {passive, s1, s2, s3, ult} -> file name
    // v36.68: 4-skill heroes (Chip, Fredrinn) lost a picture - their page has no plain "Skill 3" heading
    // (e.g. "Ultimate 1" / "Ultimate 2", a heading with the skill's name, or two pictures under one
    // heading). Now: headings h2-h4 that START with Passive / Skill N / Ultimate (anything after is
    // fine); every picture under each heading is kept; slots the hero's kit has but the labels did not
    // fill are filled, in page order, from the other active headings and the extra pictures.
    // kitSize: the hero's number of active skills (2 / 3 / 4), 0 = unknown.
    fun parseIcons(html: String, kitSize: Int = 0): Map<String, String> {
        val heads = Regex("<h([234])[^>]*>(.*?)</h\\1>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(html).toList()
        val img = Regex("/images/[0-9a-f]/[0-9a-f]{2}/([^/\"?\\s]+\\.(?:png|jpg|webp))")
        fun text(h: MatchResult) = h.groupValues[2].replace(Regex("<[^>]+>"), "").replace("&#160;", " ").replace("&nbsp;", " ").trim().lowercase()
        fun label(t: String): String? = when {
            t.startsWith("passive") -> "passive"
            Regex("^skill\\s*([1-3])\\b").find(t) != null -> "s" + Regex("^skill\\s*([1-3])").find(t)!!.groupValues[1]
            t.startsWith("ultimate") -> "ult"
            // Chip: an extra button headed "Special" (Why Walk?) - his 3rd skill button
            t.startsWith("special") || t.startsWith("extra skill") -> "special"
            else -> null
        }
        // not the skill's own picture: its greyed / cooldown / buff versions, the basic attack, hero
        // portraits and items shown in the text
        val junk = Regex("(?i)grayscale|greyscale|cooldown|_buff|^basic_attack|^hero\\d+-icon|displaymodel|_\\(\\d\\)\\.")
        // the skills block: from the first Passive heading to the first heading after it that is not
        // a skill heading and not deeper than it
        val first = heads.indexOfFirst { label(text(it)) == "passive" }
        if (first < 0) return emptyMap()
        val lvl0 = heads[first].groupValues[1].toInt()
        class Sec(val label: String?, val pics: List<String>)
        val secs = ArrayList<Sec>()
        var i = first
        while (i < heads.size) {
            val h = heads[i]; val t = text(h); val lb = label(t)
            if (i > first && lb == null && h.groupValues[1].toInt() <= lvl0) break
            if (Regex("advanced|upcoming|test server|old version|legacy|trivia|gallery|story|lore").containsMatchIn(t)) break
            val end = heads.getOrNull(i + 1)?.range?.first ?: html.length
            val body = html.substring(h.range.last + 1, end)
            // the section's opening words hold the skill's own name ("Mechanical Genius ...")
            val opening = norm(body.replace(Regex("<[^>]+>"), " ").take(1500))
            val pics = img.findAll(body).map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
                .filter { !junk.containsMatchIn(it) }.distinct()
                // best first (v36.78, Beatrix: her passive section shows her weapons' item icons -
                // Demon Hunter Sword - before her own passive icon):
                //  * a picture named after words at the start of the section = the skill's own icon
                //  * a picture of an equipment / spell / hero portrait last
                //  * a skin's version ("Brave_Assault_(Neobeasts).png") after the plain one
                .withIndex().sortedWith(compareBy({ (_, f) ->
                    val base = f.substringBeforeLast('.').replace(Regex("_\\([^)]*\\)$"), "").replace('_', ' ')
                    val named = norm(base).length >= 4 && opening.contains(norm(base))
                    // (the whole name: "Malefic_Gun_(Passive).png" is Layla's own, "Malefic_Gun.png" the item)
                    val full = f.substringBeforeLast('.').replace('_', ' ')
                    val other = ItemDb.canonical(full) != null || SpellDb.match(full)?.equals(full, true) == true || full.startsWith("Hero")
                    when { named && !other -> 0; named -> 1; !other -> 2; else -> 3 }
                }, { (_, f) -> if (Regex("_\\([^)]*\\)\\.").containsMatchIn(f)) 1 else 0 }, { it.index })).map { it.value }.toList()
            // (no "already used" check: a passive's text shows the other skills' icons inline - Fredrinn)
            secs.add(Sec(lb, pics))
            i++
        }
        val out = LinkedHashMap<String, String>()
        for (sc in secs) if (sc.label != null && sc.label != "special" && sc.label !in out && sc.pics.isNotEmpty()) out[sc.label] = sc.pics[0]
        // the page itself shows a 4th button: two Ultimates (Fredrinn: "Ultimate 1" = 3rd button,
        // "Ultimate 2" = ultimate) or a Special skill (Chip: Why Walk? = 3rd button)
        val special = secs.firstOrNull { it.label == "special" && it.pics.isNotEmpty() }
        val ults = secs.filter { it.label == "ult" && it.pics.isNotEmpty() }
        val kitSize = if (kitSize == 0 && (special != null || ults.size >= 2)) 4 else kitSize
        if ("s3" !in out && kitSize == 4) {
            // (Chip, your word: the Ultimate - Shortcut - is the 3rd button, the Special - Why Walk? - the 4th)
            if (special != null) { out["ult"]?.let { out["s3"] = it }; out["ult"] = special.pics[0] }
            else if (ults.size >= 2) { out["s3"] = ults[0].pics[0]; out["ult"] = ults[1].pics[0] }
        }
        val want = when (kitSize) { 2 -> listOf("s1", "s2"); 4 -> listOf("s1", "s2", "s3", "ult"); 3 -> listOf("s1", "s2", "ult"); else -> emptyList() }
        val missing = want.filter { it !in out }
        if (missing.isNotEmpty()) {
            // spare pictures in page order: unlabelled active headings, then the 2nd+ picture of a skill heading
            val spare = ArrayList<String>()
            for (sc in secs) if (sc.label != "passive" && sc.label != "special") {
                if (sc.label == null || out[sc.label] != sc.pics.firstOrNull()) spare.addAll(sc.pics.take(1))   // unlabelled, or a 2nd heading with a taken label
                else spare.addAll(sc.pics.drop(1).take(1))
            }
            spare.removeAll(out.values.toSet())
            // two ultimates (Fredrinn): the first one is the 3rd button, the last the ultimate
            val ultSecs = secs.filter { it.label == "ult" && it.pics.isNotEmpty() }
            if (kitSize == 4 && "s3" in missing && ultSecs.size >= 2) {
                out["s3"] = ultSecs[0].pics[0]; out["ult"] = ultSecs[1].pics[0]; spare.remove(ultSecs[1].pics[0])
            } else if (kitSize == 4 && "s3" in missing && "ult" in out && spare.isNotEmpty()) {
                val ultSec = secs.firstOrNull { it.label == "ult" }
                if (ultSec != null && ultSec.pics.size >= 2) { out["s3"] = ultSec.pics[0]; out["ult"] = ultSec.pics[1]; spare.remove(ultSec.pics[1]) }
                else out["s3"] = spare.removeAt(0)
            }
            for (m in want.filter { it !in out }) if (spare.isNotEmpty()) out[m] = spare.removeAt(0)
        }
        lastHeadings = secs.joinToString(" | ") { (it.label ?: "?") + ":" + it.pics.size }
        return out
    }
    @Volatile var lastHeadings = ""

    // download and remember (null = the page could not be read)
    fun fetchIcons(hero: String): Map<String, String>? {
        val enc = URLEncoder.encode(hero.replace(' ', '_'), "UTF-8")
        val html = get("https://mobile-legends.fandom.com/wiki/$enc?action=render") ?: get(BASE + enc) ?: return null
        // the hero's damage type, as its wiki page writes it ("Damage type: Physical / Magic")
        ItemClass.infobox(html).entries.firstOrNull { it.key.equals("Damage type", true) }?.value?.takeIf { it.isNotBlank() }
            ?.let { com.example.mlbbresearch.core.Cfg.setRaw("herodmg_" + norm(hero), it.take(40)) }
        val m = parseIcons(html, HeroKit.kitSizeHint(hero))
        if (m.isEmpty()) return null
        com.example.mlbbresearch.core.Cfg.setRaw("skillicons_dbg_" + norm(hero), lastHeadings)
        com.example.mlbbresearch.core.Cfg.setRaw(iconKey(hero), org.json.JSONObject(m as Map<*, *>).toString())
        return m
    }

    // is this skill name written in the source text? (letters / digits only, so "Ruthless Arrival" =
    // "Ruthless  arrival"); very short names must match exactly as a word
    fun nameIn(name: String, source: String): Boolean {
        val n = norm(name)
        if (n.length < 3) return false
        return norm(source).contains(n)
    }
}
