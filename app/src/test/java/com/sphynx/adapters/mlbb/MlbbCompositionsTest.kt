package com.sphynx.adapters.mlbb

import com.sphynx.adapters.mlbb.MlbbCompositions.Hero
import com.sphynx.adapters.mlbb.MlbbCompositions.Lane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 13 D4: coherent five-hero compositions
class MlbbCompositionsTest {

    private fun h(n: String, role: String, magic: Boolean, counteredBy: List<String> = emptyList()) =
        Hero(n, role, emptyList(), magic, counteredBy, counteredBy.isNotEmpty())
    private val pool = listOf(h("Tigreal", "tank", false), h("Khufra", "tank", false), h("Balmond", "fighter", false),
        h("Chou", "fighter", false), h("Ling", "assassin", false, listOf("Kaja")), h("Fanny", "assassin", false),
        h("Eudora", "mage", true), h("Kagura", "mage", true), h("Layla", "marksman", false, listOf("Saber")),
        h("Miya", "marksman", false), h("Angela", "support", true))

    @Test fun fiveLanesCoveredAndCoherent() {
        val r = MlbbCompositions.recommend(pool, emptyMap(), emptyList(), emptySet())
        assertTrue(r.isNotEmpty())
        for (c in r) {
            assertEquals(5, c.picks.size); assertEquals(5, c.picks.values.toSet().size)
            assertTrue(c.strengths.any { it.startsWith("mixed damage") })
            assertTrue(c.evidence.any { it.contains("no official pick/win/ban statistics") })
            assertTrue(c.confidence == "low" || c.confidence == "medium")
        }
        val first = r.first().picks
        assertTrue(first[Lane.GOLD] in setOf("Layla", "Miya")); assertTrue(first[Lane.MID] in setOf("Eudora", "Kagura"))
        // distinct suggestions
        if (r.size > 1) assertTrue(r[0].picks.values.toSet() != r[1].picks.values.toSet())
    }

    @Test fun picksBansAndCountersAreRespected() {
        val r = MlbbCompositions.recommend(pool, mapOf(Lane.GOLD to "Layla"), listOf("Saber"), setOf("Eudora", "Saber"))
        for (c in r) { assertEquals("Layla", c.picks[Lane.GOLD]); assertFalse("Eudora" in c.picks.values) }
        assertTrue(r.first().weaknesses.any { it.contains("Layla by Saber") })
        // the counter is avoided when there is a choice
        val free = MlbbCompositions.recommend(pool, emptyMap(), listOf("Saber"), setOf("Saber"))
        assertEquals("Miya", free.first().picks[Lane.GOLD])
    }

    @Test fun nothingFitsGivesNothing() {
        assertTrue(MlbbCompositions.recommend(listOf(h("Layla", "marksman", false)), emptyMap(), emptyList(), emptySet()).isEmpty())
    }
}
