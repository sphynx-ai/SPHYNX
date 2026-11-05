package com.example.mlbbresearch.game

import com.example.mlbbresearch.core.Cfg
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// EQUIPMENT TIERS AND STAT TYPES (v36.73, your rules):
//  * TIER: Basic / Intermediate / Advanced (the finished items; the 7 boots count as Advanced) - and
//    potions (consumables). Builds - the Build page, the role builds, the online meta's and the
//    EXPERIMENTAL builds - use ADVANCED equipment only.
//  * STAT TYPES: Attack (Physical Attack), Magic (Magic Power), Adaptive Attack (fits physical and magic
//    heroes), Physical Defense, Magic Defense, Neutral Defense (both defenses or flat HP) and Movement.
//  * Where it comes from, best first: the item's wiki page (read on the phone, no tokens), the
//    built-in table below, then the stats the Library learned.
//  * Roaming blessings (Conceal / Encourage / Favor / Dire Hit) are NOT equipment: they come with any
//    boots when you roam. Conceal is an active skill (a button), the other three passives.
object ItemClass {

    enum class Tier(val label: String) { BASIC("Basic"), INTERMEDIATE("Intermediate"), ADVANCED("Advanced"), CONSUMABLE("Consumable") }
    enum class Kind(val label: String) {
        ATTACK("Attack (Physical Attack)"), MAGIC("Magic (Magic Power)"), ADAPTIVE("Adaptive Attack"),
        PHYS_DEF("Physical Defense"), MAGIC_DEF("Magic Defense"), NEUTRAL_DEF("Neutral Defense (HP / both)"), MOVEMENT("Movement")
    }

    class Info(val tier: Tier?, val kinds: Set<Kind>, val source: String, val price: Int = -1)

    private val A = Kind.ATTACK; private val M = Kind.MAGIC; private val AD = Kind.ADAPTIVE
    private val PD = Kind.PHYS_DEF; private val MD = Kind.MAGIC_DEF; private val ND = Kind.NEUTRAL_DEF; private val MV = Kind.MOVEMENT
    private val adv = Tier.ADVANCED; private val mid = Tier.INTERMEDIATE; private val low = Tier.BASIC

