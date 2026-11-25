package com.sphynx.models

import android.content.Context
import com.sphynx.core.Diag
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// PHASE 9, step M9 (owner, 2026-11-25): downloads a model of the built-in list (ModelCatalog) from Hugging Face into
// Sphynx-Storage/Input, only when the owner taps it. The file grows as "<name>.part" and goes on from there after a
// break; a finished file is checked against the list's size and SHA-256 before it gets its real name, and then the
// Lab takes it (a .litertlm through the folder scan, a GGUF model and its mmproj into the GGUF library, where they pair).
// Only https to huggingface.co and its file servers, every redirect included (ModelCatalog.allowedHost).
object ModelDownloader {

    @Volatile var busy = false; private set
    @Volatile var status = ""; private set
    @Volatile private var stop = false

    fun catalog(ctx: Context): List<ModelCatalog.Entry> = try {
        ModelCatalog.parse(ctx.assets.open(ModelCatalog.ASSET).bufferedReader().use { it.readText() })
    } catch (e: Exception) { Diag.error("Model list", e); emptyList() }

    /** true on Wi-Fi or another unmetered network, false on mobile data, null when offline. */
    fun unmetered(ctx: Context): Boolean? = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> null
            else -> caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }
    } catch (e: Exception) { null }

    /** Where downloads go: Sphynx-Storage/Input, else the app's own Input folder. */
    fun dir(ctx: Context): File? = com.sphynx.core.Storage.dir(com.sphynx.core.StorageLayout.INPUT)
        ?: AutoFolder.localDir(ctx)?.let { File(it, com.sphynx.core.StorageLayout.INPUT).apply { mkdirs() } }

    /** Is this file of the list already in the app (same fingerprint)? */
    fun have(f: ModelCatalog.File): Boolean = ModelRegistry.records.any { it.sha256 == f.sha256 }

    fun cancel() { stop = true }

    /** Downloads [e] (with its picture part when [withMmproj]) in the background; [done] gets the last message. */
    fun start(ctx: Context, e: ModelCatalog.Entry, withMmproj: Boolean, done: (String) -> Unit) {
        if (busy) { done("A download is already running"); return }
        busy = true; stop = false
        val app = ctx.applicationContext
        Thread {
            val msg = try { run(app, e, withMmproj) } catch (t: Throwable) { "Download stopped: ${t.message ?: t}" }
            status = msg; busy = false
            Diag.info("Model download ${e.id}: $msg")
            done(msg)
        }.apply { name = "model-download" }.start()
    }

    private fun run(app: Context, e: ModelCatalog.Entry, withMmproj: Boolean): String {
        val dir = dir(app) ?: return "Sphynx-Storage/Input can't be written: give the app its storage permission first"
        val files = if (withMmproj) e.files else listOf(e.model)
        val done = ArrayList<File>()
        for (f in files) {
            val dst = File(dir, f.saveAs)
            if (dst.isFile && dst.length() == f.size) { done.add(dst); continue }   // downloaded before (checked then)
            val part = File(dir, f.saveAs + ".part")
            val have = if (part.isFile) part.length() else 0L
            ModelStorage.copyProblem(f.size - have, dir.usableSpace)?.let { return "Not enough room for ${f.saveAs}: $it" }
            fetch(e.url(f), f, part)?.let { return it }
            status = "Checking ${f.saveAs}…"
            val sha = part.inputStream().use { com.sphynx.core.FileHash.hash(it) }
            ModelCatalog.verify(f, part.length(), sha)?.let { why -> part.delete(); return "${f.saveAs} was refused: $why" }
            if (dst.exists()) dst.delete()
            if (!part.renameTo(dst)) return "Could not save ${f.saveAs}"
            com.sphynx.core.Cfg.setRaw(com.sphynx.core.FileHash.key(dst.name, dst.length()), sha)
            done.add(dst)
        }
        // the Lab takes the files: GGUF into its library (model first, then its picture part next to it), .litertlm by the scan
        val notes = ArrayList<String>()
        for (d in done) if (d.name.endsWith(".gguf", true)) notes.add(GgufLibrary.addFile(app, d))
        if (done.any { it.name.endsWith(".litertlm", true) }) {
            val lock = java.util.concurrent.CountDownLatch(1)
            AutoFolder.scan(app) { m -> notes.add(m); lock.countDown() }
            lock.await(30, java.util.concurrent.TimeUnit.MINUTES)
        }
        try { ModelRegistry.refresh(app) } catch (t: Throwable) { }
        return "Downloaded and checked ${done.joinToString { it.name }}. " + notes.filter { it.isNotBlank() }.joinToString(" ")
    }

    // one file into [part], going on from what is there; null when the whole file is there, else why not
    private fun fetch(url0: String, f: ModelCatalog.File, part: File): String? {
        var url = url0
        var from = ModelCatalog.resumeFrom(if (part.isFile) part.length() else 0L, f.size)
        if (from == null && part.isFile && part.length() == f.size) return null
        if (from == null) part.delete()
        var hops = 0
        while (true) {
            if (!ModelCatalog.allowedHost(url)) return "The download was sent to a server that is not Hugging Face, so it was stopped"
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Sphynx/" + com.sphynx.core.AppVersion.VERSION)
                from?.let { setRequestProperty("Range", "bytes=$it-") }
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return "Hugging Face sent a redirect without an address"
                    url = URL(URL(url), loc).toString()
                    if (++hops > 8) return "Too many redirects"
                    continue
                }
                when (ModelCatalog.reply(code, from)) {
                    ModelCatalog.Reply.DONE -> return null
                    ModelCatalog.Reply.FAIL -> return when (code) {
                        401, 403 -> "Hugging Face refused ${f.name} (code $code): the model may now need a login"
                        404 -> "Hugging Face no longer has ${f.name}: the list needs an update"
                        else -> "Hugging Face answered $code for ${f.name}"
                    }
                    ModelCatalog.Reply.RESTART -> { if (from != null) Diag.info("Model download: the server sent ${f.name} from the start"); from = null }
                    ModelCatalog.Reply.APPEND -> {}
                }
                val start = from ?: 0L
                c.inputStream.use { input ->
                    FileOutputStream(part, from != null).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = start
                        var shown = 0L
                        while (true) {
                            if (stop) return "Paused at ${ModelStorage.mb(got)} of ${ModelStorage.mb(f.size)} MB: download it again to go on"
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (got - shown > 4L shl 20) { shown = got; status = "Downloading ${f.saveAs}: ${got * 100 / f.size}% (${ModelStorage.mb(got)} of ${ModelStorage.mb(f.size)} MB)" }
                        }
                    }
                }
                return if (part.length() < f.size) "The connection broke at ${ModelStorage.mb(part.length())} MB: download it again to go on" else null
            } catch (e: java.io.IOException) {
                return "The connection broke (${e.message}): download it again to go on from ${ModelStorage.mb(if (part.isFile) part.length() else 0)} MB"
            } finally { c.disconnect() }
        }
    }
}
