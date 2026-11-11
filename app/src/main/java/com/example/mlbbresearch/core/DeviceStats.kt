package com.example.mlbbresearch.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.StatFs
import java.io.File

// PHONE METRICS for the Speed page (v36.60): CPU, GPU, memory (RAM) and storage (ROM).
// What Android lets an app read:
//  * CPU: this app's own use (its CPU time over wall time, all cores = 100%) and every core's current
//    clock against its maximum. The whole phone's CPU use is closed to apps since Android 8.
//  * GPU: no official API. The load / clock files the chip makers expose are tried (MediaTek GED,
//    Qualcomm KGSL, Mali); many phones block them for apps - then it says so. Which parts of the AI
//    run on the GPU is always shown (the Gemma models: GPU or CPU; the networks / detectors: CPU).
//  * RAM: the phone's total / available and this app's own use (all of it, Java and native).
//  * ROM: the phone's storage used / free, and how much the app's own files (models) take.
object DeviceStats {

    class Snapshot(
        val appCpuPct: Float, val cores: Int, val coreLoadPct: Float, val coreGhz: List<Float>,
        val gpuLoadPct: Float?, val gpuMhz: Float?, val gpuSource: String,
        val ramTotalMb: Long, val ramAvailMb: Long, val lowMemory: Boolean, val appRamMb: Long, val appJavaMb: Long, val appNativeMb: Long,
        val romTotalGb: Float, val romFreeGb: Float, val appFilesMb: Long
    )

    @Volatile var last: Snapshot? = null; private set
    val cpuHistory = FloatArray(HIST); val ramHistory = FloatArray(HIST); val gpuHistory = FloatArray(HIST)
    val availHistory = FloatArray(HIST)          // the phone's available RAM, GB (Performance page)
    // 5 minutes of samples, one every 2 s (v36.86: was 2 minutes)
    const val HIST = 150
    private var histN = 0
    private var lastCpuMs = -1L
    private var lastWallMs = 0L
    private var lastAt = 0L
    private var filesMb = -1L
    private var filesAt = 0L
    private var pssMb = 0L
    private var pssAt = 0L

    // every 2 s at most (reading memory details costs a few ms)
    @Synchronized
    fun sample(ctx: Context): Snapshot? {
        val now = System.currentTimeMillis()
        if (now - lastAt < 2000) return last
        lastAt = now
        val cores = Runtime.getRuntime().availableProcessors()
        // ---- CPU: this app ----
        val cpu = Process.getElapsedCpuTime()
        val appCpu = if (lastCpuMs >= 0 && now > lastWallMs) ((cpu - lastCpuMs).toFloat() / (now - lastWallMs) / cores * 100f).coerceIn(0f, 100f) else 0f
        lastCpuMs = cpu; lastWallMs = now
        // ---- CPU: every core's clock against its maximum ----
        val ghz = ArrayList<Float>(); var sumPct = 0f; var nPct = 0
        for (i in 0 until cores) {
            // (one entry per core, in order: -1 when the core is offline / not readable - Performance page tiles)
            val cur = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: run { ghz.add(-1f); null } ?: continue
            ghz.add(cur / 1_000_000f)
            readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.takeIf { it > 0 }?.let { sumPct += cur * 100f / it; nPct++ }
        }
        // ---- GPU ----
        val (gpuLoad, gpuMhz, gpuSrc) = gpu()
        // ---- RAM ----
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (now - pssAt > 6000) {          // the app's whole memory (PSS): every 6 s
            pssAt = now
            pssMb = try { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024L } catch (e: Exception) { 0L }
        }
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1_048_576L
        // ---- ROM ----
        val st = StatFs(Environment.getDataDirectory().path)
        if (now - filesAt > 30_000 || filesMb < 0) { filesAt = now; filesMb = (dirSize(ctx.filesDir) + dirSize(ctx.cacheDir)) / 1_048_576L }
        val snap = Snapshot(appCpu, cores, if (nPct > 0) sumPct / nPct else -1f, ghz, gpuLoad, gpuMhz, gpuSrc,
            mi.totalMem / 1_048_576L, mi.availMem / 1_048_576L, mi.lowMemory, maxOf(pssMb, javaMb + nativeMb), javaMb, nativeMb,
            st.totalBytes / 1e9f, st.availableBytes / 1e9f, filesMb)
        // history (5 min)
        val i = histN % HIST
        cpuHistory[i] = appCpu; ramHistory[i] = snap.appRamMb.toFloat(); gpuHistory[i] = gpuLoad ?: 0f
        availHistory[i] = snap.ramAvailMb / 1024f
        histN++
        last = snap
        return snap
    }

    // measured off the screen thread (memory details and the file sizes take a moment)
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "device-stats").apply { priority = Thread.MIN_PRIORITY } }
    @Volatile private var queued = false
    fun sampleAsync(ctx: Context) {
        if (queued) return
        queued = true
        io.execute { try { sample(ctx.applicationContext) } catch (e: Exception) { } finally { queued = false } }
    }

    // oldest first
    fun history(a: FloatArray): FloatArray {
        val n = minOf(histN, HIST)
        return FloatArray(n) { a[(histN - n + it) % HIST] }
    }

    // the chip makers' GPU files (load %, clock MHz); null when this phone blocks them
    private fun gpu(): Triple<Float?, Float?, String> {
        // MediaTek (POCO X6 Pro: Dimensity, Mali GPU)
        readText("/sys/kernel/ged/hal/gpu_utilization")?.let { t ->
            val load = Regex("\\d+").find(t)?.value?.toFloatOrNull()
            val mhz = readLong("/sys/kernel/ged/hal/current_freqency")?.let { if (it > 100_000) it / 1000f else it.toFloat() }
            if (load != null) return Triple(load, mhz, "MediaTek GED")
        }
        readLong("/sys/module/ged/parameters/gpu_loading")?.let { return Triple(it.toFloat(), null, "MediaTek GED") }
        readText("/proc/gpufreqv2/gpufreq_status")?.let { t ->
            Regex("(?i)freq\\D{0,12}(\\d{3,7})").find(t)?.groupValues?.get(1)?.toFloatOrNull()?.let { f ->
                return Triple(null, if (f > 100_000) f / 1000f else f, "MediaTek gpufreq") }
        }
        // Qualcomm Adreno
        readText("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.let { t ->
            Regex("\\d+").find(t)?.value?.toFloatOrNull()?.let { l ->
                return Triple(l, readLong("/sys/class/kgsl/kgsl-3d0/gpuclk")?.let { it / 1_000_000f }, "Adreno KGSL") }
        }
        readText("/sys/class/kgsl/kgsl-3d0/gpubusy")?.let { t ->
            val p = t.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (p.size >= 2 && p[1] > 0) return Triple(p[0] * 100f / p[1], null, "Adreno KGSL")
        }
        // Mali (generic)
        for (path in listOf("/sys/class/misc/mali0/device/utilization", "/sys/devices/platform/mali/utilization")) {
            readLong(path)?.let { return Triple(it.toFloat(), null, "Mali") }
        }
        return Triple(null, null, "this phone does not let apps read the GPU load")
    }

    private fun readText(path: String): String? = try {
        val f = File(path); if (f.canRead()) f.readText().take(400) else null
    } catch (e: Exception) { null }
    private fun readLong(path: String): Long? = readText(path)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()

    private fun dirSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
}
