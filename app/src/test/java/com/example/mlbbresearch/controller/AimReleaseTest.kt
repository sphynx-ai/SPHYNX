package com.example.mlbbresearch.controller

import com.example.mlbbresearch.ai.Intent
import com.example.mlbbresearch.ai.TacticalDecision
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AimReleaseTest {

    private fun d(intent: Intent, tx: Float, ty: Float, id: Int = -1) =
        TacticalDecision(intent, 0f, 0f, emptyList(), tx, ty, "x", 0.5f, id, "test")
    private val near = { ax: Float, ay: Float, bx: Float, by: Float -> kotlin.math.hypot(ax - bx, ay - by) < 0.15f }

    @Test fun noPlannedTargetKeepsThePlannedDirection() {
        assertNull(AimRelease.releaseDecision(d(Intent.JUNGLE, -1f, -1f), d(Intent.ENGAGE, 0.6f, 0.4f, 3), near))
        assertNull(AimRelease.releaseDecision(d(Intent.RETREAT, 0.5f, 0.5f), d(Intent.ENGAGE, 0.6f, 0.4f, 3), near))
        assertNull(AimRelease.releaseDecision(null, d(Intent.ENGAGE, 0.6f, 0.4f, 3), near))
    }

    @Test fun sameTargetFollowsItsLivePosition() {
        val latest = d(Intent.ENGAGE, 0.62f, 0.41f, 7)
        assertSame(latest, AimRelease.releaseDecision(d(Intent.ENGAGE, 0.6f, 0.4f, 7), latest, near))
        val mon = d(Intent.JUNGLE, 0.51f, 0.62f)
        assertSame(mon, AimRelease.releaseDecision(d(Intent.JUNGLE, 0.5f, 0.6f), mon, near))
    }

    // the recordings: at release the decision had no target ("walking direction") or had switched to retreat
    @Test fun changedDecisionKeepsThePlannedTarget() {
        val planned = d(Intent.JUNGLE, 0.438f, 0.47f)
        assertSame(planned, AimRelease.releaseDecision(planned, d(Intent.JUNGLE, -1f, -1f), near))
        assertSame(planned, AimRelease.releaseDecision(planned, d(Intent.RETREAT, 0.424f, 0.228f), near))
        val hero = d(Intent.ENGAGE, 0.6f, 0.4f, 44)
        assertSame(hero, AimRelease.releaseDecision(hero, d(Intent.ENGAGE, 0.3f, 0.3f, 45), near))
    }
}
