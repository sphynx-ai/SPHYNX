package com.example.mlbbresearch.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// Golden-image tests on the owner's reference screenshots. They are read from the
// reference-assets branch (CI exports it and sets REFERENCE_LIBRARY_DIR); without it they skip.
class ScoreboardNameCheckTest {

    companion object {
        private val LIB = System.getenv("REFERENCE_LIBRARY_DIR")?.let { File(it) }
        private const val UI = "moba-games/mobile-legends/ui-hud/"
        lateinit var m: ComputerNameMatcher

        @BeforeClass @JvmStatic fun loadTemplate() {
            m = ComputerNameMatcher(File("src/main/assets/" + ComputerNameMatcher.ASSET).readBytes())
        }

        private fun frame(rel: String): Triple<IntArray, Int, Int>? {
            val f = LIB?.let { File(it, rel) } ?: return null
            if (!f.exists()) return null
            return pixels(ImageIO.read(f))
        }
        fun pixels(img: BufferedImage): Triple<IntArray, Int, Int> =
            Triple(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
    }

    @Test fun templateAssetIsTheExpectedSize() {
        assertEquals(169 * 32, File("src/main/assets/" + ComputerNameMatcher.ASSET).length().toInt())
    }

    @Test fun practiceScoreboardsVerify() {
        for (name in listOf("mlbb-screen-in-match-scoreboard-practice-vs-computer.jpg", "mlbb-screen-in-match-scoreboard-practice-match-start.jpg")) {
            val f = frame(UI + name); assumeTrue("reference library not available", f != null)
            val (px, w, h) = f!!
            val r = ScoreboardNameCheck.check(px, w, h, m)
            assertEquals(name + " " + r, ScoreboardNameCheck.Verdict.ALL_COMPUTER, r.verdict)
            assertEquals(0, r.selfRow)
        }
    }

    @Test fun aHumanNameInAnyOtherRowIsDetected() {
        val f = frame(UI + "mlbb-screen-in-match-scoreboard-practice-vs-computer.jpg"); assumeTrue(f != null)
        val (px, w, h) = f!!
        // copy your own name row (not "Computer") over enemy row 3: a stand-in for a human opponent
        val sx = (ScoreboardNameCheck.ALLY_X * w).toInt(); val sy = (ScoreboardNameCheck.NAME_TOP[0] * h).toInt()
        val dx = (ScoreboardNameCheck.ENEMY_X * w).toInt(); val dy = (ScoreboardNameCheck.NAME_TOP[2] * h).toInt()
        val p = px.copyOf()
        for (j in 0 until 32) for (i in 0 until 169) p[(dy + j) * w + dx + i] = px[(sy + j) * w + sx + i]
        val r = ScoreboardNameCheck.check(p, w, h, m)
        assertEquals(r.toString(), ScoreboardNameCheck.Verdict.HUMAN_PRESENT, r.verdict)
    }

    @Test fun worksAtHalfResolution() {
        val f = LIB?.let { File(it, UI + "mlbb-screen-in-match-scoreboard-practice-vs-computer.jpg") }
        assumeTrue(f != null && f.exists())
        val src = ImageIO.read(f!!)
        val half = BufferedImage(src.width / 2, src.height / 2, BufferedImage.TYPE_INT_RGB)
        half.createGraphics().apply { drawImage(src, 0, 0, half.width, half.height, null); dispose() }
        val (px, w, h) = pixels(half)
        assertEquals(ScoreboardNameCheck.Verdict.ALL_COMPUTER, ScoreboardNameCheck.check(px, w, h, m).verdict)
    }

    @Test fun anInMatchFrameWithoutTheScoreboardNeverVerifies() {
        val f = frame(UI + "mlbb-hud-first-blood-banner.jpg"); assumeTrue(f != null)
        val (px, w, h) = f!!
        assertNotEquals(ScoreboardNameCheck.Verdict.ALL_COMPUTER, ScoreboardNameCheck.check(px, w, h, m).verdict)
    }

    @Test fun nonScoreboardScreensNeverVerify() {
        for (name in listOf("mlbb-screen-hero-select-practice-thamuz.jpg", "mlbb-screen-victory-mvp-result-card.jpg", "mlbb-screen-home-lobby-classic-mode-selected.jpg")) {
            val f = frame(UI + name); assumeTrue(f != null)
            val (px, w, h) = f!!
            assertNotEquals(name, ScoreboardNameCheck.Verdict.ALL_COMPUTER, ScoreboardNameCheck.check(px, w, h, m).verdict)
        }
    }
}
