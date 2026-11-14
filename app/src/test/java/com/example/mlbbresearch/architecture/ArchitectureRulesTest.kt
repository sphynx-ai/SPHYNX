package com.example.mlbbresearch.architecture

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
    private val baseline = setOf(
        "capture/ScreenCaptureService.kt -> ai", "capture/ScreenCaptureService.kt -> game",
        "core/GitHubUpload.kt -> learning", "core/GitHubUpload.kt -> models",
        "core/PhoneState.kt -> vision",
        "core/SessionRecorder.kt -> ai", "core/SessionRecorder.kt -> capture", "core/SessionRecorder.kt -> game",
        "core/SessionRecorder.kt -> learning", "core/SessionRecorder.kt -> research", "core/SessionRecorder.kt -> vision",
        "core/Settings.kt -> game",
        "core/Speed.kt -> ai", "core/Speed.kt -> capture", "core/Speed.kt -> learning", "core/Speed.kt -> vision",
        "core/TouchSpeed.kt -> capture", "core/TouchSpeed.kt -> vision",
        "core/VideoRecorder.kt -> game",
        "engines/DecisionBridge.kt -> ai", "engines/DecisionBridge.kt -> learning",
        "engines/DecisionLogging.kt -> adapters", "engines/DecisionLogging.kt -> ai",
        "engines/DecisionLogging.kt -> game", "engines/DecisionLogging.kt -> learning",
        "models/AutoFolder.kt -> ai", "models/AutoFolder.kt -> learning", "models/AutoFolder.kt -> vision",
        "models/ModelManager.kt -> ai",
        "vision/EndBoard.kt -> app", "vision/PortraitMatch.kt -> app",
    )

    private fun sourceRoot(): File = listOf("src/main/java/com/example/mlbbresearch", "app/src/main/java/com/example/mlbbresearch")
        .map { File(it) }.first { it.isDirectory }

    private val use = Regex("""com\.example\.mlbbresearch\.(\w+)\.""")

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
