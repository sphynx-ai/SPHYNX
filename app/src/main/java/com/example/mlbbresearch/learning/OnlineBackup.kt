package com.example.mlbbresearch.learning

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import com.example.mlbbresearch.game.HeroDb
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Persistent phone storage backup across uninstalls & reinstalls (v37.16):
//   mlbb-scenery-sync.json   3D scenery map, minimap sync offsets, camera preset, and minimap/HUD calibration
//                            (saved immediately on change and restored automatically on startup after reinstall;
//                            ONLY deleted/reset when you press "Clear saved map scenery" or "Reset scenery-minimap sync offsets")
//   mlbb-online-meta.json    the patch meta + every hero's meta (build, emblem, combo, tips...)
//   mlbb-online-skills.json  every hero's skills learned online (what each skill does, cast mode)
//   mlbb-learned.json        brain, Coach, ultimate cooldowns, cast gaps, hero/spell icons, level badges...
object OnlineBackup {

    const val META = "mlbb-online-meta"
    const val SKILLS = "mlbb-online-skills"
    const val LEARNED = "mlbb-learned"
    const val SCENERY_SYNC = "mlbb-scenery-sync"

    @Volatile private var learnedPending = false
    @Volatile private var sceneryPending = false
    @Volatile private var restoring = false
    @Volatile private var ctx: Context? = null
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "online-backup").apply { priority = Thread.MIN_PRIORITY } }
    @Volatile private var pending = false

    private val SCENERY_EXACT_KEYS = setOf(
        "scenery_map_v1", "scenery_auto_sync", "cam_height_mode",
        "scenery_scale_x", "scenery_scale_y", "scenery_off_x", "scenery_off_y",
        "scenery_cleared_at", "sync_reset_at", "active_preset", "preset_names", "tower_range_v2"
    )
    private val SCENERY_PREFIXES = listOf("cal_", "preset_", "minimap_col_", "mm_col_")

    fun isSceneryOrCalKey(k: String): Boolean =
        k in SCENERY_EXACT_KEYS || SCENERY_PREFIXES.any { k.startsWith(it) }

    fun init(c: Context) {
        val app = c.applicationContext
        ctx = app
        // Immediately restore persistent scenery & minimap calibration from phone storage on a fresh install / startup
        restoreFromPhoneStorageOnStartup(app)
        Cfg.watch { k ->
            if (!restoring) {
                if (isSceneryOrCalKey(k)) sceneryChanged()
                if (learnedKey(k)) learnedChanged()
            }
        }
    }

    // not in the learned file: the online data (own files), keys (their .txt files), folder/model
    // bookkeeping, cloud-training state, rate-limit timers and per-day counters
    private val SKIP_PREFIX = listOf("meta_", "skills_", "item_", "auto_", "ct_", "lim_", "ds_", "sds_", "llm_name_",
        "policy_info", "fight_choice_info", "mig_", "groq_limited_until", "gemini_", "cerebras_", "groq_", "kaggle_", "learned_")
    fun learnedKey(k: String) = SKIP_PREFIX.none { k.startsWith(it) } && !k.endsWith("_key") && !k.endsWith("_model")

    // Scenery & minimap calibration changes are written within 800 ms so they are never lost if the app closes or is uninstalled
    private fun sceneryChanged() {
        if (ctx == null || sceneryPending) return
        sceneryPending = true
        io.schedule({
            sceneryPending = false
            try { writeScenerySync() } catch (e: Exception) { Diag.error("Saving scenery & minimap sync to phone", e) }
        }, 800, TimeUnit.MILLISECONDS)
    }

    // Immediate save called when user presses "Sync Now", "Clear saved map scenery", or "Reset scenery-minimap sync offsets"
    fun saveScenerySyncNow() {
        val c = ctx ?: return
        io.execute {
            try {
                writeScenerySync()
                writeLearned()
            } catch (e: Exception) {
                Diag.error("Saving scenery & minimap sync to phone", e)
            }
        }
    }

    private fun writeScenerySync() {
        val c = ctx ?: return
        val vals = JSONObject()
        for ((k, v) in Cfg.all()) {
            if (v == null || !isSceneryOrCalKey(k)) continue
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue }
            vals.put(k, JSONObject().put("t", t).put("v", if (v is Float) v.toDouble() else v))
        }
        val payload = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("app", com.example.mlbbresearch.core.AppVersion.VERSION)
            .put("scenery_cleared", Cfg.raw("scenery_map_v1") == null)
            .put("scenery_cleared_at", Cfg.raw("scenery_cleared_at")?.toLongOrNull() ?: 0L)
            .put("sync_reset_at", Cfg.raw("sync_reset_at")?.toLongOrNull() ?: 0L)
            .put("values", vals)
            .toString()
        writeEverywhere(c, "$SCENERY_SYNC.json", payload)
    }

    private fun restoreScenerySync(o: JSONObject): Int {
        val vals = o.optJSONObject("values") ?: return 0
        val fileCleared = o.optBoolean("scenery_cleared", false)
        val fileClearedAt = o.optLong("scenery_cleared_at", 0L)
        val localClearedAt = Cfg.raw("scenery_cleared_at")?.toLongOrNull() ?: 0L
        var n = 0
        restoring = true
        try {
            if (fileCleared && fileClearedAt > localClearedAt) {
                Cfg.setRaw("scenery_map_v1", null)
                Cfg.setRaw("scenery_cleared_at", fileClearedAt.toString())
            }
            for (k in vals.keys()) {
                if (!isSceneryOrCalKey(k)) continue
                if (k == "scenery_map_v1" && (fileCleared || localClearedAt > o.optLong("at", 0L))) continue
                // Restore if missing on this install (fresh reinstall) or if the saved file is newer
                if (Cfg.has(k)) continue
                val e = vals.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "b" -> Cfg.putAny(k, e.optBoolean("v"))
                    "i" -> Cfg.putAny(k, e.optInt("v"))
                    "l" -> Cfg.putLong(k, e.optLong("v"))
                    "f" -> Cfg.putAny(k, e.optDouble("v").toFloat())
                    "s" -> Cfg.putAny(k, e.optString("v"))
                    else -> continue
                }
                n++
            }
        } finally {
            restoring = false
        }
        if (n > 0) {
            try { com.example.mlbbresearch.vision.MinimapColors.forgetCache() } catch (e: Throwable) { }
            try { com.example.mlbbresearch.game.SceneryMap.reloadFromCfg() } catch (e: Throwable) { }
        }
        return n
    }

    // Automatically restores mlbb-scenery-sync.json (and mlbb-learned.json) on startup after an uninstall & reinstall,
    // even before the user has re-selected the import folder!
    private fun restoreFromPhoneStorageOnStartup(c: Context) {
        try {
            // 1. Direct file paths: the app's folder /CodeOnTheGoProjects/Sphynx-Storage first (v37.19),
            // then Download/Sphynx-Storage (without all files access). The old MLBB-Research folders
            // are not read (owner, 2026-11-11)
            // Output/ first (owner, 2026-11-14: Input / Output / Recordings), then the top of the folder,
            // where older versions saved them
            val out = com.example.mlbbresearch.core.StorageLayout.OUTPUT
            val pub = try { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), com.example.mlbbresearch.core.Storage.FALLBACK_NAME) } catch (e: Exception) { null }
            val top = try { com.example.mlbbresearch.core.Storage.root() } catch (e: Exception) { null }
            val dirs = listOfNotNull(
                top?.let { File(it, out) }, top,
                pub?.let { File(it, out) }, pub,
                com.example.mlbbresearch.models.AutoFolder.localDir(c)
            ).distinctBy { it.absolutePath }

            for (dir in dirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                for (base in listOf(SCENERY_SYNC, LEARNED, META, SKILLS)) {
                    val f = File(dir, "$base.json")
                    if (f.exists() && f.isFile && f.canRead()) {
                        try { restore(f.name, f.readText(Charsets.UTF_8)) } catch (e: Exception) { }
                    }
                }
            }

            // 2. MediaStore.Downloads query (reads the app's mlbb-scenery-sync.json etc. in
            // Download/Sphynx-Storage across reinstalls - only there)
            val cr = c.contentResolver
            for (base in listOf(SCENERY_SYNC, LEARNED, META, SKILLS)) {
                try {
                    cr.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED),
                        MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND " + com.example.mlbbresearch.core.Storage.relIn(com.example.mlbbresearch.core.Storage.readableDownloadRels()),
                        arrayOf("$base%.json") + com.example.mlbbresearch.core.Storage.readableDownloadRels().toTypedArray(),
                        MediaStore.Downloads.DATE_MODIFIED + " DESC"
                    )?.use { cur ->
                        while (cur.moveToNext()) {
                            val id = cur.getLong(0)
                            val name = cur.getString(1) ?: "$base.json"
                            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                            try {
                                cr.openInputStream(uri)?.use { inp ->
                                    restore(name, inp.readBytes().toString(Charsets.UTF_8))
                                }
                            } catch (e: Exception) { }
                        }
                    }
                } catch (e: Exception) { }
            }

            // 3. AutoFolder treeUri if already granted
            for (base in listOf(SCENERY_SYNC, LEARNED)) {
                for ((name, doc) in folderCopies(c, base)) {
                    try {
                        cr.openInputStream(doc)?.use { inp ->
                            restore(name, inp.readBytes().toString(Charsets.UTF_8))
                        }
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) {
            Diag.error("Startup scenery/calibration restore", e)
        }
    }

    // play-learned values change often: one write every 25 seconds at most
    private fun learnedChanged() {
        if (ctx == null || learnedPending) return
        learnedPending = true
        io.schedule({ learnedPending = false; try { writeLearned() } catch (e: Exception) { Diag.error("Saving the learned data to the folder", e) } }, 25, TimeUnit.SECONDS)
    }

    // also called when you leave the app, so the last minute is not lost
    fun flushLearned() {
        if (ctx == null) return
        io.execute {
            try {
                writeScenerySync()
                writeLearned()
            } catch (e: Exception) {
                Diag.error("Saving the learned data to the folder", e)
            }
        }
    }

    private fun writeLearned() {
        val c = ctx ?: return
        val first = Cfg.raw("learned_merged") == null
        val copies = folderCopies(c, LEARNED)
        for ((name, doc) in copies) if (first || name != "$LEARNED.json") {
            try { c.contentResolver.openInputStream(doc)?.use { restore(name, it.readBytes().toString(Charsets.UTF_8)) } } catch (e: Exception) { }
        }
        val vals = JSONObject()
        for ((k, v) in Cfg.all()) {
            if (v == null || !learnedKey(k)) continue
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue }
            vals.put(k, JSONObject().put("t", t).put("v", if (v is Float) v.toDouble() else v))
        }
        if (com.example.mlbbresearch.models.AutoFolder.treeUri != null) Cfg.setRaw("learned_merged", "1")
        if (vals.length() == 0) return
        write(c, "$LEARNED.json", JSONObject().put("at", System.currentTimeMillis()).put("app", com.example.mlbbresearch.core.AppVersion.VERSION).put("values", vals).toString())
    }

    private fun restoreLearned(o: JSONObject): Int {
        val vals = o.optJSONObject("values") ?: return 0
        val localClearedAt = Cfg.raw("scenery_cleared_at")?.toLongOrNull() ?: 0L
        val fileAt = o.optLong("at", 0L)
        var n = 0
        restoring = true
        try {
            for (k in vals.keys()) {
                if (!learnedKey(k) || Cfg.has(k)) continue      // only what this install does not have
                if (k == "scenery_map_v1" && localClearedAt > fileAt) continue
                val e = vals.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "b" -> Cfg.putAny(k, e.optBoolean("v"))
                    "i" -> Cfg.putAny(k, e.optInt("v"))
                    "l" -> Cfg.putLong(k, e.optLong("v"))
                    "f" -> Cfg.putAny(k, e.optDouble("v").toFloat())
                    "s" -> Cfg.putAny(k, e.optString("v"))
                    else -> continue
                }
                n++
            }
        } finally { restoring = false }
        Cfg.setRaw("learned_merged", "1")
        if (n > 0) reloadLearned()
        return n
    }

    // the parts that keep what they read in memory read it again
    private fun reloadLearned() {
        try { Brain.forgetCache() } catch (e: Throwable) { }
        try { com.example.mlbbresearch.vision.IconMemory.forgetCache() } catch (e: Throwable) { }
        try { com.example.mlbbresearch.vision.LevelReader.forgetCache() } catch (e: Throwable) { }
        try { com.example.mlbbresearch.vision.MinimapColors.forgetCache() } catch (e: Throwable) { }
        try { com.example.mlbbresearch.game.SceneryMap.reloadFromCfg() } catch (e: Throwable) { }
    }

    private fun norm(h: String) = h.lowercase().filter { it.isLetterOrDigit() }

    // new online data: write the files a moment later (several answers in a row = one write)
    fun changed() {
        if (ctx == null || pending) return
        pending = true
        io.schedule({ pending = false; try { writeAll() } catch (e: Exception) { Diag.error("Saving the online data to the folder", e) } }, 2, TimeUnit.SECONDS)
    }

    private fun writeAll() {
        val c = ctx ?: return
        writeScenerySync()
        for (base in listOf(META, SKILLS)) for ((name, doc) in folderCopies(c, base)) if (name != "$base.json") {
            try { c.contentResolver.openInputStream(doc)?.use { restore(name, it.readBytes().toString(Charsets.UTF_8)) } } catch (e: Exception) { }
        }
        val meta = JSONObject()
        Cfg.raw("meta_general")?.let { meta.put("general", JSONObject(it)) }
        val heroes = JSONObject(); val skills = JSONObject()
        for (h in HeroDb.allNames) {
            Cfg.raw("meta_hero_" + norm(h))?.let { heroes.put(h, JSONObject(it)) }
            Cfg.raw("skills_" + h.lowercase().replace(' ', '_'))?.let { skills.put(h, JSONObject(it)) }
        }
        meta.put("heroes", heroes)
        val items = JSONObject()
        for (it in com.example.mlbbresearch.game.ItemDb.learnable) Cfg.raw(com.example.mlbbresearch.game.ItemBook.key(it))?.let { v -> items.put(it, JSONObject(v)) }
        if (items.length() > 0) meta.put("items", items)
        if (meta.has("general") || heroes.length() > 0) write(c, "$META.json", meta.toString())
        if (skills.length() > 0) write(c, "$SKILLS.json", skills.toString())
    }

    // the copies of base(.json) / base (n).json in the chosen folder: name -> document
    private fun folderCopies(c: Context, base: String): List<Pair<String, android.net.Uri>> {
        val tree = com.example.mlbbresearch.models.AutoFolder.treeUri ?: return emptyList()
        val re = Regex("^" + Regex.escape(base) + "( \\(\\d+\\))?\\.json$", RegexOption.IGNORE_CASE)
        val out = ArrayList<Pair<String, android.net.Uri>>()
        try {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            c.contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val n = cur.getString(1) ?: continue
                    if (re.matches(n)) out.add(n to android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)))
                }
            }
        } catch (e: Exception) { Diag.error("Reading the chosen folder (online data)", e) }
        return out
    }

    // Writes to ALL available persistent locations so scenery & minimap sync always survives uninstall/reinstall
    private fun writeEverywhere(c: Context, name: String, text: String) {
        // with All files access the direct copy (in Output) already lasts through a reinstall; the chosen
        // folder's copy would land at the top of the storage folder again
        if (!writeDirect(name, text)) writeInFolder(c, name, text)
        writeInDownloads(c, name, text)
        try {
            val pubDir = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), com.example.mlbbresearch.core.Storage.FALLBACK_NAME), com.example.mlbbresearch.core.StorageLayout.OUTPUT)
            if (pubDir.exists() || pubDir.mkdirs()) {
                File(pubDir, name).writeText(text, Charsets.UTF_8)
            }
        } catch (e: Exception) { }
        try {
            com.example.mlbbresearch.models.AutoFolder.localDir(c)?.let { dir ->
                File(dir, name).writeText(text, Charsets.UTF_8)
            }
        } catch (e: Exception) { }
    }

    // v37.19: straight into /CodeOnTheGoProjects/Sphynx-Storage when all files access is on
    private fun writeDirect(name: String, text: String): Boolean = try {
        com.example.mlbbresearch.core.Storage.dir(com.example.mlbbresearch.core.StorageLayout.OUTPUT)?.let { d -> File(d, name).writeText(text, Charsets.UTF_8); true } ?: false
    } catch (e: Exception) { Diag.error("Writing $name in the app's folder", e); false }

    private fun write(c: Context, name: String, text: String) {
        if (writeDirect(name, text)) return
        if (writeInFolder(c, name, text)) {
            writeInDownloads(c, name, text)
            return
        }
        writeInDownloads(c, name, text)
    }

    // through the chosen folder: overwrite the one file, delete the numbered copies
    private fun writeInFolder(c: Context, name: String, text: String): Boolean {
        val tree = com.example.mlbbresearch.models.AutoFolder.treeUri ?: return false
        val cr = c.contentResolver
        return try {
            val base = name.removeSuffix(".json")
            val copies = folderCopies(c, base)
            for ((n, doc) in copies) if (n != name) try { android.provider.DocumentsContract.deleteDocument(cr, doc) } catch (e: Exception) { }
            val target = copies.firstOrNull { it.first == name }?.second ?: run {
                val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                android.provider.DocumentsContract.createDocument(cr, parent, "application/json", name)
            } ?: return false
            cr.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: return false
            true
        } catch (e: Exception) {
            Diag.error("Writing $name in the chosen folder", e); false
        }
    }

    private fun writeInDownloads(c: Context, name: String, text: String) {
        try {
            val cr = c.contentResolver
            val rel = com.example.mlbbresearch.core.Storage.downloadsRel(com.example.mlbbresearch.core.StorageLayout.OUTPUT) + "/"
            val existing = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf(name, rel), null)
                ?.use { if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }
            val uri = existing ?: cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, rel.trimEnd('/'))
            }) ?: return
            cr.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            val base = name.removeSuffix(".json")
            cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf("$base (%).json", rel), null)?.use { cur ->
                while (cur.moveToNext()) cr.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0)), null, null)
            }
        } catch (e: Exception) { }
    }

    // ---- reading back (folder scan & startup restore) ----

    fun isBackup(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.endsWith(".json") && (n.startsWith(SCENERY_SYNC) || n.startsWith(META) || n.startsWith(SKILLS) || n.startsWith(LEARNED))
    }

    // an entry replaces the app's copy when the app has none or the file's is newer; returns how many
    fun restore(fileName: String, text: String): Int {
        val o = JSONObject(text)
        val lower = fileName.lowercase()
        if (lower.startsWith(SCENERY_SYNC)) return restoreScenerySync(o)
        if (lower.startsWith(LEARNED)) return restoreLearned(o)
        var n = 0
        fun newer(key: String, e: JSONObject): Boolean {
            val cur = Cfg.raw(key)?.let { try { JSONObject(it) } catch (x: Exception) { null } }
            return cur == null || e.optLong("at") > cur.optLong("at")
        }
        if (lower.startsWith(META)) {
            o.optJSONObject("general")?.let { g -> if (newer("meta_general", g)) { Cfg.setRaw("meta_general", g.toString()); n++ } }
            o.optJSONObject("items")?.let { its ->
                for (k in its.keys()) { val e = its.getJSONObject(k); val key = com.example.mlbbresearch.game.ItemBook.key(k); if (newer(key, e)) { Cfg.setRaw(key, e.toString()); n++ } }
            }
            val hs = o.optJSONObject("heroes")
            if (hs != null) for (h in hs.keys()) {
                val e = hs.getJSONObject(h); val key = "meta_hero_" + norm(h)
                if (newer(key, e)) { Cfg.setRaw(key, e.toString()); n++ }
            }
        } else {
            for (h in o.keys()) {
                val e = o.getJSONObject(h); val key = "skills_" + h.lowercase().replace(' ', '_')
                if (newer(key, e)) { Cfg.setRaw(key, e.toString()); n++ }
            }
            if (n > 0) com.example.mlbbresearch.game.SkillBook.forgetCache()
        }
        return n
    }
}
