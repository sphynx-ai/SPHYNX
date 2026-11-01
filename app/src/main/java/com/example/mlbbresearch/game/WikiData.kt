package com.example.mlbbresearch.game

// GAME KNOWLEDGE from the Mobile Legends wiki (pages saved by you: Battlefield Guide, Jungle,
// Battle spells, Equipment, EXP / Gold / Mid Laner, Roamer). Only what is current: removed
// items, spells and monsters are left out. The jungle monsters are in JungleDb.

// ---- equipment: every item the wiki lists as current (names exactly as in the game) ----
object ItemDb {
    val current: List<String> = listOf(
        "Active - Conceal", "Allow Throw", "Antique Cuirass", "Arcane Boots", "Ares Belt", "Azure Blade",
        "Berserker's Fury", "Black Ice Shield", "Blade Armor", "Blade of Despair", "Blade of the Heptaseas", "Blood Wings",
        "Bloody Retribution", "Book of Sages", "Boots", "Broken Heart", "Brute Force Breastplate", "Chastise Pauldron",
        "Clock of Destiny", "Concentrated Energy", "Corrosion Scythe", "Cursed Helmet", "Dagger", "Demon Hunter Sword",
        "Demon Shoes", "Divine Glaive", "Dominance Ice", "Dreadnaught Armor", "Elegant Gem", "Enchanted Talisman",
        "Endless Battle", "Exotic Veil", "Expert Gloves", "Feather of Heaven", "Flame Retribution", "Flask of the Oasis",
        "Fleeting Time", "Flower of Hope", "Fury Hammer", "Genius Wand", "Glowing Wand", "Golden Staff",
        "Great Dragon Spear", "Guardian Helmet", "Haas' Claws", "Healing Necklace", "Hero's Ring", "Holy Crystal",
        "Hunter Strike", "Ice Queen Wand", "Ice Retribution", "Immortality", "Iron Hunting Bow", "Javelin",
        "Knife", "Lantern of Hope", "Leather Jerkin", "Legion Sword", "Lightning Truncheon", "Magic Blade",
        "Magic Necklace", "Magic Potion", "Magic Resist Cloak", "Magic Shoes", "Magic Wand", "Malefic Gun",
        "Malefic Roar", "Molten Essence", "Mystery Codex", "Mystic Container", "Ogre Tomahawk", "Oracle",
        "Passive - Dire Hit", "Passive - Encourage", "Passive - Favor", "Power Crystal", "Power Potion", "Queen's Wings",
        "Radiant Armor", "Rapid Boots", "Regular Spear", "Resonating Heart", "Rock Potion", "Rogue Meteor",
        "Rose Gold Meteor", "Sea Halberd", "Silence Robe", "Sky Piercer", "Starlium Scythe", "Steel Legplates",
        "Swift Boots", "Swift Crossbow", "Throw Forbidden", "Thunder Belt", "Tome of Evil", "Tough Boots",
        "Valkyrie's Shield", "Vampire Mallet", "Vitality Crystal", "War Axe", "Warrior Boots", "Wind of Nature",
        "Windtalker", "Winter Crown", "Wishing Lantern"
    )
    private fun norm(s: String) = s.lowercase().filter { it.isLetter() }
    private val keys = current.map { norm(it) }.toSet()

    fun isCurrent(item: String) = norm(item) in keys
    // the item's exact current name (e.g. "Haas's Claws" -> "Haas' Claws"), null if not current
    fun canonical(item: String): String? = current.firstOrNull { norm(it) == norm(item) }
    fun outdated(items: List<String>) = items.filter { it.isNotBlank() && !isCurrent(it) }
}

// ---- battle spells: cooldowns (seconds) and what they do ----
object SpellInfo {
    val cooldown = mapOf(
        "Execute" to 90f, "Retribution" to 35f, "Inspire" to 75f, "Sprint" to 100f, "Revitalize" to 100f,
        "Aegis" to 75f, "Petrify" to 75f, "Purify" to 90f, "Flameshot" to 50f, "Flicker" to 120f,
        "Arrival" to 75f, "Vengeance" to 75f
    )
    val effect = mapOf(
        "Execute" to "100 (+10 x level) + 13% of the target's lost HP as true damage; a kill cuts its cooldown by 40%",
        "Retribution" to "520 (+80 x level) true damage to creeps and minions; jungle rewards +60%, minion rewards -70% for the first 5 minutes",
        "Inspire" to "faster basic attacks (up to 6 in 5 s) that heal",
        "Sprint" to "up to 50% movement speed and slow immunity for 6 s",
        "Revitalize" to "a healing spring (up to 25% max HP over 5 s)",
        "Aegis" to "a shield for you and the weakest ally nearby for 5 s",
        "Petrify" to "petrifies nearby enemies for 0.8 s, then slows them",
        "Purify" to "removes all negative effects; control immunity for 1.2 s",
        "Flameshot" to "a long-range magic shot that slows and knocks back nearby enemies",
        "Flicker" to "a short blink in the chosen direction (distance 3.8)",
        "Arrival" to "teleports to an allied turret, base or minion after 3 s",
        "Vengeance" to "35% damage reduction and reflects damage for 3 s"
    )
    // Execute's damage as a share of a target's max HP is unknown (HP numbers are not read), so it
    // is used on low targets: 13% of the lost HP + the flat part finish heroes below about 25%
    const val EXECUTE_BELOW = 0.25f
}

