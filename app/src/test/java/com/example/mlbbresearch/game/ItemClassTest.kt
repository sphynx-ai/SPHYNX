package com.example.mlbbresearch.game

import com.example.mlbbresearch.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Equipment tiers and stat types, and the readers for the wiki's item pages (synthetic HTML in the
// layout of the saved Blade of Despair / Flame Retribution pages).
class ItemClassTest {

    @Before fun setUp() { TestSupport.freshCfg() }

    @Test fun statTypesFromStatsText() {
        assertEquals(setOf(ItemClass.Kind.ADAPTIVE), ItemClass.kindsFrom("+30 Adaptive Attack, +10% Cooldown"))
        assertEquals(setOf(ItemClass.Kind.PHYS_DEF, ItemClass.Kind.MAGIC_DEF, ItemClass.Kind.NEUTRAL_DEF),
            ItemClass.kindsFrom("+600 HP, +20 Physical Defense, +20 Magic Defense"))
        assertEquals(setOf(ItemClass.Kind.MAGIC, ItemClass.Kind.MOVEMENT), ItemClass.kindsFrom("+40 Magic Power, +5% Movement Speed"))
    }

    @Test fun buildsKeepOnlyAdvancedEquipmentAndRetributions() {
        assertEquals(listOf("Blade of Despair", "Ice Retribution", "Swift Boots", "Haas' Claws"),
            ItemClass.advancedOnly(listOf("Knife", "Blade of Despair", "Ice Retribution", "Swift Boots", "Ares Belt", "Haas's Claws", "Boots")))
    }

    @Test fun roamingBlessingsAreNotEquipment() {
        assertTrue(ItemClass.isBlessing("Active - Conceal"))
        assertTrue(ItemClass.isBlessing("Passive - Dire Hit"))
        assertFalse(ItemClass.isBlessing("Tough Boots"))
    }

    private val bladePage = """
        <aside class="portable-infobox">
          <div class="pi-item pi-data" data-source="bonus"><div class="pi-data-value pi-font">+160 Physical Attack<br> +5% Movement Speed</div></div>
          <div class="pi-item pi-data" data-source="unique"><div class="pi-data-value pi-font">Unique Passive - Despair: bonus damage.</div></div>
          <table class="pi-horizontal-group"><thead><tr><th class="pi-data-label">Price</th><th class="pi-data-label">Sell</th></tr></thead>
          <tbody><tr><td class="pi-data-value"><span style="color:gold">3010</span></td><td class="pi-data-value">1806</td></tr></tbody></table>
          <h3 class="pi-data-label pi-secondary-font">Type</h3><div class="pi-data-value pi-font">Attack</div>
        </aside>""".trimIndent()

    @Test fun itemPageGivesStatsEffectPriceAndType() {
        val o = ItemClass.parseItemPage(bladePage)
        assertEquals("+160 Physical Attack\n+5% Movement Speed", o.getString("stats"))
        assertTrue(o.getString("effect").startsWith("Unique Passive - Despair"))
        assertEquals(3010, o.getInt("price"))
        assertEquals("Attack", o.getString("type"))
    }

    @Test fun blessedRetributionTextComesFromItsUniqueField() {
        val html = """<div class="pi-item pi-data" data-source="unique"><div class="pi-data-value pi-font">Unique Active - Retribution's damage is increased to 150%.</div></div>"""
        assertEquals("Unique Active - Retribution's damage is increased to 150%.", ItemClass.retributionFrom(html))
    }

    // the wiki's "List of equipment" box: Attack, Magic, Defense with 3 rows each, Movement with 2
    @Test fun equipmentListGivesTiers() {
        fun row(vararg items: String) = "<tr><td>" + items.joinToString("") { "<a href=\"x\" title=\"$it\">$it</a>" } + "</td></tr>"
        val nav = "<div>List of equipment</div><table>" +
            row("Dagger", "Knife", "Javelin", "Vampire Mallet", "Power Potion") + row("Legion Sword", "Regular Spear", "Fury Hammer") +
            row("Blade of Despair", "Hunter Strike", "Malefic Roar", "Haas' Claws", "Sea Halberd", "Windtalker", "War Axe") +
            row("Power Crystal", "Magic Necklace", "Book of Sages", "Magic Potion") + row("Magic Wand", "Tome of Evil", "Elegant Gem") +
            row("Holy Crystal", "Divine Glaive", "Blood Wings", "Genius Wand", "Lightning Truncheon", "Ice Queen Wand") +
            row("Vitality Crystal", "Leather Jerkin", "Magic Resist Cloak", "Rock Potion") + row("Ares Belt", "Molten Essence", "Silence Robe") +
            row("Blade Armor", "Oracle", "Immortality", "Dominance Ice", "Radiant Armor", "Thunder Belt") +
            row("Boots", "Allow Throw") + row("Warrior Boots", "Tough Boots", "Magic Shoes", "Arcane Boots", "Swift Boots") +
            row("Ice Retribution", "Bloody Retribution") + "</table>"
        val t = ItemClass.parseNav(nav)
        assertEquals(ItemClass.Tier.BASIC, t["Knife"])
        assertEquals(ItemClass.Tier.INTERMEDIATE, t["Molten Essence"])
        assertEquals(ItemClass.Tier.ADVANCED, t["Blade of Despair"])
        assertEquals(ItemClass.Tier.ADVANCED, t["Tough Boots"])
        assertEquals(ItemClass.Tier.BASIC, t["Boots"])
        assertEquals(ItemClass.Tier.CONSUMABLE, t["Power Potion"])
        assertFalse(t.containsKey("Ice Retribution"))     // the one-row groups are not tiers
    }

    @Test fun equipmentListWithAnUnexpectedLayoutIsIgnored() {
        assertTrue(ItemClass.parseNav("<div>List of equipment</div><table><tr><td><a title=\"Knife\">Knife</a></td></tr></table>").isEmpty())
    }
}
