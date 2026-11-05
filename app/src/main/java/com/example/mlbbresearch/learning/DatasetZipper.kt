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
//   Download/MLBB-Research/mlbb-minimap-dataset-<date>.zip   (train_mlbb_detector.ipynb)
//   Download/MLBB-Research/mlbb-screen-dataset-<date>.zip    (train_screen_detector.ipynb)
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

    private class Item(val id: Long, val name: String, val rel: String)

    // the app's own files in Download/MLBB-Research/<folder>/...
    private fun list(c: Context, kind: Kind): List<Item> {
        val base = Environment.DIRECTORY_DOWNLOADS + "/MLBB-Research/" + kind.folder + "/"
        val out = ArrayList<Item>()
        c.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
            MediaStore.Downloads.RELATIVE_PATH + " LIKE ?", arrayOf("$base%"), null)?.use { cur ->
            while (cur.moveToNext()) out.add(Item(cur.getLong(0), cur.getString(1) ?: continue, cur.getString(2) ?: ""))
        }
        return out
    }

    // the time in a picture's name (mm_1730450000000.png -> 1730450000000)
    private fun stamp(name: String, kind: Kind): Long =
        if (name.startsWith(kind.prefix)) name.removePrefix(kind.prefix).substringBefore('.').toLongOrNull() ?: 0L else 0L

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
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MLBB-Research")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create $name")
        fun uriOf(i: Item) = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, i.id)
        var n = 0
        c.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { z ->
                fun add(path: String, i: Item) {
                    c.contentResolver.openInputStream(uriOf(i))?.use { input ->
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
        for (i in pics + labels) try { if (c.contentResolver.delete(uriOf(i), null, null) > 0) removed++ } catch (e: Exception) { }
        return "$name: $n pictures ($why) in Download/MLBB-Research" + if (removed > 0) "; the zipped files were removed from ${kind.folder}" else ""
    }

}
