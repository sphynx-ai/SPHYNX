package com.sphynx.adapters.mlbb

import com.sphynx.ai.TacticalAdvisor
import com.sphynx.game.HeroDb
import com.sphynx.learning.FightOption
import com.sphynx.universal.eval.EvalSet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Phase 10 E2: the MLBB evaluation sets in the app's assets are complete, ask exactly what the app asks, and name
// only heroes that exist
class MlbbEvalSetsTest {

    private fun text(name: String) = File("src/main/assets/eval/$name").readText()
    private val names = listOf("mlbb_tactical.json", "mlbb_strategic.json", "mlbb_review.json")

    @Test fun everySetParses() {
        val sets = names.map { EvalSet.parse(text(it)).getOrThrow() }
        assertEquals(listOf("mlbb-tactical", "mlbb-strategic", "mlbb-review"), sets.map { it.id })
        assertEquals(40, sets[0].items.size); assertEquals(30, sets[1].items.size); assertEquals(10, sets[2].items.size)
        assertTrue(sets.all { it.version == 1 && it.game == "mlbb" })
        // ids are unique across the sets too, so a result row names its item without the set
        val ids = sets.flatMap { s -> s.items.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun theTacticalItemsAreAskedExactlyAsInAMatch() {
        val a = JSONObject(text("mlbb_tactical.json")).getJSONArray("items")
        val hero = Regex("""^hero (.+?) \((\w+), (ranged|melee)\)""")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            assertEquals(o.getString("id"), TacticalAdvisor.prompt(o.getString("summary")), o.getString("prompt"))
            val m = hero.find(o.getString("summary"))
            assertNotNull(o.getString("id"), m)
            val name = m!!.groupValues[1]
            assertEquals("${o.getString("id")}: $name", name, HeroDb.match(name))
            assertEquals("${o.getString("id")}: $name's role", HeroDb.role(name).name.lowercase(), m.groupValues[2])
        }
        // every option the set uses is one the app reads from a fight answer
        for (f in FightOption.values()) assertEquals(f, TacticalAdvisor.parse(f.name))
    }

    @Test fun theStrategicPlansUseTheAppsPromptAndPlans() {
        val s = EvalSet.parse(text("mlbb_strategic.json")).getOrThrow()
        for (it in s.items.filter { it.kind == EvalSet.Kind.PLAN }) {
            assertTrue(it.id, it.prompt.startsWith("MLBB strategy for "))
            assertTrue(it.id, it.prompt.endsWith("\"note\":\"max 6 words\"}"))
            val name = it.prompt.removePrefix("MLBB strategy for ").substringBefore(" (")
            assertEquals(it.id, name, HeroDb.match(name))
            // the app's plan reader accepts every option the set expects
            for (o in it.best + it.ok + it.unsafe) assertEquals(o, com.sphynx.ai.DecisionParser.strategy("{\"plan\":\"$o\"}")!!.plan)
        }
    }

    @Test fun everyHeroInARubricExists() {
        val words = Regex("""[A-Z][a-z]+(?:[ -][A-Z][a-z]+)?""")
        val notHeroes = setOf("Lord", "Turtle", "Retribution", "Purify", "Flicker", "Execute", "Petrify", "Athena", "Radiant",
            "Armor", "Oracle", "Sea", "Halberd", "Wind", "Nature", "Immortality", "Winter", "Rose", "Gold", "Meteor", "Antique",
            "Cuirass", "Blade", "Dominance", "Ice", "Windtalker", "Demon", "Hunter", "Sword", "Corrosion", "Scythe", "Golden", "Staff")
        val known = HeroDb.allNames.toSet()
        for (n in names) {
            val o = JSONObject(text(n)).getJSONArray("items")
            for (i in 0 until o.length()) {
                val must = o.getJSONObject(i).optJSONArray("must") ?: continue
                for (g in 0 until must.length()) for (k in 0 until must.getJSONArray(g).length()) {
                    val w = must.getJSONArray(g).getString(k)
                    if (w.first().isUpperCase() && w.split(' ', '-').all { it.isNotEmpty() && it.first().isUpperCase() } &&
                        w.split(' ').none { it in notHeroes } && words.matches(w) && w !in setOf("EXP"))
                        assertTrue("$n: $w is not a hero", w in known)
                }
            }
        }
    }
}
