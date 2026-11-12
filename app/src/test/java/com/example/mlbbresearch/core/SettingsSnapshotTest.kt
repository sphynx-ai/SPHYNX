package com.example.mlbbresearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// the settings saved with a recording: no keys / tokens / folder links, no big blobs
class SettingsSnapshotTest {

    @Test fun secretsAndBlobsAreLeftOut() {
        val o = SessionRecorder.settingsJson(mapOf(
            "lane" to "auto", "retri_creep_pct" to 20, "auto_record" to true,
            "groq_key" to "gsk_secret", "github_token" to "ghp_x", "auto_folder_uri" to "content://x",
            "scenery_map_v1" to "x".repeat(5000)))
        assertEquals("auto", o.getString("lane"))
        assertEquals(20, o.getInt("retri_creep_pct"))
        assertTrue(o.getBoolean("auto_record"))
        assertFalse(o.has("groq_key")); assertFalse(o.has("github_token")); assertFalse(o.has("auto_folder_uri"))
        assertFalse(o.has("scenery_map_v1"))
    }
}
