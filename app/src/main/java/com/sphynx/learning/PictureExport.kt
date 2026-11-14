package com.sphynx.learning

import android.content.Context
import com.sphynx.core.Cfg
import com.sphynx.core.Diag
import com.sphynx.core.SessionRecorder
import com.sphynx.models.AutoFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// THE TRAINING PICTURES FOR THE GITHUB UPLOAD (owner, 2026-11-12: "new pictures since the last upload, at the
// largest size GitHub takes"). On the Debug data page's upload (and the automatic one):
//  1. the new minimap / screen pictures are zipped (the Learn page's usual dataset zips),
//  2. every dataset zip not sent yet is repacked into parts of at most 95 MB (GitHub refuses files over
//     100 MB) in the recordings folder: pictures-<zip name>-p<N>.zip,
//  3. the upload sends each part in a commit of its own, then removes the part from the phone (the dataset
//     zip itself stays for training).
object PictureExport {

    const val LIMIT = 95L * 1024 * 1024       // bytes per part (GitHub's limit is 100 MB a file)
    const val PREFIX = "pictures-"
    private const val ENTRY_OVERHEAD = 512L    // a zip entry's headers, roughly

    fun isPart(name: String) = name.startsWith(PREFIX) && name.endsWith(".zip")
    fun isDatasetZip(name: String) =
        (name.startsWith(DatasetZipper.Kind.MINIMAP.zipName + "-") || name.startsWith(DatasetZipper.Kind.SCREEN.zipName + "-")) && name.endsWith(".zip")

    // pure (unit tested): the next entry starts a new part when the part already holds something and the
    // entry would take it over the limit (a single entry bigger than the limit still gets a part of its own)
    fun needsNewPart(partBytes: Long, entryBytes: Long, entriesInPart: Int, limit: Long = LIMIT): Boolean =
        entriesInPart > 0 && partBytes + entryBytes + ENTRY_OVERHEAD > limit

    fun isSent(name: String) = name in sent()

    // dataset zips a successful training used, still waiting to be packed for GitHub before they are deleted
    fun trainedWaiting(): Set<String> = (Cfg.raw("zips_trained_waiting") ?: "").split('\n').filter { it.isNotBlank() }.toSet()
    fun setTrainedWaiting(s: Set<String>) = Cfg.setRaw("zips_trained_waiting", s.joinToString("\n").ifEmpty { null })

    // pure (unit tested): after a successful training, which used zips go now and which wait for the GitHub upload
    fun afterTraining(used: List<String>, sentToGitHub: Set<String>): Pair<List<String>, List<String>> =
        used.partition { it in sentToGitHub }

    fun partName(zipName: String, part: Int) = PREFIX + zipName.removeSuffix(".zip") + "-p$part.zip"

    private fun sent(): MutableSet<String> = (Cfg.raw("pic_exported") ?: "").split('\n').filter { it.isNotBlank() }.toMutableSet()

    // zips the new pictures, then repacks every dataset zip not sent yet; returns a short report
    fun export(ctx: Context): String {
        for (k in DatasetZipper.Kind.values()) try { DatasetZipper.zipBlocking(ctx, k, "for the GitHub upload") } catch (e: Exception) { Diag.error("Zipping ${k.label} pictures", e) }
        val done = sent()
        val zips = AutoFolder.files(ctx).filter { isDatasetZip(it.name) && it.name !in done }.sortedBy { it.name }
        if (zips.isEmpty()) return "pictures: nothing new"
        var parts = 0; var pics = 0
        for (z in zips) {
            try {
                val (p, n) = repack(ctx, z)
                parts += p; pics += n
                done.add(z.name)
                Cfg.setRaw("pic_exported", done.toList().takeLast(2000).joinToString("\n"))
                // trained already (successfully): not needed any more now that it is packed for GitHub
                if (z.name in trainedWaiting()) { DatasetZipper.deleteZip(ctx, z.name); setTrainedWaiting(trainedWaiting() - z.name) }
            } catch (e: Exception) { Diag.error("Preparing ${z.name} for the upload", e) }
        }
        return "pictures: $pics file(s) from ${zips.size} zip(s) in $parts part(s)"
    }

    // one dataset zip -> parts under the limit; (parts, files)
    private fun repack(ctx: Context, z: AutoFolder.Entry): Pair<Int, Int> {
        var part = 0; var files = 0
        var out: ZipOutputStream? = null
        var counter: Counting? = null
        var inPart = 0
        try {
            ZipInputStream(z.open() ?: throw IllegalStateException("cannot read ${z.name}")).use { input ->
                while (true) {
                    val en = input.nextEntry ?: break
                    if (en.isDirectory) continue
                    val bytes = input.readBytes()
                    if (out == null || needsNewPart(counter!!.n, bytes.size.toLong(), inPart)) {
                        out?.close()
                        part++
                        counter = Counting(SessionRecorder.openOut(ctx, partName(z.name, part), "application/zip"))
                        out = ZipOutputStream(counter)
                        inPart = 0
                    }
                    out!!.putNextEntry(ZipEntry(en.name)); out!!.write(bytes); out!!.closeEntry()
                    inPart++; files++
                }
            }
        } finally { out?.close() }
        return part to files
    }

    // counts what is written (how big the part is so far)
    private class Counting(val o: java.io.OutputStream) : java.io.OutputStream() {
        var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); n += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }
}
