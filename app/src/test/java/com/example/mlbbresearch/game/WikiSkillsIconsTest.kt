package com.example.mlbbresearch.game

import org.junit.Assert.assertEquals
import org.junit.Test

// The skill picture for each button, read from a hero's wiki page layout (synthetic HTML in the
// shapes the real pages use: "Skill 3", "Ultimate 1/2", a "Special" skill, item icons first).
class WikiSkillsIconsTest {

    private fun im(f: String) = "<img src=\"https://static.wikia.nocookie.net/mobile-legends/images/a/ab/$f/revision/latest\">"
    private fun h3(t: String) = "<h3>$t</h3>"

    @Test fun plainThreeSkillHero() {
        val html = h3("Passive") + im("P.png") + h3("Skill 1") + im("A.png") + h3("Skill 2") + im("B.png") + h3("Ultimate") + im("C.png") + "<h2>Story</h2>"
        assertEquals(mapOf("passive" to "P.png", "s1" to "A.png", "s2" to "B.png", "ult" to "C.png"), WikiSkills.parseIcons(html, 3))
    }

    @Test fun twoUltimatesMeanThirdButtonAndUltimate() {    // Fredrinn
        val html = h3("Passive") + im("P.png") + h3("Skill 1") + im("A.png") + h3("Skill 2") + im("B.png") +
            h3("Ultimate 1") + im("C.png") + h3("Ultimate 2") + im("D.png") + "<h2>Gallery</h2>"
        val m = WikiSkills.parseIcons(html, 4)
        assertEquals("C.png", m["s3"]); assertEquals("D.png", m["ult"])
    }

    @Test fun specialSkillIsTheFourthButton() {             // Chip: Ultimate = 3rd button, Special = 4th
        val html = h3("Passive") + im("P.png") + h3("Skill 1") + im("A.png") + h3("Skill 2") + im("B.png") +
            h3("Ultimate") + im("Shortcut.png") + h3("Special") + im("Why_Walk%3F_%28grayscale%29.png") + im("Why_Walk%3F.png") + "<h2>Story</h2>"
        val m = WikiSkills.parseIcons(html, 4)
        assertEquals("Shortcut.png", m["s3"])
        assertEquals("Why_Walk?.png", m["ult"])                // the greyed copy is skipped
    }

    @Test fun ownPictureBeatsAnItemIconShownFirst() {        // Beatrix's passive shows Demon Hunter Sword first
        val html = h3("Passive") + im("Demon_Hunter_Sword.png") + im("Mechanical_Genius.png") + "<b>Mechanical Genius</b> talent" +
            h3("Skill 1") + im("A.png") + h3("Skill 2") + im("B.png") + h3("Ultimate") + im("C.png") + "<h2>Story</h2>"
        assertEquals("Mechanical_Genius.png", WikiSkills.parseIcons(html, 3)["passive"])
    }

    @Test fun twoSkillHeroHasNoUltimate() {                  // Hirara
        val html = h3("Passive") + im("P.png") + h3("Skill 1") + im("A.png") + h3("Skill 2") + im("B.png") + "<h2>Story</h2>"
        assertEquals(mapOf("passive" to "P.png", "s1" to "A.png", "s2" to "B.png"), WikiSkills.parseIcons(html, 2))
    }
}
