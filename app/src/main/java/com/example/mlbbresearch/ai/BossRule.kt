package com.example.mlbbresearch.ai

// THE LORD NEEDS THE TEAM (owner, 2026-11-12: "it keeps on attacking the lord even though there is no ally
// nearby to help, then the research player is killed by the lord"): the Research player hits the Lord only
// with 3 or more allied heroes near it (on the screen, or on the minimap around you). With fewer it does not
// start it, does not rotate into its pit, and walks away from it when it is close. The Turtle is unchanged.
// Pure logic, unit tested.
object BossRule {

    const val LORD_ALLIES = 3

    fun mayHitLord(alliesNear: Int): Boolean = alliesNear >= LORD_ALLIES

    // allies near the boss: the best of what the screen and the minimap show
    fun alliesNear(onScreenNearBoss: Int, onMinimapNearYou: Int): Int = maxOf(onScreenNearBoss, onMinimapNearYou)
}
