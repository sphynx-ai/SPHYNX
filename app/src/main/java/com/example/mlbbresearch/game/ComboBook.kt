package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg

// One step of a combo: a button and how long to wait before the next step.
// slot: "s1", "s2", "ult", "a" (basic attack) or "spell"
class ComboStep(val slot: String, val waitMs: Long)

// Skill combos per hero. Order of use: yours (typed, pasted or downloaded on the Player
// page) > the built-in starting point below > a plain role default.
// The built-in ones are common guide combos; check them against the current patch.
object ComboBook {

    private val builtIn: Map<String, String> = mapOf(
        "Thamuz" to "s1 > s2 > a > ult > s2 > a",
        "Balmond" to "s1 > s2 > ult",
        "Alucard" to "s1 > s2 > a > ult > a",
        "Chou" to "s1 > s1 > s2 > ult > s1",
        "Freya" to "s1 > a > s2 > ult > a",
        "Zilong" to "s2 > s1 > ult > a",
        "Sun" to "s1 > s2 > a > ult",
        "Badang" to "s1 > s2 > ult",
        "Martis" to "s1 > s2 > ult",
        "Yu Zhong" to "s1 > s2 > ult > s2",
        "Ruby" to "s1 > s2 > ult > s2",
        "Saber" to "s1 > s2 > ult > a",
        "Hayabusa" to "s1 > s2 > ult",
        "Gusion" to "s1 > s2 > ult > s1 > s2",
        "Lancelot" to "s1 > s2 > ult > s1 > s2",
        "Karina" to "s2 > s1 > ult > a",
        "Natalia" to "s1 > a > s2 > ult",
        "Tigreal" to "s2 > ult > s1",
        "Franco" to "s1 > ult > s2",
        "Akai" to "s1 > ult > s2",
        "Johnson" to "s1 > s2 > ult",
        "Eudora" to "s2 > s1 > ult",
        "Nana" to "s2 > s1 > ult",
        "Aurora" to "s1 > s2 > ult",
        "Vexana" to "s1 > s2 > ult",
        "Kagura" to "s1 > s2 > ult",
        "Layla" to "s1 > s2 > ult > a",
        "Miya" to "s1 > a > s2 > ult > a",
        "Bruno" to "s1 > s2 > a > ult",
        "Clint" to "s1 > a > s2 > a > ult",
        "Lesley" to "s1 > a > s2 > ult",
        "Brody" to "s1 > a > s2 > ult",
        "Masha" to "s1 > s2 > a > ult",
        "Rafaela" to "s1 > s2 > ult",
        "Estes" to "s1 > s2 > ult",
        "Fredrinn" to "s1 > s2 > a > ult",
        "Lylia" to "s1 > s2 > s1 > a",
        "Claude" to "s1 > a > s2 > ult > a",
        "Minotaur" to "s1 > ult > s2",
        "Mathilda" to "s2 > ult > s1"
    )

    private val roleDefault: Map<Role, String> = mapOf(
        Role.TANK to "s2 > s1 > ult",
        Role.FIGHTER to "s1 > s2 > a > ult > a",
        Role.ASSASSIN to "s1 > s2 > ult > a",
        Role.MAGE to "s1 > s2 > ult",
        Role.MARKSMAN to "s1 > a > s2 > a > ult",
        Role.SUPPORT to "s1 > s2 > ult",
        Role.UNKNOWN to "s1 > s2 > ult > a"
    )

    // the combo text in use for a hero, and where it came from
    fun text(hero: String?): Pair<String, String> {
        if (hero != null) {
            Cfg.heroCombo(hero)?.let { if (parse(it).isNotEmpty()) return it to "yours" }
            MetaStore.hero(hero)?.combo?.let { return it to "online meta" }
            builtIn[hero]?.let { return it to "built-in" }
        }
        return (roleDefault[HeroDb.role(hero)] ?: "s1 > s2 > ult") to "role default"
    }

    fun steps(hero: String?): List<ComboStep> = parse(text(hero).first)

    // "s2 > s1(500) > a > ult" -> steps. Numbers in brackets = wait in ms after that step.
    fun parse(text: String): List<ComboStep> {
        val out = ArrayList<ComboStep>()
        for (raw in text.split(">", ",", "→", "+")) {
            val t = raw.trim().lowercase()
            if (t.isEmpty()) continue
            val wait = Regex("\\((\\d{2,4})\\)").find(t)?.groupValues?.get(1)?.toLongOrNull()
            val slot = when (t.substringBefore('(').trim()) {
                "s1", "1", "skill1", "skill 1" -> "s1"
                "s2", "2", "skill2", "skill 2" -> "s2"
                "ult", "s3", "3", "ultimate", "skill 3" -> "ult"
                "a", "ba", "basic", "attack", "basic attack" -> "a"
                "spell", "flicker", "battle spell" -> "spell"
                else -> null
            } ?: continue
            out.add(ComboStep(slot, wait ?: if (slot == "a") 450L else 350L))
        }
        return out.take(12)
    }

    fun format(steps: List<ComboStep>): String = steps.joinToString(" > ") { it.slot }
}

// Finds a skill combo in any text: a guide web page, a forum post or a video description.
// It understands "S1 > S2 > Ult", "Skill 2 -> Skill 1 -> Ultimate", "1st skill, basic attack, ultimate"...
object ComboParser {

    private val tokenRules: List<Pair<Regex, String>> = listOf(
        Regex("\\b(ultimate|ulti|ult|s3|skill ?3|3rd skill|third skill)\\b") to " ULT ",
        Regex("\\b(s1|skill ?1|1st skill|first skill|skill one)\\b") to " S1 ",
        Regex("\\b(s2|skill ?2|2nd skill|second skill|skill two)\\b") to " S2 ",
        Regex("\\b(basic attacks?|basic|auto attacks?|ba|aa)\\b") to " BA ",
        Regex("\\b(flicker)\\b") to " SP "
    )
    private val seq = Regex("(?:S1|S2|ULT|BA|SP)(?:(?:\\s*(?:>|→|->|=>|»|,|\\+|-|then|and|followed by|/|use|the|a)\\s*)+(?:S1|S2|ULT|BA|SP))+")

    // best combo found, as "s1 > s2 > ult", or null
    fun find(text: String): String? {
        var t = " " + text.lowercase().replace('\n', ' ') + " "
        for ((re, rep) in tokenRules) t = re.replace(t, rep)
        var best: String? = null
        var bestScore = 0
        for (m in seq.findAll(t)) {
            val toks = Regex("S1|S2|ULT|BA|SP").findAll(m.value).map { it.value }.toList()
            if (toks.size < 3 || toks.size > 12) continue
            if (toks.none { it == "S1" || it == "S2" }) continue
            // a sequence just after the word "combo" counts much more
            val before = t.substring(maxOf(0, m.range.first - 120), m.range.first)
            val score = toks.size + (if ("combo" in before) 20 else 0) + (if ("ULT" in toks) 2 else 0)
            if (score > bestScore) {
                bestScore = score
                best = toks.joinToString(" > ") {
                    when (it) { "S1" -> "s1"; "S2" -> "s2"; "ULT" -> "ult"; "BA" -> "a"; else -> "spell" }
                }
            }
        }
        return best
    }

    // plain text of a web page (tags, scripts and styles removed)
    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&").replace("&nbsp;", " ").replace("&#8594;", "→")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
}
