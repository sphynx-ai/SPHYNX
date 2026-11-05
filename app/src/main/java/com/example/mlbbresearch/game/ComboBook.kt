package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg

// One step of a combo: a button and how long to wait before the next step.
// slot: "s1", "s2", "s3", "ult", "a" (basic attack) or "spell"
class ComboStep(val slot: String, val waitMs: Long)

// Skill combos for ALL 131 heroes in HeroDb (v37.07).
// Includes dual-identity transformation combos, multi-phase skill combos, and role-accurate sequences for every hero.
object ComboBook {

    private val builtIn: Map<String, String> = mapOf(
        // ---- TANKS (19) ----
        "Tigreal" to "s1 > s2 > s2 > ult > a",
        "Franco" to "s1 > ult > s2 > a",
        "Akai" to "s1 > s2 > ult > a",
        "Johnson" to "ult > s1 > s2 > a",
        "Grock" to "s1 > s2 > ult > a",
        "Hylos" to "s2 > ult > s1 > a",
        "Uranus" to "s2 > s1 > a > ult > s1 > a",
        "Belerick" to "ult > s1 > s2 > a",
        "Khufra" to "s1 > ult > s2 > a",
        "Atlas" to "s2 > s2 > ult > s1 > a",
        "Baxia" to "s1 > ult > s2 > s2 > a",
        "Lolita" to "s1 > a > ult > s2 > a",
        "Minotaur" to "s1 > ult > s2 > s1 > a",
        "Gatotkaca" to "ult > s2 > s1 > a",
        "Barats" to "s2 > s1 > a > ult > a",
        "Edith" to "s2 > s1 > a > ult > s2 > s1 > a > a",
        "Fredrinn" to "s1 > a > s2 > a > s3 > s1 > s2 > ult",
        "Gloo" to "s1 > s2 > ult > ult > s1 > s2 > a",
        "Marcel" to "s2 > s1 > ult > a",

        // ---- FIGHTERS (37) ----
        "Thamuz" to "s1 > s2 > a > ult > s1 > a",
        "Balmond" to "s1 > s2 > a > ult",
        "Alucard" to "s1 > a > s2 > a > ult > a > s1 > a",
        "Chou" to "s1 > s1 > s2 > s1 > a > ult > ult > a",
        "Freya" to "ult > s1 > s2 > a > s2 > a > s2 > a > s2 > a",
        "Zilong" to "ult > s2 > a > s1 > a > a",
        "Sun" to "ult > s1 > s3 > a > s2 > a",
        "Martis" to "s1 > s2 > s2 > a > ult > a",
        "Paquito" to "s2 > s1 > ult > s2 > s1 > a",
        "Yu Zhong" to "ult > s3 > s1 > s2 > a",
        "Terizla" to "s1 > ult > s2 > s2 > s2 > a",
        "X.Borg" to "s1 > s2 > ult > ult > s1 > a",
        "Dyrroth" to "s2 > s2 > s1 > a > ult > a",
        "Badang" to "s2 > s1 > ult > a > s1 > a",
        "Aldous" to "ult > s1 > a > s2 > a",
        "Leomord" to "ult > s2 > s1 > a > s2 > s1 > a",
        "Lapu-Lapu" to "s1 > s2 > ult > ult > s2 > s1 > a",
        "Masha" to "s1 > ult > a > s2 > a > a",
        "Silvanna" to "ult > s1 > s2 > a > s1 > a",
        "Khaleed" to "ult > a > s1 > s1 > s1 > a",
        "Arlott" to "s2 > s1 > s2 > ult > s2 > a",
        "Yin" to "s1 > s2 > a > ult > s2 > s1 > a",
        "Cici" to "ult > s1 > s2 > s2 > a",
        "Ruby" to "s1 > a > s2 > a > ult > s2 > s1 > a",
        "Argus" to "s1 > s2 > a > s1 > ult > a > a",
        "Jawhead" to "ult > s2 > s2 > s1 > a",
        "Hilda" to "s1 > a > s2 > s2 > s2 > ult > a",
        "Guinevere" to "s2 > ult > s1 > s2 > a",
        "Aulus" to "ult > s1 > s2 > a > a > a",
        "Alpha" to "ult > s2 > a > s1 > a",
        "Bane" to "ult > s1 > a > s2 > a",
        "Julian" to "s2 > ult > s1 > a > a",
        "Lukas" to "ult > s2 > a > s1 > a > ult > a",
        "Minsitthar" to "s1 > ult > s2 > a > a",
        "Phoveus" to "s1 > a > s2 > ult > s1 > ult > a",
        "Roger" to "s1 > s2 > a > ult > s2 > s1 > a > a",
        "Sora" to "s1 > s2 > ult > s1 > s2 > a",

        // ---- ASSASSINS (18) ----
        "Saber" to "s1 > s2 > a > ult > a",
        "Karina" to "s1 > a > s2 > ult > ult > a",
        "Fanny" to "s2 > s2 > ult > s1 > a",
        "Hayabusa" to "s2 > s1 > a > ult > s1 > s2 > a",
        "Natalia" to "a > s1 > a > s2 > ult > a > s1 > a",
        "Lancelot" to "s1 > s2 > a > ult > s1 > a",
        "Gusion" to "s2 > ult > s2 > s1 > s2 > s1 > a > ult > a",
        "Helcurt" to "ult > s1 > a > s2 > a",
        "Ling" to "s2 > a > ult > s2 > s2 > s2 > s2 > s1 > a",
        "Benedetta" to "ult > s1 > a > s2 > a",
        "Aamon" to "s2 > a > s1 > a > ult > a",
        "Joy" to "s1 > s2 > s2 > s2 > s2 > ult > a",
        "Nolan" to "s2 > s1 > a > ult > s2 > s1 > a",
        "Hanzo" to "ult > s2 > a > a > s1 > a",
        "Yi Sun-shin" to "s2 > a > s1 > a > ult > a",
        "Selena" to "s1 > s2 > ult > s2 > s1 > s2 > a",
        "Suyou" to "s1 > s2 > a > ult > a",
        "Hirara" to "s1 > s2 > a",

        // ---- MAGES (28) ----
        "Eudora" to "s2 > ult > s1 > a",
        "Alice" to "s1 > ult > s1 > s2 > a",
        "Nana" to "s2 > ult > s1 > a",
        "Kagura" to "s1 > ult > s2 > ult > s2 > s1 > a",
        "Cyclops" to "ult > s2 > s1 > a",
        "Aurora" to "s2 > ult > s1 > a",
        "Vale" to "s2 > ult > s1 > a",
        "Lunox" to "s3 > s2 > ult > s2 > s2 > s1 > a",
        "Harley" to "s2 > ult > s1 > a > s2",
        "Odette" to "s2 > s1 > a > ult",
        "Zhask" to "s1 > ult > s2 > s3 > a > a",
        "Pharsa" to "s1 > s2 > ult > s3",
        "Kadita" to "s1 > s2 > ult > s1 > a",
        "Cecilion" to "s2 > s1 > ult > s1 > a",
        "Luo Yi" to "s1 > s2 > ult > s1 > a",
        "Yve" to "s2 > s1 > ult > s1 > a",
        "Valir" to "s1 > ult > s1 > s2 > s1 > a",
        "Chang'e" to "s2 > s1 > ult > a",
        "Xavier" to "s1 > s2 > ult > a",
        "Novaria" to "ult > s1 > s2 > a",
        "Lylia" to "s2 > s1 > s2 > s2 > a > ult",
        "Vexana" to "s1 > s2 > ult > a",
        "Zhuxin" to "s1 > ult > s2 > a",
        "Esmeralda" to "ult > s1 > s2 > a > s1 > s2 > a",
        "Gord" to "s1 > s2 > ult > a",
        "Harith" to "ult > s2 > a > s1 > s2 > a > s2 > a",
        "Valentina" to "s1 > s2 > s1 > s2 > ult > a",
        "Zetian" to "s2 > s1 > ult > a",

        // ---- MARKSMEN (20) ----
        "Layla" to "s1 > a > s2 > a > ult > a",
        "Miya" to "ult > s1 > s2 > a > a > a",
        "Bruno" to "s1 > a > s2 > a > ult > a",
        "Clint" to "s1 > a > s2 > a > ult > a",
        "Granger" to "s2 > a > s1 > ult > a",
        "Moskov" to "s1 > s2 > a > a > ult > a",
        "Beatrix" to "s1 > a > ult > s2 > a > a",
        "Brody" to "s1 > a > s2 > a > ult",
        "Claude" to "s1 > s2 > ult > s2 > a",
        "Wanwan" to "s1 > s1 > a > a > ult > s2 > a",
        "Karrie" to "ult > s1 > s2 > a > a > s2 > a",
        "Irithel" to "ult > s1 > s2 > a > a > a",
        "Lesley" to "a > s1 > a > s2 > a > ult",
        "Hanabi" to "ult > s1 > s2 > a > a > a",
        "Melissa" to "s2 > s1 > a > a > ult > a",
        "Natan" to "ult > s1 > s2 > ult > s1 > a > a",
        "Ixia" to "s2 > s1 > ult > a > a",
        "Popol and Kupa" to "ult > s1 > s3 > s2 > a > a",
        "Kimmy" to "s1 > s2 > a > ult > a",
        "Obsidia" to "s2 > s1 > a > ult > a",

        // ---- SUPPORTS (11) ----
        "Rafaela" to "s1 > ult > s2 > a",
        "Estes" to "s2 > s1 > ult > a",
        "Angela" to "s2 > s1 > s1 > ult > s1 > a",
        "Diggie" to "s2 > s1 > ult > a",
        "Floryn" to "s2 > s1 > ult > a",
        "Mathilda" to "s1 > ult > s2 > a",
        "Kaja" to "ult > s2 > s1 > a",
        "Faramis" to "s1 > s2 > ult > a",
        "Carmilla" to "s2 > ult > s2 > s1 > a",
        "Chip" to "s2 > s1 > ult > s3 > a",
        "Kalea" to "s2 > s1 > a > ult > a"
    )

