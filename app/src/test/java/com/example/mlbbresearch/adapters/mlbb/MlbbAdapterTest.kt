package com.example.mlbbresearch.adapters.mlbb

import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.Lane
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.universal.Capability
import com.example.mlbbresearch.universal.MatchPhase
import com.example.mlbbresearch.universal.Side
import com.example.mlbbresearch.universal.SupportStatus
import com.example.mlbbresearch.universal.UnitKind
import com.example.mlbbresearch.vision.MapTower
import com.example.mlbbresearch.vision.Seen
import com.example.mlbbresearch.vision.Team
import com.example.mlbbresearch.vision.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlbbAdapterTest {

    private fun unit(team: Team, hero: Boolean, x: Float, y: Float, hp: Float, species: String = "") =
        Seen(team, hero, x, y, hp, x - 0.02f, y - 0.05f, x + 0.02f, y - 0.04f, species)

    private fun state(
        phase: Phase = Phase.IN_GAME, selfHp: Float = 0.8f, gold: Int = -1, items: Int = -1, matchSec: Int = -1,
        xp: Float = -1f, bossHp: Float = -1f, spell: String? = null, lane: Lane = Lane.MID
    ) = GameState(
        ts = 1000L, phase = phase,
        self = unit(Team.SELF, true, 0.5f, 0.55f, selfHp), selfHp = selfHp,
        allies = listOf(unit(Team.ALLY, true, 0.4f, 0.5f, 0.6f)),
        enemies = listOf(Track(id = 7, x = 0.7f, y = 0.4f, hp = 0.3f, vx = -0.2f, vy = 0f, lastSeen = 900L, firstSeen = 100L, minHpSeen = 0.3f)),
        enemyMinions = listOf(unit(Team.ENEMY, false, 0.65f, 0.5f, 1f), unit(Team.ENEMY, false, 0.66f, 0.52f, 0.5f)),
        allyMinions = listOf(unit(Team.ALLY, false, 0.45f, 0.6f, 0.9f)),
        minimapEnemies = 2, aspect = 2.2f, ownHero = "Karina",
        myTeam = listOf("Karina", "Tigreal"), enemyTeam = listOf("Layla"),
        items = items, gold = gold, itemsGoldAgeMs = 0L,
        monsters = listOf(unit(Team.NEUTRAL, false, 0.3f, 0.3f, 0.7f, "molten_fiend"), unit(Team.NEUTRAL, true, 0.2f, 0.2f, 0.9f, "turtle")),
        spell = spell, lane = lane, matchSec = matchSec, minimapSelf = 0.1f to 0.9f,
        towers = listOf(MapTower(0.8f, 0.2f, false)), allyTowers = listOf(0.2f to 0.8f),
        bossHp = bossHp, xp = xp, level = 4
    )

    @Test fun everyMlbbPhaseIsMapped() {
        // a new MLBB Phase without a mapping fails to compile (exhaustive when); this checks the meaning
        assertEquals(MatchPhase.OUT_OF_MATCH, MlbbAdapter.phase(Phase.LOBBY))
        assertEquals(MatchPhase.CHARACTER_SELECT, MlbbAdapter.phase(Phase.HERO_LOCKED))
        assertEquals(MatchPhase.LOADING, MlbbAdapter.phase(Phase.LOADING))
        assertEquals(MatchPhase.IN_MATCH, MlbbAdapter.phase(Phase.SHOP))
        assertEquals(MatchPhase.PLAYER_DEAD, MlbbAdapter.phase(Phase.DEAD))
        assertEquals(MatchPhase.POST_MATCH, MlbbAdapter.phase(Phase.RESULT))
        assertEquals(MatchPhase.SPECTATING, MlbbAdapter.phase(Phase.LIVE))
        for (p in Phase.values()) MlbbAdapter.phase(p)
    }

    @Test fun unitsGoToTheRightSideAndKind() {
        val u = MlbbAdapter.toUniversal(state())
        assertEquals("mlbb", u.gameId)
        assertEquals(MatchPhase.IN_MATCH, u.phase)
        assertEquals(1, u.allyCharactersSeen.size)
        assertEquals(1, u.enemyCharactersSeen.size)
        assertEquals(2, u.units(Side.ENEMY, UnitKind.MINION).size)
        assertEquals(1, u.units(Side.ALLY, UnitKind.MINION).size)
        assertEquals(1, u.units(Side.NEUTRAL, UnitKind.NEUTRAL_MONSTER).size)
        assertEquals("turtle", u.units(Side.NEUTRAL, UnitKind.MAJOR_OBJECTIVE).single().characterId)
        assertEquals(1, u.units(Side.ENEMY, UnitKind.STRUCTURE).size)
        assertEquals(1, u.units(Side.ALLY, UnitKind.STRUCTURE).size)
        val enemy = u.enemyCharactersSeen.single()
        assertEquals(0.3f, enemy.health!!, 1e-6f)
        assertEquals(-0.2f, enemy.velocity!!.x, 1e-6f)
        assertEquals("7", enemy.gameSpecific["track_id"])
    }

    @Test fun selfAndTeams() {
        val u = MlbbAdapter.toUniversal(state())
        assertNotNull(u.self)
        val me = u.self!!
        assertEquals(Side.SELF, me.side)
        assertEquals("Karina", me.characterId)
        assertEquals(0.8f, me.health!!, 1e-6f)
        assertEquals(0.1f, me.mapPos!!.x, 1e-6f)
        assertEquals(listOf("Karina", "Tigreal"), u.allyCharacters)
        assertEquals(listOf("Layla"), u.enemyCharacters)
    }

    @Test fun unknownValuesStayNullNeverMinusOne() {
        val u = MlbbAdapter.toUniversal(state(selfHp = -1f))
        assertNull(u.self!!.health)
        assertNull(u.resources.gold)
        assertNull(u.resources.itemCount)
        assertNull(u.resources.levelProgress)
        assertNull(u.matchTimeSec)
        assertEquals(4, u.resources.level)
        assertNull(u.self!!.confidence)   // the MLBB readers give no confidence: not invented
        val known = MlbbAdapter.toUniversal(state(gold = 1200, items = 3, matchSec = 300, xp = 0.5f))
        assertEquals(1200, known.resources.gold)
        assertEquals(3, known.resources.itemCount)
        assertEquals(300, known.matchTimeSec)
    }

    @Test fun mlbbOnlyDetailsStayInGameSpecific() {
        val u = MlbbAdapter.toUniversal(state(spell = "Retribution", bossHp = 0.45f, lane = Lane.JUNGLE))
        assertEquals("Retribution", u.gameSpecific["battle_spell"])
        assertEquals("0.45", u.gameSpecific["boss_hp"])
        assertEquals("2", u.gameSpecific["minimap_enemies"])
        assertEquals("jungle", u.assignedZone)
        assertEquals("molten_fiend", u.units(Side.NEUTRAL, UnitKind.NEUTRAL_MONSTER).single().gameSpecific["species"])
    }

    @Test fun capabilitiesMatchTheAuditMatrix() {
        val c = MlbbAdapter.toUniversal(state()).capabilities
        assertEquals(SupportStatus.UNSUPPORTED, c.status(Capability.GAME_RECOGNITION))
        assertEquals(SupportStatus.EXPERIMENTAL, c.status(Capability.TACTICAL_ADVICE))
        assertEquals(SupportStatus.PARTIAL, c.status(Capability.STRATEGIC_ADVICE))
        // nothing is device-verified yet, so nothing may claim OPERATIONAL
        assertTrue(c.all().values.none { it == SupportStatus.OPERATIONAL })
    }
}
