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
// Folder: /Sphynx-Auto-Pilot at the top of the phone's storage (you choose it once on the Model page;
// Android then lets the app read it for good). The app's own folder
// Android/data/com.example.mlbbresearch/files/Sphynx-Auto-Pilot is read too (no choosing needed, e.g.
// when copying from a computer), and so is the older .../files/MLBB-Research.
// Before v37.19 the folder was Download/MLBB-Research: a phone that still has it chosen keeps
// working, and the Model page asks to choose /Sphynx-Auto-Pilot instead.
//
// What it looks for (names are not case sensitive):
//   *.litertlm (big: Gemma 4 E2B / 3n E2B)  strategic model   -> copied in, loaded (Gemma 4 preferred)
//   *.litertlm (small: 270m / 1b / tactic in the name, or under 1 GB)  tactical model -> copied in
//   mlbb_detector*.tflite / *detector*.tflite   YOLO detector -> copied in, loaded
//   labels.txt / mlbb_labels.txt    detector class names      -> applied
//   mlbb_screen_detector.tflite / *screen*.tflite   whole-screen detector -> copied in, loaded
//   screen_labels.txt               its class names           -> applied
//   policy*.tflite / *tactical*.tflite          tactical network -> copied in, loaded
//   policy_info.json                how reliable the network is -> only its reliable parts used
//   mlbb_policies.zip               the notebook's networks: one per hero + all heroes -> unpacked
//   policy_<Hero>.tflite / policy_info_<Hero>.json   one hero's network (or policy_all.tflite)
//   groq_key.txt / groq.txt         Groq API key (first line)  -> applied
//   groq_model.txt                  Groq model name (optional) -> applied
//   kaggle.json                     Kaggle API key -> cloud training (Learn page)
//   roboflow_key.txt                Roboflow key (optional) -> sent with minimap cloud training
//   mlbb-online-meta.json / mlbb-online-skills.json   what Groq found (saved by the app) -> read back
// A file is only copied again when it changed (size or date), so big files are not copied
// on every start.
object AutoFolder {

    const val FOLDER = "Sphynx-Auto-Pilot"
    const val LEGACY_FOLDER = "MLBB-Research"         // the import folder before v37.19
    @Volatile var status = "folder not chosen yet"
        private set
    @Volatile var busy = false
        private set

    val treeUri: Uri? get() = Cfg.raw("auto_folder_uri")?.let { Uri.parse(it) }

    fun localDir(ctx: Context): File? =
        ctx.applicationContext.getExternalFilesDir(null)?.let { File(it, FOLDER).apply { mkdirs() } }

    // the app's own folder under its pre-v37.19 name (read only, never created)
    private fun legacyLocalDir(ctx: Context): File? =
        ctx.applicationContext.getExternalFilesDir(null)?.let { File(it, LEGACY_FOLDER) }?.takeIf { it.isDirectory }

    // the chosen folder as a path, e.g. "Sphynx-Auto-Pilot" or "Download/MLBB-Research" (null = none)
    fun chosenPath(): String? = treeUri?.let { u ->
        try { DocumentsContract.getTreeDocumentId(u).substringAfter(':') } catch (e: Exception) { null }
    }

    // true while the phone still has the old Download/MLBB-Research chosen
    fun legacyChosen(): Boolean = chosenPath()?.let { isLegacyPath(it) } ?: false
    fun isLegacyPath(path: String): Boolean = path.trimEnd('/').endsWith(LEGACY_FOLDER, ignoreCase = true)

    val folderHint: String get() = when {
        treeUri == null -> "Choose the /$FOLDER folder (Model page: Choose the folder)"
        legacyChosen() -> "Still reading the old Download/$LEGACY_FOLDER folder: Model page > Change the folder > pick /$FOLDER"
        else -> "Reading /${chosenPath() ?: FOLDER}"
    }

