package com.sphynx.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// PHASE 6, step S4 (ADR 0002): outside the role router no code names a model slot. Callers ask a role through ai/Roles;
// only the slots themselves (ai/GemmaEngine.kt), their port (ai/LlmSlotPort.kt) and ai/Roles.kt know GemmaEngine and
// TacticalLlmEngine. Comments may still mention them.
class RolesOnlyTest {

    private val allowed = setOf("ai/GemmaEngine.kt", "ai/LlmSlotPort.kt", "ai/Roles.kt")
    private val slot = Regex("""\b(GemmaEngine|TacticalLlmEngine)\b""")

    @Test fun noCallerNamesASlot() {
        val root = File("src/main/java/com/sphynx")
        val found = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            if (rel in allowed) return@forEach
            f.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if (slot.containsMatchIn(code.substringBefore("//"))) found.add("$rel:${i + 1}: $code")
            }
        }
        assertEquals("use ai/Roles (a role), not a model slot", emptyList<String>(), found)
    }
}
