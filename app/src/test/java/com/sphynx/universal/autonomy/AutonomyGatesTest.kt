package com.sphynx.universal.autonomy

import com.sphynx.universal.autonomy.AutonomyGates.Evidence
import com.sphynx.universal.autonomy.AutonomyGates.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 12 C3: the staged-autonomy gates
class AutonomyGatesTest {

    private val evalOk = Evidence(evalAnswers = 120, evalUnsafeShare = 0.02, evalMeanScore = 0.7, setReviewed = true)

    @Test fun eachStageNeedsItsEvidence() {
        assertEquals(Stage.OBSERVE, AutonomyGates.evaluate(Evidence()).stage)
        assertTrue(AutonomyGates.evaluate(evalOk.copy(setReviewed = false)).failing!!.contains("reviewed"))
        assertTrue(AutonomyGates.evaluate(evalOk.copy(evalUnsafeShare = 0.1)).failing!!.contains("unsafe answers at most 5%"))
        assertEquals(Stage.SHADOW, AutonomyGates.evaluate(evalOk).stage)
        val shadow = evalOk.copy(shadowDecisions = 300, shadowAgreed = 240, shadowUnsafe = 3)
        assertEquals("the owner's supervised-mode switch", AutonomyGates.evaluate(shadow).failing)
        assertTrue(AutonomyGates.evaluate(shadow.copy(shadowAgreed = 150)).failing!!.contains("agreement"))
        assertTrue(AutonomyGates.evaluate(shadow.copy(shadowUnsafe = 12)).failing!!.contains("unsafe disagreements"))
        val sup = AutonomyGates.evaluate(shadow.copy(ownerSupervised = true))
        assertEquals(Stage.SUPERVISED, sup.stage)
        assertTrue(sup.allows(Stage.SUPERVISED)); assertFalse(sup.allows(Stage.BOUNDED))
        assertEquals(3, sup.passed.size)
    }

    @Test fun boundedAutonomyIsLocked() {
        val all = evalOk.copy(shadowDecisions = 1000, shadowAgreed = 990, shadowUnsafe = 0, ownerSupervised = true, ownerBounded = true)
        val r = AutonomyGates.evaluate(all)
        assertEquals(Stage.SUPERVISED, r.stage)
        assertTrue(r.text().contains("locked by the owner's decision"))
        assertTrue(AutonomyGates.BOUNDED_LOCKED)
    }

    @Test fun theOwnerSwitchAloneIsNotEnough() {
        assertEquals(Stage.OBSERVE, AutonomyGates.evaluate(Evidence(ownerSupervised = true)).stage)
    }
}
