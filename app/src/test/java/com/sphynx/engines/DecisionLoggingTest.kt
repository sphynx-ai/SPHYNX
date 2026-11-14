package com.sphynx.engines

import com.sphynx.core.Cfg
import com.sphynx.game.GameState
import com.sphynx.game.Phase
import com.sphynx.learning.FightOption
import com.sphynx.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DecisionLoggingTest {

    private fun state() = GameState(1000L, Phase.IN_GAME, null, 0.7f, emptyList(), emptyList(), emptyList(), emptyList(),
        0, 2.2f, "Karina", emptyList(), emptyList(), -1, -1, 0L)

    @Before fun clean() { TestSupport.freshCfg(); DecisionLogging.log.clear() }

    @Test fun recordsAtMostTwiceASecondAndOnlyWhenSomethingIsPlayed() {
        Cfg.decisionLogOn = true                                                      // (off by default on a new install since #72)
        DecisionLogging.observe(state(), FightOption.FARM, null, 10_000L)            // nothing played: no record
        DecisionLogging.observe(state(), FightOption.FARM, FightOption.FARM, 10_000L)
        DecisionLogging.observe(state(), FightOption.FARM, FightOption.FARM, 10_200L) // too soon
        DecisionLogging.observe(state(), FightOption.FARM, FightOption.FARM, 10_600L)
        assertEquals(2, DecisionLogging.log.total)
    }

    @Test fun canBeSwitchedOff() {
        Cfg.decisionLogOn = false
        DecisionLogging.observe(state(), FightOption.FARM, FightOption.FARM, 20_000L)
        assertEquals(0, DecisionLogging.log.total)
    }
}
