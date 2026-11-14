package com.sphynx.inference

// PHASE 8: can this phone's processor run the llama.cpp library at all? The library is built for
// ARMv8.2 with dot product and half-precision maths (CMakeLists.txt). On an older core (Cortex-A53 /
// A73) loading it would stop the app with an illegal instruction, so the CPU is checked first and the
// library is not loaded on a phone that lacks either feature: the GGUF backend then reports why.
object LlamaCpu {

    /** The CPU features llama.cpp needs: "asimddp" (dot product) and "fphp" / "asimdhp" (fp16). */
    fun missingFeatures(cpuinfo: String): List<String> {
        // every core's "Features" line (on big.LITTLE phones the kernel can list each core)
        val lines = cpuinfo.lines().filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
        if (lines.isEmpty()) return listOf("no CPU feature list in /proc/cpuinfo")
        val miss = ArrayList<String>()
        if (lines.any { "asimddp" !in it }) miss.add("dot product (asimddp)")
        if (lines.any { "fphp" !in it && "asimdhp" !in it }) miss.add("half-precision maths (fphp)")
        return miss
    }

    /** Why the library cannot run on this phone, or null when it can. */
    fun unsupportedReason(abis: List<String>, cpuinfo: String?): String? {
        if ("arm64-v8a" !in abis) return "this phone is not a 64-bit ARM phone (the GGUF engine is built for arm64-v8a only)"
        if (cpuinfo == null) return null           // cannot read the list: let the library try
        val miss = missingFeatures(cpuinfo)
        if (miss.isEmpty()) return null
        return "this phone's processor lacks " + miss.joinToString(" and ") + ", which the GGUF engine needs"
    }

    /** Threads for llama.cpp: the big cores of most phones; it gets slower when it also uses the small ones. */
    fun threads(cores: Int = Runtime.getRuntime().availableProcessors()): Int = cores.coerceIn(1, 4)
}
