package com.example.mlbbresearch.engines

import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.game.GameState
import com.example.mlbbresearch.game.Phase
import com.example.mlbbresearch.learning.FightOption
import com.example.mlbbresearch.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DecisionLoggingTest {

    private fun state() = GameState(1000L, Phase.IN_GAME, null, 0.7f, emptyList(), emptyList(), emptyList(), emptyList(),
        0, 2.2f, "Karina", emptyList(), emptyList(), -1, -1, 0L)

    @Before fun clean() { TestSupport.freshCfg(); DecisionLogging.log.clear() }

    @Test fun recordsAtMostTwiceASecondAndOnlyWhenSomethingIsPlayed() {
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
