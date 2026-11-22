package com.sphynx.inference

// PHASE 6, step S8 (owner, 2026-11-22): a GGUF model reads pictures through its separate multimodal projector file
// ("mmproj"), so the picture part is only downloaded when it is wanted. Both files go in the GGUF library; this decides
// which mmproj belongs to which model, from the file names the model makers use, e.g.
//     gemma-3-4b-it-Q4_K_M.gguf      + mmproj-gemma-3-4b-it-f16.gguf
//     Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf + Qwen2.5-VL-3B-Instruct-mmproj-f16.gguf
// Pure, unit-tested.
object MmprojPairing {

    fun isProjector(fileName: String): Boolean = fileName.lowercase().let { it.endsWith(".gguf") && "mmproj" in it }

    // quantization and precision tags at the end of a name (Q4_K_M, IQ3_XS, F16, BF16, Q8_0, ...)
    private val QUANT = Regex("""[-_.](i?q\d[a-z0-9_]*|bf16|f16|f32|fp16|fp32)$""")

    /** The name without ".gguf", "mmproj", the quantization tag and separators, for comparing. */
    fun stem(fileName: String): String {
        var s = fileName.lowercase().removeSuffix(".gguf")
        s = s.replace("mmproj", "")
        repeat(2) { s = s.replace(QUANT, "") }
        return s.replace(Regex("[^a-z0-9]+"), "")
    }

    /**
     * The projector for [modelFile] among [projectors], or null. The one whose name matches the model's best (same stem,
     * else the longest stem that one starts with the other) wins; a projector shorter than 4 letters never matches.
     */
    fun pairFor(modelFile: String, projectors: List<String>): String? {
        if (isProjector(modelFile)) return null
        val m = stem(modelFile)
        if (m.isEmpty()) return null
        return projectors.filter { isProjector(it) }
            .map { it to stem(it) }
            .filter { (_, p) -> p.length >= 4 && (p == m || m.startsWith(p) || p.startsWith(m)) }
            .maxByOrNull { (_, p) -> if (p == m) Int.MAX_VALUE else minOf(p.length, m.length) }
            ?.first
    }
}
