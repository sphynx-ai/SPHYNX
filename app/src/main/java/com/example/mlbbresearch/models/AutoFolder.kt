package com.example.mlbbresearch.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import java.io.File
import java.io.InputStream

// ONE FOLDER FOR EVERYTHING: put the downloaded files in it and the app finds, copies, loads
// and applies them by itself (when it starts, and when you tap "Scan the folder now").
//
// Folder: Download/MLBB-Research (you choose it once on the Model page; Android then lets the
// app read it for good). The app's own folder Android/data/com.example.mlbbresearch/files/
// MLBB-Research is read too (no choosing needed, e.g. when copying from a computer).
//
// What it looks for (names are not case sensitive):
//   *.litertlm                      Gemma 3n E2B model        -> copied in, loaded
//   mlbb_detector*.tflite / *detector*.tflite   YOLO detector -> copied in, loaded
//   labels.txt / mlbb_labels.txt    detector class names      -> applied
//   policy*.tflite / *tactical*.tflite          tactical network -> copied in, loaded
//   groq_key.txt / groq.txt         Groq API key (first line)  -> applied
//   groq_model.txt                  Groq model name (optional) -> applied
// A file is only copied again when it changed (size or date), so big files are not copied
// on every start.
object AutoFolder {

    const val FOLDER = "MLBB-Research"
    @Volatile var status = "folder not chosen yet"
        private set
    @Volatile var busy = false
        private set

    val treeUri: Uri? get() = Cfg.raw("auto_folder_uri")?.let { Uri.parse(it) }

    fun localDir(ctx: Context): File? =
        ctx.applicationContext.getExternalFilesDir(null)?.let { File(it, FOLDER).apply { mkdirs() } }

    // the folder picker, opening at Download/MLBB-Research when possible
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$FOLDER")
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun onPicked(ctx: Context, uri: Uri, done: (String) -> Unit) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { Diag.error("Folder permission", e) }
        Cfg.setRaw("auto_folder_uri", uri.toString())
        scan(ctx, done)
    }

    private class Entry(val name: String, val size: Long, val modified: Long, val open: () -> InputStream?)

    private fun list(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val cr = ctx.contentResolver
        treeUri?.let { tree ->
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                cr.query(children, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        out.add(Entry(c.getString(1) ?: continue, c.getLong(2), c.getLong(3)) { cr.openInputStream(doc) })
                    }
                }
            } catch (e: Exception) {
                Diag.error("Reading the chosen folder", e)
                status = "cannot read the chosen folder: choose it again"
            }
        }
        localDir(ctx)?.listFiles()?.forEach { f -> if (f.isFile) out.add(Entry(f.name, f.length(), f.lastModified()) { f.inputStream() }) }
        return out
    }

    // copy only when it changed since the last time
    private fun changed(kind: String, e: Entry, dst: File): Boolean {
        val sig = "${e.name}:${e.size}:${e.modified}"
        return !dst.exists() || Cfg.raw("auto_sig_$kind") != sig
    }
    private fun remember(kind: String, e: Entry) = Cfg.setRaw("auto_sig_$kind", "${e.name}:${e.size}:${e.modified}")

    private fun copy(e: Entry, dst: File, progress: (Long) -> Unit = {}) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        (e.open() ?: throw IllegalStateException("cannot open ${e.name}")).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n); total += n
                    progress(total)
                }
            }
        }
        dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("could not save ${dst.name}")
    }

    private fun text(e: Entry): String = (e.open() ?: throw IllegalStateException("cannot open ${e.name}")).use { it.readBytes().toString(Charsets.UTF_8) }

    // look at the folder(s) and apply everything found; runs in the background
    fun scan(ctx: Context, done: ((String) -> Unit)? = null) {
        if (busy) { done?.invoke("Already scanning"); return }
        busy = true
        val app = ctx.applicationContext
        Thread {
            val found = ArrayList<String>()
            try {
                val files = list(app)
                if (treeUri == null && files.isEmpty()) {
                    status = "folder not chosen yet (Model page: Choose the folder)"
                } else {
                    for (e in files) {
                        val n = e.name.lowercase()
                        try {
                            when {
                                n.endsWith(".litertlm") -> {
                                    val dst = ModelManager.modelFile(app)
                                    if (changed("gemma", e, dst)) {
                                        status = "copying the Gemma model..."
                                        copy(e, dst) { b -> status = "copying the Gemma model... ${b / 1048576} MB" }
                                        remember("gemma", e)
                                        found.add("Gemma model copied")
                                    }
                                    if (!com.example.mlbbresearch.ai.GemmaEngine.ready) {
                                        ModelManager.load(app) { err -> Diag.info(if (err == null) "Gemma loaded from the folder" else "Gemma load: $err") }
                                        found.add("Gemma loading")
                                    }
                                }
                                n.endsWith(".tflite") && (n.contains("policy") || n.contains("tactical")) -> {
                                    val dst = com.example.mlbbresearch.learning.PolicyNet.modelFile(app)
                                    if (changed("policy", e, dst)) {
                                        copy(e, dst); remember("policy", e)
                                        found.add("tactical network " + (com.example.mlbbresearch.learning.PolicyNet.load(app) ?: "loaded"))
                                    } else if (!com.example.mlbbresearch.learning.PolicyNet.ready) com.example.mlbbresearch.learning.PolicyNet.load(app)
                                }
                                n.endsWith(".tflite") -> {           // the detector (mlbb_detector.tflite or any other .tflite)
                                    val dst = com.example.mlbbresearch.vision.ObjectDetector.modelFile(app)
                                    if (changed("detector", e, dst)) {
                                        copy(e, dst); remember("detector", e)
                                        found.add("detector " + (com.example.mlbbresearch.vision.ObjectDetector.load(app) ?: "loaded"))
                                    } else if (!com.example.mlbbresearch.vision.ObjectDetector.ready) com.example.mlbbresearch.vision.ObjectDetector.load(app)
                                }
                                n == "labels.txt" || n == "mlbb_labels.txt" -> {
                                    val labels = text(e).lines().map { it.trim() }.filter { it.isNotEmpty() }
                                        .flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
                                    if (labels.isNotEmpty() && labels != Cfg.detectorLabels) {
                                        Cfg.detectorLabels = labels
                                        if (com.example.mlbbresearch.vision.ObjectDetector.modelFile(app).exists()) com.example.mlbbresearch.vision.ObjectDetector.load(app)
                                        found.add("detector class names")
                                    }
                                }
                                n.startsWith("groq") && n.endsWith(".txt") && !n.contains("model") -> {
                                    val key = text(e).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                                    if (key.isNotEmpty() && key != Cfg.groqKey) { Cfg.groqKey = key; found.add("Groq API key") }
                                }
                                n == "groq_model.txt" -> {
                                    val m = text(e).trim()
                                    if (m.isNotEmpty() && m != Cfg.groqModel) { Cfg.groqModel = m; found.add("Groq model $m") }
                                }
                            }
                        } catch (ex: Exception) {
                            Diag.error("Folder file ${e.name}", ex)
                            found.add("${e.name}: failed (${ex.message})")
                        }
                    }
                    status = "last scan: " + (if (found.isEmpty()) "nothing new (${files.size} files)" else found.joinToString(", "))
                }
            } catch (t: Throwable) {
                Diag.error("Folder scan", t)
                status = "scan failed (see Logs)"
            } finally {
                busy = false
            }
            Diag.info("Folder: $status")
            done?.invoke(status)
        }.start()
    }
}
