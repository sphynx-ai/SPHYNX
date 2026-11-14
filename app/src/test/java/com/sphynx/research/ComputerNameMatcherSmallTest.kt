package com.sphynx.research

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The device captures 800x359 frames (device test 2026-11-10): "Computer" is then about 49x9 pixels.
// The word is drawn into a synthetic frame at that size by averaging the template's pixels (as a
// downscaled screen does); the matcher must still find it, and must not find it in an empty frame.
class ComputerNameMatcherSmallTest {

    private val bytes = File("src/main/assets/" + ComputerNameMatcher.ASSET).readBytes()
    private val m = ComputerNameMatcher(bytes)

    private fun frameWithWord(w: Int, h: Int, fx: Float, fy: Float, ww: Int, wh: Int): IntArray {
        val px = IntArray(w * h) { (0xFF shl 24) or (30 shl 16) or (40 shl 8) or 55 }
        val x0 = (fx * w).toInt(); val y0 = (fy * h).toInt()
        for (y in 0 until wh) for (x in 0 until ww) {
            val ya = y * 32 / wh; val yb = maxOf(ya + 1, (y + 1) * 32 / wh)
            val xa = x * 169 / ww; val xb = maxOf(xa + 1, (x + 1) * 169 / ww)
            var s = 0; var n = 0
            for (yy in ya until yb) for (xx in xa until xb) { s += bytes[yy * 169 + xx].toInt() and 255; n++ }
            val v = s / n
            px[(y0 + y) * w + x0 + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return px
    }

    @Test fun findsTheWordAtTheAppsCaptureSize() {
        // the scoreboard spelling: 49x9 at 800 wide; a loading card: 39x7 (0.8x) - and one pixel off each way
        for ((ww, wh, mul) in listOf(Triple(49, 9, 1f), Triple(50, 10, 1f), Triple(39, 7, 0.8f), Triple(40, 8, 0.8f))) {
            val px = frameWithWord(800, 359, 0.7363f, 0.2754f, ww, wh)
            val s = m.score(px, 800, 359, 0.7363f, 0.2754f, sizeMul = mul)
            assertTrue("${ww}x$wh: $s", s >= 0.9f)
        }
    }

    @Test fun anEmptyRowIsNotTheWord() {
        val px = IntArray(800 * 359) { (0xFF shl 24) or (((it * 7919) % 97) shl 8) }
        assertTrue(m.score(px, 800, 359, 0.7363f, 0.2754f) < 0.6f)
    }
}
