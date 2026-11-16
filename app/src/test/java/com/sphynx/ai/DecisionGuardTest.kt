package com.sphynx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class DecisionGuardTest {

    private fun dec(intent: Intent, mx: Float = 0.5f, my: Float = -0.5f, casts: List<String> = emptyList(),
                    tx: Float = -1f, ty: Float = -1f, hp: Float = -1f, reason: String = "r") =
        TacticalDecision(intent, mx, my, casts, tx, ty, "-", hp, -1, reason)

    @Before fun reset() = DecisionGuard.resetForTest()

    @Test fun aValidDecisionIsReturnedUnchanged() {
        val d = dec(Intent.ENGAGE, casts = listOf("attack", "s1"), tx = 0.4f, ty = 0.5f, hp = 0.3f)
        assertSame(d, DecisionGuard.check(dec(Intent.ENGAGE), d))
        assertEquals(0L, DecisionGuard.corrected)
    }

    @Test fun aBlendNeverReplacesTheRulesRetreatRecallOrRegen() {
        for (safe in listOf(Intent.RETREAT, Intent.RECALL, Intent.REGEN)) {
            val rules = dec(safe, mx = -1f, my = 0f, reason = "low hp")
            val out = DecisionGuard.check(rules, dec(Intent.ENGAGE, casts = listOf("ult")))
            assertSame(rules, out)
        }
        assertEquals(3L, DecisionGuard.corrected)
    }

    @Test fun anotherIntentFromABlendIsKept() {
        val blended = dec(Intent.IMITATE)
        assertSame(blended, DecisionGuard.check(dec(Intent.LANE_CLEAR), blended))
    }

    @Test fun movementIsFiniteAndInsideTheJoystick() {
        val out = DecisionGuard.sane(dec(Intent.PUSH, mx = Float.NaN, my = 3f))
        assertEquals(0f, out.moveX, 0f)
        assertEquals(1f, out.moveY, 0f)
        val inf = DecisionGuard.sane(dec(Intent.PUSH, mx = Float.NEGATIVE_INFINITY, my = -2f))
        assertEquals(0f, inf.moveX, 0f)
        assertEquals(-1f, inf.moveY, 0f)
    }

    @Test fun aNonFiniteTargetMeansNoTarget() {
        val out = DecisionGuard.sane(dec(Intent.ENGAGE, tx = Float.NaN, ty = 0.3f, hp = Float.NaN))
        assertEquals(-1f, out.targetX, 0f)
        assertEquals(-1f, out.targetY, 0f)
        assertEquals(-1f, out.targetHp, 0f)
    }

    @Test fun castsArePressedOnceAndNeverBlank() {
        val out = DecisionGuard.sane(dec(Intent.ENGAGE, casts = listOf("s1", "", "attack", "s1", " ")))
        assertEquals(listOf("s1", "attack"), out.casts)
        assertEquals(1L, DecisionGuard.corrected)
    }
}
