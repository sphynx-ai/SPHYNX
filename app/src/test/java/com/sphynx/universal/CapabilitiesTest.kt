package com.sphynx.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {

    @Test fun unlistedCapabilityIsUnsupported() {
        val c = CapabilitySet(mapOf(Capability.MINIMAP to SupportStatus.OPERATIONAL))
        assertEquals(SupportStatus.UNSUPPORTED, c.status(Capability.ITEMS_GOLD))
        assertEquals(SupportStatus.UNSUPPORTED, CapabilitySet.NONE.status(Capability.MINIMAP))
        assertEquals(Capability.values().size, c.all().size)
    }

    @Test fun onlyOperationalAndPartialAreUsable() {
        assertTrue(SupportStatus.OPERATIONAL.usable)
        assertTrue(SupportStatus.PARTIAL.usable)
        assertFalse(SupportStatus.EXPERIMENTAL.usable)
        assertFalse(SupportStatus.PLANNED.usable)
        assertFalse(SupportStatus.UNSUPPORTED.usable)
    }

    @Test fun stateFiltersUnitsBySideAndKind() {
        val st = UniversalMatchState("g", 0L, MatchPhase.IN_MATCH, units = listOf(
            UnitState(Side.ENEMY, UnitKind.CHARACTER), UnitState(Side.ENEMY, UnitKind.MINION),
            UnitState(Side.ALLY, UnitKind.CHARACTER), UnitState(Side.NEUTRAL, UnitKind.MAJOR_OBJECTIVE)))
        assertEquals(1, st.enemyCharactersSeen.size)
        assertEquals(1, st.allyCharactersSeen.size)
        assertEquals(2, st.units(Side.ENEMY).size)
        assertTrue(MatchPhase.PLAYER_DEAD.inMatch)
        assertFalse(MatchPhase.SPECTATING.inMatch)
    }
}
