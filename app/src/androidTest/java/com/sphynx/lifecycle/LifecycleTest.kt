package com.sphynx.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sphynx.app.MainActivity
import com.sphynx.core.DeviceProfile
import com.sphynx.core.Diag
import com.sphynx.core.ExitHistory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// PHASE 5 LIFECYCLE CHECKS ON AN EMULATOR (owner, 2026-11-21: emulator CI approved). The phone checklist is in
// docs/PHASE_5_RELIABILITY_AUDIT.md section 8; these are the ones an emulator can run without the game, screen
// capture or the accessibility service: L5 (the screen is recreated), going to the background and back (L10, the part
// that does not need the system to kill the process), and that every start leaves its diagnostics lines (L1, L11).
@RunWith(AndroidJUnit4::class)
class LifecycleTest {

    private fun waitFor(ms: Long = 15_000, what: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (what()) return true; Thread.sleep(200) }
        return what()
    }

    // L1 / L11: the start writes the device profile, the start-up time and how the app closed last time
    @Test fun aStartLeavesItsDiagnosticsLines() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("device profile read", waitFor { DeviceProfile.last != null })
            assertTrue("exit history read", waitFor { ExitHistory.last != null })
            assertTrue("start-up line", waitFor { Diag.read().contains("Start-up: ") })
            assertTrue("device profile line", Diag.read().contains("Device profile: "))
            assertNotNull(DeviceProfile.last?.let { DeviceProfile.summary(it) })
        }
    }

    // L5: the screen is built again (what a configuration change the app does not handle does) without a crash
    @Test fun theScreenSurvivesBeingRecreated() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.recreate()
            s.recreate()
            assertTrue(s.state.isAtLeast(Lifecycle.State.RESUMED))
        }
    }

    // L10 (in part): the app goes to the background and comes back
    @Test fun backgroundAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.moveToState(Lifecycle.State.CREATED)
            s.moveToState(Lifecycle.State.RESUMED)
            assertTrue(s.state.isAtLeast(Lifecycle.State.RESUMED))
        }
    }
}
