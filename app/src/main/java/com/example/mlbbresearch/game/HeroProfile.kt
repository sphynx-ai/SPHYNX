package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import kotlin.math.min

enum class Role { TANK, FIGHTER, ASSASSIN, MAGE, MARKSMAN, SUPPORT, UNKNOWN }
enum class Damage { PHYSICAL, MAGIC }

// When the ultimate should be used
enum class UltRule {
    ENGAGE,      // to start a fight when it looks winnable
    EXECUTE,     // to finish a low-HP target
    TEAMFIGHT,   // when 2+ enemies are close
    DEFENSIVE,   // to survive when your HP is low
    BUFF         // self-buff while fighting at close range
}

class HeroProfile(
    val name: String,
    val role: Role,
    val damage: Damage,
    val attackRange: Float,
    val engageRange: Float,
    val retreatHp: Float,
    val combo: List<String>,
    val waveClearSkill: String?,
    val escapeSkill: String?,
    val ult: UltRule,
    val cooldownSec: Map<String, Float>,
    val ranged: Boolean = false
)

object HeroDb {

    private val roles: Map<String, Role> = buildMap {
        fun add(r: Role, vararg names: String) = names.forEach { put(it, r) }
        add(Role.TANK, "Tigreal", "Franco", "Akai", "Johnson", "Grock", "Hylos", "Uranus", "Belerick", "Khufra",
            "Atlas", "Baxia", "Lolita", "Minotaur", "Gatotkaca", "Barats", "Edith", "Fredrinn", "Gloo", "Marcel")
        add(Role.FIGHTER, "Thamuz", "Balmond", "Alucard", "Chou", "Freya", "Zilong", "Sun", "Martis", "Paquito",
            "Yu Zhong", "Terizla", "X.Borg", "Dyrroth", "Badang", "Aldous", "Leomord", "Lapu-Lapu", "Masha",
            "Silvanna", "Khaleed", "Arlott", "Yin", "Cici", "Ruby", "Argus", "Jawhead", "Hilda", "Guinevere", "Aulus",
            "Alpha", "Bane", "Julian", "Lukas", "Minsitthar", "Phoveus", "Roger", "Sora")
        add(Role.ASSASSIN, "Saber", "Karina", "Fanny", "Hayabusa", "Natalia", "Lancelot", "Gusion", "Helcurt",
            "Ling", "Benedetta", "Aamon", "Joy", "Nolan", "Hanzo", "Yi Sun-shin", "Selena", "Suyou", "Hirara")
        add(Role.MAGE, "Eudora", "Alice", "Nana", "Kagura", "Cyclops", "Aurora", "Vale", "Lunox", "Harley",
            "Odette", "Zhask", "Pharsa", "Kadita", "Cecilion", "Luo Yi", "Yve", "Valir", "Chang'e", "Xavier",
            "Novaria", "Lylia", "Vexana", "Zhuxin", "Esmeralda", "Gord", "Harith", "Valentina", "Zetian")
        add(Role.MARKSMAN, "Layla", "Miya", "Bruno", "Clint", "Granger", "Moskov", "Beatrix", "Brody", "Claude",
            "Wanwan", "Karrie", "Irithel", "Lesley", "Hanabi", "Melissa", "Natan", "Ixia", "Popol and Kupa", "Kimmy", "Obsidia")
        add(Role.SUPPORT, "Rafaela", "Estes", "Angela", "Diggie", "Floryn", "Mathilda", "Kaja", "Faramis",
            "Carmilla", "Chip", "Kalea")
    }

    private val magicOverride = setOf(
        "Karina", "Gusion", "Selena", "Natan", "Esmeralda",
        "Aamon", "Joy", "Julian", "Silvanna", "Guinevere", "Phoveus", "Kimmy",
        // Magic-damage Tanks:
        "Uranus", "Belerick", "Hylos", "Gloo", "Atlas", "Johnson", "Gatotkaca", "Baxia", "Edith"
    )

    val sustain = setOf("Estes", "Rafaela", "Angela", "Floryn", "Alucard", "Uranus", "Esmeralda", "Ruby",
        "Yu Zhong", "Thamuz", "Lapu-Lapu", "Julian", "Alice", "Fredrinn", "Freya", "Suyou")

