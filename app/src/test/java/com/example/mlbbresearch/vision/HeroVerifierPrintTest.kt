package com.example.mlbbresearch.vision

import com.example.mlbbresearch.testutil.SynthFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The colour fingerprint used to compare skill buttons and portraits.
class HeroVerifierPrintTest {

    private fun icon(f: SynthFrame, fx: Float, fy: Float, a: Int, b: Int) {
        f.rect(fx - 0.013f, fy - 0.03f, fx, fy + 0.03f, a)          // left half one colour
        f.rect(fx, fy - 0.03f, fx + 0.013f, fy + 0.03f, b)          // right half another
    }

    @Test fun sameIconMatchesItselfAndADifferentOneDoesNot() {
        val f = SynthFrame()
        icon(f, 0.3f, 0.5f, SynthFrame.rgb(220, 60, 40), SynthFrame.rgb(40, 80, 220))
        icon(f, 0.6f, 0.5f, SynthFrame.rgb(40, 80, 220), SynthFrame.rgb(220, 60, 40))   // the same colours, swapped sides
        val a = HeroVerifier.buttonPrint(f.px, f.w, f.h, 0.3f, 0.5f)
        val b = HeroVerifier.buttonPrint(f.px, f.w, f.h, 0.6f, 0.5f)
        assertNotNull(a); assertNotNull(b)
        assertEquals(1f, HeroVerifier.corr(a!!, a), 1e-4f)
        assertTrue(HeroVerifier.corr(a, b!!) < 0f)
    }

    @Test fun flatOrBlackAreaHasNoFingerprint() {
        val f = SynthFrame(fill = SynthFrame.rgb(5, 5, 5))
        assertNull(HeroVerifier.buttonPrint(f.px, f.w, f.h, 0.5f, 0.5f))
    }

    @Test fun offScreenSpotHasNoFingerprint() {
        val f = SynthFrame()
        assertNull(HeroVerifier.buttonPrint(f.px, f.w, f.h, 0.001f, 0.5f))
    }
}
