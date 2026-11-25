package com.sphynx.models

import com.sphynx.inference.MmprojPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 9 M9: the download list in the app's assets
class ModelCatalogTest {

    private val asset = java.io.File("src/main/assets/" + ModelCatalog.ASSET).readText()

    @Test fun theBuiltInListIsCompleteAndSafe() {
        val all = ModelCatalog.parse(asset)
        val raw = org.json.JSONObject(asset).getJSONArray("models").length()
        assertEquals("every entry in the asset is complete", raw, all.size)
        assertTrue(all.size >= 5)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        for (e in all) {
            for (f in e.files) assertTrue(f.saveAs, ModelCatalog.allowedHost(e.url(f)))
            // a picture part lands next to its model under a name that pairs by itself
            e.mmproj?.let { mm -> assertEquals(e.title, mm.saveAs, MmprojPairing.pairFor(e.model.saveAs, listOf(mm.saveAs))) }
            if (e.mmproj != null) assertTrue(e.pictures)
        }
        assertTrue(all.any { it.model.format == ModelRecord.Format.LITERT_LM })
        assertTrue(all.any { it.mmproj != null })
    }

    private val good = """{"schema":1,"models":[{"id":"a","title":"A","repo":"o/r","revision":"${"1".repeat(40)}","job":"tactical",
        "file":"a.gguf","size":10,"sha256":"${"b".repeat(64)}","mmproj":{"file":"mmproj-model-f16.gguf","saveAs":"mmproj-a-f16.gguf","size":5,"sha256":"${"c".repeat(64)}"}}]}"""

    @Test fun parsing() {
        val e = ModelCatalog.parse(good).single()
        assertEquals("https://huggingface.co/o/r/resolve/${"1".repeat(40)}/mmproj-model-f16.gguf", e.url(e.mmproj!!))
        assertEquals("mmproj-a-f16.gguf", e.mmproj!!.saveAs); assertEquals(15, e.totalBytes); assertEquals("tactical", e.job)
        // unsafe or incomplete entries are left out
        assertTrue(ModelCatalog.parse(good.replace("a.gguf", "../a.gguf")).isEmpty())
        assertTrue(ModelCatalog.parse(good.replace("\"o/r\"", "\"o/r/../x\"")).isEmpty())
        assertTrue(ModelCatalog.parse(good.replace("b".repeat(64), "b".repeat(63))).isEmpty())
        assertTrue(ModelCatalog.parse(good.replace("\"size\":10", "\"size\":0")).isEmpty())
        assertTrue(ModelCatalog.parse(good.replace("\"schema\":1", "\"schema\":2")).isEmpty())
        assertTrue(ModelCatalog.parse("not json").isEmpty())
        assertTrue(ModelCatalog.parse(good.replace("mmproj-a-f16.gguf", "a2-f16.gguf")).isEmpty())   // not a projector name
    }

    @Test fun onlyHuggingFaceOverHttps() {
        assertTrue(ModelCatalog.allowedHost("https://huggingface.co/o/r/resolve/x/a.gguf"))
        assertTrue(ModelCatalog.allowedHost("https://cdn-lfs.huggingface.co/x"))
        assertTrue(ModelCatalog.allowedHost("https://cas-bridge.xethub.hf.co/x?y=1"))
        assertFalse(ModelCatalog.allowedHost("http://huggingface.co/x"))
        assertFalse(ModelCatalog.allowedHost("https://huggingface.co.evil.example/x"))
        assertFalse(ModelCatalog.allowedHost("https://evilhuggingface.co/x"))
        assertFalse(ModelCatalog.allowedHost("not a url"))
    }

    @Test fun resumingAndReplies() {
        assertNull(ModelCatalog.resumeFrom(0, 100)); assertEquals(50L, ModelCatalog.resumeFrom(50, 100))
        assertNull(ModelCatalog.resumeFrom(100, 100)); assertNull(ModelCatalog.resumeFrom(120, 100))
        assertEquals(ModelCatalog.Reply.APPEND, ModelCatalog.reply(206, 50))
        assertEquals(ModelCatalog.Reply.RESTART, ModelCatalog.reply(200, 50))
        assertEquals(ModelCatalog.Reply.DONE, ModelCatalog.reply(416, 50))
        assertEquals(ModelCatalog.Reply.FAIL, ModelCatalog.reply(206, null))
        assertEquals(ModelCatalog.Reply.FAIL, ModelCatalog.reply(404, null))
    }

    @Test fun verifying() {
        val f = ModelCatalog.File("a.gguf", 10, "b".repeat(64), "a.gguf")
        assertNull(ModelCatalog.verify(f, 10, "B".repeat(64)))
        assertNotNull(ModelCatalog.verify(f, 9, "b".repeat(64)))
        assertTrue(ModelCatalog.verify(f, 10, "c".repeat(64))!!.contains("fingerprint"))
    }
}
