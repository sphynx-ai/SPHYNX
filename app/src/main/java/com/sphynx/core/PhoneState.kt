package com.sphynx.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

// The phone's state for the speed recorders (read at most every 10 s): a hot or memory-starved phone
// is slower, so every speed row carries it.
object PhoneState {
    data class Reading(val thermal: Int?, val batteryC: Float?, val ramAvailMb: Long?)

    @Volatile private var at = 0L
    @Volatile private var last = Reading(null, null, null)

    fun read(): Reading {
        val now = System.currentTimeMillis()
        if (now - at < 10_000) return last
        at = now
        val c: Context = Probes.appContext() ?: return last
        // Android thermal status: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown
        val thermal = try { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus } catch (e: Exception) { null }
        val batt = try {
            c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        } catch (e: Exception) { null }
        val ram = try {
            val mi = android.app.ActivityManager.MemoryInfo()
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            mi.availMem / (1024 * 1024)
        } catch (e: Exception) { null }
        last = Reading(thermal, batt, ram)
        return last
    }
}
