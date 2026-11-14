package com.sphynx.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Persistent bug/error history, shown on the Logs page (can be deleted there).
object Diag {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "diagnostics.log")
    }

    @Synchronized
    fun log(level: String, msg: String) {
        SessionRecorder.log(level, msg)
        val f = file ?: return
        try {
            if (f.exists() && f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
            f.appendText("${fmt.format(Date())} [$level] ${msg.replace("\n", " | ")}\n")
        } catch (e: Exception) {
        }
    }

    // the log file itself (copied next to every recording - SessionRecorder)
    fun logFile(): File? = file

    fun info(msg: String) = log("INFO", msg)

    fun error(msg: String, t: Throwable? = null) =
        log("ERROR", if (t == null) msg else msg + " :: " + t.stackTraceToString().take(700))

    @Synchronized
    fun read(): String = try {
        val f = file
        if (f != null && f.exists()) f.readText() else ""
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clear() {
        try { file?.writeText("") } catch (e: Exception) { }
    }
}

object CrashLog {
    private var installed = false
    fun install() {
        if (installed) return
        installed = true
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Diag.error("CRASH in thread ${t.name}", e)
            old?.uncaughtException(t, e)
        }
    }
}
