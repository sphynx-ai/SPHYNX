package com.sphynx.core

import com.sphynx.core.NpuCheck.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuCheckTest {

    @Test fun readyWhenTheAdapterIsOpenToApps() {
        val v = NpuCheck.verdict(Facts("Mediatek MT6897", true, true, true, listOf("libneuronusdk_adapter.mtk.so"), listOf("libneuronusdk_adapter.mtk.so")))
        assertEquals("NPU: ready to try on Mediatek MT6897 (MediaTek Neuron adapter: libneuronusdk_adapter.mtk.so)", v)
    }

    @Test fun anAdapterAppsMayNotUseIsSaid() =
        assertTrue(NpuCheck.verdict(Facts("Mediatek MT6897", true, true, true, emptyList(), listOf("libneuron_adapter.so"))).contains("not open to apps"))

    @Test fun missingAppLibraries() =
        assertTrue(NpuCheck.verdict(Facts("Mediatek MT6897", true, false, true, emptyList(), emptyList())).contains(NpuCheck.DISPATCH))

    @Test fun anotherChip() =
        assertTrue(NpuCheck.verdict(Facts("QTI SM8650", false, true, true, emptyList(), emptyList())).contains("not a MediaTek one"))

    @Test fun publicLibrariesFiles() {
        val s = NpuCheck.listed(listOf("# comment\nlibneuronusdk_adapter.mtk.so\nlibOpenCL.so 64\n\n  libfoo.so # x\nnot-a-lib"))
        assertEquals(setOf("libneuronusdk_adapter.mtk.so", "libOpenCL.so", "libfoo.so"), s)
    }
}
