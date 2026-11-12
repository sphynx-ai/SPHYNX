package com.example.mlbbresearch.learning

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.mlbbresearch.core.Cfg
import com.example.mlbbresearch.core.Diag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Zips the detector training pictures, ready to upload to the training notebooks (or Roboflow).
// No daily limit: a collector saves until its CAPACITY (minimap 300, screen 300 pictures since
// the last zip); then the batch is zipped, the zipped pictures are removed from the folder (the
// zip holds them, the phone keeps its space), and that collector's switch turns OFF. Turn it on
// again (Learn page) and it collects the next batch. "Zip now" on the Learn page zips early.
// A zip holds the folders the notebooks read: images/, labels/, classes.txt.
//   Download/Sphynx-Auto-Pilot-Storage/mlbb-minimap-dataset-<date>.zip   (train_mlbb_detector.ipynb)
//   Download/Sphynx-Auto-Pilot-Storage/mlbb-screen-dataset-<date>.zip    (train_screen_detector.ipynb)
object DatasetZipper {

    enum class Kind(val folder: String, val prefix: String, val zipName: String, val key: String, val capacity: Int, val label: String) {
        MINIMAP("dataset", "mm_", "mlbb-minimap-dataset", "zip_last_mm", 300, "minimap"),
        SCREEN("screen_dataset", "sc_", "mlbb-screen-dataset", "zip_last_sc", 300, "screen")
    }

    // pictures saved since the last zip
    fun count(kind: Kind) = Cfg.raw(kind.key + "_n")?.toIntOrNull() ?: 0
    fun switchOn(kind: Kind) = if (kind == Kind.MINIMAP) Cfg.collectDataset else Cfg.collectScreenDataset
    private fun setSwitch(kind: Kind, on: Boolean) { if (kind == Kind.MINIMAP) Cfg.collectDataset = on else Cfg.collectScreenDataset = on }

    // a collector saved a picture: at capacity the batch is zipped and the collector switched off
    fun saved(ctx: Context, kind: Kind) {
        val n = count(kind) + 1
        Cfg.setRaw(kind.key + "_n", n.toString())
        if (n >= kind.capacity) {
            setSwitch(kind, false)
            zipNew(ctx, kind, "capacity of ${kind.capacity} reached; ${kind.label} collector switched off")
        }
    }

    @Volatile var status = "-"
        private set
    @Volatile private var busy = false

    // a saved picture / label: rel is its folder ("…/<folder>/images"), open / delete work on either store
    private class Item(val name: String, val rel: String, val open: () -> java.io.InputStream?, val delete: () -> Boolean)