    // the built-in table: TIERS exactly as the wiki's "List of equipment" (v36.75, from your saved
    // Flame Retribution page: per category one row per tier); types: the category plus what the stats
    // say (empty = taken from the item's wiki stats / the Library)
    private val table: Map<String, Pair<Tier, Set<Kind>>> = mapOf(
        // ---- Attack ----
        "Dagger" to (low to setOf(A)), "Knife" to (low to setOf(A)), "Javelin" to (low to setOf(A)), "Vampire Mallet" to (low to setOf(A)),
        "Iron Hunting Bow" to (low to setOf(A)), "Expert Gloves" to (low to setOf(AD)),
        "Legion Sword" to (mid to setOf(A)), "Regular Spear" to (mid to setOf(A)), "Ogre Tomahawk" to (mid to setOf(A, ND)), "Rogue Meteor" to (mid to setOf(A)),
        "Fury Hammer" to (mid to setOf(A)), "Magic Blade" to (mid to setOf(A)), "Swift Crossbow" to (mid to setOf(A)),
        "Demon Hunter Sword" to (adv to setOf(A)), "Corrosion Scythe" to (adv to setOf(A)), "Golden Staff" to (adv to setOf(A)),
        "Wind of Nature" to (adv to setOf(A)), "War Axe" to (adv to setOf(A, ND)), "Sky Piercer" to (adv to setOf(AD)),
        "Malefic Roar" to (adv to setOf(A)), "Haas' Claws" to (adv to setOf(A)), "Berserker's Fury" to (adv to setOf(A)),
        "Endless Battle" to (adv to setOf(A, ND)), "Windtalker" to (adv to setOf(A)), "Blade of the Heptaseas" to (adv to setOf(A, ND)),
        "Blade of Despair" to (adv to setOf(A, MV)), "Hunter Strike" to (adv to setOf(A)), "Rose Gold Meteor" to (adv to setOf(A, MD)),
        "Sea Halberd" to (adv to setOf(A)), "Great Dragon Spear" to (adv to setOf(A)), "Malefic Gun" to (adv to setOf(A)),
        // ---- Magic ----
        "Mystery Codex" to (low to setOf(M)), "Power Crystal" to (low to setOf(M)), "Magic Necklace" to (low to setOf(M)),
        "Book of Sages" to (low to setOf(M)), "Flower of Hope" to (low to setOf(M)), "Lantern of Hope" to (low to setOf(M)),
        "Mystic Container" to (mid to setOf(M)), "Magic Wand" to (mid to setOf(M)), "Tome of Evil" to (mid to setOf(M)),
        "Azure Blade" to (mid to setOf(M)), "Exotic Veil" to (mid to setOf(M)), "Elegant Gem" to (mid to setOf(M)),
        "Enchanted Talisman" to (adv to setOf(M)), "Feather of Heaven" to (adv to setOf(M)), "Fleeting Time" to (adv to setOf(M)),
        "Winter Crown" to (adv to setOf(M, PD)), "Clock of Destiny" to (adv to setOf(M, ND)), "Divine Glaive" to (adv to setOf(M)),
        "Holy Crystal" to (adv to setOf(M)), "Concentrated Energy" to (adv to setOf(M, ND)), "Ice Queen Wand" to (adv to setOf(M)),
        "Glowing Wand" to (adv to setOf(M)), "Starlium Scythe" to (adv to setOf(M)), "Blood Wings" to (adv to setOf(M, ND)),
        "Lightning Truncheon" to (adv to setOf(M)), "Genius Wand" to (adv to setOf(M)), "Flask of the Oasis" to (adv to setOf(M)),
        "Wishing Lantern" to (adv to setOf(M)),
        // ---- Defense ----
        "Vitality Crystal" to (low to setOf(ND)), "Leather Jerkin" to (low to setOf(PD)), "Magic Resist Cloak" to (low to setOf(MD)),
        "Healing Necklace" to (low to setOf(ND)), "Hero's Ring" to (low to setOf(MD)),
        "Ares Belt" to (mid to setOf(ND)), "Molten Essence" to (mid to setOf(ND)), "Silence Robe" to (mid to emptySet()),
        "Black Ice Shield" to (mid to setOf(PD)), "Dreadnaught Armor" to (mid to emptySet()), "Steel Legplates" to (mid to setOf(PD)),
        "Blade Armor" to (adv to setOf(PD)), "Queen's Wings" to (adv to setOf(ND)), "Thunder Belt" to (adv to setOf(ND, PD)),
        "Cursed Helmet" to (adv to setOf(MD, ND)), "Guardian Helmet" to (adv to setOf(ND)), "Antique Cuirass" to (adv to setOf(PD, ND)),
        "Oracle" to (adv to setOf(ND, MD)), "Valkyrie's Shield" to (adv to emptySet()), "Dominance Ice" to (adv to setOf(ND, PD)),
        "Immortality" to (adv to setOf(PD, ND)), "Brute Force Breastplate" to (adv to setOf(PD)), "Chastise Pauldron" to (adv to emptySet()),
        "Radiant Armor" to (adv to setOf(MD, ND)),
        // ---- Movement ----
        "Boots" to (low to setOf(MV)), "Throw Forbidden" to (low to setOf(MV)), "Allow Throw" to (low to setOf(MV)),
        "Broken Heart" to (low to setOf(MV)), "Resonating Heart" to (low to setOf(MV)),
        "Warrior Boots" to (adv to setOf(MV, PD)), "Tough Boots" to (adv to setOf(MV, MD)), "Magic Shoes" to (adv to setOf(MV)),
        "Arcane Boots" to (adv to setOf(MV)), "Swift Boots" to (adv to setOf(MV)), "Rapid Boots" to (adv to setOf(MV)), "Demon Shoes" to (adv to setOf(MV)),
        // ---- potions ----
        "Magic Potion" to (Tier.CONSUMABLE to emptySet()), "Power Potion" to (Tier.CONSUMABLE to emptySet()), "Rock Potion" to (Tier.CONSUMABLE to emptySet()),
    )

