package com.sphynx.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-12: a red bar is an enemy hero only with its own level badge AND an enemy near you on the minimap
class HeroBarRuleTest {

    @Test fun needsBadgeAndMinimap() {
        assertTrue(HeroBarRule.isEnemyHero(badge = true, enemyNearOnMap = true, atRecentMonster = false, levelNumber = false))
        assertFalse(HeroBarRule.isEnemyHero(badge = true, enemyNearOnMap = false, atRecentMonster = false, levelNumber = true))
        assertFalse(HeroBarRule.isEnemyHero(badge = false, enemyNearOnMap = true, atRecentMonster = false, levelNumber = false))
    }

    @Test fun aCampBeingFoughtStaysTheMonster() {
        assertFalse(HeroBarRule.isEnemyHero(badge = true, enemyNearOnMap = true, atRecentMonster = true, levelNumber = false))
        // a real hero standing at the camp: its level number is readable
        assertTrue(HeroBarRule.isEnemyHero(badge = true, enemyNearOnMap = true, atRecentMonster = true, levelNumber = true))
    }

    // the recording (0:36): your bar at x 0.471 and the Molten Fiend's bar starting right next to your badge
    @Test fun yourOwnBadgeIsNotTheirs() {
        assertTrue(HeroBarRule.overlapsOwnBadge(barLeft = 0.465f, cy = 0.385f, selfLeft = 0.471f, selfCy = 0.39f))
        assertFalse(HeroBarRule.overlapsOwnBadge(barLeft = 0.30f, cy = 0.385f, selfLeft = 0.471f, selfCy = 0.39f))
        assertFalse(HeroBarRule.overlapsOwnBadge(barLeft = 0.465f, cy = 0.20f, selfLeft = 0.471f, selfCy = 0.39f))
    }

    // 2026-11-13 recording: the first sighting of a camp monster's bar, right above your own bar
    @Test fun atACampSpotABarIsAHeroOnlyWithItsLevelNumber() {
        assertFalse(HeroBarRule.isEnemyHero(badge = true, enemyNearOnMap = true, atRecentMonster = false, levelNumber = false, atCampSpot = true))
        assertTrue(HeroBarRule.isEnemyHero(badge = true, enemyNearOnMap = true, atRecentMonster = false, levelNumber = true, atCampSpot = true))
    }
}