    private val roleDefault: Map<Role, String> = mapOf(
        Role.TANK to "s2 > s1 > ult > a",
        Role.FIGHTER to "s1 > s2 > a > ult > a",
        Role.ASSASSIN to "s1 > s2 > ult > a",
        Role.MAGE to "s2 > s1 > ult > a",
        Role.MARKSMAN to "s1 > a > s2 > a > ult",
        Role.SUPPORT to "s2 > s1 > ult > a",
        Role.UNKNOWN to "s1 > s2 > ult > a"
    )

    // Dynamic form-aware combo for Roger, Selena, and Julian
    fun dynamicComboText(hero: String?, enemiesClose: Int = 1, selfHp: Float = 1f, targetDist: Float = 0.25f): String? {
        if (hero == null || Cfg.heroCombo(hero) != null) return null
        return when (hero) {
            "Julian" -> HeroKit.julianCombo(enemiesClose, selfHp, targetDist)
            "Selena" -> if (HeroKit.formOf("Selena") == 1) {
                // Already in Abyssal (melee) form: dash with Garotte -> Soul Eater -> Garotte reset -> basic attack -> back to Elven
                "s2 > s1 > s2 > a > ult"
            } else {
                // In Elven (ranged) form: Abyssal Trap -> Abyssal Arrow -> transform to Abyssal -> Garotte -> Soul Eater -> Garotte reset
                "s1 > s2 > ult > s2 > s1 > s2 > a"
            }
            "Roger" -> if (HeroKit.formOf("Roger") == 1) {
                // Already in Wolf (melee) form: Bloodthirsty Howl -> Lycan Pounce -> basic attacks
                "s2 > s1 > a > a"
            } else {
                // In Human (ranged) form: Open Fire slow/shred -> Hunter's Steps -> Wolf Transformation lunge -> Wolf s2 > s1 > basic attacks
                "s1 > s2 > a > ult > s2 > s1 > a > a"
            }
            else -> null
        }
    }

