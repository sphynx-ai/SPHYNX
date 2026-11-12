package com.example.mlbbresearch.vision

import com.example.mlbbresearch.testutil.SynthFrame
import com.example.mlbbresearch.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

// Screen phases from synthetic frames that carry only the signature each rule measures.
class ScreenPhaseTest {

    @Before fun setUp() { TestSupport.freshCfg() }

    private val loadRed = SynthFrame.rgb(200, 30, 40)

    @Test fun loadingScreenByItsRedLineUnderTheLowerRow() {
        val f = SynthFrame()
        f.rect(0.34f, 0.965f, 0.95f, 0.975f, loadRed)
        assertEquals(ScreenHint.LOADING, ScreenReader.classify(f.px, f.w, f.h))
    }

    @Test fun loadingScreenWithTheTeamRowsSwapped() {
        val f = SynthFrame()
        f.rect(0.06f, 0.44f, 0.66f, 0.45f, loadRed)          // the red line under the TOP row instead
        assertEquals(ScreenHint.LOADING, ScreenReader.classify(f.px, f.w, f.h))
    }

    @Test fun aShortRedMarkIsNotTheLoadingScreen() {
        val f = SynthFrame()
        f.rect(0.34f, 0.965f, 0.45f, 0.975f, loadRed)         // under 60% of the row
        assertNotEquals(ScreenHint.LOADING, ScreenReader.classify(f.px, f.w, f.h))
    }

    @Test fun anEmptyDarkScreenIsNotTheLoadingScreen() {
        val f = SynthFrame()
        assertNotEquals(ScreenHint.LOADING, ScreenReader.classify(f.px, f.w, f.h))
    }

    // SOLO PRACTICE loading screen (owner's screenshot 2026-11-10): night sky in both top corners
    // (measured 52, 81, 141) and the enemy card's red loading bar (11.5% of the width at y 0.97)
    private val sky = SynthFrame.rgb(52, 81, 141)

    @Test fun soloPracticeLoadingScreen() {
        val f = SynthFrame(fill = sky)
        f.rect(0.567f, 0.965f, 0.682f, 0.975f, loadRed)
        assertEquals(ScreenHint.LOADING, ScreenReader.classify(f.px, f.w, f.h))
    }

    @Test fun soloLoadingWithTheEnemyCardOnTop() {
        val f = SynthFrame(fill = sky)
        f.rect(0.30f, 0.44f, 0.415f, 0.45f, loadRed)
        assertEquals(ScreenHint.LOADING, ScreenReader.classify(f.px, f.w, f.h))
    }

    @Test fun skyWithoutTheRedBarIsNotSoloLoading() {
        assertEquals(false, ScreenReader.soloLoading(SynthFrame(fill = sky).px, 2000, 900))
    }

    @Test fun aRedBarWithoutTheSkyIsNotSoloLoading() {
        val f = SynthFrame()
        f.rect(0.567f, 0.965f, 0.682f, 0.975f, loadRed)
        assertEquals(false, ScreenReader.soloLoading(f.px, f.w, f.h))
    }
}
