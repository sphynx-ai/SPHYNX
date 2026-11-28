package com.sphynx.universal.pairing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Phase 11 P4: every model of the download list has a declared class, and the catalog document lists the same ones
class ConfigCatalogTest {

    @Test fun theCatalogDocumentMatchesTheDownloadList() {
        val list = JSONObject(File("src/main/assets/model_catalog.json").readText()).getJSONArray("models")
        val doc = File("../docs/PHASE_11_CONFIG_CATALOG.md").readText()
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val mb = (e.getLong("size") + (e.optJSONObject("mmproj")?.getLong("size") ?: 0L)) / 1_048_576
            val cls = Workloads.declaredClass(mb).label
            val row = doc.lines().firstOrNull { it.startsWith("| " + e.getString("title") + " |") }
            assertTrue("${e.getString("title")} is in the catalog document", row != null)
            assertTrue("${e.getString("title")}: $cls", row!!.contains("| $cls |"))
        }
        assertEquals(Workloads.PhoneClass.MIDRANGE, Workloads.declaredClass(2_468))
    }
}