    fun text(hero: String?): Pair<String, String> {
        if (hero != null) {
            Cfg.heroCombo(hero)?.let { if (parse(it).isNotEmpty()) return it to "yours" }
            if (hero in HeroKit.MULTI_PHASE_HEROES) {
                builtIn[hero]?.let { return it to "built-in (multi-phase / complex)" }
            }
            MetaStore.hero(hero)?.combo?.let { return it to "online meta" }
            builtIn[hero]?.let { return it to "built-in" }
            HeroPlaybook.of(hero).takeIf { it.learned }?.let { return it.combo to "from its skills" }
        }
        return (roleDefault[HeroDb.role(hero)] ?: "s1 > s2 > ult") to "role default"
    }

    fun steps(hero: String?, overrideText: String? = null): List<ComboStep> {
        val four = HeroKit.has(hero, "s3")
        val rawText = overrideText ?: text(hero).first
        var st = parse(rawText).map { if (it.slot == "s3" && !four) ComboStep("ult", it.waitMs) else it }
            .filter { it.slot !in HeroKit.ALL || HeroKit.has(hero, it.slot) }
        if (four && st.none { it.slot == "s3" }) {
            val i = st.indexOfFirst { it.slot == "ult" }.let { if (it < 0) st.size else it }
            st = st.take(i) + ComboStep("s3", 350L) + st.drop(i)
        }
        return st
    }

