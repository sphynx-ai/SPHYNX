package com.sphynx.core

import android.app.ActivityManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// WHY THE APP WAS CLOSED LAST TIME (Phase 5, P2; audit finding F1). The Java crash handler (CrashLog) only sees
// Java crashes. A native crash (llama.cpp, the LiteRT-LM GPU driver), an ANR or a low-memory kill leaves nothing
// in the diagnostics log. Android 11+ (the app's minimum) keeps a short history of how the app's process ended;
// at every start the entries not seen before are written into the diagnostics log, and the trace Android keeps
// for an ANR (text) or a native crash (tombstone) is exported into the Recordings folder (owner's debug-data rule:
// it is uploaded and then deleted from the phone). Only reads; nothing in play changes.
object ExitHistory {

    /** One way the process ended (Android's ApplicationExitInfo, copied so the rules below are pure). */
    data class Exit(
        val atMs: Long,
        val reason: Int,
        val status: Int,            // the exit code, or the signal number for a native crash / kill
        val importance: Int,        // ActivityManager.RunningAppProcessInfo importance when it ended
        val pssKb: Long,
        val rssKb: Long,
        val description: String?
    )

    // ApplicationExitInfo.REASON_* values (stable Android API numbers; written out so this file needs no newer
    // API level for the ones added after Android 11)
    const val UNKNOWN = 0
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7
    const val PERMISSION_CHANGE = 8
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val USER_STOPPED = 11
    const val DEPENDENCY_DIED = 12
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_STATE_CHANGE = 15
    const val PACKAGE_UPDATED = 16

    /** The reason in plain words. */
    fun reasonName(reason: Int): String = when (reason) {
        EXIT_SELF -> "closed itself"
        SIGNALED -> "stopped by the system (signal)"
        LOW_MEMORY -> "closed by Android for lack of memory"
        CRASH -> "crash (Java)"
        CRASH_NATIVE -> "crash in native code"
        ANR -> "not responding (ANR)"
        INITIALIZATION_FAILURE -> "failed to start"
        PERMISSION_CHANGE -> "closed after a permission change"
        EXCESSIVE_RESOURCE_USAGE -> "closed by Android for using too much CPU or memory"
        USER_REQUESTED -> "closed by you (force stop or swiped away)"
        USER_STOPPED -> "stopped by you"
        DEPENDENCY_DIED -> "closed because a part it depends on stopped"
        OTHER -> "closed by the system (other reason)"
        FREEZER -> "closed while frozen in the background"
        PACKAGE_STATE_CHANGE -> "closed after the app was turned off or changed"
        PACKAGE_UPDATED -> "closed for an app update"
        else -> "unknown reason"
    }

    /** A failure worth an ERROR line (the app did not end the normal way). */
    fun isProblem(reason: Int): Boolean = reason in setOf(
        CRASH, CRASH_NATIVE, ANR, LOW_MEMORY, INITIALIZATION_FAILURE, EXCESSIVE_RESOURCE_USAGE, SIGNALED
    )

    /** Where the app was when it ended (from the process importance). */
    fun whereName(importance: Int): String = when {
        importance <= 0 -> "state unknown"
        importance <= 100 -> "on screen"            // IMPORTANCE_FOREGROUND
        importance <= 125 -> "foreground service"   // IMPORTANCE_FOREGROUND_SERVICE
        importance <= 200 -> "visible"              // IMPORTANCE_VISIBLE
        importance <= 230 -> "perceptible"          // IMPORTANCE_PERCEPTIBLE
        else -> "in the background"
    }

    /** The entries newer than [seenMs], oldest first. */
    fun newSince(exits: List<Exit>, seenMs: Long): List<Exit> = exits.filter { it.atMs > seenMs }.sortedBy { it.atMs }

    /** The trace file name for an exit that has one (ANR text, native tombstone), or null. */
    fun traceName(e: Exit, stamp: String): String? = when (e.reason) {
        ANR -> "diagnostics-exit-$stamp-anr.txt"
        CRASH_NATIVE -> "diagnostics-exit-$stamp-native-tombstone.pb"
        else -> null
    }

    /** The diagnostics line, e.g. "App closed 11-21 10:02:11: crash in native code (on screen, signal 11, memory 1843 MB)". */
    fun line(e: Exit, time: String): String {
        val parts = ArrayList<String>()
        parts.add(whereName(e.importance))
        if (e.reason == CRASH_NATIVE || e.reason == SIGNALED) { if (e.status > 0) parts.add("signal ${e.status}") }
        else if (e.reason == EXIT_SELF) parts.add("code ${e.status}")
        val memKb = maxOf(e.pssKb, e.rssKb)
        if (memKb > 0) parts.add("memory ${memKb / 1024} MB")
        val d = e.description?.trim()?.replace("\n", " ")?.take(160)
        if (!d.isNullOrEmpty()) parts.add(d)
        return "App closed $time: ${reasonName(e.reason)} (${parts.joinToString(", ")})"
    }

    // ---- on the phone ----

    private const val SEEN_KEY = "exit_seen_ms"
    private const val MAX_TRACE_BYTES = 2_000_000

    /** The latest exit (seen before or not), for the Logs page; null until checked or when there is none. */
    @Volatile var last: String? = null; private set

    /** Reads the history once per start, in the background. */
    fun checkAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ try { check(app) } catch (t: Throwable) { Diag.error("Exit history: could not read", t) } }, "exit-history").start()
    }

    private fun check(c: Context) {
        val am = c.getSystemService(ActivityManager::class.java) ?: return
        val infos = am.getHistoricalProcessExitReasons(c.packageName, 0, 16)
        if (infos.isEmpty()) { last = "No earlier close recorded by Android"; return }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val exits = infos.map { Exit(it.timestamp, it.reason, it.status, it.importance, it.pss, it.rss, it.description) }
        exits.maxByOrNull { it.atMs }?.let { last = line(it, fmt.format(Date(it.atMs))) }
        val seen = Cfg.raw(SEEN_KEY)?.toLongOrNull() ?: 0L
        val fresh = newSince(exits, seen)
        for (e in fresh) {
            val msg = line(e, fmt.format(Date(e.atMs)))
            if (isProblem(e.reason)) Diag.error(msg) else Diag.info(msg)
            traceName(e, stampFmt.format(Date(e.atMs)))?.let { name ->
                val info = infos.firstOrNull { it.timestamp == e.atMs } ?: return@let
                try {
                    info.traceInputStream?.use { input ->
                        val bytes = input.readNBytesCompat(MAX_TRACE_BYTES)
                        if (bytes.isNotEmpty()) {
                            val mime = if (name.endsWith(".txt")) "text/plain" else "application/octet-stream"
                            SessionRecorder.openOut(c, name, mime).use { it.write(bytes) }
                            Diag.info("Exit history: saved $name (${bytes.size / 1024} KB) in ${SessionRecorder.FOLDER}")
                        }
                    }
                } catch (t: Throwable) { Diag.error("Exit history: could not save $name", t) }
            }
        }
        exits.maxOfOrNull { it.atMs }?.let { if (it > seen) Cfg.setRaw(SEEN_KEY, it.toString()) }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
