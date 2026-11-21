package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitHistoryTest {

    private fun exit(at: Long, reason: Int, status: Int = 0, importance: Int = 100, pss: Long = 0, rss: Long = 0, d: String? = null) =
        ExitHistory.Exit(at, reason, status, importance, pss, rss, d)

    // the numbers are Android's ApplicationExitInfo.REASON_* values; they must never drift
    @Test fun reasonNumbersMatchAndroid() {
        assertEquals(3, ExitHistory.LOW_MEMORY)
        assertEquals(4, ExitHistory.CRASH)
        assertEquals(5, ExitHistory.CRASH_NATIVE)
        assertEquals(6, ExitHistory.ANR)
        assertEquals(10, ExitHistory.USER_REQUESTED)
        assertEquals(16, ExitHistory.PACKAGE_UPDATED)
    }

    @Test fun everyReasonHasWords() {
        for (r in 1..16) assertFalse("reason $r", ExitHistory.reasonName(r) == "unknown reason")
        assertEquals("unknown reason", ExitHistory.reasonName(0))
        assertEquals("unknown reason", ExitHistory.reasonName(99))
    }

    @Test fun failuresAreProblemsNormalClosesAreNot() {
        for (r in listOf(ExitHistory.CRASH, ExitHistory.CRASH_NATIVE, ExitHistory.ANR, ExitHistory.LOW_MEMORY))
            assertTrue(ExitHistory.isProblem(r))
        for (r in listOf(ExitHistory.USER_REQUESTED, ExitHistory.PACKAGE_UPDATED, ExitHistory.EXIT_SELF, ExitHistory.UNKNOWN))
            assertFalse(ExitHistory.isProblem(r))
    }

    @Test fun onlyUnseenExitsOldestFirst() {
        val l = listOf(exit(300, 4), exit(100, 6), exit(200, 5))
        assertEquals(listOf(200L, 300L), ExitHistory.newSince(l, 100).map { it.atMs })
        assertEquals(listOf(100L, 200L, 300L), ExitHistory.newSince(l, 0).map { it.atMs })
        assertTrue(ExitHistory.newSince(l, 300).isEmpty())
    }

    @Test fun lineNamesReasonPlaceSignalAndMemory() {
        val s = ExitHistory.line(exit(1, ExitHistory.CRASH_NATIVE, status = 11, importance = 100, pss = 1_887_436, d = "crash\nin llama"), "11-21 10:02:11")
        assertEquals("App closed 11-21 10:02:11: crash in native code (on screen, signal 11, memory 1843 MB, crash in llama)", s)
        val bg = ExitHistory.line(exit(1, ExitHistory.LOW_MEMORY, importance = 400), "t")
        assertEquals("App closed t: closed by Android for lack of memory (in the background)", bg)
    }

    @Test fun tracesOnlyForAnrAndNativeCrashAndTheyGoToRecordings() {
        val anr = ExitHistory.traceName(exit(1, ExitHistory.ANR), "20261121-100211")!!
        val nat = ExitHistory.traceName(exit(1, ExitHistory.CRASH_NATIVE), "20261121-100211")!!
        assertEquals("diagnostics-exit-20261121-100211-anr.txt", anr)
        assertEquals(StorageLayout.RECORDINGS, StorageLayout.areaFor(anr))
        assertEquals(StorageLayout.RECORDINGS, StorageLayout.areaFor(nat))
        assertNull(ExitHistory.traceName(exit(1, ExitHistory.CRASH), "x"))
        assertNull(ExitHistory.traceName(exit(1, ExitHistory.LOW_MEMORY), "x"))
    }

    @Test fun placeFromImportance() {
        assertEquals("on screen", ExitHistory.whereName(100))
        assertEquals("foreground service", ExitHistory.whereName(125))
        assertEquals("in the background", ExitHistory.whereName(400))
        assertEquals("state unknown", ExitHistory.whereName(0))
    }
}
