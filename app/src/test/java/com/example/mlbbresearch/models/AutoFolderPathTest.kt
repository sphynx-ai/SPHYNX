package com.example.mlbbresearch.models

import com.example.mlbbresearch.core.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFolderPathTest {

    @Test fun theAppFolder() {
        assertEquals("CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage", Storage.REL)
        assertEquals(Storage.REL, AutoFolder.FOLDER_PATH)
        assertEquals("Sphynx-Auto-Pilot-Storage", AutoFolder.FOLDER)
        assertEquals("MLBB-Research", AutoFolder.LEGACY_FOLDER)
    }

    @Test fun messagesNameWhereFilesGo() {
        assertEquals("/CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage", Storage.label(true))
        assertEquals("Download/MLBB-Research", Storage.label(false))
    }

    @Test fun recognisesTheOldFolder() {
        assertTrue(AutoFolder.isLegacyPath("Download/MLBB-Research"))
        assertTrue(AutoFolder.isLegacyPath("Download/mlbb-research/"))
        assertFalse(AutoFolder.isLegacyPath("CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage"))
    }
}
