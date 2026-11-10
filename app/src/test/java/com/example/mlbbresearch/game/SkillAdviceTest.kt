package com.example.mlbbresearch.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillAdviceTest {
    private val order = listOf("ult", "s2", "s1")

    @Test fun nothingReadyShowsThePriorityOnly() {
        assertNull(SkillAdvice.pick(order, emptySet()))
        assertEquals("skill priority: Ultimate > Skill 2 > Skill 1", SkillAdvice.line(order, emptySet()))
    }

    @Test fun ultimateFirstWheneverItCanBeLeveled() {
        assertEquals("ult", SkillAdvice.pick(order, setOf("s1", "s2", "ult")))
    }

    @Test fun otherwiseTheHeroPriorityOrder() {
        assertEquals("s2", SkillAdvice.pick(order, setOf("s1", "s2")))
        assertEquals("s1", SkillAdvice.pick(order, setOf("s1")))
        assertEquals("level up now: Skill 2 (priority Ultimate > Skill 2 > Skill 1)", SkillAdvice.line(order, setOf("s1", "s2")))
    }
}
