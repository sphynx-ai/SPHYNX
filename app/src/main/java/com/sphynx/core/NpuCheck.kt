package com.sphynx.core

import android.content.Context
import android.os.Build
import java.io.File

// CAN THIS PHONE'S NPU RUN GEMMA? (Phase 7, step L8; experimental, owner 2026-11-23)
// LiteRT-LM compiles an ordinary .litertlm for a MediaTek NPU on the phone when it needs three things:
//  1. LiteRT's MediaTek dispatch library and 2. its compiler plugin (shipped in the app from Google Maven,
//     com.google.ai.edge.litert:litert-npu-runtime-mediatek), in the app's native library folder;
//  3. MediaTek's Neuron adapter, which this app does not ship: it must come with the phone, in a vendor library that
//     apps are allowed to use (listed in a public.libraries file; the app's manifest asks for it, optional).
// This check reads those facts so the Model page and the log say why the NPU did or did not load. Pure part tested.
object NpuCheck {

    const val DISPATCH = "libLiteRtDispatch_MediaTek.so"
    const val COMPILER = "libLiteRtCompilerPlugin_MediaTek.so"
    /** The Neuron adapter names LiteRT's MediaTek dispatcher tries on Android, in its order. */
    val ADAPTERS = listOf("libneuronusdk_adapter.so", "libneuronusdk_adapter.10.mtk.so", "libneuronusdk_adapter.9.mtk.so",
        "libneuronusdk_adapter.8.mtk.so", "libneuronusdk_adapter.mtk.so", "libneuron_adapter_mgvi.so", "libneuron_adapter.so")

    class Facts(val soc: String, val mediatek: Boolean, val dispatch: Boolean, val compiler: Boolean,
                val publicAdapters: List<String>, val presentAdapters: List<String>)

    /** One line: can the NPU be tried, and if not, why. */
    fun verdict(f: Facts): String = when {
        !f.mediatek -> "NPU: this phone's chip (${f.soc}) is not a MediaTek one; this app only has MediaTek's NPU libraries"
        !f.dispatch || !f.compiler -> "NPU: the app's MediaTek NPU libraries are missing (" +
            listOfNotNull(DISPATCH.takeIf { !f.dispatch }, COMPILER.takeIf { !f.compiler }).joinToString() + ")"
        f.publicAdapters.isNotEmpty() -> "NPU: ready to try on ${f.soc} (MediaTek Neuron adapter: ${f.publicAdapters.first()})"
        f.presentAdapters.isNotEmpty() -> "NPU: ${f.soc} has MediaTek's Neuron adapter (${f.presentAdapters.first()}), but it is not " +
            "open to apps, so the NPU will probably fail and the GPU is used"
        else -> "NPU: no MediaTek Neuron adapter was found on ${f.soc}, so the NPU will probably fail and the GPU is used"
    }

    /** The library names listed in public.libraries files' text (one per line, # comments, an optional word after). */
    fun listed(texts: List<String>): Set<String> = texts.flatMap { t ->
        t.lines().map { it.substringBefore('#').trim().split(Regex("\\s+")).first() }.filter { it.endsWith(".so") }
    }.toSet()

    fun facts(ctx: Context): Facts {
        val soc = (if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE).trim()
        val mediatek = soc.contains("mediatek", true) || soc.contains("mt6", true) || Build.HARDWARE.startsWith("mt", true)
        val libDir = File(ctx.applicationInfo.nativeLibraryDir ?: "")
        val texts = ArrayList<String>()
        for (d in listOf(File("/vendor/etc"), File("/system/etc")))
            try { d.listFiles()?.filter { it.name.startsWith("public.libraries") && it.name.endsWith(".txt") }?.forEach { texts.add(it.readText()) } }
            catch (e: Exception) { }
        val pub = listed(texts)
        val present = ADAPTERS.filter { n -> listOf("/vendor/lib64/", "/system/lib64/").any { File(it + n).exists() } }
        return Facts(soc, mediatek, File(libDir, DISPATCH).isFile, File(libDir, COMPILER).isFile,
            ADAPTERS.filter { it in pub }, present)
    }

    fun line(ctx: Context): String = try { verdict(facts(ctx)) } catch (t: Throwable) { "NPU: could not check (${t.message})" }
}
