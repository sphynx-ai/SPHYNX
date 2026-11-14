package com.sphynx.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

// THE PERFORMANCE PAGE'S "THIS PHONE" SECTION (owner, 2026-11-11: the Speed page becomes the Performance
// page, with what a phone performance screen shows): the phone's name, memory, storage, CPU and its cores,
// the graphics processor, the NPU / accelerator drivers the phone has, temperature and battery, and the
// local models. Live values come from DeviceStats (every 2 s); the ones that never change are read once.
// Everything here only reads.
object DeviceInfo {

    fun phoneName(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL

    // the chip: Android 12+ names it (e.g. MT6897), older ones give the board name
    fun chip(): String = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ").ifBlank { Build.HARDWARE }
    } else Build.HARDWARE

    // ---- CPU ----
    fun onlineCores(): Int? = readText("/sys/devices/system/cpu/online")?.trim()?.split(',')?.sumOf { part ->
        val r = part.split('-').mapNotNull { it.trim().toIntOrNull() }
        when (r.size) { 1 -> 1; 2 -> r[1] - r[0] + 1; else -> 0 }
    }

    private val maxGhz = HashMap<Int, Float?>()
    fun coreMaxGhz(i: Int): Float? = synchronized(maxGhz) {
        maxGhz.getOrPut(i) { readText("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull()?.let { it / 1_000_000f } }
    }

    // ---- memory the system can give back right now ("unused"), from /proc/meminfo ----
    fun unusedRamMb(): Long? = readText("/proc/meminfo", 4000)?.lineSequence()
        ?.firstOrNull { it.startsWith("MemFree:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { it / 1024 }

    // ---- graphics processor: the GPU driver's own name and OpenGL ES version (read once, off-screen) ----
    @Volatile var gpu: Triple<String, String, String>? = null; private set     // renderer, vendor, version
    @Volatile private var gpuTried = false

    fun readGpu() {
        if (gpuTried) return
        gpuTried = true
        try {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return
            val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
            if (n[0] < 1) return
            val ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
                gpu = Triple(GLES20.glGetString(GLES20.GL_RENDERER) ?: "?", GLES20.glGetString(GLES20.GL_VENDOR) ?: "?",
                    GLES20.glGetString(GLES20.GL_VERSION) ?: "?")
            } finally {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
            }
        } catch (t: Throwable) { Diag.error("Performance page: GPU name", t) }
    }

    // ---- NPU / accelerator drivers: the chip makers' neural-network libraries and services the phone has.
    // Android has no Java way to list the NPU; what the phone's vendor folders show is listed, by name.
    // (They say an NPU / DSP is there - not that a given model can run on it.)
    private val ACCEL = listOf(
        "/vendor/lib64/libneuron_adapter.so" to "MediaTek NeuroPilot (Neuron)",
        "/vendor/lib64/libneuronusdk_adapter.mtk.so" to "MediaTek NeuroPilot (Neuron SDK)",
        "/vendor/lib64/libapuwareapusys.so" to "MediaTek APU (MDLA / VPU)",
        "/vendor/lib64/libQnnHtp.so" to "Qualcomm Hexagon NPU (QNN HTP)",
        "/vendor/lib64/libcdsprpc.so" to "Qualcomm Hexagon DSP",
        "/vendor/lib64/libeden_nn_on_system.so" to "Samsung Exynos NPU (Eden)",
        "/vendor/lib64/libedgetpu_client.so" to "Google Tensor TPU",
        "/vendor/lib64/libarmnn.so" to "Arm NN"
    )
    @Volatile var accelerators: List<String>? = null; private set

    fun readAccelerators() {
        if (accelerators != null) return
        val found = ArrayList<String>()
        for ((path, name) in ACCEL) if (try { File(path).exists() } catch (e: Exception) { false }) found.add(name)
        // the Android neural-network services (their names carry the driver, e.g. "mtk-neuron")
        for (dir in listOf("/vendor/etc/vintf/manifest", "/vendor/bin/hw")) {
            try {
                File(dir).list()?.filter { it.contains("neuralnetworks", true) }?.forEach { found.add("service: $it") }
            } catch (e: Exception) { }
        }
        accelerators = found.distinct()
    }

    // ---- temperature and battery ----
    class Power(val thermal: String, val headroom: Float?, val batteryC: Float?, val batteryPct: Int?, val charging: Boolean?)

    fun power(c: Context): Power {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = when (try { pm.currentThermalStatus } catch (e: Exception) { -1 }) {
            PowerManager.THERMAL_STATUS_NONE -> "No thermal throttling"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting down (too hot)"
            else -> "not reported"
        }
        // 0..1 = how close the phone is to throttling in 10 s (1 = throttling); NaN when not supported
        val headroom = try { pm.getThermalHeadroom(10).takeIf { !it.isNaN() } } catch (e: Exception) { null }
        val b = try { c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { null }
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val level = b?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null }
        val charging = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        return Power(thermal, headroom, temp, level, charging)
    }

    private fun readText(path: String, max: Int = 400): String? = try {
        val f = File(path); if (f.canRead()) f.readText().take(max) else null
    } catch (e: Exception) { null }
}
