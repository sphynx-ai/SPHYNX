package com.sphynx.adapters.mlbb

import com.sphynx.universal.LandmarkKind
import com.sphynx.universal.RegionKind
import com.sphynx.universal.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class MlbbSpatialTest {

    private val map = MlbbSpatial.parse(File("src/main/assets/" + MlbbSpatial.ASSET).readText())
    private fun lm(id: String) = map.landmarks.single { it.id == id }

    @Test fun containsTheVerifiedLandmarks() {
        val byKind = map.landmarks.groupBy { it.kind }
        assertEquals(2, byKind[LandmarkKind.BASE]!!.size)
        assertEquals(18, byKind[LandmarkKind.TURRET]!!.size)
        assertEquals(2, byKind[LandmarkKind.MAJOR_OBJECTIVE]!!.size)
        assertEquals(4, byKind[LandmarkKind.BUFF_CAMP]!!.size)
        assertTrue(byKind[LandmarkKind.CAMP]!!.size >= 4)
        assertTrue(map.landmarks.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test fun yourBaseBottomLeftEnemyTopRight() {
        assertTrue(lm("ally_base").x < 0.3f && lm("ally_base").y > 0.7f)
        assertTrue(lm("enemy_base").x > 0.7f && lm("enemy_base").y < 0.3f)
    }

    @Test fun everyTurretIsInItsOwnLaneOrBase() {
        for (t in map.landmarks.filter { it.kind == LandmarkKind.TURRET }) {
            val r = map.regionAt(t.x, t.y)
            val lane = t.id.split('_')[1]                     // exp / mid / gold
            val ok = (r.kind == RegionKind.LANE && r.name.lowercase().startsWith(lane)) || (r.kind == RegionKind.BASE && r.side == t.side)
            assertTrue("${t.id} -> $r", ok)
        }
    }

    @Test fun mapIsPointSymmetricLikeTheGame() {
        for (a in map.landmarks.filter { it.side == Side.ALLY && (it.kind == LandmarkKind.TURRET || it.kind == LandmarkKind.BASE) }) {
            // lanes are named by physical lane, so under the 180-degree symmetry your EXP turrets
            // mirror the enemy's Gold-lane turrets (and Gold mirrors EXP; mid mirrors mid)
            val mirrorId = a.id.replaceFirst("ally_", "enemy_").let {
                when { "_exp_" in it -> it.replace("_exp_", "_gold_"); "_gold_" in it -> it.replace("_gold_", "_exp_"); else -> it }
            }
            val e = lm(mirrorId)
            assertTrue("${a.id} mirror off by ${hypot(1 - a.x - e.x, 1 - a.y - e.y)}", hypot(1 - a.x - e.x, 1 - a.y - e.y) < 0.05f)
        }
    }

    @Test fun agreesWithTheAppsOwnOuterTurretCalibration() {
        // MinimapAnalyzer.outerSpots (hand-calibrated in v37.16, independent of the 3D model)
        val spots = mapOf("enemy_exp_turret_3" to (0.275f to 0.042f), "enemy_mid_turret_3" to (0.606f to 0.404f), "enemy_gold_turret_3" to (0.933f to 0.714f))
        for ((id, s) in spots) {
            val t = lm(id)
            assertTrue("$id off by ${hypot(t.x - s.first, t.y - s.second)}", hypot(t.x - s.first, t.y - s.second) < 0.035f)
        }
    }

    @Test fun pitsOnTheRiverBuffsAndCampsInTheirOwnJungle() {
        assertEquals(RegionKind.RIVER, map.regionAt(lm("lord_pit").x, lm("lord_pit").y).kind)
        assertEquals(RegionKind.RIVER, map.regionAt(lm("turtle_pit").x, lm("turtle_pit").y).kind)
        for (b in map.landmarks.filter { it.kind == LandmarkKind.BUFF_CAMP || it.kind == LandmarkKind.CAMP }) {
            val r = map.regionAt(b.x, b.y)
            assertTrue("${b.id} -> $r", r.kind == RegionKind.JUNGLE && r.side == b.side)
        }
    }

    @Test fun describeGivesStrategicContext() {
        val p = lm("enemy_purple_buff")
        val d = map.describe(p.x, p.y)
        assertTrue(d, d.startsWith("position: enemy jungle"))
        assertTrue(d, d.contains("nearest objective:") && d.contains("nearest enemy turret:") && d.contains("your base"))
        val mid = map.regionAt(0.5f, 0.5f)                    // map centre: mid lane crossing the river
        assertEquals(RegionKind.LANE, mid.kind)
    }
}
