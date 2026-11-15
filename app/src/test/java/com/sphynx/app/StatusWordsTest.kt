package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StatusWordsTest {
    @Test fun permissionLabelsCarryAWord() {
        assertEquals("3. Notifications  ·  Granted", StatusWords.permission("3. Notifications", true))
        assertEquals("3. Notifications  ·  Missing", StatusWords.permission("3. Notifications", false))
    }

    @Test fun baseUndoesTheWord() {
        val l = "1. Accessibility service (touches)"
        assertEquals(l, StatusWords.base(StatusWords.permission(l, true)))
        assertEquals(l, StatusWords.base(StatusWords.permission(StatusWords.permission(l, true), false).let { StatusWords.base(it) }))
        assertEquals(l, StatusWords.base(l))
    }

    @Test fun everyLevelHasItsOwnWordAndShape() {
        val words = (0..2).map { StatusWords.levelWord(it) }
        val dots = (0..2).map { StatusWords.levelDot(it) }
        assertEquals(3, words.toSet().size)
        assertEquals(3, dots.toSet().size)
        assertNotEquals(StatusWords.levelWord(2), StatusWords.levelWord(-1))
        assertEquals("Missing", StatusWords.levelWord(-1))
    }
}
