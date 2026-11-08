package com.example.mlbbresearch.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JungleCampDoneTest {

    @Test fun hiddenBarsOnAHealthyMonsterAreNotAKill() {
        // device test: effects hid the bar for 1.5 s while the monster still had 60% HP
        assertFalse(JungleNav.campDone(sawMonsterAt = 10_000L, lastMonsterHp = 0.6f, now = 11_500L, campIconUp = true))
    }

    @Test fun nearlyDeadAndGoneIsAKill() {
        assertTrue(JungleNav.campDone(10_000L, 0.2f, 11_300L, campIconUp = true))
    }

    @Test fun campIconLeftTheMinimap() {
        assertTrue(JungleNav.campDone(10_000L, 0.6f, 11_300L, campIconUp = false))
    }

    @Test fun longSilenceEndsIt() {
        assertFalse(JungleNav.campDone(10_000L, 0.9f, 15_000L, campIconUp = true))
        assertTrue(JungleNav.campDone(10_000L, 0.9f, 16_500L, campIconUp = true))
    }

    @Test fun neverSawAMonster() {
        assertFalse(JungleNav.campDone(0L, 1f, 99_000L, campIconUp = false))
    }
}
