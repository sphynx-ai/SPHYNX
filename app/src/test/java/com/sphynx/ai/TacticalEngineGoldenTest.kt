package com.sphynx.ai

import com.sphynx.core.Cfg
import com.sphynx.game.CooldownTracker
import com.sphynx.game.Damage
import com.sphynx.game.GameMemory
import com.sphynx.game.GameState
import com.sphynx.game.HeroProfile
import com.sphynx.game.Lane
import com.sphynx.game.Lithowanderer
import com.sphynx.game.MapSide
import com.sphynx.game.Phase
import com.sphynx.game.Role
import com.sphynx.game.UltRule
import com.sphynx.testing.MemoryPrefs
import com.sphynx.vision.MapTower
import com.sphynx.vision.Seen
import com.sphynx.vision.Team
import com.sphynx.vision.Track
import com.sphynx.vision.WallMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

// THE MLBB REGRESSION NET (Phase 2 submilestone 9, started early - docs/PHASE_2_ARCHITECTURE_MAP.md section 10).
// Fixed MLBB situations go through TacticalEngine.decide and the decisions are compared with the recorded ones in
// src/test/resources/golden/tactical_engine_v1.txt. The Phase 2 refactors (ADR 0001) must not change MLBB
// behaviour: a different decision fails here. A change made ON PURPOSE updates the golden file in the same PR,
// and the PR says why.
// The scenarios run in a fixed order on a fresh in-memory settings store (new-install defaults), so hidden engine
// state (memory, deliberation scores) is the same on every run.
class TacticalEngineGoldenTest {

    private val goldenPath = "/golden/tactical_engine_v1.txt"

    // ---- the heroes (fixed profiles, not read from the hero database, so a data update does not move them) ----
    private val cds = mapOf("s1" to 6f, "s2" to 8f, "s3" to 10f, "ult" to 35f, "spell" to 100f, "recall" to 0f, "regen" to 40f)
    private val fighter = HeroProfile("TestFighter", Role.FIGHTER, Damage.PHYSICAL, 0.16f, 0.42f, 0.28f,
        listOf("s1", "s2", "ult"), "s2", "s1", UltRule.ENGAGE, cds)
    private val marksman = HeroProfile("TestMarksman", Role.MARKSMAN, Damage.PHYSICAL, 0.40f, 0.45f, 0.40f,
        listOf("s1", "s2", "ult"), "s1", "s2", UltRule.EXECUTE, cds, ranged = true)

    private fun unit(team: Team, hero: Boolean, x: Float, y: Float, hp: Float, species: String = "") =
        Seen(team, hero, x, y, hp, x - 0.02f, y - 0.06f, x + 0.02f, y - 0.05f, species)

    private fun enemy(id: Int, x: Float, y: Float, hp: Float, vx: Float = 0f, vy: Float = 0f, at: Long) =
        Track(id = id, x = x, y = y, hp = hp, vx = vx, vy = vy, lastSeen = at, firstSeen = at - 2_000L, minHpSeen = hp)

    private fun state(
        ts: Long, phase: Phase = Phase.IN_GAME, selfHp: Float = 1f, self: Boolean = true,
        allies: List<Seen> = emptyList(), enemies: List<Track> = emptyList(), enemyMinions: List<Seen> = emptyList(),
        allyMinions: List<Seen> = emptyList(), monsters: List<Seen> = emptyList(), towers: List<MapTower> = emptyList(),
        matchSec: Int = 300, level: Int = 6, bossHp: Float = -1f, lane: Lane = Lane.MID, minimapSelf: Pair<Float, Float>? = 0.5f to 0.5f
    ) = GameState(
        ts = ts, phase = phase, self = if (self) unit(Team.SELF, true, 0.5f, 0.55f, selfHp) else null, selfHp = selfHp,
        allies = allies, enemies = enemies, enemyMinions = enemyMinions, allyMinions = allyMinions,
        minimapEnemies = enemies.size, aspect = 2.2f, ownHero = null, myTeam = emptyList(), enemyTeam = emptyList(),
        items = 2, gold = 800, itemsGoldAgeMs = 0L, monsters = monsters, lane = lane, matchSec = matchSec,
        minimapSelf = minimapSelf, towers = towers, bossHp = bossHp, level = level
    )

    private class Scenario(val name: String, val hero: HeroProfile, val plan: StrategyPlan, val state: GameState)

    private val farm = StrategyPlan.DEFAULT
    private val fight = StrategyPlan("fight", 0.8f, "weakest", "test: fight", 0L)
    private val retreat = StrategyPlan("retreat", 0.2f, "nearest", "test: retreat", 0L)