// ---- the match timeline (Battlefield Guide: Battlefield Events) ----
object MatchEvents {
    class Event(val sec: Int, val what: String)
    val ALL = listOf(
        Event(30, "Purple and Orange Buffs spawn (respawn 2:00 after each kill)"),
        Event(120, "first Turtle; the jungler's extra damage reduction ends; Crab upgraded"),
        Event(300, "outer turrets' energy shields drop; side-lane minion bonus ends; the jungler's minion penalty ends"),
        Event(480, "first Lord; inner turrets' damage reduction ends; the roamer shares minion / creep rewards"),
        Event(720, "Lord enhanced; inhibitor turrets' damage reduction ends; minions much faster"),
        Event(1080, "Lord evolved; death timers much longer")
    )
    fun next(sec: Int): Event? = if (sec < 0) null else ALL.firstOrNull { it.sec > sec }
    fun status(sec: Int): String {
        val n = next(sec) ?: return "late game: Lord evolved, long death timers"
        val left = n.sec - sec
        return "next at %d:%02d (in %d:%02d): %s".format(n.sec / 60, n.sec % 60, left / 60, left % 60, n.what)
    }
    // pushing these turrets before the time is slow (they take less damage)
    fun outerShielded(sec: Int) = sec in 0 until 300
    fun innerReduced(sec: Int) = sec in 0 until 480
}

// ---- the wiki's laning recommendations (who plays where, and what kind of hero they are) ----
object WikiLanes {
    // EXP laners: utility (tough + crowd control), hybrid (damage + sustain), damage (squishy)
    val expUtility = setOf("Arlott", "Balmond", "Barats", "Chou", "Edith", "Esmeralda", "Gatotkaca", "Guinevere", "Gloo",
        "Hilda", "Hylos", "Jawhead", "Ruby", "Silvanna", "Terizla", "Uranus")
    val expHybrid = setOf("Aldous", "Alice", "Alpha", "Aulus", "Badang", "Bane", "Cici", "Dyrroth", "Freya", "Lapu-Lapu",
        "Lukas", "Masha", "Paquito", "Phoveus", "Sora", "Thamuz", "X.Borg", "Yu Zhong")
    val expDamage = setOf("Argus", "Benedetta", "Sun", "Yin", "Zilong")
    val expUnofficial = setOf("Hylos", "Baxia", "Fredrinn", "Barats", "Zhuxin", "Joy", "Suyou", "Belerick", "Valir", "Akai", "Atlas", "Minotaur")
    val expEarly = setOf("Cici", "Hilda", "Terizla", "Thamuz", "Yu Zhong", "Freya", "X.Borg", "Dyrroth", "Alpha")
    val expLate = setOf("Zilong", "Sun", "Aldous", "Argus")

    // Gold laners: late-game carries, early burst, magic damage (+ non-marksmen listed by the wiki)
    val goldLate = setOf("Layla", "Miya", "Hanabi", "Claude", "Melissa", "Ixia", "Karrie", "Moskov", "Wanwan", "Lesley", "Obsidia")
    val goldBurst = setOf("Granger", "Brody", "Clint", "Irithel", "Beatrix", "Popol and Kupa", "Bruno")
    val goldMagic = setOf("Kimmy", "Natan", "Harith")

    // Mid laners: utility, hybrid, damage, high dive
    val midUtility = setOf("Aurora", "Eudora", "Faramis", "Vale", "Valentina", "Vexana", "Zhuxin", "Angela", "Mathilda", "Terizla", "Lapu-Lapu")
    val midHybrid = setOf("Luo Yi", "Valir", "Kimmy", "Nana", "Yve", "Zhask", "Zetian", "Bane", "Phoveus")
    val midDamage = setOf("Cecilion", "Chang'e", "Cyclops", "Gord", "Lunox", "Lylia", "Novaria", "Odette", "Pharsa", "Xavier", "Alice", "Alpha", "Natan")
    val midDive = setOf("Gusion", "Harley", "Kagura", "Kadita", "Selena", "Guinevere", "Joy", "Harith", "Julian", "Chou")

