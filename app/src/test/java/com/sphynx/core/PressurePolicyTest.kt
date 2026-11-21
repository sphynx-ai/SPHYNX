package com.sphynx.core

import com.sphynx.core.PressurePolicy.Level
import com.sphynx.core.PressurePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 5 P5, owner decision 2026-11-21: a Settings choice between "Only warn me" and "Step down gently"
class PressurePolicyTest {

    @Test fun warnIsTheDefaultAndUnknownKeysFallBackToIt() {
        assertEquals(Mode.WARN, Mode.of(null))
        assertEquals(Mode.WARN, Mode.of("something else"))
        assertEquals(Mode.STEP_DOWN, Mode.of("stepdown"))
    }

    @Test fun onlyWarnNeverUnloads() {
        for (l in Level.values()) assertFalse(PressurePolicy.plan(Mode.WARN, l).any)
    }

    @Test fun stepDownGoesTacticalThenGgufThenStrategic() {
        assertEquals(PressurePolicy.Plan(false, false, false), PressurePolicy.plan(Mode.STEP_DOWN, Level.OK))
        assertEquals(PressurePolicy.Plan(true, false, false), PressurePolicy.plan(Mode.STEP_DOWN, Level.MODERATE))
        assertEquals(PressurePolicy.Plan(true, true, false), PressurePolicy.plan(Mode.STEP_DOWN, Level.SEVERE))
        assertEquals(PressurePolicy.Plan(true, true, true), PressurePolicy.plan(Mode.STEP_DOWN, Level.CRITICAL))
    }

    @Test fun memoryLevels() {
        assertEquals(Level.OK, PressurePolicy.memoryLevel(PressureLog.UI_HIDDEN))
        assertEquals(Level.OK, PressurePolicy.memoryLevel(PressureLog.RUNNING_MODERATE))
        assertEquals(Level.MODERATE, PressurePolicy.memoryLevel(PressureLog.RUNNING_LOW))
        assertEquals(Level.MODERATE, PressurePolicy.memoryLevel(PressureLog.BACKGROUND))
        assertEquals(Level.CRITICAL, PressurePolicy.memoryLevel(PressureLog.RUNNING_CRITICAL))
        assertEquals(Level.CRITICAL, PressurePolicy.memoryLevel(PressureLog.COMPLETE))
    }

    // heat never unloads the strategic model: only critical MEMORY does
    @Test fun heatLevelsStopAtSevere() {
        assertEquals(Level.OK, PressurePolicy.heatLevel(1))
        assertEquals(Level.MODERATE, PressurePolicy.heatLevel(2))
        assertEquals(Level.SEVERE, PressurePolicy.heatLevel(3))
        assertEquals(Level.SEVERE, PressurePolicy.heatLevel(6))
        assertFalse(PressurePolicy.plan(Mode.STEP_DOWN, PressurePolicy.heatLevel(6)).strategic)
    }

    @Test fun modelsComeBackOnlyAfterTheCoolDownAndNeverInAFight() {
        val t = 10_000_000L
        assertFalse(PressurePolicy.mayRestore(t + PressurePolicy.COOL_DOWN_MS - 1, t, inFight = false))
        assertTrue(PressurePolicy.mayRestore(t + PressurePolicy.COOL_DOWN_MS, t, inFight = false))
        assertFalse(PressurePolicy.mayRestore(t + 10 * PressurePolicy.COOL_DOWN_MS, t, inFight = true))
    }

    @Test fun panelNotice() {
        assertEquals("", PressurePolicy.notice(Level.OK, Mode.WARN, emptyList()))
        assertTrue(PressurePolicy.notice(Level.MODERATE, Mode.WARN, emptyList()).startsWith("Phone warm or low on memory"))
        assertEquals("Phone under pressure: unloaded the tactical model, the GGUF model",
            PressurePolicy.notice(Level.SEVERE, Mode.STEP_DOWN, listOf("the tactical model", "the GGUF model")))
    }
}
