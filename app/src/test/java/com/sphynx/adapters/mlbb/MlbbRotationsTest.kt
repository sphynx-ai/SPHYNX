package com.sphynx.adapters.mlbb

import com.sphynx.game.JungleDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 13 D6: rotation concepts, on the app's own objective timers
class MlbbRotationsTest {

    private val team = MlbbRotations.Team(engage = 2, hasMarksman = true, splitPusher = true, magicJungler = false)

    @Test fun theTimersAreTheAppsOwn() {
        assertEquals(120, MlbbRotations.TURTLE_FIRST); assertEquals(120, MlbbRotations.TURTLE_RESPAWN); assertEquals(480, MlbbRotations.LORD_FIRST)
        assertEquals(JungleDb.TURTLE.firstSec, MlbbRotations.TURTLE_FIRST)
    }

    @Test fun plansFollowTheMatch() {
        val early = MlbbRotations.plans(team, MlbbRotations.Moment(30))
        assertTrue(early.first().name.startsWith("1-2-2 opening with a orange buff"))
        assertTrue(MlbbRotations.plans(team.copy(magicJungler = true), MlbbRotations.Moment(30)).first().opening.contains("purple"))
        assertTrue(MlbbRotations.plans(team, MlbbRotations.Moment(110)).any { it.name == "Turtle set-up" })
        assertTrue(MlbbRotations.plans(team, MlbbRotations.Moment(300, turtleUp = false)).none { it.name == "Turtle set-up" })
        assertTrue(MlbbRotations.plans(team, MlbbRotations.Moment(500)).any { it.name.startsWith("Lord") })
        val late = MlbbRotations.plans(team, MlbbRotations.Moment(1000))
        assertTrue(late.any { it.name.startsWith("1-3-1") }); assertTrue(late.any { it.name.startsWith("4-1") })
        assertTrue(MlbbRotations.plans(team.copy(splitPusher = false), MlbbRotations.Moment(1000)).none { it.name.startsWith("1-3-1") })
        assertEquals("Defend while allies are down", MlbbRotations.plans(team, MlbbRotations.Moment(600, alliesDown = 3, enemiesDown = 1)).first().name)
    }

    @Test fun everyPlanIsAConceptWithItsParts() {
        for (s in listOf(30, 110, 500, 1000)) for (p in MlbbRotations.plans(team, MlbbRotations.Moment(s))) {
            assertEquals(MlbbRotations.CONCEPT, p.label)
            assertTrue(p.name, p.prerequisites.isNotEmpty() && p.risks.isNotEmpty() && p.fallback.isNotBlank() && p.responsibilities.isNotEmpty())
        }
    }
}