    // ---- roaming blessings (not equipment) ----
    // (Library groups an item by its FIRST type: Thunder Belt, Dominance Ice, Oracle = Neutral Defense - your word)
    class Blessing(val name: String, val kind: String, val text: String) {
        // the wiki's picture (as on its Equipment page): "Active_-_Conceal.png", "Passive_-_Favor.png"
        val file get() = (if (kind.startsWith("Active")) "Active_-_" else "Passive_-_") + name.replace(' ', '_') + ".png"
    }
    val blessings = listOf(
        Blessing("Conceal", "Active skill (a button - not an equipment)", "Makes nearby allied heroes invisible to enemies for a short time and faster. Best for starting team fights, ganks and ambushes (ideal: Tigreal, Khufra, Atlas). (The AI presses it to sneak up on a fight or to slip away when hurt.)"),
        Blessing("Encourage", "Passive", "Increases nearby allied heroes' attack speed and adaptive attack. Best for team fights and boosting team damage. Ideal: Hilda, Grock, Minotaur."),
        Blessing("Favor", "Passive", "Periodically restores HP to the lowest-HP nearby allied hero when you use healing or shielding skills. Best for sustaining teammates. Ideal: Rafaela, Estes, Floryn."),
        Blessing("Dire Hit", "Passive", "Revamped: builds Force stacks while moving; at 20 stacks your next skill or basic attack uses them for bonus damage - kill pressure on low-HP heroes. Ideal: Hilda, Natalia, Saber."),
    )
    // the item-list names the blessings had ("Active - Conceal", "Passive - Favor")
    fun isBlessing(item: String) = item.startsWith("Active - ") || item.startsWith("Passive - ")

    // ---- what an item is ----
    fun info(item: String): Info {
        val n = ItemDb.canonical(item) ?: item
        wiki(n)?.let { return it }
        navTier(n)?.let { t -> if (table[n] == null) return Info(t, ItemBook.get(n)?.let { kindsFrom(it.stats + " " + it.effect) } ?: emptySet(), "the wiki's equipment list") }
        table[n]?.let { (t0, k) -> val t = navTier(n) ?: t0
            val lib = ItemBook.get(n)
            // no types in the table: the Library's learned stats give them
            val kinds = k.ifEmpty { lib?.let { kindsFrom(it.stats + " " + it.effect) } ?: emptySet() }
            return Info(t, kinds, if (k.isEmpty() && lib != null) "built in (types: the Library)" else "built in", lib?.price ?: -1)
        }
        // the Library's learned stats (and the price for the tier)
        val lib = ItemBook.get(n)
        if (lib != null) {
            val k = kindsFrom(lib.stats + " " + lib.effect)
            val t = when {
                n.contains("Potion") -> Tier.CONSUMABLE
                lib.price in 1..399 -> Tier.BASIC
                lib.price in 400..1199 -> if (n.contains("Boots") || n.contains("Shoes")) Tier.ADVANCED else Tier.INTERMEDIATE
                lib.price >= 1200 -> Tier.ADVANCED
                else -> null
            }
            return Info(t, k, "the Library (learned stats)", lib.price)
        }
        return Info(null, emptySet(), "not known yet")
    }
    fun tier(item: String) = info(item).tier
    fun isAdvanced(item: String) = !isBlessing(item) && tier(item) == Tier.ADVANCED

