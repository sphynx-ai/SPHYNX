package com.sphynx.vision

import com.sphynx.testutil.SynthFrame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Each bright-red respawn countdown next to the score = one dead hero (enemies right, allies left).
class DeathTimersTest {

    @Before fun setUp() = DeathTimers.reset()

    private val red = SynthFrame.rgb(230, 20, 20)
    private fun countdown(f: SynthFrame, x: Float) = f.rect(x - 0.006f, 0.03f, x + 0.006f, 0.062f, red)

    @Test fun countsEnemyAndAllyCountdownsSeparately() {
        val f = SynthFrame()
        listOf(0.565f, 0.592f, 0.619f).forEach { countdown(f, it) }   // 3 enemies dead
        countdown(f, 0.435f)                                          // 1 ally dead
        repeat(2) { DeathTimers.observe(f.px, f.w, f.h) }             // a count needs 2 agreeing readings
        assertEquals(3, DeathTimers.enemiesDead)
        assertEquals(1, DeathTimers.alliesDead)
    }

    @Test fun noCountdownMeansNobodyDead() {
        val f = SynthFrame()
        repeat(2) { DeathTimers.observe(f.px, f.w, f.h) }
        assertEquals(0, DeathTimers.enemiesDead)
        assertEquals(0, DeathTimers.alliesDead)
    }

    @Test fun darkRedIsNotACountdown() {
        val f = SynthFrame()
        f.rect(0.56f, 0.03f, 0.57f, 0.062f, SynthFrame.rgb(150, 60, 60))   // portrait reds, not the bright digits
        assertEquals(0, DeathTimers.count(f.px, f.w, f.h, 0.545f, 0.715f))
    }

    @Test fun oneReadingAloneDoesNotChangeTheCount() {
        val f = SynthFrame()
        countdown(f, 0.565f)
        DeathTimers.observe(f.px, f.w, f.h)
        assertEquals(0, DeathTimers.enemiesDead)
    }
}
