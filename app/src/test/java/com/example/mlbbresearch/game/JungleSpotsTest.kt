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
        MapSide.reset()                                    // not read yet: the tables' own map (Lord top-left)
        val icon = floatArrayOf(0f, 0f, 0.01f, 0.01f)
        // solo practice: both are up (both icons on the minimap) - the pit decides, at any time
        fun both(x: Float, y: Float) = com.example.mlbbresearch.vision.MinimapInfo(0, 0, emptyList(), self = x to y, lordIcon = icon, turtleIcon = icon)
        assertEquals(JungleDb.LORD, JungleDb.boss(both(0.29f, 0.30f), 340, MatchKind.Kind.UNKNOWN))
        assertEquals(JungleDb.TURTLE, JungleDb.boss(both(0.70f, 0.70f), 600, MatchKind.Kind.UNKNOWN))
        // icons not read: the pit before 9:00
        fun mini(x: Float, y: Float) = com.example.mlbbresearch.vision.MinimapInfo(0, 0, emptyList(), self = x to y)
        assertEquals(JungleDb.TURTLE, JungleDb.boss(mini(0.70f, 0.70f), 300, MatchKind.Kind.UNKNOWN))
        // a real match (wiki): from 9:00 there is no Turtle - the Lord can stand in its pit
        assertEquals(JungleDb.LORD, JungleDb.boss(mini(0.70f, 0.70f), 600, MatchKind.Kind.UNKNOWN))
        // only the Lord's icon shows (it replaced the Turtle at 8:00): the Lord, even in the Turtle's pit
        assertEquals(JungleDb.LORD, JungleDb.boss(com.example.mlbbresearch.vision.MinimapInfo(0, 0, emptyList(), self = 0.70f to 0.70f, lordIcon = icon), 500, MatchKind.Kind.UNKNOWN))
    }

    // the owner's setup (2026-11-11): solo practice for calibration (both bosses up, no timers), 5v5 for real data
    @Test fun soloPracticeAndFiveVFive() {
        MapSide.reset()
        val solo = MatchKind.Kind.SOLO; val five = MatchKind.Kind.FIVE_V_FIVE
        fun mini(x: Float, y: Float) = com.example.mlbbresearch.vision.MinimapInfo(0, 0, emptyList(), self = x to y)
        // solo: the pit decides at any time, even far off and with no icons read
        assertEquals(JungleDb.TURTLE, JungleDb.boss(mini(0.70f, 0.70f), 900, solo))
        assertEquals(JungleDb.LORD, JungleDb.boss(mini(0.29f, 0.30f), 10, solo))
        assertEquals(JungleDb.TURTLE, JungleDb.boss(mini(0.55f, 0.95f), 900, solo))   // nearer the Turtle's pit
        // 5v5: from 9:00 no Turtle, whatever the icons show
        val icon = floatArrayOf(0f, 0f, 0.01f, 0.01f)
        assertEquals(JungleDb.LORD, JungleDb.boss(com.example.mlbbresearch.vision.MinimapInfo(0, 0, emptyList(),
            self = 0.70f to 0.70f, lordIcon = icon, turtleIcon = icon), 600, five))
        assertEquals(JungleDb.TURTLE, JungleDb.boss(mini(0.70f, 0.70f), 200, five))
    }

    @Test fun theMatchKind() {
        MatchKind.reset()
        assertEquals(MatchKind.Kind.SOLO, MatchKind.fromPlayers(2))
        assertEquals(MatchKind.Kind.FIVE_V_FIVE, MatchKind.fromPlayers(10))
        assertEquals(MatchKind.Kind.UNKNOWN, MatchKind.fromPlayers(0))
        MatchKind.set(7L, MatchKind.Kind.SOLO)
        assertEquals(MatchKind.Kind.SOLO, MatchKind.of(7L))
        assertEquals(MatchKind.Kind.UNKNOWN, MatchKind.of(8L))      // a new match starts unknown
        MatchKind.reset()
    }

    // the owner's 5 enemy-jungle screenshots (2026-11-11), measured where the hero stood
    @Test fun theEnemyBuffsMeasured() {
        assertEquals(JungleDb.THUNDER_FENRIR, at(0.75f, 0.52f, MonsterKind.BUFF, 34))
        assertEquals(JungleDb.THUNDER_FENRIR, at(0.73f, 0.46f, MonsterKind.BUFF, 42))
        assertEquals(JungleDb.MOLTEN_FIEND, at(0.51f, 0.16f, MonsterKind.BUFF, 55))
        assertEquals(JungleDb.MOLTEN_FIEND, at(0.47f, 0.22f, MonsterKind.BUFF, 60))
        assertEquals(JungleDb.FIRE_BEETLE, at(0.40f, 0.16f, MonsterKind.CAMP, 68))
    }

    // only ONE Lithowanderer is up at a time (owner, 2026-11-11)
    @Test fun oneLithowandererAtATime() {
        Lithowanderer.reset()
        try {
            Lithowanderer.observe(10_000, 0.38f to 0.37f, 0.9f)                    // seen alive on the top side
            assertEquals(JungleDb.LITHOWANDERER, JungleSpots.at(0.38f to 0.37f, MonsterKind.CAMP, 100, 20_000))
            assertNull(JungleSpots.at(0.62f to 0.63f, MonsterKind.CAMP, 100, 20_000))   // so not on the other side
            assertEquals(JungleDb.LITHOWANDERER, JungleSpots.at(0.62f to 0.63f, MonsterKind.CAMP, 100))   // no memory given
        } finally { Lithowanderer.reset() }
    }
}
