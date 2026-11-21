package com.sphynx.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build

// THIS PHONE'S CAPABILITIES IN ONE RECORD (Phase 5, P3; audit findings F3, F7). The Performance page already shows
// the phone's facts one by one; this puts the ones that decide what the app can run into one record, checks them
// against the owner-approved requirements (docs/UNIVERSAL_COMPATIBILITY.md A.9.4: Android 11, arm64-v8a, 6 GB
// RAM minimum, 8-12 GB recommended) and writes it into the diagnostics log and every session recording, so the
// compatibility matrix can be filled from uploaded recordings.
//
// It does NOT pick a model profile (Lite / Balanced / ...): A.9.3 says profiles are chosen from measurements on
// the phone, never from advertised RAM; that selector is Phase 11. Only reads.
object DeviceProfile {

    data class Facts(
        val phone: String,
        val chip: String,
        val android: Int,
        val abis: List<String>,
        val totalRamMb: Long,
        val cores: Int,
        val maxGhz: Float?,
        val gpu: String?,                 // the GPU driver's renderer name, null when not read
        val accelerators: List<String>,   // NPU / DSP driver libraries found (present, not proven usable)
        val ggufUnsupported: String?      // why the GGUF engine cannot run here; null = the CPU has what it needs
    )

    /** One requirement: ok = true / false, or null when it cannot be decided from the facts. */
    data class Check(val name: String, val ok: Boolean?, val detail: String)

    // A phone sold as "6 GB" reports less (the kernel and the GPU keep some): about 5.4-5.8 GB. The thresholds sit
    // below the advertised sizes so that a real 6 GB / 8 GB phone is counted as one.
    const val MIN_RAM_MB = 5_120L         // "6 GB" class: Lite profile only (A.9.4)
    const val RECOMMENDED_RAM_MB = 7_168L // "8 GB" class and up: Balanced / Advanced candidates (A.9.4)

    fun memoryCheck(totalRamMb: Long): Check {
        val gb = String.format(java.util.Locale.US, "%.1f GB", totalRamMb / 1024f)
        return when {
            totalRamMb <= 0 -> Check("Memory", null, "not reported")
            totalRamMb < MIN_RAM_MB -> Check("Memory", false, "$gb: below the 6 GB minimum")
            totalRamMb < RECOMMENDED_RAM_MB -> Check("Memory", true, "$gb: the minimum (Lite use only, not full autonomy)")
            else -> Check("Memory", true, "$gb: recommended size")
        }
    }

    fun checks(f: Facts): List<Check> = listOf(
        Check("Android", f.android >= 30, "Android API ${f.android}" + if (f.android >= 30) "" else ": the app needs Android 11 (API 30)"),
        Check("Processor type", "arm64-v8a" in f.abis,
            if ("arm64-v8a" in f.abis) "64-bit ARM" else "not 64-bit ARM (${f.abis.joinToString()}): not supported"),
        memoryCheck(f.totalRamMb),
        Check("GGUF engine (CPU)", f.ggufUnsupported == null, f.ggufUnsupported ?: "the processor has dot product and fp16"),
        // the GPU path is proven only by a model loading on it (the Model page); its name is a fact, not a check
        Check("GPU", null, f.gpu ?: "not read yet"),
        Check("NPU / DSP drivers", null, if (f.accelerators.isEmpty()) "none found" else f.accelerators.joinToString())
    )

    /** False when a hard requirement fails (the app cannot run as built here). */
    fun supported(f: Facts): Boolean = checks(f).take(3).none { it.ok == false }

    /** One line for the diagnostics log and the recordings. */
    fun summary(f: Facts): String = buildString {
        append(f.phone).append(" · ").append(f.chip).append(" · Android API ").append(f.android)
        append(" · ").append(f.abis.firstOrNull() ?: "?")
        append(" · RAM ").append(String.format(java.util.Locale.US, "%.1f GB", f.totalRamMb / 1024f))
        append(" · ").append(f.cores).append(" cores")
        f.maxGhz?.let { append(" up to ").append(String.format(java.util.Locale.US, "%.2f GHz", it)) }
        append(" · GPU ").append(f.gpu ?: "?")
        append(" · GGUF ").append(if (f.ggufUnsupported == null) "yes" else "no")
        if (f.accelerators.isNotEmpty()) append(" · NPU/DSP drivers ").append(f.accelerators.size)
    }

    // ---- on the phone ----

    @Volatile var last: Facts? = null; private set

    /**
     * Reads the facts (call off the screen thread: the GPU name needs an off-screen GL context) and logs them once.
     * [ggufUnsupported] comes from the app layer (inference.LlamaCpu), which core may not call (ADR 0001).
     */
    fun collect(ctx: Context, ggufUnsupported: String?): Facts {
        val mi = ActivityManager.MemoryInfo()
        try { ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi) } catch (e: Exception) { }
        DeviceInfo.readGpu(); DeviceInfo.readAccelerators()
        val cores = Runtime.getRuntime().availableProcessors()
        val f = Facts(
            phone = DeviceInfo.phoneName(), chip = DeviceInfo.chip(), android = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(), totalRamMb = mi.totalMem / 1_048_576L, cores = cores,
            maxGhz = (0 until cores).mapNotNull { DeviceInfo.coreMaxGhz(it) }.maxOrNull(),
            gpu = DeviceInfo.gpu?.let { it.first + " (" + it.third + ")" },
            accelerators = DeviceInfo.accelerators.orEmpty(), ggufUnsupported = ggufUnsupported
        )
        last = f
        val failed = checks(f).filter { it.ok == false }
        Diag.info("Device profile: " + summary(f))
        if (failed.isNotEmpty()) Diag.error("Device profile: " + failed.joinToString("; ") { it.name + ": " + it.detail })
        return f
    }
}