    val allNames: List<String> get() = roles.keys.sorted()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val byNorm = roles.keys.associateBy { norm(it) }

    fun match(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val n = norm(raw)
        if (n.isEmpty() || n == "unknown" || n == "none" || n == "null" || n == "na") return null
        byNorm[n]?.let { return it }
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for ((k, v) in byNorm) {
            val d = distance(n, k)
            if (d < bestD) { bestD = d; best = v }
        }
        val allowed = if (n.length >= 5) 2 else 1
        return if (bestD <= allowed) best else null
    }

    // Complete dash / mobility table for all 131 heroes in HeroDb (v37.07)
    private val dashTable = mapOf(
        // Tanks
        "Tigreal" to "s2", "Franco" to "none", "Akai" to "s1", "Johnson" to "ult", "Grock" to "ult",
        "Hylos" to "none", "Uranus" to "s2", "Belerick" to "none", "Khufra" to "s1", "Atlas" to "s2",
        "Baxia" to "s1", "Lolita" to "s1", "Minotaur" to "s1", "Gatotkaca" to "ult", "Barats" to "none",
        "Edith" to "s2", "Fredrinn" to "s2", "Gloo" to "s2", "Marcel" to "s2",
        // Fighters
        "Thamuz" to "s2", "Balmond" to "s1", "Alucard" to "s1", "Chou" to "s2", "Freya" to "s1",
        "Zilong" to "s2", "Sun" to "s2", "Martis" to "s2", "Paquito" to "s2", "Yu Zhong" to "s3",
        "Terizla" to "ult", "X.Borg" to "ult", "Dyrroth" to "s2", "Badang" to "s2", "Aldous" to "ult",
        "Leomord" to "s2", "Lapu-Lapu" to "s2", "Masha" to "ult", "Silvanna" to "ult", "Khaleed" to "s1",
        "Arlott" to "s2", "Yin" to "s2", "Cici" to "s2", "Ruby" to "s2", "Argus" to "s1",
        "Jawhead" to "ult", "Hilda" to "s1", "Guinevere" to "s2", "Aulus" to "s1", "Alpha" to "ult",
        "Bane" to "none", "Julian" to "s2", "Lukas" to "s2", "Minsitthar" to "s1", "Phoveus" to "ult",
        "Roger" to "ult", "Sora" to "s1",
        // Assassins
        "Saber" to "s2", "Karina" to "ult", "Fanny" to "s2", "Hayabusa" to "s2", "Natalia" to "s1",
        "Lancelot" to "s1", "Gusion" to "s2", "Helcurt" to "s1", "Ling" to "s2", "Benedetta" to "s2",
        "Aamon" to "none", "Joy" to "s2", "Nolan" to "s2", "Hanzo" to "s2", "Yi Sun-shin" to "s1",
        "Selena" to "s2", "Suyou" to "s1", "Hirara" to "s1",
        // Mages
        "Eudora" to "none", "Alice" to "s1", "Nana" to "none", "Kagura" to "s2", "Cyclops" to "none",
        "Aurora" to "none", "Vale" to "none", "Lunox" to "ult", "Harley" to "s2", "Odette" to "ult",
        "Zhask" to "none", "Pharsa" to "s3", "Kadita" to "s1", "Cecilion" to "none", "Luo Yi" to "none",
        "Yve" to "none", "Valir" to "none", "Chang'e" to "none", "Xavier" to "none", "Novaria" to "s2",
        "Lylia" to "ult", "Vexana" to "none", "Zhuxin" to "ult", "Esmeralda" to "ult", "Gord" to "none",
        "Harith" to "s2", "Valentina" to "s2", "Zetian" to "none",
        // Marksmen
        "Layla" to "none", "Miya" to "ult", "Bruno" to "s2", "Clint" to "s2", "Granger" to "s2",
        "Moskov" to "s1", "Beatrix" to "s2", "Brody" to "s2", "Claude" to "s2", "Wanwan" to "none",
        "Karrie" to "s2", "Irithel" to "ult", "Lesley" to "s2", "Hanabi" to "none", "Melissa" to "s1",
        "Natan" to "ult", "Ixia" to "none", "Popol and Kupa" to "none", "Kimmy" to "s2", "Obsidia" to "s2",
        // Supports
        "Rafaela" to "none", "Estes" to "none", "Angela" to "none", "Diggie" to "none", "Floryn" to "none",
        "Mathilda" to "s2", "Kaja" to "s2", "Faramis" to "s1", "Carmilla" to "none", "Chip" to "s2",
        "Kalea" to "s2"
    )

