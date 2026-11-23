package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFitTest {

    private val gb = 1024L * 1024 * 1024

    // the owner's phone after loading both models had 3,236 MB free (snapshot v37.92)
    @Test fun gemma4E2bFitsOnAPhoneWithRoom() {
        assertNull(MemoryFit.warning("gemma-4-E2B.litertlm", (2.6 * gb).toLong(), availMb = 6_000))
    }

    @Test fun aBigModelOnAFullPhoneWarns() {
        val w = MemoryFit.warning("gemma-4-E4B.litertlm", (4.3 * gb).toLong(), availMb = 5_000)
        assertTrue(w!!.startsWith("gemma-4-E4B.litertlm needs about 4.5 GB"))
        assertTrue(w.contains("the game would be left with about 0.4 GB"))
    }

    // replacing the job's model gives its memory back first
    @Test fun theReplacedModelCounts() {
        assertTrue(MemoryFit.warning("e4b.litertlm", (4.3 * gb).toLong(), availMb = 3_000) != null)
        assertNull(MemoryFit.warning("e4b.litertlm", (4.3 * gb).toLong(), availMb = 3_000, alreadyLoadedMb = 4_200))
    }

    @Test fun unknownSizesDoNotWarn() {
        assertNull(MemoryFit.warning("x", -1, 3_000))
        assertNull(MemoryFit.warning("x", gb, 0))
        assertEquals(1224L, MemoryFit.neededMb(gb))
    }
}
