package com.sphynx.models

import com.sphynx.core.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFolderPathTest {

    @Test fun theAppFolder() {
        assertEquals("CodeOnTheGoProjects/Sphynx-Storage", Storage.REL)
        assertEquals(Storage.REL, AutoFolder.FOLDER_PATH)
        assertEquals("Sphynx-Storage", AutoFolder.FOLDER)
        assertEquals("MLBB-Research", AutoFolder.LEGACY_FOLDER)
    }

    @Test fun messagesNameWhereFilesGo() {
        assertEquals("/CodeOnTheGoProjects/Sphynx-Storage", Storage.label(true))
        // without all files access: Download/Sphynx-Storage (owner, 2026-11-11), never Download/MLBB-Research
        assertEquals("Download/Sphynx-Storage", Storage.label(false))
        assertEquals("Download/Sphynx-Storage", Storage.FALLBACK_REL)
    }

    @Test fun recognisesTheOldFolder() {
        assertTrue(AutoFolder.isLegacyPath("Download/MLBB-Research"))
        assertTrue(AutoFolder.isLegacyPath("Download/mlbb-research/"))
        assertFalse(AutoFolder.isLegacyPath("CodeOnTheGoProjects/Sphynx-Storage"))
    }
}