    fun dashSlot(hero: String?): String? {
        val chosen = if (hero == null) "auto" else Cfg.heroDash(hero)
        val slot = if (chosen != "auto") chosen else MetaStore.hero(hero)?.dash ?: dashTable[hero] ?: when (role(hero)) {
            Role.FIGHTER -> "s1"
            Role.ASSASSIN, Role.TANK -> "s2"
            else -> "none"
        }
        return if (slot == "none") null else slot
    }

    fun dashSource(hero: String?): String = when {
        hero != null && Cfg.heroDash(hero) != "auto" -> "your choice"
        MetaStore.hero(hero)?.dash != null -> "online meta"
        dashTable.containsKey(hero) -> "built-in"
        else -> "role default"
    }

    fun role(name: String?): Role = name?.let { roles[it] } ?: Role.UNKNOWN

    fun wikiDamage(name: String?): String? = name?.let { Cfg.raw("herodmg_" + it.lowercase().filter { c -> c.isLetterOrDigit() }) }
    fun damageText(name: String?): String {
        val w = wikiDamage(name)?.lowercase()
        return when {
            w != null && "physical" in w && "magic" in w -> "Physical + Magic (wiki)"
            w != null && "magic" in w -> "Magic (wiki)"
            w != null && "physical" in w -> "Physical (wiki)"
            else -> (if (damage(name) == Damage.MAGIC) "Magic" else "Physical") + " (by role - read from the wiki when the Library learns it)"
        }
    }

    fun damage(name: String?): Damage {
        if (name == null) return Damage.PHYSICAL
        wikiDamage(name)?.lowercase()?.let { w ->
            val p = w.indexOf("physical"); val m = w.indexOf("magic")
            if (p >= 0 && (m < 0 || p < m)) return Damage.PHYSICAL
            if (m >= 0) return Damage.MAGIC
        }
        if (name in magicOverride) return Damage.MAGIC
        return when (role(name)) {
            Role.MAGE, Role.SUPPORT -> Damage.MAGIC
            else -> Damage.PHYSICAL
        }
    }

    val fallbackPicks: Map<Role, List<String>> = mapOf(
        Role.TANK to listOf("Tigreal", "Franco", "Akai"),
        Role.FIGHTER to listOf("Thamuz", "Balmond", "Freya"),
        Role.ASSASSIN to listOf("Saber", "Karina", "Hayabusa"),
        Role.MAGE to listOf("Eudora", "Kagura", "Nana"),
        Role.MARKSMAN to listOf("Layla", "Miya", "Bruno"),
        Role.SUPPORT to listOf("Rafaela", "Estes", "Angela")
    )

