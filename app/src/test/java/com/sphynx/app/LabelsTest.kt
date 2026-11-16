package com.sphynx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelsTest {
    @Test fun words() {
        assertEquals(listOf("Blade", "of", "Despair"), Labels.words("Blade of Despair"))
        assertEquals(listOf("Hero", "match"), Labels.words("Hero / match"))
        assertEquals(emptyList<String>(), Labels.words(""))
    }

    @Test fun fitsAtFullSizeWhenThereIsRoom() {
        assertEquals(13f, Labels.fitSp(widthAt1Sp = 5f, availablePx = 100f, maxSp = 13f, minSp = 9f))
    }

    @Test fun shrinksUntilTheLongestWordFits() {
        // 8 px per sp: 13 sp = 104 px is too wide for 90 px; 11 sp = 88 px fits
        assertEquals(11f, Labels.fitSp(widthAt1Sp = 8f, availablePx = 90f, maxSp = 13f, minSp = 9f))
    }

    @Test fun tooWideEvenAtTheSmallestSize() {
        assertNull(Labels.fitSp(widthAt1Sp = 20f, availablePx = 90f, maxSp = 13f, minSp = 9f))
        assertNull(Labels.fitSp(widthAt1Sp = 5f, availablePx = 0f, maxSp = 13f, minSp = 9f))
    }

    @Test fun emptyTextKeepsItsSize() {
        assertEquals(13f, Labels.fitSp(widthAt1Sp = 0f, availablePx = 50f, maxSp = 13f, minSp = 9f))
    }
}
