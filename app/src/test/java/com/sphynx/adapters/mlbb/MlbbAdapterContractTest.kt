package com.sphynx.adapters.mlbb

import com.sphynx.game.GameState
import com.sphynx.game.Lane
import com.sphynx.game.Phase
import com.sphynx.core.Cfg
import com.sphynx.testing.AdapterContractKit
import com.sphynx.testing.MemoryPrefs
import com.sphynx.universal.StateContract
import com.sphynx.universal.UniversalMatchState
import com.sphynx.vision.MapTower
import com.sphynx.vision.Seen
import com.sphynx.vision.Team
import com.sphynx.vision.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// Phase 2 submilestone 3: whatever the MLBB readers report, the MLBB adapter's output keeps the universal
// game-state contract (docs/contracts/UNIVERSAL_MATCH_STATE.md). The contract test every future adapter copies.
class MlbbAdapterContractTest {

    @Before fun setUp() { Cfg.useForTests(MemoryPrefs()) }

    private fun unit(team: Team, hero: Boolean, x: Float, y: Float, hp: Float, species: String = "") =
        Seen(team, hero, x, y, hp, x - 0.02f, y - 0.05f, x + 0.02f, y - 0.04f, species)

    private fun full(phase: Phase, lane: Lane) = GameState(
        ts = 1000L, phase = phase,
        self = unit(Team.SELF, true, 0.5f, 0.55f, 0.8f), selfHp = 0.8f,
        allies = listOf(unit(Team.ALLY, true, 0.4f, 0.5f, 0.6f)),
        enemies = listOf(Track(id = 7, x = 0.7f, y = 0.4f, hp = 0.3f, vx = -0.2f, vy = 0f, lastSeen = 900L, firstSeen = 100L, minHpSeen = 0.3f)),
        enemyMinions = listOf(unit(Team.ENEMY, false, 0.65f, 0.5f, 1f)),
        allyMinions = listOf(unit(Team.ALLY, false, 0.45f, 0.6f, 0.9f)),
        minimapEnemies = 2, aspect = 2.2f, ownHero = "Karina",
        myTeam = listOf("Karina", "Tigreal"), enemyTeam = listOf("Layla"),
        items = 2, gold = 1500, itemsGoldAgeMs = 0L,
        monsters = listOf(unit(Team.NEUTRAL, false, 0.3f, 0.3f, 0.7f, "molten_fiend"), unit(Team.NEUTRAL, true, 0.2f, 0.2f, 0.9f, "")),
        spell = "Flicker", lane = lane, matchSec = 240, minimapSelf = 0.1f to 0.9f,
        towers = listOf(MapTower(0.8f, 0.2f, false)), allyTowers = listOf(0.2f to 0.8f),
        bossHp = 0.5f, xp = 0.4f, level = 6
    )

    // nothing read: the "-1 = unknown" values the MLBB readers use
    private fun empty(phase: Phase, lane: Lane = Lane.GOLD) = GameState(
        ts = 0L, phase = phase, self = null, selfHp = -1f, allies = emptyList(), enemies = emptyList(),
        enemyMinions = emptyList(), allyMinions = emptyList(), minimapEnemies = 0, aspect = 2.2f, ownHero = null,
        myTeam = emptyList(), enemyTeam = emptyList(), items = -1, gold = -1, itemsGoldAgeMs = 0L,
        monsters = emptyList(), spell = null, lane = lane, matchSec = -1, minimapSelf = null,
        towers = emptyList(), allyTowers = emptyList(), bossHp = -1f, xp = -1f, level = -1
    )

    private fun assertKeeps(s: GameState) {
        val u = MlbbAdapter.toUniversal(s)
        assertEquals("phase ${s.phase} lane ${s.lane}", emptyList<String>(), StateContract.violations(u))
        assertEquals(UniversalMatchState.SCHEMA_VERSION, u.schemaVersion)
    }

    @Test fun everyPhaseAndLaneKeepsTheContract() {
        for (p in Phase.values()) for (l in Lane.values()) { assertKeeps(full(p, l)); assertKeeps(empty(p, l)) }
    }

    @Test fun unknownReadingsBecomeNullNotSentinels() {
        val u = MlbbAdapter.toUniversal(empty(Phase.IN_GAME))
        assertNull(u.matchTimeSec); assertNull(u.self)
        assertNull(u.resources.gold); assertNull(u.resources.itemCount); assertNull(u.resources.level); assertNull(u.resources.levelProgress)
        assertEquals(emptyMap<String, String>(), u.gameSpecific)
    }

    // the adapter test kit every game adapter runs (docs/ADAPTER_GUIDE.md)
    @Test fun theAdapterPassesTheAdapterTestKit() {
        val samples = Phase.values().flatMap { p -> Lane.values().flatMap { l -> listOf(full(p, l), empty(p, l)) } }
        assertEquals(emptyList<String>(), AdapterContractKit.check(MlbbAdapter, samples))
    }
}