    private val ultOverride = mapOf(
        "Thamuz" to UltRule.BUFF,
        "Alucard" to UltRule.BUFF,
        "Freya" to UltRule.BUFF,
        "Zilong" to UltRule.BUFF,
        "Sun" to UltRule.BUFF,
        "Argus" to UltRule.DEFENSIVE,
        "Aulus" to UltRule.ENGAGE,
        "Leomord" to UltRule.BUFF,
        "Balmond" to UltRule.EXECUTE,
        "Dyrroth" to UltRule.EXECUTE,
        "Martis" to UltRule.EXECUTE,
        "Tigreal" to UltRule.TEAMFIGHT,
        "Atlas" to UltRule.TEAMFIGHT,
        "Khufra" to UltRule.TEAMFIGHT,
        "Minotaur" to UltRule.TEAMFIGHT,
        "Gatotkaca" to UltRule.TEAMFIGHT,
        "Belerick" to UltRule.TEAMFIGHT,
        "Carmilla" to UltRule.TEAMFIGHT,
        "Estes" to UltRule.DEFENSIVE,
        "Rafaela" to UltRule.DEFENSIVE,
        "Angela" to UltRule.DEFENSIVE,
        "Floryn" to UltRule.DEFENSIVE,
        "Diggie" to UltRule.DEFENSIVE,
        "Faramis" to UltRule.DEFENSIVE,
        "Lylia" to UltRule.DEFENSIVE,
        "Miya" to UltRule.BUFF,
        "Karrie" to UltRule.BUFF,
        "Irithel" to UltRule.BUFF,
        "Claude" to UltRule.ENGAGE,
        "Natan" to UltRule.ENGAGE,
        "Popol and Kupa" to UltRule.BUFF,
        // Complex / Transformation / Multi-phase heroes:
        "Julian" to UltRule.ENGAGE,
        "Gusion" to UltRule.ENGAGE,
        "Suyou" to UltRule.ENGAGE,
        "Sora" to UltRule.ENGAGE,
        "Ling" to UltRule.ENGAGE,
        "Fanny" to UltRule.ENGAGE,
        "Selena" to UltRule.ENGAGE,
        "Roger" to UltRule.ENGAGE,
        "Lapu-Lapu" to UltRule.ENGAGE,
        "Edith" to UltRule.ENGAGE,
        "Yu Zhong" to UltRule.ENGAGE,
        "Lukas" to UltRule.ENGAGE,
        "Yin" to UltRule.ENGAGE,
        "Hanzo" to UltRule.ENGAGE,
        "Kagura" to UltRule.ENGAGE,
        "Lunox" to UltRule.ENGAGE,
        "Chou" to UltRule.ENGAGE,
        "Paquito" to UltRule.ENGAGE,
        "Hayabusa" to UltRule.ENGAGE,
        "Lancelot" to UltRule.ENGAGE,
        "Benedetta" to UltRule.ENGAGE,
        "Joy" to UltRule.ENGAGE,
        "Nolan" to UltRule.ENGAGE,
        "Harith" to UltRule.BUFF
    )

    fun profile(hero: String?): HeroProfile = HeroPlaybook.adapt(baseProfile(hero), ultFixed = hero != null && hero in ultOverride)

    fun baseProfile(hero: String?): HeroProfile {
        val name = hero ?: "Unknown"
        val role = role(hero)
        val dmg = damage(hero)
        val cds = mapOf("s1" to 6f, "s2" to 8f, "s3" to 10f, "ult" to 35f, "spell" to 100f, "recall" to 0f, "regen" to 40f)
        val base = when (role) {
            Role.TANK -> HeroProfile(name, role, dmg, 0.16f, 0.45f, 0.22f, HeroKit.slots(hero), "s1", dashSlot(hero), UltRule.TEAMFIGHT, cds)
            Role.FIGHTER -> HeroProfile(name, role, dmg, 0.16f, 0.42f, 0.28f, HeroKit.slots(hero), "s2", dashSlot(hero), UltRule.ENGAGE, cds)
            Role.ASSASSIN -> HeroProfile(name, role, dmg, 0.16f, 0.42f, 0.35f, HeroKit.slots(hero), "s1", dashSlot(hero), UltRule.EXECUTE, cds)
            Role.MAGE -> HeroProfile(name, role, dmg, 0.36f, 0.45f, 0.40f, HeroKit.slots(hero), "s1", dashSlot(hero), UltRule.EXECUTE, cds)
            Role.MARKSMAN -> HeroProfile(name, role, dmg, 0.40f, 0.45f, 0.40f, HeroKit.slots(hero), "s1", dashSlot(hero), UltRule.EXECUTE, cds)
            Role.SUPPORT -> HeroProfile(name, role, dmg, 0.32f, 0.40f, 0.35f, HeroKit.slots(hero), "s1", dashSlot(hero), UltRule.TEAMFIGHT, cds)
            Role.UNKNOWN -> HeroProfile(name, role, dmg, 0.20f, 0.40f, 0.30f, HeroKit.slots(hero), "s1", dashSlot(hero), UltRule.ENGAGE, cds)
        }
        val ranged = isRanged(hero)
        val range = when {
            ranged && base.attackRange < 0.3f -> 0.34f
            !ranged && base.attackRange > 0.2f -> 0.16f
            else -> base.attackRange
        }
        return HeroProfile(base.name, base.role, base.damage, range, maxOf(base.engageRange, range * 1.1f), base.retreatHp,
            base.combo, base.waveClearSkill, base.escapeSkill, ultOverride[hero] ?: base.ult, base.cooldownSec, ranged)
    }

