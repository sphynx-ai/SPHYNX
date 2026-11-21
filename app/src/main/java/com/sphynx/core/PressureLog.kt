package com.sphynx.core

import android.content.Context
import android.os.PowerManager

// MEMORY PRESSURE AND HEAT, WRITTEN DOWN (Phase 5, P4; audit findings F2, F4). Android tells the app when it should
// free memory (onTrimMemory) and when the phone's thermal status changes. Until now neither was recorded, so a
// low-memory kill or a slow, throttled match could not be explained afterwards. Each event goes into the
// diagnostics log, and so into the session recording (Diag.log writes there too).
//
// This only RECORDS. What the app should unload or slow down by itself is the P5 policy, an owner decision.
object PressureLog {

    // ComponentCallbacks2.TRIM_MEMORY_* values (stable Android API numbers)
    const val RUNNING_MODERATE = 5
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40
    const val MODERATE = 60
    const val COMPLETE = 80

    fun trimName(level: Int): String = when (level) {
        RUNNING_MODERATE -> "memory getting low (app running)"
        RUNNING_LOW -> "memory low (app running)"
        RUNNING_CRITICAL -> "memory critically low (app running): Android may close background apps"
        UI_HIDDEN -> "app screen hidden"
        BACKGROUND -> "app in the background, memory low"
        MODERATE -> "app in the background, memory lower: it may be closed"
        COMPLETE -> "app in the background, memory very low: it will be closed first"
        else -> "memory signal $level"
    }

    /**
     * Should this trim signal be written down? The screen-hidden signal comes every time you switch to the game,
     * so it is skipped; the same level is written at most once a minute; a worse level always.
     */
    fun shouldLogTrim(level: Int, lastLevel: Int, lastAtMs: Long, nowMs: Long): Boolean = when {
        level == UI_HIDDEN -> false
        level > lastLevel -> true
        else -> nowMs - lastAtMs >= 60_000L
    }

    /** A trim level that means real pressure (an ERROR line), not only a hint. */
    fun isSerious(level: Int): Boolean = level == RUNNING_CRITICAL || level >= MODERATE

    // PowerManager.THERMAL_STATUS_* values
    fun thermalName(status: Int): String = when (status) {
        0 -> "no throttling"
        1 -> "light throttling"
        2 -> "moderate throttling"
        3 -> "severe throttling"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutting down (too hot)"
        else -> "not reported"
    }

    // ---- on the phone ----

    private var lastTrim = 0
    private var lastTrimAt = 0L
    @Volatile private var installed = false

    /** Called from Application.onTrimMemory. */
    @Synchronized
    fun onTrim(level: Int) {
        val now = System.currentTimeMillis()
        if (!shouldLogTrim(level, lastTrim, lastTrimAt, now)) return
        lastTrim = level; lastTrimAt = now
        val msg = "Memory: " + trimName(level) + (DeviceStats.last?.let { " (phone RAM available ${it.ramAvailMb} MB, this app ${it.appRamMb} MB)" } ?: "")
        if (isSerious(level)) Diag.error(msg) else Diag.info(msg)
    }

    /** Listens to the phone's thermal status for the app's whole life (once). */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        try {
            val pm = ctx.applicationContext.getSystemService(PowerManager::class.java) ?: return
            pm.addThermalStatusListener(ctx.applicationContext.mainExecutor) { status ->
                val msg = "Heat: the phone reports " + thermalName(status)
                if (status >= 3) Diag.error(msg) else Diag.info(msg)
            }
        } catch (t: Throwable) { Diag.error("Heat: could not listen to the thermal status", t) }
    }
}