    // stats text -> types ("+60 Physical Attack, +400 HP")
    fun kindsFrom(stats: String): Set<Kind> {
        val t = stats.lowercase()
        val out = LinkedHashSet<Kind>()
        if ("adaptive attack" in t) out.add(Kind.ADAPTIVE)
        if (Regex("physical attack|attack speed|crit").containsMatchIn(t)) out.add(Kind.ATTACK)
        if ("magic power" in t) out.add(Kind.MAGIC)
        val pd = Regex("physical defen[cs]e|hybrid defen[cs]e").containsMatchIn(t)
        val md = Regex("magic defen[cs]e|magic resist|hybrid defen[cs]e").containsMatchIn(t)
        if (pd) out.add(Kind.PHYS_DEF)
        if (md) out.add(Kind.MAGIC_DEF)
        if ((pd && md) || Regex("\\+\\s*\\d+\\s*(max )?hp\\b|\\bhp\\b\\s*\\+").containsMatchIn(t)) out.add(Kind.NEUTRAL_DEF)
        if ("movement speed" in t || "move speed" in t) out.add(Kind.MOVEMENT)
        return out
    }
    // ---- Specialized equipment sub-classifications (v37.01, your request) ----
    // 1. Physical Penetration under Physical Attack
    val physicalPenetrationItems: List<String> = listOf(
        "Malefic Roar", "Blade of the Heptaseas", "Hunter Strike", "War Axe", "Malefic Gun", "Fury Hammer"
    )
    // 2. Magic Penetration under Magic Power
    val magicPenetrationItems: List<String> = listOf(
        "Divine Glaive", "Genius Wand", "Glowing Wand", "Arcane Boots", "Exotic Veil"
    )
    // 3. Attack Speed under Physical Attack (best used by Marksman or basic-attack-dependent Physical heroes)
    val physicalAttackSpeedItems: List<String> = listOf(
        "Demon Hunter Sword", "Corrosion Scythe", "Golden Staff", "Windtalker", "Wind of Nature",
        "Sea Halberd", "Haas' Claws", "Swift Boots", "Swift Crossbow", "Rogue Meteor", "Knife"
    )
    // 4. Attack Speed under Magic Power (best used by Magic basic-attack-dependent heroes like Natan, Zhask, Edith, Harith, Julian)
    val magicAttackSpeedItems: List<String> = listOf(
        "Feather of Heaven"
    )

    fun isPhysicalPenetration(item: String): Boolean {
        val n = ItemDb.canonical(item) ?: item
        if (n in physicalPenetrationItems) return true
        val st = ((wikiStats(n) ?: "") + " " + (ItemBook.get(n)?.let { it.stats + " " + it.effect } ?: "")).lowercase()
        return "physical penetration" in st
    }

    fun isMagicPenetration(item: String): Boolean {
        val n = ItemDb.canonical(item) ?: item
        if (n in magicPenetrationItems) return true
        val st = ((wikiStats(n) ?: "") + " " + (ItemBook.get(n)?.let { it.stats + " " + it.effect } ?: "")).lowercase()
        return "magic penetration" in st || "magic defense reduction" in st
    }

    fun isAttackSpeed(item: String): Boolean {
        val n = ItemDb.canonical(item) ?: item
        if (n in physicalAttackSpeedItems || n in magicAttackSpeedItems) return true
        val st = ((wikiStats(n) ?: "") + " " + (ItemBook.get(n)?.let { it.stats + " " + it.effect } ?: "")).lowercase()
        return "attack speed" in st
    }

    fun isPhysicalAttackSpeed(item: String): Boolean {
        val n = ItemDb.canonical(item) ?: item
        if (n in physicalAttackSpeedItems) return true
        return isAttackSpeed(n) && !isMagicAttackSpeed(n)
    }

    fun isMagicAttackSpeed(item: String): Boolean {
        val n = ItemDb.canonical(item) ?: item
        if (n in magicAttackSpeedItems) return true
        return isAttackSpeed(n) && Kind.MAGIC in info(n).kinds
    }

    fun subClassLabels(item: String): List<String> {
        val out = ArrayList<String>()
        if (isPhysicalPenetration(item)) out.add("Physical Penetration")
        if (isMagicPenetration(item)) out.add("Magic Penetration")
        if (isPhysicalAttackSpeed(item)) out.add("Attack Speed (Physical - best for Marksmen / basic-attack heroes)")
        if (isMagicAttackSpeed(item)) out.add("Attack Speed (Magic - best for Magic basic-attack heroes)")
        return out
    }

    fun kindsText(item: String): String {
        val base = info(item).kinds.joinToString(" · ") { it.label }
        val subs = subClassLabels(item)
        return listOf(base, subs.joinToString(" · ")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "-" }
    }