    // Roamers: setters (AoE crowd control, tanks), initiators (pick one or two), utility / healing, damage
    val roamSetter = setOf("Atlas", "Belerick", "Carmilla", "Gatotkaca", "Johnson", "Khaleed", "Khufra", "Marcel", "Minotaur", "Tigreal")
    val roamInitiator = setOf("Akai", "Badang", "Chip", "Chou", "Edith", "Franco", "Gloo", "Grock", "Hilda", "Hylos", "Jawhead",
        "Kaja", "Kalea", "Minsitthar", "Arlott", "Kadita", "Valir", "Novaria")
    val roamUtility = setOf("Angela", "Diggie", "Estes", "Floryn", "Lolita", "Mathilda", "Rafaela")
    val roamDamage = setOf("Helcurt", "Natalia", "Saber", "Selena")

    private val exp = expUtility + expHybrid + expDamage
    private val gold = goldLate + goldBurst + goldMagic
    private val mid = midUtility + midHybrid + midDamage + midDive
    private val roam = roamSetter + roamInitiator + roamUtility + roamDamage

    private fun has(set: Set<String>, hero: String?) = hero != null && set.any { it.equals(hero, ignoreCase = true) }

    // the wiki's lane for a hero (official lists first; null = not listed)
    fun lane(hero: String?): Lane? = when {
        has(gold, hero) -> Lane.GOLD
        has(roamSetter + roamUtility, hero) -> Lane.ROAM
        has(midUtility + midHybrid + midDamage, hero) -> Lane.MID
        has(exp, hero) -> Lane.EXP
        has(roam, hero) -> Lane.ROAM
        has(mid, hero) -> Lane.MID
        else -> null
    }
    // a roamer who starts fights (tank setters and initiators) / one who protects (utility)
    fun engager(hero: String?) = has(roamSetter + roamInitiator + expUtility, hero)
    fun protector(hero: String?) = has(roamUtility, hero)
    fun lateGame(hero: String?) = has(goldLate + expLate, hero)
    fun earlyGame(hero: String?) = has(goldBurst + expEarly, hero)

    fun describe(hero: String?): String {
        if (hero == null) return "-"
        val tags = ArrayList<String>()
        if (has(expUtility, hero)) tags.add("utility EXP")
        if (has(expHybrid, hero)) tags.add("hybrid EXP")
        if (has(expDamage, hero)) tags.add("damage EXP")
        if (has(goldLate, hero)) tags.add("late-game gold")
        if (has(goldBurst, hero)) tags.add("burst gold")
        if (has(goldMagic, hero)) tags.add("magic gold")
        if (has(midUtility, hero)) tags.add("utility mid")
        if (has(midHybrid, hero)) tags.add("hybrid mid")
        if (has(midDamage, hero)) tags.add("damage mid")
        if (has(midDive, hero)) tags.add("high-dive mid")
        if (has(roamSetter, hero)) tags.add("setter roamer")
        if (has(roamInitiator, hero)) tags.add("initiator roamer")
        if (has(roamUtility, hero)) tags.add("utility roamer")
        if (has(roamDamage, hero)) tags.add("damage roamer")
        if (has(expEarly, hero)) tags.add("strong early")
        if (has(expLate, hero)) tags.add("late-game carry")
        return if (tags.isEmpty()) "not in the wiki's lane lists" else tags.joinToString()
    }
}

// ---- role tips from the wiki, used in the strategy prompt and the status ----
object RoleTips {
    fun of(lane: Lane, sec: Int): String = when (lane) {
        Lane.EXP -> when {
            sec in 0 until 120 -> "reach level 4 before the first Turtle (2:00, next to the EXP lane); EXP-lane minions give 25% more EXP until 5:00"
            sec in 120 until 300 -> "after clearing the lane, help the mid laner; do not die before Turtle fights; zone or pick off the enemy jungler at the Turtle"
            else -> "give the EXP lane to the jungler and fight / push with the mid laner"
        }
        Lane.GOLD -> when {
            sec in 0 until 120 -> "farm minions; around 1:15-1:30 the enemy jungler finishes clearing: do not show yourself"
            sec in 120 until 300 -> "keep farming the gold lane; rotate to mid only while both teams fight at the Turtle, then come back"
            else -> "farm any lane with minions or small jungle camps; never die - hide in the bush between your first and second turret when enemies are missing"
        }
        Lane.MID -> if (sec < 300) "clear waves fast, then rotate with the roamer to gank and to the Turtle" else "rotate with the roamer, pick off enemies, group for objectives"
        Lane.ROAM -> if (sec < 480) "give vision (check bushes), protect the jungler's first buff, roam with the mid laner" else "lead teamfights; from 8:00 your farm shares minion / creep rewards"
        Lane.JUNGLE -> when {
            sec in 0 until 300 -> "clear buffs and camps; with Retribution, minions give 70% less until 5:00 (do not take lane waves); secure Turtles"
            else -> "take Orange / Purple Buffs and Turtles, farm minions in any lane, keep Retribution for the Lord"
        }
    }
}
