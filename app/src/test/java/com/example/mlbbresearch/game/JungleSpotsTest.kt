package com.example.mlbbresearch.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Every monster in the owner's 20 jungle screenshots (2026-11-11), named from where the hero stood on the
// minimap (read off each screenshot) and the size of its bar (buff or camp).
class JungleSpotsTest {

    private fun at(x: Float, y: Float, kind: MonsterKind, sec: Int = 300) = JungleSpots.at(x to y, kind, sec)

    @Test fun theOwnersScreenshots() {
        assertEquals(JungleDb.THUNDER_FENRIR, at(0.29f, 0.52f, MonsterKind.BUFF, 50))   // 0:50 and 1:27
        assertEquals(JungleDb.MOLTEN_FIEND, at(0.43f, 0.78f, MonsterKind.BUFF, 108))    // 1:48 and 1:55
        assertEquals(JungleDb.LAVA_GOLEM, at(0.48f, 0.70f, MonsterKind.CAMP, 131))      // 2:11 and 2:17
        assertEquals(JungleDb.FIRE_BEETLE, at(0.58f, 0.82f, MonsterKind.CAMP, 149))     // 2:29 .. 2:44
        assertEquals(JungleDb.CRAB, at(0.79f, 0.72f, MonsterKind.CAMP, 173))            // 2:53 and 2:59
        assertEquals(JungleDb.HORNED_LIZARD, at(0.20f, 0.41f, MonsterKind.CAMP, 222))   // 3:42 and 3:47
        assertEquals(JungleDb.CRAB, at(0.20f, 0.25f, MonsterKind.CAMP, 237))            // 3:57 and 4:03 (upgraded)
        assertEquals(JungleDb.LITHOWANDERER, at(0.38f, 0.37f, MonsterKind.CAMP, 256))   // 4:16 and 5:07
    }

    @Test fun theEnemyJungleIsTheMirror() {
        assertEquals(JungleDb.THUNDER_FENRIR, at(0.71f, 0.48f, MonsterKind.BUFF))
        assertEquals(JungleDb.MOLTEN_FIEND, at(0.54f, 0.23f, MonsterKind.BUFF))
        assertEquals(JungleDb.LAVA_GOLEM, at(0.51f, 0.30f, MonsterKind.CAMP))
        assertEquals(JungleDb.HORNED_LIZARD, at(0.79f, 0.60f, MonsterKind.CAMP))
        assertEquals(JungleDb.LITHOWANDERER, at(0.62f, 0.63f, MonsterKind.CAMP))
    }

    @Test fun theBarsKindDecidesBetweenNeighbours() {
        // the orange buff and the Lava Golem are close: a buff bar is the buff, a camp bar the golem
        assertEquals(JungleDb.MOLTEN_FIEND, at(0.47f, 0.74f, MonsterKind.BUFF))
        assertEquals(JungleDb.LAVA_GOLEM, at(0.47f, 0.72f, MonsterKind.CAMP))
    }

    @Test fun nothingAwayFromTheSpotsOrTooEarly() {
        assertNull(at(0.5f, 0.5f, MonsterKind.CAMP))                       // the mid crossing
        assertNull(at(0.38f, 0.37f, MonsterKind.CAMP, 20))                // the Lithowanderer is not out before 0:35
        assertNull(at(0.29f, 0.52f, MonsterKind.BOSS))
        assertNull(JungleSpots.at(null, MonsterKind.CAMP))
    }

    // the two bosses (owner, 2026-11-11): the Lord in the top-left pit (screenshot at 5:40 - before its usual
    // 8:00, so the pit decides, not the clock) and the Turtle in the bottom-right pit
    @Test fun lordAndTurtleByTheirPits() {
        fun mini(x: Float, y: Float) = com.example.mlbbresearch.vision.MinimapInfo(0, 0, emptyList(), self = x to y)
        assertEquals(JungleDb.LORD, JungleDb.boss(mini(0.29f, 0.30f), 340))
        assertEquals(JungleDb.TURTLE, JungleDb.boss(mini(0.70f, 0.70f), 600))
    }
}