    // Strict check: NEVER mix Physical Attack equipment with Magic Power equipment!
    // Only choose offensive equipment based on that hero's damage type.
    fun fitsStrict(item: String, dmg: Damage): Boolean {
        val n = ItemDb.canonical(item) ?: item
        if (n in ItemDb.retributions) return true
        val k = info(n).kinds
        // Arcane Boots gives Magic Penetration -> only for MAGIC damage heroes
        if (n == "Arcane Boots") return dmg == Damage.MAGIC
        // Swift Boots gives Physical Attack Speed -> primarily for PHYSICAL / basic-attack heroes
        if (dmg == Damage.PHYSICAL && (Kind.MAGIC in k || isMagicPenetration(n))) return false
        if (dmg == Damage.MAGIC && (Kind.ATTACK in k || isPhysicalPenetration(n))) return false
        return true
    }

    fun isDefensiveItem(item: String): Boolean {
        val n = ItemDb.canonical(item) ?: item
        val k = info(n).kinds
        return Kind.PHYS_DEF in k || Kind.MAGIC_DEF in k || Kind.NEUTRAL_DEF in k ||
            n in setOf("Blade Armor", "Queen's Wings", "Thunder Belt", "Cursed Helmet", "Guardian Helmet",
                "Antique Cuirass", "Oracle", "Valkyrie's Shield", "Dominance Ice", "Immortality",
                "Brute Force Breastplate", "Chastise Pauldron", "Radiant Armor")
    }

    fun isDefensiveOrBoots(item: String): Boolean {
        val n = ItemDb.canonical(item) ?: item
        val k = info(n).kinds
        return isDefensiveItem(n) || Kind.MOVEMENT in k || n.contains("Boots") || n.contains("Shoes")
    }

    // For Tank heroes: all defensive items (plus boots/Retribution), and if any physical or magic equipment is added,
    // it MUST strictly match the tank's damage type (never mixing physical and magic).
    fun fitsTank(item: String, dmg: Damage): Boolean {
        if (!fitsStrict(item, dmg)) return false
        return true
    }

    // fits a hero of this damage type? (Adaptive fits both; defense / movement fit everyone; never mixes Attack and Magic)
    fun fits(item: String, dmg: Damage): Boolean = fitsStrict(item, dmg)

    // a build: real current Advanced items only (the Retribution kinds stay - the jungle item)
    fun advancedOnly(items: List<String>): List<String> = items.mapNotNull { ItemDb.canonical(it) }
        .filter { it in ItemDb.retributions || isAdvanced(it) }.distinct()

    // the defense to buy against an enemy team (Drafter, when their damage types are known)
    fun defenseAgainst(enemies: List<String>): String? {
        if (enemies.isEmpty()) return null
        val mg = enemies.count { HeroDb.damage(it) == Damage.MAGIC }
        val ph = enemies.size - mg
        val pick = when {
            ph >= mg + 2 -> Kind.PHYS_DEF
            mg >= ph + 2 -> Kind.MAGIC_DEF
            else -> Kind.NEUTRAL_DEF
        }
        // pure defense items (no attack / magic power, not boots)
        val examples = ItemDb.current.filter { n -> val k = info(n).kinds
            isAdvanced(n) && pick in k && k.none { it == Kind.ATTACK || it == Kind.MAGIC || it == Kind.ADAPTIVE || it == Kind.MOVEMENT } }.take(4)
        return "Enemy damage: $ph physical, $mg magic → ${pick.label}" + (if (examples.isNotEmpty()) " (e.g. ${examples.joinToString()})" else "")
    }

    // ---------------- the item's wiki page (no tokens) ----------------
    private fun wkey(item: String) = "itemwiki_" + item.lowercase().filter { it.isLetterOrDigit() }
    private fun wiki(item: String): Info? {
        val raw = Cfg.raw(wkey(item)) ?: return null
        return try {
            val o = org.json.JSONObject(raw)
            val k = kindsFrom(o.optString("stats"))
            val t = navTier(item) ?: table[item]?.first
            Info(t, (table[item]?.second.orEmpty() + k).toSet(), "the wiki", o.optInt("price", -1))
        } catch (e: Exception) { null }
    }
    fun wikiStats(item: String): String? = Cfg.raw(wkey(item))?.let { try { org.json.JSONObject(it).optString("stats").ifBlank { null } } catch (e: Exception) { null } }
    fun wikiKnown(item: String) = Cfg.raw(wkey(item)) != null

