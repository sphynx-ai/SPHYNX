package com.sphynx.universal.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 10 E2/E3: the set form and the scoring
class EvalScoringTest {

    private val opts = listOf("ALL_IN", "POKE", "FARM", "RETREAT", "GROUP", "RULES")
    private fun choice(best: List<String>, ok: List<String> = emptyList(), unsafe: List<String> = emptyList()) =
        EvalSet.Item("t", EvalSet.Kind.CHOICE, "p", opts, null, best, ok, unsafe, emptyList(), emptyList(), "")

    @Test fun choices() {
        val it = choice(listOf("RETREAT"), listOf("GROUP"), listOf("ALL_IN"))
        assertEquals(EvalScoring.Verdict.BEST, EvalScoring.score(it, "RETREAT").verdict)
        assertEquals(EvalScoring.Verdict.OK, EvalScoring.score(it, " group.").verdict)
        assertEquals(EvalScoring.Verdict.UNSAFE, EvalScoring.score(it, "All in!").verdict)
        assertEquals(EvalScoring.Verdict.UNSAFE, EvalScoring.score(it, "allin").verdict)
        assertEquals(EvalScoring.Verdict.WRONG, EvalScoring.score(it, "FARM").verdict)
        assertEquals(EvalScoring.Verdict.NO_ANSWER, EvalScoring.score(it, "I think you should leave").verdict)
        assertEquals("RETREAT", EvalScoring.firstOption("RETREAT, then GROUP", opts))     // the first one named counts
        assertEquals(EvalScoring.Verdict.BEST, EvalScoring.score(it, "<think>ALL_IN is bad</think> RETREAT").verdict)
        assertEquals(0.5, EvalScoring.score(it, "GROUP").score, 0.0)
    }

    @Test fun plans() {
        val it = EvalSet.Item("s", EvalSet.Kind.PLAN, "p", listOf("farm", "push", "fight", "retreat", "group", "defend"), "plan",
            listOf("retreat"), listOf("group"), listOf("fight"), emptyList(), emptyList(), "")
        assertEquals(EvalScoring.Verdict.BEST, EvalScoring.score(it, "```json\n{\"plan\":\"Retreat\",\"note\":\"a {b}\"}\n```").verdict)
        assertEquals(EvalScoring.Verdict.UNSAFE, EvalScoring.score(it, "{\"plan\": \"fight\"}").verdict)
        assertEquals(EvalScoring.Verdict.NO_ANSWER, EvalScoring.score(it, "retreat now").verdict)
        assertEquals(EvalScoring.Verdict.NO_ANSWER, EvalScoring.score(it, "{\"plan\": \"dance\"}").verdict)
        assertEquals(EvalScoring.Verdict.BEST, EvalScoring.score(it, "{broken {\"plan\":\"retreat\"}").verdict)
        assertNull(EvalScoring.firstJson("no json {"))
    }

    @Test fun rubrics() {
        val it = EvalSet.Item("r", EvalSet.Kind.RUBRIC, "p", emptyList(), null, emptyList(), emptyList(), emptyList(),
            listOf(listOf("defend", "tower"), listOf("together", "group"), listOf("pick", "mistake")), listOf("dive"), "")
        assertEquals(EvalScoring.Verdict.BEST, EvalScoring.score(it, "Defend towers together and wait for a mistake.").verdict)
        val two = EvalScoring.score(it, "Stay together near the tower.")
        assertEquals(EvalScoring.Verdict.OK, two.verdict); assertEquals(2.0 / 3, two.score, 1e-9)
        assertEquals(EvalScoring.Verdict.WRONG, EvalScoring.score(it, "Farm more.").verdict)
        assertEquals(EvalScoring.Verdict.UNSAFE, EvalScoring.score(it, "Dive their tower together.").verdict)
        // a negated claim is not a claim
        assertEquals(EvalScoring.Verdict.BEST, EvalScoring.score(it, "Don't dive; defend together and wait for a pick.").verdict)
        assertTrue(EvalScoring.says("they dive", "dive")); assertFalse(EvalScoring.says("never ever dive", "dive"))
        assertEquals(EvalScoring.Verdict.NO_ANSWER, EvalScoring.score(it, "  ").verdict)
    }
}
