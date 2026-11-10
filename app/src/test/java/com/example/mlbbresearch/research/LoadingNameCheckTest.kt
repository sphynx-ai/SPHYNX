package com.example.mlbbresearch.research

import com.example.mlbbresearch.research.LoadingNameCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingNameCheckTest {

    private fun reply(top: List<String>, bottom: List<String>): String {
        fun row(n: List<String>) = n.joinToString(",", "[", "]") { "{\"player\":\"$it\",\"hero\":\"Layla\",\"spell\":\"Flicker\"}" }
        return "Here: {\"top_row\":${row(top)},\"bottom_row\":${row(bottom)}}"
    }
    private val bots = List(5) { "Computer" }

    @Test fun computerWord() {
        assertTrue(LoadingNameCheck.isComputer("Computer"))
        assertTrue(LoadingNameCheck.isComputer("Cornputer"))     // one reading slip
        assertFalse(LoadingNameCheck.isComputer("ComputerKing"))
        assertFalse(LoadingNameCheck.isComputer("Griffith"))
    }

    @Test fun nineComputersAndMeByGreenCard() {
        val r = LoadingNameCheck.check(reply(listOf("Griffith") + bots.drop(1), bots), green = 0 to 0, myName = "")
        assertEquals(Verdict.ALL_COMPUTER, r.verdict)
    }

    @Test fun meFoundAsTheOnlyOtherName() {
        val r = LoadingNameCheck.check(reply(bots, bots.take(3) + "Griffith" + "Computer"), green = null, myName = "")
        assertEquals(Verdict.ALL_COMPUTER, r.verdict)
    }

    @Test fun aRealPlayerIsNotProven() {
        val r = LoadingNameCheck.check(reply(listOf("Griffith") + bots.drop(1), bots.take(4) + "xXSlayerXx"), green = 0 to 0, myName = "")
        assertEquals(Verdict.NOT_PROVEN, r.verdict)
        // without knowing my card, two names that are not "Computer" are never proven
        assertEquals(Verdict.NOT_PROVEN, LoadingNameCheck.check(reply(listOf("Griffith") + bots.drop(1), bots.take(4) + "xXSlayerXx"), null, "").verdict)
        // my name typed on the Player page finds my card
        assertEquals(Verdict.NOT_PROVEN, LoadingNameCheck.check(reply(listOf("Griffith") + bots.drop(1), bots.take(4) + "xXSlayerXx"), null, "Griffith").verdict)
    }

    @Test fun incompleteOrBadReadingsAreNotProven() {
        assertEquals(Verdict.NOT_PROVEN, LoadingNameCheck.check(null, null, "").verdict)
        assertEquals(Verdict.NOT_PROVEN, LoadingNameCheck.check("no json", null, "").verdict)
        assertEquals(Verdict.NOT_PROVEN, LoadingNameCheck.check(reply(bots.take(4), bots), 0 to 0, "").verdict)
        assertEquals(Verdict.NOT_PROVEN, LoadingNameCheck.check(reply(listOf("") + bots.drop(1), bots), 0 to 0, "").verdict)
    }
}
