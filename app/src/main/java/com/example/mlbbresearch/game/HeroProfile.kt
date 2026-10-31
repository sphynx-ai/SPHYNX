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

// How the tactical engine plays a hero. Ranges are in screen-height units.
// Cooldowns are rough estimates; the app also tracks when it last used each skill.
class HeroProfile(
    val name: String,
    val role: Role,
    val damage: Damage,
    val attackRange: Float,
    val engageRange: Float,
    val retreatHp: Float,
    val combo: List<String>,          // skill order when fighting
    val waveClearSkill: String?,      // skill used on a minion wave
    val escapeSkill: String?,         // skill used to run away
    val ult: UltRule,
    val cooldownSec: Map<String, Float>,
    val ranged: Boolean = false       // attacks from a distance (can poke heroes and keep away)
)

object HeroDb {

    private val roles: Map<String, Role> = buildMap {
        fun add(r: Role, vararg names: String) = names.forEach { put(it, r) }
        add(Role.TANK, "Tigreal", "Franco", "Akai", "Johnson", "Grock", "Hylos", "Uranus", "Belerick", "Khufra",
            "Atlas", "Baxia", "Lolita", "Minotaur", "Gatotkaca", "Barats", "Edith", "Fredrinn", "Gloo")
        add(Role.FIGHTER, "Thamuz", "Balmond", "Alucard", "Chou", "Freya", "Zilong", "Sun", "Martis", "Paquito",
            "Yu Zhong", "Terizla", "X.Borg", "Dyrroth", "Badang", "Aldous", "Leomord", "Lapu-Lapu", "Masha",
            "Silvanna", "Khaleed", "Arlott", "Yin", "Cici", "Ruby", "Argus", "Jawhead", "Hilda", "Guinevere", "Aulus")
        add(Role.ASSASSIN, "Saber", "Karina", "Fanny", "Hayabusa", "Natalia", "Lancelot", "Gusion", "Helcurt",
            "Ling", "Benedetta", "Aamon", "Joy", "Nolan", "Hanzo", "Yi Sun-shin", "Selena", "Suyou")
        add(Role.MAGE, "Eudora", "Alice", "Nana", "Kagura", "Cyclops", "Aurora", "Vale", "Lunox", "Harley",
            "Odette", "Zhask", "Pharsa", "Kadita", "Cecilion", "Luo Yi", "Yve", "Valir", "Chang'e", "Xavier",
            "Novaria", "Lylia", "Vexana", "Zhuxin", "Esmeralda", "Gord")
        add(Role.MARKSMAN, "Layla", "Miya", "Bruno", "Clint", "Granger", "Moskov", "Beatrix", "Brody", "Claude",
            "Wanwan", "Karrie", "Irithel", "Lesley", "Hanabi", "Melissa", "Natan", "Ixia", "Popol and Kupa")
        add(Role.SUPPORT, "Rafaela", "Estes", "Angela", "Diggie", "Floryn", "Mathilda", "Kaja", "Faramis",
            "Carmilla", "Chip")
    }

    private val magicOverride = setOf("Karina", "Gusion", "Selena", "Natan", "Esmeralda")

    // Heroes that heal or sustain a lot (anti-heal items help against them)
    val sustain = setOf("Estes", "Rafaela", "Angela", "Floryn", "Alucard", "Uranus", "Esmeralda", "Ruby",
        "Yu Zhong", "Thamuz", "Lapu-Lapu")

    val allNames: List<String> get() = roles.keys.sorted()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val byNorm = roles.keys.associateBy { norm(it) }

    // Turns whatever Gemma wrote ("thamuz", "Thamus", "X Borg") into a real hero name, or null
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

    // The skill that dashes / leaps / blinks, used to chase and to escape. Common picks below
    // (check them); others use a role default; you can set it per hero on the Player page.
    private val dashTable = mapOf(
        "Thamuz" to "s2", "Saber" to "s2", "Gusion" to "s2", "Lancelot" to "s1", "Alucard" to "s1",
        "Chou" to "s2", "Zilong" to "s2", "Hayabusa" to "s2", "Fanny" to "s1", "Ling" to "s1",
        "Karina" to "ult", "Natalia" to "s1", "Balmond" to "s1", "Freya" to "s1", "Tigreal" to "s2",
        "Akai" to "s1", "Brody" to "s2", "Granger" to "s2", "Lesley" to "s1", "Clint" to "s2",
        "Miya" to "ult", "Benedetta" to "s2", "Paquito" to "s1", "Aldous" to "ult", "Martis" to "s2",
        "Ruby" to "s2", "Yu Zhong" to "s2", "Khufra" to "s1", "Franco" to "none", "Layla" to "none",
        "Eudora" to "none", "Nana" to "none", "Vexana" to "none", "Badang" to "none", "Sun" to "none",
        "Masha" to "s2", "Kagura" to "s2", "Harley" to "s2", "Wanwan" to "s1", "Beatrix" to "none"
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

    fun damage(name: String?): Damage {
        if (name == null) return Damage.PHYSICAL
        if (name in magicOverride) return Damage.MAGIC
        return when (role(name)) {
            Role.MAGE, Role.SUPPORT -> Damage.MAGIC
            else -> Damage.PHYSICAL
        }
    }

    // Safe, well-known picks per role, used when the AI's own suggestion is not a real hero
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
        "Balmond" to UltRule.EXECUTE,
        "Tigreal" to UltRule.TEAMFIGHT,
        "Estes" to UltRule.DEFENSIVE,
        "Rafaela" to UltRule.DEFENSIVE,
        "Angela" to UltRule.DEFENSIVE,
        "Floryn" to UltRule.DEFENSIVE
    )