    // Basic-attack reliant heroes (Fighters, Marksmen, and Basic-Attack Mages/Tanks) that need continuous basic attack holds (v37.09)
    val basicAttackReliant = setOf(
        "Zilong", "Argus", "Thamuz", "Layla", "Miya", "Freya", "Sun", "Masha", "Aulus", "Aldous",
        "Leomord", "Roger", "Badang", "Minsitthar", "Alucard", "Bane", "Julian", "Silvanna", "Cici",
        "Bruno", "Clint", "Moskov", "Beatrix", "Brody", "Claude", "Wanwan", "Karrie", "Irithel",
        "Lesley", "Hanabi", "Melissa", "Natan", "Ixia", "Popol and Kupa", "Kimmy", "Obsidia",
        "Zhask", "Edith", "Harith", "Chang'e", "Belerick", "Yi Sun-shin", "Natalia", "Helcurt", "Karina"
    )

    fun isBasicAttackReliant(hero: String?): Boolean =
        hero != null && (hero in basicAttackReliant || role(hero) == Role.MARKSMAN)

    private val rangedExtra = setOf("Estes", "Rafaela", "Angela", "Floryn", "Faramis", "Diggie", "Chip", "Yi Sun-shin", "Kimmy", "Natan", "Cici", "Bane")
    private val meleeExtra = setOf("Esmeralda", "Alice", "Guinevere", "Silvanna", "Kadita", "Karina", "Gusion", "Joy", "Aamon", "Julian", "Kaja", "Kalea")

    // Dynamic ranged/melee check: adapts in real time when Roger, Selena, or Edith transforms!
    fun isRanged(hero: String?): Boolean {
        if (hero != null) when (Cfg.heroRange(hero)) { "ranged" -> return true; "melee" -> return false }
        HeroKit.rangedNow(hero)?.let { return it }
        if (hero in rangedExtra) return true
        if (hero in meleeExtra) return false
        return role(hero) == Role.MARKSMAN || role(hero) == Role.MAGE
    }

    fun rangeSource(hero: String?): String = if (hero != null && Cfg.heroRange(hero) != "auto") "your choice" else "auto, from the role / form"

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + min(prev, min(dp[j], dp[j - 1]))
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

object BuildBook {
    val defaults: Map<Role, String> = mapOf(
        Role.FIGHTER to "Warrior Boots, War Axe, Hunter Strike, Queen's Wings, Brute Force Breastplate, Immortality",
        Role.TANK to "Tough Boots, Dominance Ice, Antique Cuirass, Guardian Helmet, Radiant Armor, Immortality",
        Role.ASSASSIN to "Warrior Boots, Hunter Strike, Blade of Despair, Malefic Roar, Queen's Wings, Immortality",
        Role.MAGE to "Arcane Boots, Enchanted Talisman, Lightning Truncheon, Holy Crystal, Divine Glaive, Blood Wings",
        Role.MARKSMAN to "Swift Boots, Windtalker, Berserker's Fury, Haas' Claws, Blade of Despair, Malefic Roar",
        Role.SUPPORT to "Tough Boots, Fleeting Time, Oracle, Dominance Ice, Radiant Armor, Immortality",
        Role.UNKNOWN to "Warrior Boots, War Axe, Dominance Ice, Radiant Armor, Brute Force Breastplate, Immortality"
    )

