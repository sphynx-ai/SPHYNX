package com.sphynx.learning

import com.sphynx.learning.TrainingResults.Kind
import com.sphynx.learning.TrainingResults.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingResultsTest {

    @Test fun improvementIsRelativeToTheLastTraining() {
        assertEquals(25.0, TrainingResults.improvementPct(0.40, 0.50)!!, 1e-9)
        assertEquals(-50.0, TrainingResults.improvementPct(0.40, 0.20)!!, 1e-9)
        assertNull(TrainingResults.improvementPct(null, 0.5))
        assertNull(TrainingResults.improvementPct(0.0, 0.5))
    }

    @Test fun scoresFromEachResultFile() {
        assertEquals(0.62, TrainingResults.scoreOf(Kind.MINIMAP, """{"map50":0.62,"map50_95":0.4}""")!!, 1e-9)
        assertEquals(0.41, TrainingResults.scoreOf(Kind.POLICY, """{"move_balanced":0.41,"examples":900}""")!!, 1e-9)
        assertEquals(0.35, TrainingResults.scoreOf(Kind.LLM, """{"acc":0.6,"base":0.5,"balanced":0.35}""")!!, 1e-9)
        assertEquals(0.6, TrainingResults.scoreOf(Kind.LLM, """{"acc":0.6,"base":0.5,"balanced":-1}""")!!, 1e-9)
        assertNull(TrainingResults.scoreOf(Kind.SCREEN, "{}"))
        assertNull(TrainingResults.scoreOf(Kind.SCREEN, "not json"))
        assertNull(TrainingResults.scoreOf(Kind.SCREEN, """{"map50":7}"""))
        assertEquals(900, TrainingResults.countOf("""{"move_balanced":0.41,"examples":900}"""))
    }

    @Test fun sameFileIsKeptOnceAndHistoryIsCapped() {
        val a = Result(1, 0.4, 10, "x")
        assertEquals(1, TrainingResults.added(listOf(a), Result(2, 0.4, 10, "x")).size)
        var l = emptyList<Result>()
        for (i in 0 until 40) l = TrainingResults.added(l, Result(i.toLong(), 0.5, 1, "s$i"))
        assertEquals(TrainingResults.KEEP, l.size)
        assertEquals("s39", l.last().sig)
        val back = TrainingResults.fromJson(TrainingResults.toJson(l))
        assertEquals(l.map { it.sig }, back.map { it.sig })
    }

    @Test fun circleText() {
        assertEquals("no result yet", TrainingResults.circleText(null, null))
        assertEquals("first result", TrainingResults.circleText(null, Result(1, 0.5, 1, "a")))
        assertEquals("+25.0%", TrainingResults.circleText(Result(1, 0.4, 1, "a"), Result(2, 0.5, 1, "b")))
    }
}
