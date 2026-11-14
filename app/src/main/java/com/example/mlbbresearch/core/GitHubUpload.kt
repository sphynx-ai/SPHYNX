package com.example.mlbbresearch.core

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// UPLOAD THE DEBUG DATA TO GITHUB (owner, 2026-11-12): the files in the app's "recordings" folder (session data,
// videos, screenshots, logs, settings, speed / scorecard exports) are committed to the branch
// "recordings-for-debugging" of the owner's repository - by a button on the Debug data page, and by itself after
// each recording when that switch is on. Never the training data (CSV exports, picture zips: another folder).
//  - the token: a fine-grained GitHub token (this repository only, Contents: read and write) in the file
//    github_token.txt in the app's folder (like groq_key.txt). It is only ever sent to api.github.com, never
//    logged, never recorded.
//  - one commit for all the files that are not videos; each video in a commit of its own (owner's rule).
//  - a file is uploaded once (its name is remembered); files of a recording still running are left for later.
//  - once a commit is on GitHub its files are deleted from the phone (owner, 2026-11-12).
// Uses GitHub's Git Data API: blobs -> tree -> commit -> move the branch.
object GitHubUpload {

    const val REPO = "sphynx-ai/SPHYNX"
    const val BRANCH = "recordings-for-debugging"
    const val TOKEN_FILE = "github_token.txt"
    const val API = "https://api.github.com"
    const val BATCH = 60                       // files per commit (not videos)

    @Volatile var busy = false; private set
    @Volatile var status = "not run yet"; private set

    class Item(val name: String, val size: Long, val open: () -> InputStream?)

    fun isVideo(name: String) = name.endsWith(".mp4", ignoreCase = true)
    fun isZip(name: String) = name.endsWith(".zip", ignoreCase = true)
    // a video or a picture zip goes in a commit of its own
    fun isBig(name: String) = isVideo(name) || isZip(name)
    const val MAX_FILE = 100L * 1024 * 1024    // GitHub refuses a file over 100 MB

    // the commits to make, in order: the non-videos together (in batches), then one per video (pure, unit tested)
    fun plan(names: List<String>, uploaded: Set<String>, recordingNow: String?): List<List<String>> {
        val base = recordingNow?.removeSuffix(".jsonl")
        val todo = names.filter { it !in uploaded && (base == null || !it.startsWith(base)) }.distinct().sorted()
        val out = ArrayList<List<String>>()
        todo.filter { !isBig(it) }.chunked(BATCH).forEach { out.add(it) }
        todo.filter { isBig(it) }.forEach { out.add(listOf(it)) }
        return out
    }

    fun message(files: List<String>): String =
        if (files.size == 1) (when { isVideo(files[0]) -> "Recording video: "; isZip(files[0]) -> "Training pictures: "; else -> "Debug data: " }) + files[0]
        else "Debug data: ${files.size} files (${files.first()} ...)"

    private fun uploaded(): MutableSet<String> = (Cfg.raw("gh_uploaded") ?: "").split('\n').filter { it.isNotBlank() }.toMutableSet()
    private fun remember(names: List<String>) {
        val all = (uploaded() + names).toList().takeLast(3000)
        Cfg.setRaw("gh_uploaded", all.joinToString("\n"))
    }

