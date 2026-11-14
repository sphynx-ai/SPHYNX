package com.sphynx.core

import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-13: a recording always continues in a new file at the limit; a part covers about 4 minutes
class RollOverTest {
    @Test fun aDefaultPartCoversAboutFourMinutes() {
        val s = VideoRecorder.partSeconds(VideoRecorder.DEFAULT_MB)
        assertTrue("part is $s s", s in 240..300)
        assertTrue(VideoRecorder.DEFAULT_MB in VideoRecorder.SIZE_CHOICES_MB)
    }

    @Test fun noPartIsOverGitHubsLimit() {
        assertTrue(VideoRecorder.SIZE_CHOICES_MB.all { it * 1024L * 1024L < GitHubUpload.MAX_FILE })
    }
}
