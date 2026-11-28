package com.sphynx.adapters.mlbb

import com.sphynx.adapters.mlbb.MlbbProHub.Draft
import com.sphynx.adapters.mlbb.MlbbProHub.Game
import com.sphynx.adapters.mlbb.MlbbProHub.Link
import com.sphynx.adapters.mlbb.MlbbProHub.Match
import com.sphynx.adapters.mlbb.MlbbProHub.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

// Phase 13 D7: the Pro Match Hub's model, states, time zones and draft-level evidence
class MlbbProHubTest {

    private val d1 = Draft(listOf("Ling", "Chou", "Kagura", "Layla", "Tigreal"), listOf("Fanny"))
    private val d2 = Draft(listOf("Fanny", "Paquito", "Valir", "Miya", "Khufra"), emptyList())
    private fun m(id: String, st: State, at: Long?, games: List<Game> = emptyList()) = Match(id, "mpl-ph", "Week 1", "A", "B", at, st,
        null, null, games, listOf(Link("YouTube (official)", "https://example.org/official", true), Link("fan stream", "https://example.org/fan", false)),
        "test", null, 0)

    @Test fun statesAndOrder() {
        val g = MlbbProHub.grouped(listOf(m("c1", State.COMPLETED, 100), m("u2", State.UPCOMING, 300), m("u1", State.UPCOMING, 200),
            m("l", State.LIVE, 50), m("p", State.POSTPONED, null), m("c2", State.COMPLETED, 400)))
        assertEquals(listOf(State.LIVE, State.UPCOMING, State.POSTPONED, State.COMPLETED), g.map { it.first })
        assertEquals(listOf("u1", "u2"), g[1].second.map { it.id })
        assertEquals(listOf("c2", "c1"), g[3].second.map { it.id })
    }

    @Test fun localTimeAndOfficialLinks() {
        assertEquals("Sat 6 Dec, 17:00 (Asia/Manila)", MlbbProHub.localTime(1765011600000L, ZoneId.of("Asia/Manila")))
        assertEquals("time not announced", MlbbProHub.localTime(null, ZoneId.of("UTC")))
        assertEquals(listOf("YouTube (official)"), MlbbProHub.watchLinks(m("x", State.LIVE, 0)).map { it.label })
        assertTrue(MlbbProHub.NotConnected.status().startsWith("No esports source is connected"))
        assertTrue(MlbbProHub.COMPETITIONS.any { it.name == "MPL Philippines" })
    }

    @Test fun draftLevelEvidenceOnlyFromCompleteGames() {
        val games = listOf(Game(1, "A", "1.9.20", d1, d2, 900), Game(2, "B", null, d1, d2, 800), Game(3, null, "1.9.20", d1, d2, 700),
            Game(4, "B", "1.9.20", d1, Draft(listOf("Fanny"), emptyList()), 1000))
        val ev = MlbbProHub.draftEvidence(listOf(m("done", State.COMPLETED, 1000, games), m("next", State.UPCOMING, 2000, games)))
        val k1 = MlbbProHub.compositionKey(d1)!!
        assertEquals(listOf(true, false), ev[k1]!!.map { it.won })            // games 1 and 4 (game 2: no patch, game 3: no winner)
        assertEquals(1, ev[MlbbProHub.compositionKey(d2)!!]!!.size)            // game 4's B draft is not a full five
        assertEquals("Chou+Kagura+Layla+Ling+Tigreal", k1)
        assertTrue(ev.values.flatten().all { it.complete && it.tournament == "mpl-ph" })
    }
}
