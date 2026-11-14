package com.sphynx.universal

import com.sphynx.universal.TargetFusion.Observation
import com.sphynx.universal.TargetFusion.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetFusionTest {

    private val me = 0.40f to 0.60f
    private fun icon(x: Float, y: Float) = Observation(x, y, Source.MAP_ICON)
    private fun arc(x: Float, y: Float) = Observation(x, y, Source.PARTIAL_ARC)
    private fun screen(x: Float, y: Float) = Observation(x, y, Source.SCREEN)

    @Test fun arcsOfOneCoveredIconAreOneTarget() {
        val f = TargetFusion()
        f.update(0L, me, listOf(icon(0.43f, 0.61f)))
        // next frame your ring covers it: two arc fragments of the same red ring remain
        val out = f.update(100L, me, listOf(arc(0.425f, 0.60f), arc(0.44f, 0.625f)))
        assertEquals(1, out.size)
        assertEquals(1, out[0].id)
    }

    @Test fun arcsAloneNeverCreateAnEnemy() {
        // red specks next to your icon (minion marks, effects) with no enemy known there
        val out = TargetFusion().update(0L, me, listOf(arc(0.42f, 0.61f), arc(0.39f, 0.58f)))
        assertTrue(out.isEmpty())
    }

    @Test fun screenEnemyAndItsMapIconCountOnce() {
        val out = TargetFusion().update(0L, me, listOf(icon(0.50f, 0.55f), screen(0.53f, 0.57f)))
        assertEquals(1, out.size)
        assertEquals(0.50f, out[0].x, 1e-6f)                    // the map icon's position is kept
        assertEquals(setOf(Source.MAP_ICON, Source.SCREEN), out[0].sources)
    }

    @Test fun enemyHiddenUnderYourIconIsKeptForAMoment() {
        val f = TargetFusion()
        f.update(0L, me, listOf(icon(0.42f, 0.61f)))
        val covered = f.update(800L, me, emptyList())           // nothing seen: your icon covers it
        assertEquals(1, covered.size)
        assertTrue(covered[0].occluded)
        assertTrue(f.update(1600L, me, emptyList()).isEmpty())  // gone after 1.5 s
    }

    @Test fun enemyLostElsewhereIsDroppedQuickly() {
        val f = TargetFusion()
        f.update(0L, me, listOf(icon(0.80f, 0.20f)))            // far from you
        assertEquals(1, f.update(300L, me, emptyList()).size)
        assertTrue(f.update(500L, me, emptyList()).isEmpty())
    }

    @Test fun screenKeepsACoveredEnemyAndItsId() {
        val f = TargetFusion()
        val a = f.update(0L, me, listOf(icon(0.43f, 0.61f))).single()
        // the map icon is covered, the gameplay screen still sees the enemy
        val b = f.update(2000L, me, listOf(screen(0.44f, 0.62f))).single()
        assertEquals(a.id, b.id)
        assertFalse(b.occluded)
        assertEquals(2000L, b.confirmedMs)
    }

    @Test fun idsStayStableAndDoNotSwap() {
        val f = TargetFusion()
        val first = f.update(0L, me, listOf(icon(0.30f, 0.30f), icon(0.36f, 0.30f)))
        val ids = first.associate { it.x to it.id }
        val next = f.update(100L, me, listOf(icon(0.31f, 0.30f), icon(0.37f, 0.30f)))
        assertEquals(ids[0.30f], next.first { it.x == 0.31f }.id)
        assertEquals(ids[0.36f], next.first { it.x == 0.37f }.id)
    }

    @Test fun atMostFiveTargets() {
        val obs = (0 until 7).map { icon(0.1f + it * 0.12f, 0.1f) }
        assertEquals(5, TargetFusion().update(0L, me, obs).size)
    }

    @Test fun screenOnlyTargetsAreDroppedAsMisreads() {
        val f = TargetFusion()
        // a jungle monster's red HP bar read as an enemy hero: on screen, never on the minimap
        assertEquals(1, f.update(0L, me, listOf(screen(0.45f, 0.62f))).size)
        assertEquals(1, f.update(500L, me, listOf(screen(0.45f, 0.62f))).size)
        assertTrue(f.update(900L, me, listOf(screen(0.45f, 0.62f))).isEmpty())
    }

    @Test fun screenTargetConfirmedByTheMapStays() {
        val f = TargetFusion()
        f.update(0L, me, listOf(screen(0.60f, 0.40f)))
        f.update(400L, me, listOf(screen(0.60f, 0.40f), icon(0.61f, 0.41f)))
        assertEquals(1, f.update(2000L, me, listOf(screen(0.60f, 0.40f))).size)
    }

    @Test fun screenCheckDropsWhatTheMapNeverBacks() {
        val k = TargetFusion.Companion
        // the Lithowanderer read as "an enemy at 4%" right below you, no red icon on the map: dropped after 0.8 s
        assertTrue(k.keepScreenEnemy(firstSeenMs = 0L, nowMs = 500L, mapConfirmedNear = false, distToYou = 0.09f, hp = 0.04f))
        assertFalse(k.keepScreenEnemy(0L, 1000L, false, 0.09f, 0.04f))
        // a real enemy the map shows: kept
        assertTrue(k.keepScreenEnemy(0L, 5000L, true, 0.5f, 0.04f))
        // a healthy enemy right next to you whose map icon your own icon hides: kept
        assertTrue(k.keepScreenEnemy(0L, 5000L, false, 0.10f, 0.6f))
        // something far away the map never shows: dropped
        assertFalse(k.keepScreenEnemy(0L, 5000L, false, 0.5f, 0.6f))
    }

    @Test fun mapConfirmedFlag() {
        val f = TargetFusion()
        assertFalse(f.update(0L, me, listOf(screen(0.6f, 0.4f))).single().mapConfirmed)
        assertTrue(f.update(100L, me, listOf(screen(0.6f, 0.4f), icon(0.61f, 0.4f))).single().mapConfirmed)
    }
}
