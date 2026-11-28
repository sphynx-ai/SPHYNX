package com.sphynx.adapters.mlbb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Phase 13 D1: the emblem library from the Wiki, with every icon mapped and no invented recommendation
class MlbbEmblemsTest {

    private val dir = File("src/main/assets/" + MlbbEmblems.ASSET_DIR)
    private val lib = MlbbEmblems.parse(File("src/main/assets/" + MlbbEmblems.ASSET).readText())

    @Test fun theWikiDataIsComplete() {
        assertEquals(7, lib.sets.size)
        assertEquals(listOf("Basic Common Emblem", "Custom Tank Emblem", "Custom Assassin Emblem", "Custom Mage Emblem",
            "Custom Fighter Emblem", "Custom Support Emblem", "Custom Marksman Emblem"), lib.sets.map { it.name })
        assertEquals(26, lib.sets.sumOf { it.talents.size })
        for (s in lib.sets) {
            assertEquals(s.name, 3, s.attributes.size)
            assertTrue(s.name, s.core.isNotEmpty() && s.standard.isNotEmpty())
            for (t in s.talents) assertTrue("${s.name}/${t.name}", t.description.length > 10)
        }
        assertEquals(4, lib.set("Basic Common Emblem")!!.core.size)
        assertEquals(setOf(1, 2), lib.set("Basic Common Emblem")!!.standard.mapNotNull { it.tier }.toSet())
        assertTrue(lib.attribution().contains("https://mobile-legends.fandom.com/wiki/Emblems"))
        assertTrue(lib.notes.any { it.contains("Temporal Reign") })
    }

    @Test fun everyIconIsThereAndUsedOnce() {
        val files = dir.listFiles()!!.map { it.name }.filter { it.endsWith(".webp") }.toSet()
        assertEquals(33, lib.icons.size)
        assertEquals(lib.icons.toSet(), files)                         // no icon missing, no extra file
        assertEquals(lib.icons.size, lib.icons.toSet().size)           // each icon belongs to one entry
        for (f in files) {
            val b = File(dir, f).readBytes()
            assertTrue(f, String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP")
        }
    }

    @Test fun recommendationsOnlyFromTheWiki() {
        assertTrue(lib.recommendationNote.contains("no recommended set"))
        assertEquals("Custom Tank Emblem", MlbbEmblems.setNamedFor(lib, "tank")!!.name)
        assertNull(MlbbEmblems.setNamedFor(lib, "jungler"))
        val r = MlbbEmblems.recommendationFor(lib, "Mage")
        assertTrue(r.startsWith("No Wiki-supported recommendation")); assertTrue(r.contains("Custom Mage Emblem"))
        assertEquals(MlbbEmblems.NO_RECOMMENDATION, MlbbEmblems.recommendationFor(lib, null))
        assertEquals("Emblem data not available", MlbbEmblems.recommendationFor(null, "tank"))
    }
}
