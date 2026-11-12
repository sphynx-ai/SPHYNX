package com.example.mlbbresearch.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureExportTest {

    @Test fun partsStayUnderGitHubsLimit() {
        val mb = 1024L * 1024
        assertFalse(PictureExport.needsNewPart(0, 200 * mb, 0))            // the first entry always fits
        assertFalse(PictureExport.needsNewPart(90 * mb, 4 * mb, 10))
        assertTrue(PictureExport.needsNewPart(94 * mb, 2 * mb, 10))
        assertTrue(PictureExport.LIMIT < GitHubLimit)
    }

    @Test fun names() {
        assertEquals("pictures-mlbb-screen-dataset-20261112-2300-p2.zip", PictureExport.partName("mlbb-screen-dataset-20261112-2300.zip", 2))
        assertTrue(PictureExport.isPart("pictures-mlbb-screen-dataset-20261112-2300-p2.zip"))
        assertTrue(PictureExport.isDatasetZip("mlbb-minimap-dataset-20261112-2300.zip"))
        assertFalse(PictureExport.isDatasetZip("pictures-mlbb-minimap-dataset-20261112-2300-p1.zip"))
    }

    private val GitHubLimit = 100L * 1024 * 1024

    @Test fun trainedZipsWaitForTheGitHubUpload() {
        val (now, later) = PictureExport.afterTraining(listOf("a.zip", "b.zip"), setOf("a.zip"))
        assertEquals(listOf("a.zip"), now)
        assertEquals(listOf("b.zip"), later)
    }
}