    // the app's own files: in /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage/<folder>/... (all files access)
    // and in Download/Sphynx-Auto-Pilot-Storage/<folder>/... (saved without it)
    private fun list(c: Context, kind: Kind): List<Item> {
        val out = ArrayList<Item>()
        com.example.mlbbresearch.core.Storage.dir(kind.folder)?.let { top ->
            top.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = kind.folder + f.parentFile!!.absolutePath.removePrefix(top.absolutePath)
                out.add(Item(f.name, rel, { f.inputStream() }, { f.delete() }))
            }
        }
        val base = com.example.mlbbresearch.core.Storage.downloadsRel(kind.folder) + "/"
        c.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
            MediaStore.Downloads.RELATIVE_PATH + " LIKE ?", arrayOf("$base%"), null)?.use { cur ->
            while (cur.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0))
                out.add(Item(cur.getString(1) ?: continue, cur.getString(2) ?: "", { c.contentResolver.openInputStream(uri) },
                    { try { c.contentResolver.delete(uri, null, null) > 0 } catch (e: Exception) { false } }))
            }
        }
        return out
    }

    // the time in a picture's name (mm_1730450000000.png -> 1730450000000)
    private fun stamp(name: String, kind: Kind): Long =
        if (name.startsWith(kind.prefix)) name.removePrefix(kind.prefix).substringBefore('.').substringBefore('_').toLongOrNull() ?: 0L else 0L   // (mm_<time>_gs.png: the tag is not part of the time)

    // zip the pictures newer than the last zip, in the background; done(message)
    fun zipNew(ctx: Context, kind: Kind, why: String, done: ((String) -> Unit)? = null) {
        if (busy) { done?.invoke("Already zipping"); return }
        busy = true
        val c = ctx.applicationContext
        Thread {
            val msg = try { zip(c, kind, why) } catch (t: Throwable) {
                Diag.error("Zipping the training pictures", t); "zipping failed (see Logs)"
            } finally { busy = false }
            status = msg
            Diag.info("Training pictures: $msg")
            done?.invoke(msg)
        }.start()
    }

    // delete a dataset zip (after a successful training): the app's folder, or its Download folder
    fun deleteZip(c: Context, name: String): Boolean {
        var gone = com.example.mlbbresearch.core.Storage.dir()?.let { java.io.File(it, name).delete() } ?: false
        try {
            gone = c.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(name, com.example.mlbbresearch.core.Storage.downloadsRel() + "/")) > 0 || gone
        } catch (e: Exception) { }
        return gone
    }

    // zip now on the caller's thread (the GitHub upload zips the new pictures first)
    fun zipBlocking(ctx: Context, kind: Kind, why: String): String {
        synchronized(this) { if (busy) return "${kind.zipName}: already zipping"; busy = true }
        return try { zip(ctx.applicationContext, kind, why).also { status = it } } finally { busy = false }
    }

    private fun zip(c: Context, kind: Kind, why: String): String {
        val since = Cfg.raw(kind.key)?.toLongOrNull() ?: 0L
        val all = list(c, kind)
        val pics = all.filter { it.rel.trimEnd('/').endsWith("/images") && stamp(it.name, kind) > since }
        if (pics.isEmpty()) return "${kind.zipName}: no new pictures since the last zip"
        val stems = pics.map { it.name.substringBeforeLast('.') }.toSet()
        val labels = all.filter { it.rel.trimEnd('/').endsWith("/labels") && it.name.substringBeforeLast('.') in stems }
        val classes = all.firstOrNull { it.name == "classes.txt" && it.rel.trimEnd('/').endsWith(kind.folder) }
        val newest = pics.maxOf { stamp(it.name, kind) }
        val name = kind.zipName + "-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
        // v37.19: the zip goes straight into the app's folder when all files access is on
        val direct = com.example.mlbbresearch.core.Storage.dir()
        val os0: java.io.OutputStream? = if (direct != null) java.io.File(direct, name).outputStream() else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, com.example.mlbbresearch.core.Storage.downloadsRel())
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create $name")
            c.contentResolver.openOutputStream(uri)
        }
        var n = 0
        os0?.use { os ->
            ZipOutputStream(os).use { z ->
                fun add(path: String, i: Item) {
                    i.open()?.use { input ->
                        z.putNextEntry(ZipEntry(path)); input.copyTo(z); z.closeEntry()
                    }
                }
                classes?.let { add("${kind.folder}/classes.txt", it) }
                for (p in pics) { add("${kind.folder}/images/${p.name}", p); n++ }
                for (l in labels) add("${kind.folder}/labels/${l.name}", l)
            }
        } ?: throw IllegalStateException("could not write $name")
        Cfg.setRaw(kind.key, newest.toString())
        Cfg.setRaw(kind.key + "_n", "0")
        // the zip holds them now: remove the zipped pictures and labels from the folder
        var removed = 0
        for (i in pics + labels) try { if (i.delete()) removed++ } catch (e: Exception) { }
        return "$name: $n pictures ($why) in ${com.example.mlbbresearch.core.Storage.label(direct != null)}" + if (removed > 0) "; the zipped files were removed from ${kind.folder}" else ""
    }

}
