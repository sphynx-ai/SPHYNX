package com.sphynx.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// ADR 0001 (docs/adr/0001-layered-architecture.md): which package may use which. It reads the source and
// fails when a package uses one it may not. Today's violations are a baseline that may only shrink: a new
// one fails, and a fixed one that is still listed fails too (so the list stays exact). Each migration step
// removes its entries.
class ArchitectureRulesTest {

    private val packages = setOf("adapters", "ai", "app", "capture", "controller", "core", "engines", "game",
        "inference", "learning", "models", "research", "universal", "vision")

    // what each ruled package may use besides itself (packages not listed have no rule yet)
    private val allowed = mapOf(
        "universal" to emptySet(),
        "inference" to emptySet(),
        "core" to emptySet(),
        "capture" to setOf("core"),
        "models" to setOf("core", "inference"),
        "engines" to setOf("universal", "core", "inference"),
    )

    // the violations on 2026-11-14 (ADR 0001): remove an entry when its step is done
    // (step B1, v37.28: Speed, TouchSpeed, PhoneState, VideoRecorder, GitHubUpload, Settings read the layers
    // above through core.Probes - 11 entries gone; step B2, v37.29: SessionRecorder too - 6 more; step B3, v37.61:
    // ScreenCaptureService reports start / stop / emergency stop and vision reads the pictures through Probes - 4 more;
    // step B4, v37.62: ModelManager and AutoFolder reach the engines through models.ModelHooks - 4 more)
    private val baseline = setOf(
        "engines/DecisionBridge.kt -> ai", "engines/DecisionBridge.kt -> learning",
        "engines/DecisionLogging.kt -> adapters", "engines/DecisionLogging.kt -> ai",
        "engines/DecisionLogging.kt -> game", "engines/DecisionLogging.kt -> learning",
    )

    private fun sourceRoot(): File = listOf("src/main/java/com/sphynx", "app/src/main/java/com/sphynx")
        .map { File(it) }.first { it.isDirectory }

    private val use = Regex("""com\.sphynx\.(\w+)\.""")

    /** "pkg/File.kt -> used" for every use the rules forbid. */
    private fun violations(): Set<String> {
        val root = sourceRoot()
        val out = HashSet<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            val pkg = rel.substringBefore('/')
            val used = use.findAll(f.readText()).map { it.groupValues[1] }.filter { it in packages && it != pkg }.toSet()
            for (u in used) {
                val ruled = allowed[pkg]
                if ((ruled != null && u !in ruled) || (u == "app" && pkg != "app")) out.add("$rel -> $u")
            }
        }
        return out
    }

    @Test fun noNewForbiddenDependency() {
        val fresh = violations() - baseline
        assertTrue("New dependencies that ADR 0001 forbids (see docs/adr/0001-layered-architecture.md):\n" +
            fresh.sorted().joinToString("\n"), fresh.isEmpty())
    }

    @Test fun theBaselineOnlyListsWhatIsStillThere() {
        val fixed = baseline - violations()
        assertTrue("Fixed - remove these from the baseline in ArchitectureRulesTest:\n" + fixed.sorted().joinToString("\n"),
            fixed.isEmpty())
    }

    @Test fun theSourceIsFound() {
        assertTrue(sourceRoot().resolve("universal").isDirectory)
    }
}
