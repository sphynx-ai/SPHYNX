package com.sphynx.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

// The universal game-state contract, version 1 (docs/contracts/UNIVERSAL_MATCH_STATE.md).
class StateContractTest {

    private fun fine() = UniversalMatchState("mlbb", 1000L, MatchPhase.IN_MATCH, matchTimeSec = 90,
        self = UnitState(Side.SELF, UnitKind.CHARACTER, characterId = "karina", health = 0.8f, screenPos = Vec2(0.5f, 0.5f)),
        units = listOf(UnitState(Side.ENEMY, UnitKind.CHARACTER, health = 0.3f, velocity = Vec2(-0.2f, 0f),
            gameSpecific = mapOf("track_id" to "7"))),
        resources = ResourceState(gold = 1200, itemCount = 2, level = 4, levelProgress = 0.5f),
        assignedZone = "mid_lane", gameSpecific = mapOf("battle_spell" to "flicker"))

    @Test fun aGoodStateKeepsTheContract() {
        assertEquals(emptyList<String>(), StateContract.violations(fine()))
    }

    @Test fun unknownEverywhereIsAllowed() {
        // the smallest state: only the game, the time and the phase
        assertEquals(emptyList<String>(), StateContract.violations(UniversalMatchState("dota_like", 0L, MatchPhase.UNKNOWN)))
    }

    @Test fun sentinelsAndOutOfRangeValuesAreCaught() {
        val bad = fine().copy(
            matchTimeSec = -1,
            self = UnitState(Side.ALLY, UnitKind.MINION, health = -1f),
            units = listOf(UnitState(Side.SELF, UnitKind.CHARACTER, confidence = 1.5f, screenPos = Vec2(Float.NaN, 0f))),
            resources = ResourceState(gold = -1, level = 0, levelProgress = 2f),
            assignedZone = "Mid Lane", gameSpecific = mapOf("BattleSpell" to "x"), gameId = "MLBB",
            allyCharacters = listOf(" "))
        val v = StateContract.violations(bad).joinToString("\n")
        for (part in listOf("matchTimeSec", "self has side ALLY", "self is a MINION", "self.health", "units[0] has side SELF",
                "units[0].confidence", "units[0].screenPos", "resources.gold", "resources.level ", "resources.levelProgress",
                "assignedZone", "gameSpecific key 'BattleSpell'", "gameId", "allyCharacters[0]"))
            assertTrue("expected a violation about $part in:\n$v", v.contains(part))
    }

    @Test fun anotherSchemaVersionIsReported() {
        assertTrue(StateContract.violations(fine().copy(schemaVersion = 2)).single().startsWith("schemaVersion 2"))
    }

    @Test fun aNewStateCarriesTheCurrentVersion() {
        assertEquals(UniversalMatchState.SCHEMA_VERSION, fine().schemaVersion)
        assertEquals(1, UniversalMatchState.SCHEMA_VERSION)
    }

    // A field added, removed or renamed without updating the contract fails here: update
    // docs/contracts/UNIVERSAL_MATCH_STATE.md, raise SCHEMA_VERSION if the meaning changes, then this list.
    private fun fields(c: Class<*>) = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    @Test fun theFieldsAreThoseOfVersion1() {
        assertEquals(setOf("gameId", "timestampMs", "phase", "matchTimeSec", "self", "units", "resources", "assignedZone",
            "allyCharacters", "enemyCharacters", "gameSpecific", "capabilities", "schemaVersion"),
            fields(UniversalMatchState::class.java))
        assertEquals(setOf("side", "kind", "characterId", "health", "screenPos", "mapPos", "velocity", "confidence", "gameSpecific"),
            fields(UnitState::class.java))
        assertEquals(setOf("gold", "itemCount", "level", "levelProgress"), fields(ResourceState::class.java))
        assertEquals(setOf("x", "y"), fields(Vec2::class.java))
        assertEquals(listOf("UNKNOWN", "OUT_OF_MATCH", "CHARACTER_SELECT", "LOADING", "IN_MATCH", "PLAYER_DEAD", "POST_MATCH",
            "SPECTATING"), MatchPhase.values().map { it.name })
        assertEquals(listOf("SELF", "ALLY", "ENEMY", "NEUTRAL"), Side.values().map { it.name })
        assertEquals(listOf("CHARACTER", "MINION", "NEUTRAL_MONSTER", "MAJOR_OBJECTIVE", "STRUCTURE"), UnitKind.values().map { it.name })
    }

    @Test fun theDecisionLogKeepsTheStateVersion() {
        val e = DecisionLog().record(fine(), emptyList(), null, 1000L)
        assertEquals(UniversalMatchState.SCHEMA_VERSION, e.schemaVersion)
    }
}