    // the token: github_token.txt in the app's folder (first line)
    fun token(ctx: Context): String? = try {
        (Storage.dir()?.let { File(it, TOKEN_FILE) }?.takeIf { it.isFile }?.readText()
            ?: com.example.mlbbresearch.models.AutoFolder.files(ctx).firstOrNull { it.name.equals(TOKEN_FILE, ignoreCase = true) }
                ?.open?.invoke()?.use { it.readBytes().toString(Charsets.UTF_8) })
            ?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.length >= 20 }
    } catch (e: Exception) { null }

    // the files of the recordings folder (the app's folder with all files access, else Download/...)
    fun files(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        Storage.dir(SessionRecorder.FOLDER)?.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { f ->
            out.add(Item(f.name, f.length()) { f.inputStream() })
        }
        try {
            val rel = Storage.downloadsRel(SessionRecorder.FOLDER) + "/"
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
                MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf(rel), null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (out.any { it.name == name } || c.getLong(2) <= 0) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out.add(Item(name, c.getLong(2)) { ctx.contentResolver.openInputStream(uri) })
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun pending(ctx: Context): Int = try {
        plan(files(ctx).map { it.name }, uploaded(), if (SessionRecorder.on) SessionRecorder.currentName() else null).sumOf { it.size }
    } catch (e: Exception) { 0 }

    // uploads everything not uploaded yet; done(message). Runs on its own thread.
    fun uploadAll(ctx: Context, done: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        if (busy) { done("An upload is already running: $status"); return }
        busy = true
        Thread({
            val msg = try { run(app) } catch (e: Exception) { "Upload failed: ${e.message?.take(160)}" }
            status = msg; busy = false
            Diag.info("GitHub upload: $msg")
            done(msg)
        }, "github-upload").start()
    }

    private fun run(ctx: Context): String {
        val token = token(ctx) ?: return "No token: put a fine-grained GitHub token (this repository, Contents: read and write) " +
            "in $TOKEN_FILE in the app's folder"
        // the training results and the new training pictures go up with the debug data (owner, 2026-11-12)
        status = "preparing the training results and pictures…"
        val prepared = listOf(
            try { com.example.mlbbresearch.learning.TrainingResults.refresh(ctx); com.example.mlbbresearch.learning.TrainingResults.export(ctx) }
                catch (e: Exception) { "training results failed: ${e.message?.take(80)}" },
            try { com.example.mlbbresearch.learning.PictureExport.export(ctx) } catch (e: Exception) { "pictures failed: ${e.message?.take(80)}" })
        val all = files(ctx)
        val tooBig = all.filter { it.size > MAX_FILE }.map { it.name }.toSet()
        val commits = plan(all.map { it.name }.filter { it !in tooBig }, uploaded(), if (SessionRecorder.on) SessionRecorder.currentName() else null)
        val skipped = if (tooBig.isEmpty()) "" else "; ${tooBig.size} file(s) over 100 MB skipped (GitHub refuses them)"
        if (commits.isEmpty()) return "Nothing new to upload (${all.size} file(s) in the folder are already on GitHub)$skipped"
        var filesDone = 0; var n = 0
        for (group in commits) {
            n++
            status = "commit $n of ${commits.size}: ${group.first()}" + if (group.size > 1) " + ${group.size - 1} more" else ""
            val items = group.mapNotNull { name -> all.firstOrNull { it.name == name } }
            commit(token, items, message(group))
            remember(group)
            // on GitHub now: the phone's copy is deleted (owner, 2026-11-12: "delete all exported data if uploaded
            // successfully"); a failed commit throws before this, so its files stay for the next try
            for (name in group) try { SessionRecorder.deleteFile(ctx, name) } catch (e: Exception) { }
            filesDone += group.size
        }
        return "Uploaded $filesDone file(s) in ${commits.size} commit(s) to $BRANCH (${prepared.joinToString("; ")})$skipped"
    }

    // one commit: a blob per file, a tree on top of the branch's tree, the commit, then the branch moves to it
    private fun commit(token: String, items: List<Item>, message: String) {
        val head = call(token, "GET", "/repos/$REPO/git/ref/heads/$BRANCH", null).getJSONObject("object").getString("sha")
        val baseTree = call(token, "GET", "/repos/$REPO/git/commits/$head", null).getJSONObject("tree").getString("sha")
        val tree = JSONArray()
        for (it in items) {
            val blob = blob(token, it)
            tree.put(JSONObject().put("path", it.name).put("mode", "100644").put("type", "blob").put("sha", blob))
        }
        val newTree = call(token, "POST", "/repos/$REPO/git/trees", JSONObject().put("base_tree", baseTree).put("tree", tree).toString()).getString("sha")
        val newCommit = call(token, "POST", "/repos/$REPO/git/commits",
            JSONObject().put("message", message).put("tree", newTree).put("parents", JSONArray(listOf(head))).toString()).getString("sha")
        val body = JSONObject().put("sha", newCommit).put("force", false).toString()
        try { call(token, "PATCH", "/repos/$REPO/git/refs/heads/$BRANCH", body) }
        catch (e: java.net.ProtocolException) { call(token, "POST", "/repos/$REPO/git/refs/heads/$BRANCH", body) }
    }

    // a file as a blob, streamed (a 25 MB video must not sit in memory as text)
    private fun blob(token: String, item: Item): String {
        val c = open(token, "POST", "/repos/$REPO/git/blobs")
        try {
            c.doOutput = true
            c.setChunkedStreamingMode(64 * 1024)
            c.outputStream.use { o ->
                o.write("{\"encoding\":\"base64\",\"content\":\"".toByteArray())
                val input = item.open() ?: throw IllegalStateException("cannot read ${item.name}")
                input.use { i ->
                    android.util.Base64OutputStream(NoClose(o), android.util.Base64.NO_WRAP).use { b -> i.copyTo(b, 48 * 1024) }
                }
                o.write("\"}".toByteArray())
            }
            return JSONObject(read(c)).getString("sha")
        } finally { c.disconnect() }
    }

    // (Base64OutputStream closes what it wraps: the connection's stream must stay open for the closing quote)
    private class NoClose(val o: java.io.OutputStream) : java.io.OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun flush() = o.flush()
        override fun close() { o.flush() }
    }

    private fun open(token: String, method: String, path: String): HttpURLConnection {
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000; c.readTimeout = 120_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "sphynx")
        c.setRequestProperty("Content-Type", "application/json")
        return c
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) throw IllegalStateException(explain(code, text))
        return text
    }

    // what a GitHub error means for the owner (pure, unit tested)
    fun explain(code: Int, body: String): String {
        val m = try { JSONObject(body).optString("message") } catch (e: Exception) { "" }
        return when (code) {
            401 -> "GitHub refused the token (401): make a new one and replace $TOKEN_FILE"
            403 -> "GitHub refused (403): the token needs Contents: read and write on $REPO"
            404 -> "Not found (404): the branch $BRANCH, or the token has no access to $REPO"
            409, 422 -> "GitHub could not take the commit ($code): $m - tap Upload again"
            else -> "GitHub error $code: ${m.take(120)}"
        }
    }

    private fun call(token: String, method: String, path: String, body: String?): JSONObject {
        val c = open(token, method, path)
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            return JSONObject(read(c))
        } finally { c.disconnect() }
    }
}
