package com.sphynx.inference.roles

import com.sphynx.inference.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Owner decision 2026-11-22: a model that was loading when the app stopped is not loaded again by itself.
class LoadSentinelTest {

    private class MemStore : LoadSentinel.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun setNow(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test fun aCleanLoadLeavesNothingBehind() {
        val st = MemStore()
        val s = LoadSentinel(st)
        s.loading(ModelRole.STRATEGIC, "gemma-4-E4B.litertlm"); s.loaded(ModelRole.STRATEGIC)
        assertTrue(LoadSentinel(st).checkAtStart().isEmpty())
    }

    @Test fun aCrashDuringLoadingBlocksTheAutomaticLoadOnly() {
        val st = MemStore()
        LoadSentinel(st).loading(ModelRole.STRATEGIC, "gemma-4-E4B.litertlm")       // ... and the app died here
        val next = LoadSentinel(st)                                                   // the next start
        assertEquals(listOf(ModelRole.STRATEGIC to "gemma-4-E4B.litertlm"), next.checkAtStart())
        assertTrue(st.m.isEmpty())                                                    // the note is read once
        assertEquals("crash_suspected", next.refusal(ModelRole.STRATEGIC, "gemma-4-E4B.litertlm", byOwner = false)?.code)
        assertNull(next.refusal(ModelRole.STRATEGIC, "gemma-3n-E2B.litertlm", byOwner = false))   // another model may load
        assertNull(next.refusal(ModelRole.TACTICAL, "gemma-4-E4B.litertlm", byOwner = false))     // the other role is not affected
        assertNull(next.refusal(ModelRole.STRATEGIC, "gemma-4-E4B.litertlm", byOwner = true))     // Try again by hand
        assertNull(next.refusal(ModelRole.STRATEGIC, "gemma-4-E4B.litertlm", byOwner = false))    // ... and then it is forgiven
    }

    @Test fun forgiveAll() {
        val st = MemStore()
        LoadSentinel(st).loading(ModelRole.TACTICAL, "qwen.gguf")
        val s = LoadSentinel(st); s.checkAtStart()
        assertEquals("qwen.gguf", s.suspected(ModelRole.TACTICAL))
        s.forgiveAll()
        assertNull(s.suspected(ModelRole.TACTICAL))
    }
}
