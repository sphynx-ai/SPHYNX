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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// What Groq found online is also kept as files in Download/MLBB-Research, so it survives a
// reinstall (the app's own storage is wiped then) and the folder scan reads it back:
//   mlbb-online-meta.json    the patch meta + every hero's meta (build, emblem, combo, tips...)
//   mlbb-online-skills.json  every hero's skills learned online (what each skill does, cast mode)
// Each file is rewritten a few seconds after new data arrives. When reading back, an entry only
// replaces what the app has when it is newer ("at").
// ONE FILE ONLY: after a reinstall Android would make "mlbb-online-meta (1).json" next to the old
// one (the old install's file cannot be overwritten through Downloads). Through the folder you
// chose (Model page) the app may change every file in it: any extra copies are read in first (so
// nothing is lost), then the one "mlbb-online-meta.json" is overwritten and the copies deleted.
object OnlineBackup {

    const val META = "mlbb-online-meta"
    const val SKILLS = "mlbb-online-skills"
    // what the app learned while playing + your settings (v36.51): brain, Coach, ultimate cooldowns,
    // combo burst gaps, aim leads, cast modes, hero/spell icons, level badges, minimap colours,
    // tower range, lane travel, layout presets, switches... Restored only where the app has nothing
    // (a fresh install), so it never overwrites what this install learned.
    const val LEARNED = "mlbb-learned"
    @Volatile private var learnedPending = false
    @Volatile private var restoring = false
    @Volatile private var ctx: Context? = null
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "online-backup").apply { priority = Thread.MIN_PRIORITY } }
    @Volatile private var pending = false

    fun init(c: Context) {
        ctx = c.applicationContext
        Cfg.watch { k -> if (!restoring && learnedKey(k)) learnedChanged() }
    }

    // not in the learned file: the online data (own files), keys (their .txt files), folder/model
    // bookkeeping, cloud-training state, rate-limit timers and per-day counters
    private val SKIP_PREFIX = listOf("meta_", "skills_", "item_", "auto_", "ct_", "lim_", "ds_", "sds_", "llm_name_",
        "policy_info", "fight_choice_info", "mig_", "groq_limited_until", "gemini_", "cerebras_", "groq_", "kaggle_", "learned_")
    fun learnedKey(k: String) = SKIP_PREFIX.none { k.startsWith(it) } && !k.endsWith("_key") && !k.endsWith("_model")

    // play-learned values change often: one write a minute at most
    private fun learnedChanged() {
        if (ctx == null || learnedPending) return
        learnedPending = true
        io.schedule({ learnedPending = false; try { writeLearned() } catch (e: Exception) { Diag.error("Saving the learned data to the folder", e) } }, 60, TimeUnit.SECONDS)
    }

    // also called when you leave the app, so the last minute is not lost
    fun flushLearned() {
        if (ctx == null) return
        io.execute { try { writeLearned() } catch (e: Exception) { Diag.error("Saving the learned data to the folder", e) } }
    }

    private fun writeLearned() {
        val c = ctx ?: return
        // the first write of this install reads the folder's file first (a fresh install must not
        // replace the old install's learning before it was restored); later writes may drop what you reset
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
        var n = 0
        restoring = true
        try {
            for (k in vals.keys()) {
                if (!learnedKey(k) || Cfg.has(k)) continue      // only what this install does not have
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
    }

    private fun norm(h: String) = h.lowercase().filter { it.isLetterOrDigit() }

    // new online data: write the files a moment later (several answers in a row = one write)
    fun changed() {
        if (ctx == null || pending) return
        pending = true
        io.schedule({ pending = false; try { writeAll() } catch (e: Exception) { Diag.error("Saving the online data to the folder", e) } }, 3, TimeUnit.SECONDS)
    }

    private fun writeAll() {
        val c = ctx ?: return
        // extra copies ("... (1).json") are merged into the app's data first, then removed when writing
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

    private fun write(c: Context, name: String, text: String) {
        if (writeInFolder(c, name, text)) return
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
        val cr = c.contentResolver
        val rel = Environment.DIRECTORY_DOWNLOADS + "/MLBB-Research/"
        val existing = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf(name, rel), null)
            ?.use { if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }
        val uri = existing ?: cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, rel.trimEnd('/'))
        }) ?: return
        cr.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        // our own extra copies ("... (1).json" made by this install) go
        try {
            val base = name.removeSuffix(".json")
            cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf("$base (%).json", rel), null)?.use { cur ->
                while (cur.moveToNext()) cr.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0)), null, null)
            }
        } catch (e: Exception) { }
    }

    // ---- reading back (folder scan) ----

    fun isBackup(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.endsWith(".json") && (n.startsWith(META) || n.startsWith(SKILLS) || n.startsWith(LEARNED))
    }

    // an entry replaces the app's copy when the app has none or the file's is newer; returns how many
    fun restore(fileName: String, text: String): Int {
        val o = JSONObject(text)
        if (fileName.lowercase().startsWith(LEARNED)) return restoreLearned(o)
        var n = 0
        fun newer(key: String, e: JSONObject): Boolean {
            val cur = Cfg.raw(key)?.let { try { JSONObject(it) } catch (x: Exception) { null } }
            return cur == null || e.optLong("at") > cur.optLong("at")
        }
        if (fileName.lowercase().startsWith(META)) {
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
