package com.sphynx.ai

import com.sphynx.learning.FightOption
import com.sphynx.universal.DecisionKind
import com.sphynx.universal.EngineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecisionBridgeTest {

    @Test fun everyFightOptionMaps() {
        assertEquals(DecisionKind.ENGAGE, DecisionBridge.kind(FightOption.ALL_IN))
        assertEquals(DecisionKind.DEFER, DecisionBridge.kind(FightOption.RULES))
        for (o in FightOption.values()) DecisionBridge.kind(o)
        val d = DecisionBridge.fromFight(FightOption.RETREAT, EngineSource.TACTICAL, 500L)
        assertEquals(EngineSource.TACTICAL, d.source)
        assertNull(d.confidence)
    }

    @Test fun strategicPlanMapsWithoutInventingConfidence() {
        val d = DecisionBridge.fromPlan(StrategyPlan("push", 0.9f, "weakest", "take the mid tower", 1000L))!!
        assertEquals(DecisionKind.TAKE_OBJECTIVE, d.kind)
        assertEquals(EngineSource.STRATEGIC, d.source)
        assertNull(d.confidence)   // aggression 0.9 is a style, not a confidence
        assertEquals(DecisionKind.DEFEND, DecisionBridge.fromPlan(StrategyPlan("Defend", 0.2f, "nearest", "", 5L))!!.kind)
    }

    @Test fun defaultOrUnknownPlanIsNoAdvice() {
        assertNull(DecisionBridge.fromPlan(StrategyPlan.DEFAULT))
        assertNull(DecisionBridge.fromPlan(StrategyPlan("dance", 0.5f, "weakest", "", 1000L)))
    }
}
