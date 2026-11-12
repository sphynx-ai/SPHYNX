package com.example.mlbbresearch.vision

// AT A JUNGLE CAMP, SMALL RED BARS ARE THE CAMP (owner, 2026-11-12 - "it sometimes leaves the buff alive and
// didn't finish it off"; the 2026-11-11 recording, 0:36-0:46 at the Molten Fiend: no monster was seen at all -
// its bar was read as enemy heroes (fixed by HeroBarRule) and as enemy MINIONS ("clearing the wave", "last hit:
// minion") in a pit with no minions, so the Player walked off to "a jungle camp" while the buff lived).
// While you stand at a known camp spot (JungleSpots) and no enemy minion wave is near you on the minimap, an
// enemy minion-sized bar is that camp's monster - so the camp's own rules (finish it first, stay in its leash)
// keep you on it until it dies. Pure logic, unit tested.
object CampRule {

    const val WAVE_NEAR = 0.12f        // minimap fractions

    fun minionIsTheCamp(atCampSpot: Boolean, enemyWaveNear: Boolean): Boolean = atCampSpot && !enemyWaveNear

    fun waveNear(me: Pair<Float, Float>, waves: List<Pair<Float, Float>>): Boolean =
        waves.any { kotlin.math.hypot(it.first - me.first, it.second - me.second) < WAVE_NEAR }
}