    // the folder picker, opening at /Sphynx-Auto-Pilot (top of the phone's storage) when possible
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$FOLDER")
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun onPicked(ctx: Context, uri: Uri, done: (String) -> Unit) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { Diag.error("Folder permission", e) }
        Cfg.setRaw("auto_folder_uri", uri.toString())
        if (legacyChosen()) Diag.info("Import folder: the old Download/$LEGACY_FOLDER was chosen; /$FOLDER is the new folder")
        scan(ctx) { msg -> done(if (legacyChosen()) "$msg - note: this is the old folder, /$FOLDER is the new one" else msg) }
    }

    class Entry(val name: String, val size: Long, val modified: Long, val open: () -> InputStream?)

    // every file in the folder (and the app's own folder): cloud training reads its inputs from here
    fun files(ctx: Context): List<Entry> = list(ctx.applicationContext)

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
        for (dir in listOfNotNull(localDir(ctx), legacyLocalDir(ctx)))
            dir.listFiles()?.forEach { f -> if (f.isFile && out.none { it.name == f.name }) out.add(Entry(f.name, f.length(), f.lastModified()) { f.inputStream() }) }
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

    // "policy_Chou.tflite" -> "chou", "policy_all.tflite" -> "all"
    private fun policyKey(fileName: String): String {
        val stem = fileName.substringBeforeLast('.').substringAfter('_')
        return com.example.mlbbresearch.learning.PolicyNet.key(stem).ifEmpty { "all" }
    }

    // mlbb_policies.zip from the notebook: policy_<Hero>.tflite / policy_info_<Hero>.json files
    private fun unpackPolicies(app: Context, e: Entry): List<String> {
        val names = ArrayList<String>()
        (e.open() ?: throw IllegalStateException("cannot open ${e.name}")).use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                while (true) {
                    val z = zip.nextEntry ?: break
                    val n = z.name.substringAfterLast('/').lowercase()
                    when {
                        z.isDirectory -> {}
                        // the fight-choice network rides in the same zip
                        n == "fight_choice.tflite" -> {
                            val dst = com.example.mlbbresearch.learning.FightNet.file(app)
                            val tmp = File(dst.parentFile, dst.name + ".tmp")
                            tmp.outputStream().use { zip.copyTo(it) }
                            dst.delete(); tmp.renameTo(dst)
                            com.example.mlbbresearch.learning.FightNet.load(app)
                            names.add("fight-choice network")
                        }
                        n == "fight_choice_info.json" -> com.example.mlbbresearch.learning.FightNet.saveInfo(zip.readBytes().toString(Charsets.UTF_8))
                        n.endsWith(".tflite") && n.startsWith("policy_") -> {
                            val k = policyKey(n)
                            val dst = com.example.mlbbresearch.learning.PolicyNet.fileFor(app, k)
                            val tmp = File(dst.parentFile, dst.name + ".tmp")
                            tmp.outputStream().use { zip.copyTo(it) }
                            dst.delete(); tmp.renameTo(dst)
                            names.add(if (k == "all") "all heroes" else z.name.substringAfterLast('/').substringBeforeLast('.').substringAfter('_'))
                        }
                        n.endsWith(".json") && n.startsWith("policy_info_") -> {
                            val k = policyKey("policy_" + n.removePrefix("policy_info_"))
                            com.example.mlbbresearch.learning.PolicyNet.saveInfo(k, zip.readBytes().toString(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
        return names
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
                    status = "folder not chosen yet (Model page: Choose the folder, pick /$FOLDER)"
                } else {
                    // the strategic model: the newest kind when several big .litertlm files are there
                val bigModel = files.filter { it.name.lowercase().endsWith(".litertlm") && !ModelManager.isTactical(it.name, it.size) }
                    .maxWithOrNull(compareBy({ ModelManager.strategicRank(it.name) }, { it.modified }))
                for (e in files) {
                        val n = e.name.lowercase()
                        try {
                            when {
                                n.endsWith(".litertlm") && ModelManager.isTactical(e.name, e.size) -> {
                                    val dst = ModelManager.tacticalFile(app)
                                    if (changed("gemma_tactical", e, dst)) {
                                        status = "copying the small model..."
                                        copy(e, dst) { b -> status = "copying the small model... ${b / 1048576} MB" }
                                        remember("gemma_tactical", e)
                                        Cfg.setRaw("llm_name_tactical", ModelManager.prettyName(e.name))
                                        found.add("small tactical model copied (${ModelManager.prettyName(e.name)})")
                                        if (com.example.mlbbresearch.ai.TacticalLlmEngine.ready) ModelManager.unloadTactical()
                                    }
                                    if (Cfg.tacticalLlmOn && !com.example.mlbbresearch.ai.TacticalLlmEngine.ready)
                                        ModelManager.loadTactical(app) { err -> Diag.info(if (err == null) "Small tactical model loaded" else "Small model load: $err") }
                                }
                                n.endsWith(".litertlm") && e !== bigModel -> found.add("${e.name} skipped (a newer model is used)")
                                n.endsWith(".litertlm") -> {
                                    val dst = ModelManager.modelFile(app)
                                    var fresh = false
                                    if (changed("gemma", e, dst)) {
                                        status = "copying the Gemma model..."
                                        copy(e, dst) { b -> status = "copying the Gemma model... ${b / 1048576} MB" }
                                        remember("gemma", e)
                                        Cfg.setRaw("llm_name_strategic", ModelManager.prettyName(e.name))
                                        found.add("${ModelManager.prettyName(e.name)} copied")
                                        fresh = true
                                    }
                                    // a new model replaces the loaded one
                                    if (fresh && com.example.mlbbresearch.ai.GemmaEngine.ready) kotlinx.coroutines.runBlocking { com.example.mlbbresearch.ai.GemmaEngine.unload() }
                                    if (!com.example.mlbbresearch.ai.GemmaEngine.ready) {
                                        ModelManager.load(app) { err -> Diag.info(if (err == null) "Gemma loaded from the folder" else "Gemma load: $err") }
                                        found.add("Gemma loading")
                                    }
                                }
                                // the notebook's bundle: every hero's network + the all-heroes one
                                n.endsWith(".zip") && (n.contains("polic") || n.contains("tactical")) -> {
                                    val sig = "${e.name}:${e.size}:${e.modified}"
                                    if (Cfg.raw("auto_sig_policyzip") != sig) {
                                        val names = unpackPolicies(app, e)
                                        Cfg.setRaw("auto_sig_policyzip", sig)
                                        found.add("tactical networks: " + names.joinToString())
                                        com.example.mlbbresearch.learning.PolicyNet.load(app)
                                    }
                                }
                                n.endsWith(".tflite") && (n.startsWith("policy_") || n.startsWith("tactical_")) -> {
                                    val k = policyKey(n)
                                    val dst = com.example.mlbbresearch.learning.PolicyNet.fileFor(app, k)
                                    if (changed("policy_$k", e, dst)) {
                                        copy(e, dst); remember("policy_$k", e)
                                        found.add("tactical network for ${if (k == "all") "all heroes" else k}")
                                        com.example.mlbbresearch.learning.PolicyNet.load(app)
                                    }
                                }
                                n.endsWith(".json") && n.startsWith("policy_info_") -> {
                                    val k = policyKey(n.removePrefix("policy_info_").let { "policy_$it" })
                                    val txt = text(e)
                                    if (txt != com.example.mlbbresearch.learning.PolicyNet.infoFor(k)) {
                                        com.example.mlbbresearch.learning.PolicyNet.saveInfo(k, txt)
                                        found.add("network quality for ${if (k == "all") "all heroes" else k}")
                                        com.example.mlbbresearch.learning.PolicyNet.load(app)
                                    }
                                }
                                n.endsWith(".tflite") && (n.contains("policy") || n.contains("tactical")) -> {
                                    val dst = com.example.mlbbresearch.learning.PolicyNet.modelFile(app)
                                    if (changed("policy", e, dst)) {
                                        copy(e, dst); remember("policy", e)
                                        found.add("tactical network " + (com.example.mlbbresearch.learning.PolicyNet.load(app) ?: "loaded"))
                                    } else if (!com.example.mlbbresearch.learning.PolicyNet.ready) com.example.mlbbresearch.learning.PolicyNet.load(app)
                                }
                                n.endsWith(".tflite") && n.contains("screen") -> {   // the whole-screen detector
                                    val dst = com.example.mlbbresearch.vision.ScreenDetector.modelFile(app)
                                    if (changed("screen", e, dst)) {
                                        copy(e, dst); remember("screen", e)
                                        found.add("screen detector " + (com.example.mlbbresearch.vision.ScreenDetector.load(app) ?: "loaded"))
                                    } else if (!com.example.mlbbresearch.vision.ScreenDetector.ready) com.example.mlbbresearch.vision.ScreenDetector.load(app)
                                }
                                n == "policy_info.json" -> {
                                    // how reliable the tactical network is: the app then only uses the reliable parts
                                    val txt = text(e)
                                    if (txt != Cfg.raw("policy_info")) {
                                        Cfg.setRaw("policy_info", txt)
                                        com.example.mlbbresearch.learning.PolicyNet.load(app)
                                        found.add("tactical network quality: " + com.example.mlbbresearch.learning.PolicyNet.qualityText)
                                    }
                                }
                                n == "screen_labels.txt" -> {
                                    val labels = text(e).lines().flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
                                    if (labels.isNotEmpty() && labels != Cfg.screenLabels) {
                                        Cfg.screenLabels = labels
                                        if (com.example.mlbbresearch.vision.ScreenDetector.modelFile(app).exists()) com.example.mlbbresearch.vision.ScreenDetector.load(app)
                                        found.add("screen detector class names")
                                    }
                                }
                                n.endsWith(".tflite") -> {           // the minimap detector (mlbb_detector.tflite or any other .tflite)
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
                                // the other online models that share Groq's work
                                n.startsWith("gemini") && n.endsWith(".txt") && !n.contains("model") -> {
                                    val key = text(e).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                                    if (key.isNotEmpty() && key != Cfg.geminiKey) { Cfg.geminiKey = key; found.add("Gemini API key") }
                                }
                                // the small model's quality report (its notebook writes it next to the model)
                                n == "tactical_llm_info.json" -> {
                                    val t = text(e)
                                    if (t != Cfg.raw("tactical_llm_info")) { Cfg.setRaw("tactical_llm_info", t); found.add("small model quality report") }
                                }
                                n.startsWith("cerebras") && n.endsWith(".txt") && !n.contains("model") -> {
                                    val key = text(e).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                                    if (key.isNotEmpty() && key != Cfg.cerebrasKey) { Cfg.cerebrasKey = key; found.add("Cerebras API key") }
                                }
                                n in setOf("gemini_flash_model.txt", "gemini_mid_model.txt", "gemini_lite_model.txt", "cerebras_model.txt", "groq_model2.txt") -> {
                                    val m = text(e).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                                    val k = n.removeSuffix(".txt")
                                    if (m.isNotEmpty() && m != Cfg.raw(k)) { Cfg.setRaw(k, m); found.add("$k: $m") }
                                }
                                n.startsWith("groq") && n.endsWith(".txt") && !n.contains("model") -> {
                                    val key = text(e).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                                    if (key.isNotEmpty() && key != Cfg.groqKey) { Cfg.groqKey = key; found.add("Groq API key") }
                                }
                                // the online meta / skills Groq found, kept in the folder (survives a reinstall)
                                com.example.mlbbresearch.learning.OnlineBackup.isBackup(e.name) -> {
                                    val sig = "${e.name}:${e.size}:${e.modified}"
                                    if (Cfg.raw("auto_sig_online_${e.name}") != sig) {
                                        val k = com.example.mlbbresearch.learning.OnlineBackup.restore(e.name, text(e))
                                        Cfg.setRaw("auto_sig_online_${e.name}", sig)
                                        if (k > 0) found.add("online data restored ($k from ${e.name})")
                                    }
                                }
                                // Kaggle API key (kaggle.com > Settings > API > Create New Token): cloud training
                                n == "kaggle.json" -> {
                                    val o = org.json.JSONObject(text(e))
                                    val u = o.optString("username").trim(); val k = o.optString("key").trim()
                                    if (u.isNotEmpty() && k.isNotEmpty() && (u != Cfg.kaggleUser || k != Cfg.kaggleKey)) {
                                        Cfg.kaggleUser = u; Cfg.kaggleKey = k; found.add("Kaggle key ($u)")
                                    }
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