    // the infobox rows of a rendered wiki page: label -> value ("Tier" -> "3", "Price" -> "2010")
    fun infobox(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val rx = Regex("<h3[^>]*class=\"pi-data-label[^\"]*\"[^>]*>(.*?)</h3>\\s*<div[^>]*class=\"pi-data-value[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        for (m in rx.findAll(html)) {
            val k = clean(m.groupValues[1]); val v = clean(m.groupValues[2].replace(Regex("(?i)<br\\s*/?>"), "\n"))
            if (k.isNotBlank() && k !in out) out[k] = v
        }
        return out
    }
    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(Regex("[ \\t\\u00a0]+"), " ").replace(Regex(" *\n *"), "\n").trim()

    // a field of the page's info box by its data-source ("bonus" = the stats, "unique" = the passive /
    // active), as on your saved Blade of Despair / Flame Retribution pages
    fun dataSource(html: String, key: String): String? =
        Regex("data-source=\"$key\"[^>]*>.*?<div[^>]*class=\"pi-data-value[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { clean(it.replace(Regex("(?i)<br\\s*/?>"), "\n")) }?.takeIf { it.isNotBlank() }

    fun parseItemPage(html: String): org.json.JSONObject {
        val box = infobox(html)
        val stats = dataSource(html, "bonus") ?: ""
        val effect = listOf("unique", "unique2", "unique3", "passive", "active").mapNotNull { dataSource(html, it) }.distinct().joinToString("\n")
        // Price / Upgrade price / Sell: the first number under "Price"
        val price = Regex(">\\s*Price\\s*</th>.*?<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.let { Regex("\\b(\\d{1,5})\\b").find(clean(it))?.value?.toIntOrNull() } ?: -1
        parseNav(html)
        return org.json.JSONObject().put("type", box["Type"] ?: "").put("price", price).put("stats", stats).put("effect", effect)
            .put("at", System.currentTimeMillis())
    }

    // ---- the wiki's "List of equipment" box (on every equipment page): per category one row per
    // tier - Basic / Intermediate / Advanced (Movement: Basic / Advanced). Read once, kept: the tiers
    // follow the wiki when it changes. ----
    private const val NAV = "itemwiki_nav"
    private var navCache: Map<String, String>? = null
    fun navTier(item: String): Tier? {
        val m = navCache ?: (Cfg.raw(NAV)?.let { r -> try { org.json.JSONObject(r).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } catch (e: Exception) { null } } ?: emptyMap()).also { navCache = it }
        return m[item]?.let { try { Tier.valueOf(it) } catch (e: Exception) { null } }
    }
    fun parseNav(html: String): Map<String, Tier> {
        val i = html.indexOf("List of equipment"); if (i < 0) return emptyMap()
        val seg = html.substring(i, minOf(html.length, i + 200_000))
        val rows = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(seg).map { r ->
            Regex("<a [^>]*title=\"([^\"]+)\"").findAll(r.groupValues[1]).map { clean(it.groupValues[1]) }
                .filter { ItemDb.isCurrent(it) || it.contains("Potion") }.distinct().toList()
        }.toList()
        // the rows in order (your saved page): Attack, Magic, Defense - 3 rows each (Basic / Intermediate /
        // Advanced) - then Movement - 2 rows (Basic / Advanced) - then Jungling and Roaming (1 row each).
        // Checked: the 10th row must hold "Boots" and the 11th the boots ("Warrior Boots"), else the
        // layout changed and nothing is kept.
        val withNames = rows.filter { it.isNotEmpty() }
        val out = LinkedHashMap<String, Tier>()
        if (withNames.size < 11 || "Boots" !in withNames[9] || withNames[10].none { it.endsWith("Boots") || it.endsWith("Shoes") }) return out
        val tierOf = listOf(Tier.BASIC, Tier.INTERMEDIATE, Tier.ADVANCED, Tier.BASIC, Tier.INTERMEDIATE, Tier.ADVANCED,
            Tier.BASIC, Tier.INTERMEDIATE, Tier.ADVANCED, Tier.BASIC, Tier.ADVANCED)
        for (ri in 0 until 11) for (n in withNames[ri]) {
            val t = if (n.contains("Potion")) Tier.CONSUMABLE else tierOf[ri]
            val c = ItemDb.canonical(n) ?: n
            // an item listed in two categories keeps its higher tier
            if (out[c] == null || (t != Tier.CONSUMABLE && t.ordinal > out[c]!!.ordinal)) out[c] = t
        }
        if (out.size >= 40) {     // a whole list was read
            Cfg.setRaw(NAV, org.json.JSONObject(out.mapValues { it.value.name } as Map<*, *>).toString()); navCache = null
        }
        return out
    }

    // read one item's wiki page (background thread); false when the page could not be read
    fun fetchWiki(item: String): Boolean {
        val html = get("https://mobile-legends.fandom.com/wiki/" + URLEncoder.encode(item.replace(' ', '_'), "UTF-8") + "?action=render") ?: return false
        val o = parseItemPage(html)
        if (o.optString("stats").isBlank() && o.optString("effect").isBlank()) return false
        Cfg.setRaw(wkey(item), o.toString())
        return true
    }

    internal fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "MLBB-Research")
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    } catch (e: Exception) { null }

