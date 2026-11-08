package com.example.mlbbresearch.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFolderPathTest {

    @Test fun newImportFolder() {
        assertEquals("Sphynx-Auto-Pilot", AutoFolder.FOLDER)
        assertEquals("MLBB-Research", AutoFolder.LEGACY_FOLDER)
    }

    @Test fun recognisesTheOldFolder() {
        assertTrue(AutoFolder.isLegacyPath("Download/MLBB-Research"))
        assertTrue(AutoFolder.isLegacyPath("Download/mlbb-research/"))
        assertFalse(AutoFolder.isLegacyPath("Sphynx-Auto-Pilot"))
        assertFalse(AutoFolder.isLegacyPath("Download/Sphynx-Auto-Pilot"))
    }
}