    fun profile(hero: String?): HeroProfile {
        val name = hero ?: "Unknown"
        val role = role(hero)
        val dmg = damage(hero)
        val cds = mapOf("s1" to 6f, "s2" to 8f, "ult" to 35f, "spell" to 100f, "recall" to 0f, "regen" to 40f)
        val base = when (role) {
            Role.TANK -> HeroProfile(name, role, dmg, 0.16f, 0.45f, 0.22f, listOf("s1", "s2", "ult"), "s1", null, UltRule.TEAMFIGHT, cds)
            Role.FIGHTER -> HeroProfile(name, role, dmg, 0.16f, 0.42f, 0.28f, listOf("s1", "s2", "ult"), "s2", null, UltRule.ENGAGE, cds)
            Role.ASSASSIN -> HeroProfile(name, role, dmg, 0.16f, 0.40f, 0.35f, listOf("s1", "s2", "ult"), "s1", "s2", UltRule.EXECUTE, cds)
            Role.MAGE -> HeroProfile(name, role, dmg, 0.36f, 0.45f, 0.40f, listOf("s1", "s2", "ult"), "s1", null, UltRule.EXECUTE, cds)
            Role.MARKSMAN -> HeroProfile(name, role, dmg, 0.40f, 0.45f, 0.40f, listOf("s1", "s2", "ult"), "s1", null, UltRule.EXECUTE, cds)
            Role.SUPPORT -> HeroProfile(name, role, dmg, 0.32f, 0.40f, 0.35f, listOf("s1", "s2", "ult"), null, null, UltRule.TEAMFIGHT, cds)
            Role.UNKNOWN -> HeroProfile(name, role, dmg, 0.20f, 0.40f, 0.30f, listOf("s1", "s2", "ult"), "s1", null, UltRule.ENGAGE, cds)
        }
        // melee (close range) or ranged decides the attack range and how heroes are fought
        val ranged = isRanged(hero)
        val range = when {
            ranged && base.attackRange < 0.3f -> 0.32f       // a ranged hero of a melee role
            !ranged && base.attackRange > 0.2f -> 0.16f      // a melee hero of a ranged role
            else -> base.attackRange
        }
        return HeroProfile(base.name, base.role, base.damage, range, maxOf(base.engageRange, range * 1.1f), base.retreatHp,
            base.combo, base.waveClearSkill, base.escapeSkill, ultOverride[hero] ?: base.ult, base.cooldownSec, ranged)
    }

    // Ranged = marksmen, mages and these; everyone else fights up close (melee).
    private val rangedExtra = setOf("Estes", "Rafaela", "Angela", "Floryn", "Faramis", "Diggie", "Chip", "Yi Sun-shin", "Kimmy", "Natan")
    private val meleeExtra = setOf<String>()

    fun isRanged(hero: String?): Boolean {
        if (hero != null) when (Cfg.heroRange(hero)) { "ranged" -> return true; "melee" -> return false }
        if (hero in rangedExtra) return true
        if (hero in meleeExtra) return false
        return role(hero) == Role.MARKSMAN || role(hero) == Role.MAGE
    }

    fun rangeSource(hero: String?): String = if (hero != null && Cfg.heroRange(hero) != "auto") "your choice" else "auto, from the role"

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

// Primary builds per role. Editable on the Build page; check them against the current patch.
object BuildBook {
    val defaults: Map<Role, String> = mapOf(
        Role.FIGHTER to "Warrior Boots, War Axe, Hunter Strike, Queen's Wings, Brute Force Breastplate, Immortality",
        Role.TANK to "Tough Boots, Dominance Ice, Antique Cuirass, Athena's Shield, Radiant Armor, Immortality",
        Role.ASSASSIN to "Warrior Boots, Hunter Strike, Blade of Despair, Malefic Roar, Queen's Wings, Immortality",
        Role.MAGE to "Arcane Boots, Enchanted Talisman, Lightning Truncheon, Holy Crystal, Divine Glaive, Blood Wings",
        Role.MARKSMAN to "Swift Boots, Windtalker, Berserker's Fury, Haas's Claws, Blade of Despair, Malefic Roar",
        Role.SUPPORT to "Tough Boots, Fleeting Time, Oracle, Dominance Ice, Athena's Shield, Immortality",
        Role.UNKNOWN to "Warrior Boots, War Axe, Dominance Ice, Athena's Shield, Brute Force Breastplate, Immortality"
    )

    fun primary(role: Role): List<String> =
        Cfg.roleBuild(role.name, defaults[role] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

// Secondary (counter) items picked from the enemy team's make-up
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
            out.add(Pick("Athena's Shield", "$magic magic-damage enemies (burst)"))
            out.add(Pick("Radiant Armor", "sustained magic damage"))
        }
        if (physical >= 3) {
            out.add(Pick("Antique Cuirass", "$physical physical-damage enemies"))
            out.add(Pick("Dominance Ice", "armour and slows their attack speed"))
        }
        if (healers.isNotEmpty()) {
            val anti = when {
                myRole == Role.TANK || myRole == Role.SUPPORT -> "Dominance Ice"
                myDmg == Damage.MAGIC -> "Necklace of Durance"
                else -> "Sea Halberd"
            }
            out.add(Pick(anti, "anti-heal vs ${healers.joinToString()}"))
        }
        if (tanks >= 2 && myRole != Role.TANK && myRole != Role.SUPPORT) {
            out.add(Pick(if (myDmg == Damage.MAGIC) "Divine Glaive" else "Malefic Roar", "$tanks tanks: penetration"))
        }
        if (burst >= 2) {
            out.add(Pick("Immortality", "$burst assassins: second life"))
            out.add(Pick("Winter Crown", "freeze to survive burst (if it fits your build)"))
        }
        return out.distinctBy { it.item }
    }
}