    // ---------------- the Blessed Retributions ----------------
    // your guide (v36.76, Blessed_Retribution_MLBB.txt): what each one does and when to pick it -
    // shown always; the wiki's exact effect text is added once read
    const val RETRI_INTRO = "Blessed Retribution is the upgraded Retribution battle spell. It activates after 5 jungle creep kills, hero kills or assists while using jungle footwear."
    val retributionGuide: Map<String, Pair<List<String>, List<String>>> = linkedMapOf(
        "Ice Retribution" to (listOf("Steals the target's movement speed.", "Slows the target while increasing your mobility.",
            "Useful for chasing, escaping and catching mobile heroes.") to listOf("Chasing enemies", "Fighting mobile enemies")),
        "Flame Retribution" to (listOf("Steals Physical Attack and Magic Power.", "Reduces the target's offensive strength.",
            "Useful for 1v1 fights and burst-damage matchups.") to listOf("Burst / assassin", "Winning 1v1 fights")),
        "Bloody Retribution" to (listOf("Deals damage while stealing HP.", "Restores your HP based partly on your Extra HP.",
            "Useful for tanky junglers and extended fights.") to listOf("Tank / sustain", "Frontline jungler")),
    )

    // ---------------- the Blessed Retributions: the wiki's effect text ----------------
    // (their effect is shown only as the wiki writes it - never an AI's words)
    private fun rkey(n: String) = "retriwiki_" + n.lowercase().filter { it.isLetterOrDigit() }
    fun retributionText(name: String): String? = Cfg.raw(rkey(name))
    fun fetchRetribution(name: String): Boolean {
        // its own page, else its part of the Retribution page
        get("https://mobile-legends.fandom.com/wiki/" + URLEncoder.encode(name.replace(' ', '_'), "UTF-8") + "?action=render")?.let { html ->
            val desc = retributionFrom(html)
            if (!desc.isNullOrBlank()) { Cfg.setRaw(rkey(name), desc.take(700)); return true }
        }
        get("https://mobile-legends.fandom.com/wiki/Retribution?action=render")?.let { html ->
            val text = clean(html.replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</h\\d>"), "\n"))
            val i = text.indexOf(name, ignoreCase = true)
            if (i >= 0) {
                val part = text.substring(i, minOf(text.length, i + 700))
                val cut = Regex("(?i)(ice|flame|bloody) retribution").findAll(part).map { it.range.first }.firstOrNull { it > name.length + 5 } ?: part.length
                Cfg.setRaw(rkey(name), part.take(cut).trim()); return true
            }
        }
        return false
    }
    // the effect as the page writes it: its "unique" field ("Unique Active - Retribution's damage ...",
    // your saved Flame Retribution page), else the first paragraphs
    fun retributionFrom(html: String): String? {
        parseNav(html)
        return dataSource(html, "unique") ?: firstParagraphs(html)
    }
    private fun firstParagraphs(html: String): String? = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(html)
        .map { clean(it.groupValues[1]) }.filter { it.length > 30 }.take(2).joinToString("\n").ifBlank { null }
}
