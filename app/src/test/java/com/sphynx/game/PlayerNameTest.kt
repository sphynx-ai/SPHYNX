package com.sphynx.game

import com.sphynx.testutil.SynthFrame
import com.sphynx.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Your card / team on the loading screen and the scoreboard: found by your player name (with small
// reading slips) or, on the loading screen, by the GREEN name - never by position (cards are random
// and the team rows can swap).
class PlayerNameTest {

    @Before fun setUp() { TestSupport.freshCfg() }

    @Test fun nameMatchAllowsSmallReadingSlipsAndClanTags() {
        assertTrue(PlayerName.same("Griffith", "Griffith"))
        assertTrue(PlayerName.same("Grifflth", "Griffith"))          // one misread letter
        assertTrue(PlayerName.same("EFB Griffith", "Griffith"))      // clan tag in front
        assertTrue(PlayerName.same("100%KO", "100%KO"))
        assertFalse(PlayerName.same("Griffin", "Griffith"))          // a different name
        assertFalse(PlayerName.same("", "Griffith"))
    }

    private fun card(p: String, h: String, s: String = "Flicker") = """{"player":"$p","hero":"$h","spell":"$s"}"""
    private val top = listOf(card("100%KO", "Karina", "Retribution"), card("Idang", "Zilong"), card("shaenananana", "Nana"),
        card("Alma Jesse~98", "Miya"), card("nyeeee", "Layla"))
    private val bottom = listOf(card("Tasya", "Alucard"), card("Celine", "Thamuz"), card("rifqi", "Clint"),
        card("kakung", "Gord"), card("GrottoOlah", "Franco"))
    private fun loading(t: List<String>, b: List<String>) = """{"top_row":[${t.joinToString(",")}],"bottom_row":[${b.joinToString(",")}]}"""

    @Test fun loadingScreenFindsMyCardByNameEvenWhenRowsSwap() {
        for (reply in listOf(loading(top, bottom), loading(bottom, top))) {
            val lu = PlayerName.parseLineup(reply, loading = true, name = "100%KO")!!
            assertEquals("Karina", lu.me?.hero)
            assertEquals("Retribution", lu.me?.spell)
            assertEquals(listOf("Karina", "Zilong", "Nana", "Miya", "Layla"), lu.mine.map { it.hero })
            assertEquals(listOf("Alucard", "Thamuz", "Clint", "Gord", "Franco"), lu.theirs.map { it.hero })
        }
    }

    @Test fun loadingScreenUsesTheGreenCardWithoutATypedName() {
        val lu = PlayerName.parseLineup(loading(top, bottom), loading = true, name = "", green = 0 to 0)!!
        assertEquals("Karina", lu.me?.hero)
    }

    @Test fun nothingIsDecidedWithoutNameOrGreenCard() {
        assertNull(PlayerName.parseLineup(loading(top, bottom), loading = true, name = ""))
        assertNull(PlayerName.parseLineup(loading(top, bottom), loading = true, name = "Somebody Else"))
    }

    @Test fun scoreboardSwapsSidesWhenMyNameIsOnTheRight() {
        val reply = """{"my_team":[${bottom.joinToString(",")}],"enemy_team":[${top.joinToString(",")}]}"""
        val lu = PlayerName.parseLineup(reply, loading = false, name = "100%KO")!!
        assertEquals("Karina", lu.me?.hero)
        assertEquals("Karina", lu.mine.first().hero)
    }

    // the mint-green name text, painted where the first top card's name sits (synthetic frame)
    @Test fun greenNameIsFoundOnTheRightCard() {
        val f = SynthFrame()
        f.rect(0.075f, 0.36f, 0.115f, 0.39f, SynthFrame.rgb(100, 190, 130))
        assertEquals(0 to 0, PlayerName.greenCard(f.px, f.w, f.h))
        val g = SynthFrame()
        g.rect(0.50f, 0.885f, 0.54f, 0.915f, SynthFrame.rgb(100, 190, 130))   // second card of the bottom row
        assertEquals(1 to 1, PlayerName.greenCard(g.px, g.w, g.h))
        assertNull(PlayerName.greenCard(SynthFrame().px, 2000, 900))           // no green name at all
    }
}
