package com.example.mlbbresearch.vision

// WHEN A RED BAR IS AN ENEMY HERO (owner, 2026-11-12, from the 2026-11-11 recording): only with its OWN level
// badge AND an enemy hero icon near you on the minimap. While a camp is being fought, a bar where the monster
// was just seen stays the monster unless its level number can be read. Everything else is a monster (or a
// tower), so no chase and no Retribution is ever spent on it as a "hero". Pure logic, unit tested.
object HeroBarRule {

    const val MONSTER_MEMORY_MS = 2500L
    const val MONSTER_NEAR = 0.12f            // screen heights (x scaled by the aspect)
    const val BADGE_W = 0.022f                // the badge spot left of a bar (screen widths), as badgeDark reads it

    fun isEnemyHero(badge: Boolean, enemyNearOnMap: Boolean, atRecentMonster: Boolean, levelNumber: Boolean): Boolean =
        badge && enemyNearOnMap && (!atRecentMonster || levelNumber)

    // the badge spot of a bar at (barLeft, cy) overlaps YOUR badge (left of your bar at (selfLeft, selfCy))
    fun overlapsOwnBadge(barLeft: Float, cy: Float, selfLeft: Float, selfCy: Float): Boolean =
        kotlin.math.abs(cy - selfCy) < 0.03f && barLeft - BADGE_W < selfLeft && selfLeft - BADGE_W < barLeft
}
