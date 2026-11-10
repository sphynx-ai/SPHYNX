package com.example.mlbbresearch.research

import com.example.mlbbresearch.research.PlayerGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGateTest {

    @Test fun playsOnlyInTheVerifiedPracticeMatch() {
        assertEquals(Verdict.PLAY, PlayerGate.verdict(100L, 100L, PracticeGate.State.VERIFIED))
        // a verification belongs to its match only
        assertEquals(Verdict.WAIT, PlayerGate.verdict(101L, 100L, PracticeGate.State.VERIFIED))
    }

    @Test fun waitsUntilVerified() {
        assertEquals(Verdict.WAIT, PlayerGate.verdict(100L, 100L, PracticeGate.State.UNVERIFIED))
    }

    @Test fun realPlayerLocksTheMatch() {
        assertEquals(Verdict.LOCKED, PlayerGate.verdict(100L, 100L, PracticeGate.State.REVOKED))
    }

    @Test fun unidentifiedMatchNeverPlays() {
        assertEquals(Verdict.UNKNOWN, PlayerGate.verdict(0L, 0L, PracticeGate.State.VERIFIED))
    }
}
