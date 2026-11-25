package com.sphynx.models

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 9 M7: the registry's debugging file names no folders
class ModelRegistryExportTest {

    @Test fun pathsAreCutToTheFileName() {
        assertEquals("a y.gguf b", ModelRegistryExport.redact("a /storage/emulated/0/CodeOnTheGoProjects/Sphynx-Storage/Input/y.gguf b"))
        assertEquals("x.litertlm", ModelRegistryExport.redact("/data/user/0/com.sphynx/files/litertlm/x.litertlm"))
        assertEquals("content: doc", ModelRegistryExport.redact("content: content://com.android.externalstorage.documents/tree/doc"))
        assertEquals("plain name.gguf", ModelRegistryExport.redact("plain name.gguf"))
    }

    @Test fun theFileHoldsTheRecordsAndNoFolders() {
        val r = ModelRecord("id1", "y.gguf", ModelRecord.Format.GGUF, 10, originalName = "/storage/emulated/0/Download/y.gguf",
            facts = mapOf("processors" to "CPU|1|9|1.000|1|s|d"))
        val t = ModelRegistryExport.text(listOf(r), "38.40", mapOf("strategic" to "gguf:y.gguf"), "Models 0 MB", 5)
        assertFalse(t.contains("/storage")); assertFalse(t.contains("emulated"))
        val o = JSONObject(t)
        assertEquals("sphynx-model-registry", o.getString("kind"))
        assertEquals("y.gguf", o.getJSONArray("models").getJSONObject(0).getString("originalName"))
        assertEquals("gguf:y.gguf", o.getJSONObject("jobs").getString("strategic"))
        assertTrue(ModelRegistryExport.fileName("1").startsWith("diagnostics-"))   // goes to Recordings (StorageLayout)
    }
}
