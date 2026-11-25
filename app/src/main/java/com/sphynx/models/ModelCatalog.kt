package com.sphynx.models

import org.json.JSONObject
import java.util.Locale

/**
 * PHASE 9, step M9 (owner, 2026-11-25): the short list of known-good models the Model Lab can download from Hugging
 * Face. The list is the app's asset [ASSET], changed only by a pull request and never fetched from anywhere else. Every
 * file carries its size and Hugging Face's SHA-256, shown before a download starts and checked after it. A model with
 * a picture part lists its mmproj file, saved under a name that pairs with the model (MmprojPairing). Pure, tested.
 */
object ModelCatalog {

    const val ASSET = "model_catalog.json"
    const val SCHEMA = 1

    class File(val name: String, val size: Long, val sha256: String, val saveAs: String) {
        val format: ModelRecord.Format? get() = ModelRecord.formatOf(saveAs, false)
    }

    class Entry(val id: String, val title: String, val repo: String, val revision: String, val license: String?,
                val job: String, val pictures: Boolean, val note: String, val model: File, val mmproj: File?) {
        val files: List<File> get() = listOfNotNull(model, mmproj)
        val totalBytes: Long get() = files.sumOf { it.size }
        fun url(f: File): String = "https://huggingface.co/$repo/resolve/$revision/${f.name}"
        /** "Gemma 4 E2B (LiteRT-LM) · 2,468 MB · reads pictures · for the strategic job · apache-2.0". */
        fun line(): String = listOfNotNull(title, "${ModelStorage.mb(totalBytes)} MB" + (if (mmproj != null) " with its picture part" else ""),
            if (pictures) "reads pictures" else "text only", "for the $job job", license).joinToString(" · ")
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val REV = Regex("^[0-9a-f]{40}$")
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.(gguf|litertlm)$")

    private fun file(o: JSONObject?): File? {
        o ?: return null
        val n = o.optString("file"); val save = o.optString("saveAs").ifEmpty { n }
        val sha = o.optString("sha256").lowercase(Locale.US); val size = o.optLong("size", -1)
        if (!NAME.matches(n) || !NAME.matches(save) || !HEX64.matches(sha) || size <= 0) return null
        return File(n, size, sha, save)
    }

    /** The entries of the asset's text; an entry that is not complete and safe is left out. */
    fun parse(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(text)
            if (root.optInt("schema", 0) != SCHEMA) return emptyList()
            val a = root.getJSONArray("models")
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val model = file(o) ?: return@mapNotNull null
                val mm = if (o.has("mmproj")) (file(o.optJSONObject("mmproj")) ?: return@mapNotNull null) else null
                val repo = o.optString("repo"); val rev = o.optString("revision")
                if (!REPO.matches(repo) || !REV.matches(rev) || model.format == null) return@mapNotNull null
                if (mm != null && (model.format != ModelRecord.Format.GGUF || !com.sphynx.inference.MmprojPairing.isProjector(mm.saveAs))) return@mapNotNull null
                Entry(o.optString("id").ifEmpty { model.saveAs }, o.optString("title").ifEmpty { model.saveAs }, repo, rev,
                    o.optString("license").ifEmpty { null }, if (o.optString("job") == "tactical") "tactical" else "strategic",
                    o.optBoolean("pictures"), o.optString("note"), model, mm)
            }
        } catch (e: Exception) { emptyList() }
    }

    /** May a download (or a redirect of one) go to [url]? Only https to Hugging Face and its file servers. */
    fun allowedHost(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (e: Exception) { return false }
        if (u.scheme?.lowercase(Locale.US) != "https") return false
        val h = u.host?.lowercase(Locale.US) ?: return false
        return h == "huggingface.co" || h.endsWith(".huggingface.co") || h == "hf.co" || h.endsWith(".hf.co")
    }

    /** What to ask the server for: null = start again from the beginning, else the byte to go on from. */
    fun resumeFrom(partBytes: Long, total: Long): Long? = if (partBytes in 1 until total) partBytes else null

    /** What the reply means for a request that asked to go on from [from]. */
    enum class Reply { APPEND, RESTART, DONE, FAIL }
    fun reply(code: Int, from: Long?): Reply = when {
        code == 206 && from != null -> Reply.APPEND
        code == 200 -> Reply.RESTART             // the server sent the whole file: start the part again
        code == 416 && from != null -> Reply.DONE // nothing left to send: check what is there
        else -> Reply.FAIL
    }

    /** Why the finished file is refused, or null when its size and hash are the list's. */
    fun verify(f: File, size: Long, sha256: String): String? = when {
        size != f.size -> "the file is ${ModelStorage.mb(size)} MB, the list says ${ModelStorage.mb(f.size)} MB"
        !sha256.equals(f.sha256, ignoreCase = true) -> "its fingerprint does not match the list (the download is broken, or the file on Hugging Face changed)"
        else -> null
    }
}
