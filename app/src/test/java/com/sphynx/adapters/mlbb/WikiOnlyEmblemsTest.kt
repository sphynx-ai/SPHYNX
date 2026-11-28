package com.sphynx.adapters.mlbb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Phase 13 D2 (roadmap 13.1): emblem advice shown anywhere in the app comes only from the Wiki data (MlbbEmblems).
// The online meta keeps its emblem field as data (MetaStore), but no code reads it out for display or for a model.
class WikiOnlyEmblemsTest {

    @Test fun noOtherEmblemSourceIsShown() {
        val root = File("src/main/java/com/sphynx")
        val found = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "MetaStore.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l ->
                val code = l.substringBefore("//")
                if (Regex("""\.emblem\b(?!s)""").containsMatchIn(code)) "${f.name}:${i + 1}: ${l.trim()}" else null } }
            .toList()
        assertTrue("Non-Wiki emblem text is read out:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
