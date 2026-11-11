package com.example.mlbbresearch.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

// THE APP'S FOLDER ON THE PHONE (v37.19): /CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage
// Everything the app imports (models, detectors, networks, keys) is read from it, and everything it
// exports (CSV exports, training pictures and their zips, Kaggle results, backups) is written into it.
//
// Android lets an app write into a folder of its own choosing at the top of the storage only with
// "All files access" (Settings > Apps > special access). The Model page has a button for it.
// Until it is allowed, the app reads the folder you choose on the Model page and writes its own files
// into Download/Sphynx-Auto-Pilot-Storage (owner, 2026-11-11: the old Download/MLBB-Research is ignored
// completely - neither written nor read).

object Storage {

    const val REL = "CodeOnTheGoProjects/Sphynx-Auto-Pilot-Storage"
    const val LEGACY_REL = "Download/MLBB-Research"     // where earlier versions wrote: ignored now (not read, not written)
    const val LEGACY_NAME = "MLBB-Research"
    // where the app writes without "All files access" (a folder in Download, the same name as the app's folder)
    const val FALLBACK_NAME = "Sphynx-Auto-Pilot-Storage"
    const val FALLBACK_REL = "Download/$FALLBACK_NAME"
    // the MediaStore RELATIVE_PATH of that folder (or a sub-folder), e.g. "Download/Sphynx-Auto-Pilot-Storage/recordings"
    fun downloadsRel(sub: String = ""): String = Environment.DIRECTORY_DOWNLOADS + "/" + FALLBACK_NAME + (if (sub.isEmpty()) "" else "/$sub")

    // "All files access" granted (Android 11+; the app needs Android 11)
    val allFilesAccess: Boolean get() = try { Environment.isExternalStorageManager() } catch (e: Throwable) { false }

    fun root(): File = File(Environment.getExternalStorageDirectory(), REL)

    // the folder (or a sub-folder) to write into directly; null = no permission, use Download/Sphynx-Auto-Pilot-Storage
    fun dir(sub: String = ""): File? {
        if (!allFilesAccess) return null
        val d = if (sub.isEmpty()) root() else File(root(), sub)
        return if (d.isDirectory || d.mkdirs()) d else null
    }

    // where the app's own files go right now, for messages
    val writeLabel: String get() = label(allFilesAccess)
    fun label(direct: Boolean): String = if (direct) "/$REL" else FALLBACK_REL

    // the system screen where the owner turns "All files access" on for this app
    fun requestIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))

    fun status(): String = if (allFilesAccess) "Folder: /$REL (all files access on: imports and exports both use it)"
        else "Folder: /$REL needs \"All files access\" to save into it. Until then the app reads the folder you choose " +
            "below and saves its own files in $FALLBACK_REL."
}
