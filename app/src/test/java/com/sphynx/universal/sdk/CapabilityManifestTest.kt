package com.sphynx.universal.sdk

import com.sphynx.adapters.mlbb.MlbbManifest
import com.sphynx.core.Cfg
import com.sphynx.testing.MemoryPrefs
import com.sphynx.universal.Capability
import com.sphynx.universal.SupportStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Phase 14 K1: the capability manifest
class CapabilityManifestTest {

    @Before fun setUp() { Cfg.useForTests(MemoryPrefs()) }

    @Test fun mlbbHasAManifestWithEvidenceForEveryClaim() {
        val m = MlbbManifest.manifest()
        assertEquals("mlbb", m.gameId)
        assertEquals(Capability.values().size, m.capabilities.size)
        assertEquals(SupportStatus.UNSUPPORTED, m.capabilities[Capability.GAME_RECOGNITION]!!.status)
        assertEquals(emptyList<Capability>(), m.unbackedClaims())            // nothing claimed usable without evidence
        assertTrue(m.matrixLine(conformancePassed = true).startsWith("Mobile Legends: Bang Bang — supported"))
        assertTrue(m.matrixLine(conformancePassed = false).contains("not supported"))
        assertEquals(m, CapabilityManifest.fromJson(JSONObject(m.toJson().toString())))
        assertNull(CapabilityManifest.fromJson(m.toJson().put("schema", 9)))
        assertEquals(m.capabilities.mapValues { it.value.status }, m.capabilitySet().all())
        assertEquals(emptyList<String>(), com.sphynx.testing.AdapterContractKit.manifest(com.sphynx.adapters.mlbb.MlbbAdapter, m))
        // a manifest that claims what the adapter doesn't, or without evidence, is reported
        val bad = m.copy(capabilities = m.capabilities + (Capability.GAME_RECOGNITION to CapabilityManifest.Claim(SupportStatus.PARTIAL, CapabilityManifest.Evidence.NONE)))
        val problems = com.sphynx.testing.AdapterContractKit.manifest(com.sphynx.adapters.mlbb.MlbbAdapter, bad)
        assertTrue(problems.any { it.contains("GAME_RECOGNITION is PARTIAL") }); assertTrue(problems.any { it.contains("without evidence") })
    }
}
