package com.sphynx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Owner decision 2026-11-21 (D1): the settings file with the API keys never goes into Android's backup.
class BackupRulesTest {

    private fun excludes(xml: String): Int =
        Regex("""<exclude\s+domain="sharedpref"\s+path="cfg\.xml"\s*/>""").findAll(xml).count()

    @Test fun theSettingsFileIsLeftOutOfEveryBackup() {
        val dataExtraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertEquals("cloud backup and device transfer", 2, excludes(dataExtraction))
        assertTrue(dataExtraction.contains("<cloud-backup>") && dataExtraction.contains("<device-transfer>"))
        assertEquals(1, excludes(File("src/main/res/xml/backup_rules.xml").readText()))
    }

    @Test fun theManifestUsesBothRuleFiles() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(m.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }

    // the keys really are in that file: if the settings ever move, this test says the rule must follow
    @Test fun theKeysLiveInTheExcludedFile() {
        val settings = File("src/main/java/com/sphynx/core/Settings.kt").readText()
        assertTrue(settings.contains("getSharedPreferences(\"cfg\""))
        for (k in listOf("groq_key", "gemini_key", "cerebras_key", "kaggle_key", "kaggle_user")) assertTrue(k, settings.contains("\"$k\""))
    }
}