    fun primary(role: Role): List<String> {
        val raw = Cfg.roleBuild(role.name, defaults[role] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { ItemDb.canonical(it) }.filter { ItemClass.isAdvanced(it) || it in ItemDb.retributions }
        return if (role == Role.TANK) raw.filter { ItemClass.isDefensiveOrBoots(it) }.ifEmpty {
            defaults[Role.TANK]!!.split(",").map { it.trim() }
        } else raw
    }

    fun forHero(hero: String?, candidates: List<String>? = null): List<String> {
        val role = HeroDb.role(hero)
        val dmg = HeroDb.damage(hero)
        val base = (candidates?.takeIf { it.isNotEmpty() } ?: primary(role))
            .mapNotNull { ItemDb.canonical(it) }
            .filter { ItemClass.isAdvanced(it) || it in ItemDb.retributions }
        val filtered = if (role == Role.TANK) {
            base.filter { ItemClass.fitsTank(it, dmg) }
        } else {
            base.filter { ItemClass.fitsStrict(it, dmg) }
        }.distinct()
        if (filtered.size >= 6) return filtered.take(6)
        val fallbackPool = if (role == Role.TANK) {
            listOf("Tough Boots", "Dominance Ice", "Antique Cuirass", "Guardian Helmet", "Radiant Armor", "Immortality", "Thunder Belt", "Oracle", "Cursed Helmet", "Blade Armor")
        } else if (dmg == Damage.MAGIC) {
            listOf("Arcane Boots", "Enchanted Talisman", "Lightning Truncheon", "Holy Crystal", "Divine Glaive", "Blood Wings", "Glowing Wand", "Genius Wand")
        } else {
            listOf("Warrior Boots", "War Axe", "Hunter Strike", "Blade of Despair", "Malefic Roar", "Immortality", "Queen's Wings", "Brute Force Breastplate")
        }
        val out = ArrayList(filtered)
        for (fb in fallbackPool) {
            if (out.size >= 6) break
            if (fb in out) continue
            if (fb.contains("Boots") && out.any { it.contains("Boots") || it.contains("Shoes") }) continue
            out.add(fb)
        }
        return out.take(6)
    }

    fun outdated(role: Role): List<String> =
        ItemDb.outdated(Cfg.roleBuild(role.name, defaults[role] ?: "").split(",").map { it.trim() })
}

object CounterRules {
    class Pick(val item: String, val reason: String)

    fun counters(myHero: String?, enemies: List<String>): List<Pick> {
        if (enemies.isEmpty()) return emptyList()
        val myDmg = HeroDb.damage(myHero)
        val myRole = HeroDb.role(myHero)
        val magic = enemies.count { HeroDb.damage(it) == Damage.MAGIC }
        val physical = enemies.size - magic
        val tanks = enemies.count { HeroDb.role(it) == Role.TANK }
        val burst = enemies.count { HeroDb.role(it) == Role.ASSASSIN }
        val healers = enemies.filter { it in HeroDb.sustain }
        val out = ArrayList<Pick>()
        if (magic >= 2) {
            out.add(Pick("Radiant Armor", "$magic magic-damage enemies"))
        }
        if (physical >= 3) {
            out.add(Pick("Antique Cuirass", "$physical physical-damage enemies"))
            out.add(Pick("Dominance Ice", "armour and slows their attack speed"))
        }
        if (healers.isNotEmpty()) {
            val anti = when {
                myRole == Role.TANK || myRole == Role.SUPPORT -> "Dominance Ice"
                myDmg == Damage.MAGIC -> null
                else -> "Sea Halberd"
            }
            if (anti != null) out.add(Pick(anti, "anti-heal vs ${healers.joinToString()}"))
        }
        if (tanks >= 2 && myRole != Role.TANK && myRole != Role.SUPPORT) {
            out.add(Pick(if (myDmg == Damage.MAGIC) "Divine Glaive" else "Malefic Roar", "$tanks tanks: penetration"))
        }
        if (burst >= 2) {
            out.add(Pick("Immortality", "$burst assassins: second life"))
            out.add(Pick("Winter Crown", "freeze to survive burst (if it fits your build)"))
        }
        return out.filter { ItemDb.isCurrent(it.item) && ItemClass.isAdvanced(it.item) }.distinctBy { it.item }
    }
}
