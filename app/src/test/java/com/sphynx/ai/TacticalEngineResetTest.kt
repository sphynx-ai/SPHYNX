package com.sphynx.ai

import com.sphynx.core.Cfg
import com.sphynx.testing.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Modifier

// TacticalEngine.reset() forgets the whole match (fixed 2026-11-14: it used to keep the last monster, the
// objective commitment, the committed decision, invade and travel timers). Found by the regression net.
class TacticalEngineResetTest {

    private val golden = TacticalEngineGoldenTest()

    // what the engine remembers: its static fields (a Kotlin object), and one level into its own helper objects
    private fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in TacticalEngine::class.java.declaredFields) {
            if (!Modifier.isStatic(f.modifiers) || f.name == "INSTANCE") continue
            f.isAccessible = true
            out[f.name] = describe(f.get(null), 1)
        }
        return out
    }

    private fun describe(v: Any?, depth: Int): String = when {
        v == null -> "null"
        v is Number || v is Boolean || v is String || v is Enum<*> || v is Char -> v.toString()
        v is Collection<*> || v is Map<*, *> -> v.toString()
        v is BooleanArray -> v.contentToString()
        v is IntArray -> v.contentToString()
        v is FloatArray -> v.contentToString()
        depth > 0 && v.javaClass.name.startsWith("com.sphynx.") -> v.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .joinToString(", ", "{", "}") { f -> f.isAccessible = true; f.name + "=" + describe(f.get(v), depth - 1) }
        else -> v.javaClass.name
    }

    @Test fun aResetEngineRemembersNothingFromBefore() {
        Cfg.useForTests(MemoryPrefs())
        golden.resetAll()
        val fresh = snapshot()
        for (sc in golden.scenarios()) TacticalEngine.decide(sc.state, sc.hero, sc.plan, 0f)   // a match without resets
        golden.resetAll()
        val after = snapshot()
        val changed = fresh.keys.filter { fresh[it] != after[it] }
            .joinToString("\n") { "  $it: start ${fresh[it]}, after reset ${after[it]}" }
        assertEquals("TacticalEngine.reset() does not reset:\n$changed", fresh, after)
    }

    @Test fun aMonsterFromBeforeTheResetIsForgotten() {
        Cfg.useForTests(MemoryPrefs())
        val all = golden.scenarios().associateBy { it.name }
        golden.resetAll()
        val turtle = all.getValue("turtle_low_with_ally")
        TacticalEngine.decide(turtle.state, turtle.hero, turtle.plan, 0f)
        golden.resetAll()
        val next = all.getValue("enemy_tower_on_minimap")
        val d = TacticalEngine.decide(next.state, next.hero, next.plan, 0f)
        assertFalse(d.reason, d.reason.contains("holding position at monster"))
    }
}