    fun parse(text: String): List<ComboStep> {
        val out = ArrayList<ComboStep>()
        for (raw in text.split(">", ",", "→", "+")) {
            val t = raw.trim().lowercase()
            if (t.isEmpty()) continue
            val wait = Regex("\\((\\d{2,4})\\)").find(t)?.groupValues?.get(1)?.toLongOrNull()
            val slot = when (t.substringBefore('(').trim()) {
                "s1", "1", "skill1", "skill 1" -> "s1"
                "s2", "2", "skill2", "skill 2" -> "s2"
                "s3", "3", "skill3", "skill 3" -> "s3"
                "ult", "s4", "4", "ultimate", "skill 4", "skill4" -> "ult"
                "a", "ba", "basic", "attack", "basic attack" -> "a"
                "spell", "flicker", "battle spell" -> "spell"
                else -> null
            } ?: continue
            out.add(ComboStep(slot, wait ?: if (slot == "a") 350L else 350L))
        }
        return out.take(14)
    }

    fun format(steps: List<ComboStep>): String = steps.joinToString(" > ") { it.slot }
}

object ComboParser {

    private val tokenRules: List<Pair<Regex, String>> = listOf(
        Regex("\\b(ultimate|ulti|ult|s3|skill ?3|3rd skill|third skill)\\b") to " ULT ",
        Regex("\\b(s1|skill ?1|1st skill|first skill|skill one)\\b") to " S1 ",
        Regex("\\b(s2|skill ?2|2nd skill|second skill|skill two)\\b") to " S2 ",
        Regex("\\b(basic attacks?|basic|auto attacks?|ba|aa)\\b") to " BA ",
        Regex("\\b(flicker)\\b") to " SP "
    )
    private val seq = Regex("(?:S1|S2|ULT|BA|SP)(?:(?:\\s*(?:>|→|->|=>|»|,|\\+|-|then|and|followed by|/|use|the|a)\\s*)+(?:S1|S2|ULT|BA|SP))+")

    fun find(text: String): String? {
        var t = " " + text.lowercase().replace('\n', ' ') + " "
        for ((re, rep) in tokenRules) t = re.replace(t, rep)
        var best: String? = null
        var bestScore = 0
        for (m in seq.findAll(t)) {
            val toks = Regex("S1|S2|ULT|BA|SP").findAll(m.value).map { it.value }.toList()
            if (toks.size < 3 || toks.size > 12) continue
            if (toks.none { it == "S1" || it == "S2" }) continue
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

    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&").replace("&nbsp;", " ").replace("&#8594;", "→")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
}