    private fun scenarios(): List<Scenario> {
        var t = 100_000L
        fun next(): Long { t += 1_000L; return t }
        return listOf(
            Scenario("nothing_around", fighter, farm, state(next())),
            Scenario("enemy_minion_wave", fighter, farm, state(next(), enemyMinions = listOf(
                unit(Team.ENEMY, false, 0.62f, 0.50f, 1f), unit(Team.ENEMY, false, 0.64f, 0.53f, 0.6f), unit(Team.ENEMY, false, 0.66f, 0.49f, 0.3f)),
                allyMinions = listOf(unit(Team.ALLY, false, 0.55f, 0.52f, 1f)))),
            Scenario("weak_enemy_in_reach", fighter, fight, state(next(), selfHp = 0.9f,
                enemies = listOf(enemy(1, 0.58f, 0.52f, 0.12f, at = t)))),
            Scenario("outnumbered_low_hp", fighter, farm, state(next(), selfHp = 0.15f,
                enemies = listOf(enemy(2, 0.62f, 0.50f, 1f, at = t), enemy(3, 0.66f, 0.58f, 0.9f, at = t)))),
            Scenario("low_hp_alone", marksman, farm, state(next(), selfHp = 0.18f)),
            Scenario("even_duel_with_ally", fighter, fight, state(next(), selfHp = 0.8f,
                allies = listOf(unit(Team.ALLY, true, 0.47f, 0.50f, 0.8f)), enemies = listOf(enemy(4, 0.66f, 0.50f, 0.75f, at = t)))),
            Scenario("marksman_poke_range", marksman, fight, state(next(), selfHp = 0.85f,
                enemies = listOf(enemy(5, 0.75f, 0.50f, 0.5f, at = t)), allyMinions = listOf(unit(Team.ALLY, false, 0.6f, 0.52f, 1f)))),
            Scenario("enemy_dashing_at_me", marksman, farm, state(next(), selfHp = 0.6f,
                enemies = listOf(enemy(6, 0.62f, 0.55f, 0.9f, vx = -1.6f, vy = 0f, at = t)))),
            Scenario("jungle_creep", fighter, farm, state(next(), lane = Lane.JUNGLE,
                monsters = listOf(unit(Team.NEUTRAL, false, 0.58f, 0.50f, 0.8f, "molten_fiend")))),
            Scenario("turtle_low_with_ally", fighter, fight, state(next(), lane = Lane.JUNGLE, bossHp = 0.3f,
                allies = listOf(unit(Team.ALLY, true, 0.45f, 0.5f, 0.9f)),
                monsters = listOf(unit(Team.NEUTRAL, true, 0.62f, 0.45f, 0.3f, "turtle")))),
            Scenario("enemy_tower_on_minimap", fighter, farm, state(next(), minimapSelf = 0.70f to 0.30f,
                towers = listOf(MapTower(0.74f, 0.27f, false)))),
            Scenario("before_ultimate_level_2", fighter, fight, state(next(), level = 2, matchSec = 60,
                enemies = listOf(enemy(7, 0.62f, 0.52f, 0.9f, at = t)))),
            Scenario("retreat_plan_enemy_far", marksman, retreat, state(next(), selfHp = 0.7f,
                enemies = listOf(enemy(8, 0.9f, 0.3f, 1f, at = t)))),
            Scenario("dead", fighter, farm, state(next(), phase = Phase.DEAD, self = false, selfHp = 0f)),
            Scenario("shop_open", fighter, farm, state(next(), phase = Phase.SHOP)),
            Scenario("no_self_seen", fighter, farm, state(next(), self = false, selfHp = -1f)),
        )
    }

    private fun resetAll() {
        TacticalEngine.reset()          // also JungleNav and MapIntel
        CooldownTracker.reset()
        GameMemory.reset()
        WallMap.reset()
        MapSide.reset()
        Lithowanderer.reset()
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun line(name: String, d: TacticalDecision) =
        "$name | ${d.intent} | move ${f(d.moveX)},${f(d.moveY)} | casts ${d.casts.joinToString(",").ifEmpty { "-" }} | " +
            "target ${d.targetLabel} ${f(d.targetHp)} | ${d.reason}"

    private fun runAll(): List<String> {
        Cfg.useForTests(MemoryPrefs())
        resetAll()
        return scenarios().map { sc ->
            resetAll()
            line(sc.name, TacticalEngine.decide(sc.state, sc.hero, sc.plan, learnedBias = 0f))
        }
    }

    @Test fun theSameSituationGivesTheSameDecision() {
        assertEquals(runAll(), runAll())
    }

    @Test fun everyDecisionIsWellFormed() {
        Cfg.useForTests(MemoryPrefs())
        val allowed = setOf("attack", "s1", "s2", "s3", "ult", "spell", "recall", "regen")
        for (sc in scenarios()) {
            resetAll()
            val d = TacticalEngine.decide(sc.state, sc.hero, sc.plan, 0f)
            assertTrue("${sc.name}: move ${d.moveX},${d.moveY}", d.moveX.isFinite() && d.moveY.isFinite() &&
                d.moveX in -1.01f..1.01f && d.moveY in -1.01f..1.01f)
            assertTrue("${sc.name}: casts ${d.casts}", d.casts.all { it in allowed })
            assertTrue("${sc.name}: no reason", d.reason.isNotBlank())
        }
    }

    @Test fun theDecisionsAreTheRecordedOnes() {
        val actual = runAll()
        val text = javaClass.getResource(goldenPath)?.readText()
            ?: throw AssertionError("No golden file $goldenPath yet. Today's decisions, to record as " +
                "app/src/test/resources$goldenPath:\n" + actual.joinToString("\n"))
        val expected = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (expected != actual) {
            val diff = (0 until maxOf(expected.size, actual.size)).filter { expected.getOrNull(it) != actual.getOrNull(it) }
                .joinToString("\n") { "  was: ${expected.getOrNull(it)}\n  now: ${actual.getOrNull(it)}" }
            fail("TacticalEngine decisions changed (MLBB behaviour). If on purpose, update $goldenPath and say why in the PR.\n$diff")
        }
    }
}
