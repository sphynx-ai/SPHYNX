package com.sphynx.inference

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * PHASE 8, step G2: a GGUF model file's own description, read before native code opens it.
 *
 * GGUF (version 2 and 3) starts with "GGUF", a version, the tensor count and the key/value count, then the key/value
 * pairs, all little-endian. Only the keys below are kept; every other value (the tokenizer's long arrays among them) is
 * skipped without being stored. Every length is checked against the file, so a broken or hostile file gives an error
 * instead of an out-of-memory or a crash inside llama.cpp. Pure, unit-tested with made-up files.
 */
data class GgufInfo(
    val version: Int,
    val architecture: String,
    val name: String?,
    /** llama.cpp's file-type number (general.file_type), shown as [quantization]. */
    val fileType: Int?,
    val sizeLabel: String?,
    /** The context the model was trained for (<architecture>.context_length). */
    val contextLength: Long?,
    val hasChatTemplate: Boolean,
    /** A picture projector (mmproj) file, not a model that answers. */
    val isProjector: Boolean,
    val tensorCount: Long,
) {
    val quantization: String get() = fileType?.let { QUANT[it] ?: "type $it" } ?: "unknown"

    /** One line for the Model page: "llama · Q4_K_M · 1B · trained for 131,072 tokens · chat format". */
    fun summary(): String = listOfNotNull(
        if (isProjector) "picture projector (mmproj)" else architecture,
        quantization.takeIf { fileType != null },
        sizeLabel,
        contextLength?.let { "trained for " + String.format(Locale.US, "%,d", it) + " tokens" },
        if (isProjector) null else if (hasChatTemplate) "chat format" else "no chat format",
    ).joinToString(" · ")

    /** Why this file can't be used as a model with the built-in llama.cpp, or null. */
    fun problemAsModel(): String? = when {
        isProjector -> "this is a picture projector (mmproj), not a model: keep it next to its model in the GGUF library"
        architecture !in GgufArchitectures.KNOWN ->
            "its model family \"$architecture\" is newer than this app's llama.cpp (b11900): it can't be loaded yet"
        else -> null
    }

    companion object {
        /** llama.cpp's file types (include/llama.h, llama_ftype). */
        val QUANT: Map<Int, String> = mapOf(
            0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1", 10 to "Q2_K",
            11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S",
            17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S", 22 to "IQ3_XS",
            23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M", 28 to "IQ2_S", 29 to "IQ2_M",
            30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0", 37 to "TQ2_0", 38 to "MXFP4_MOE", 39 to "NVFP4",
            40 to "Q1_0", 41 to "Q2_0")

        const val MAX_KEYS = 100_000L
        const val MAX_STRING = 16L * 1024 * 1024          // a chat template is a few KB; 16 MB is far above any real one
        const val MAX_ARRAY = 50_000_000L

        fun read(file: File): Result<GgufInfo> =
            try { file.inputStream().use { read(it, file.length()) } } catch (e: Exception) { Result.failure(e) }

        /** Reads from [input]; [size] is the file's length (bounds for every length field), or -1 when unknown. */
        fun read(input: InputStream, size: Long): Result<GgufInfo> = try {
            Result.success(Reader(DataInputStream(BufferedInputStream(input, 1 shl 16)), size).info())
        } catch (e: EOFException) {
            Result.failure(GgufError("the file ends in the middle of its description: the download did not finish"))
        } catch (e: GgufError) {
            Result.failure(e)
        }
    }
}

class GgufError(message: String) : Exception(message)

private class Reader(private val d: DataInputStream, private val size: Long) {
    private var pos = 0L

    private fun need(n: Long, what: String) {
        if (n < 0 || (size >= 0 && pos + n > size)) throw GgufError("the file is broken: $what is longer than the file")
    }
    private fun u8(): Int { need(1, "a value"); pos += 1; return d.readUnsignedByte() }
    private fun u32(): Long { need(4, "a value"); pos += 4; val b = ByteArray(4); d.readFully(b)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24) }
    private fun u64(): Long { val lo = u32(); val hi = u32(); return lo or (hi shl 32) }
    private fun skip(n: Long, what: String) {
        need(n, what)
        var left = n
        while (left > 0) { val s = d.skip(left); if (s <= 0) { d.readByte(); left -= 1 } else left -= s }
        pos += n
    }
    private fun str(keep: Boolean, what: String): String? {
        val n = u64()
        if (n > GgufInfo.MAX_STRING) throw GgufError("the file is broken: $what is ${n / 1024 / 1024} MB long")
        if (!keep) { skip(n, what); return null }
        need(n, what); val b = ByteArray(n.toInt()); d.readFully(b); pos += n
        return String(b, Charsets.UTF_8)
    }

    /** A value of [type]: kept (as String / Long / Boolean) or skipped (null). */
    private fun value(type: Long, keep: Boolean, what: String): Any? = when (type) {
        0L, 1L -> u8().toLong().let { if (keep) it else null }
        2L, 3L -> { skip(2, what); null }
        4L -> u32().let { if (keep) it else null }
        5L -> u32().let { if (keep) it.toInt().toLong() else null }
        6L -> { skip(4, what); null }
        7L -> (u8() != 0).let { if (keep) it else null }
        8L -> str(keep, what)
        9L -> {
            val itemType = u32(); val count = u64()
            if (count > GgufInfo.MAX_ARRAY) throw GgufError("the file is broken: $what has $count items")
            val fixed = when (itemType) { 0L, 1L, 7L -> 1L; 2L, 3L -> 2L; 4L, 5L, 6L -> 4L; 10L, 11L, 12L -> 8L; else -> -1L }
            if (fixed > 0) skip(count * fixed, what)
            else if (itemType == 8L) for (i in 0 until count) str(false, what)
            else throw GgufError("the file is broken: $what holds items of unknown type $itemType")
            null
        }
        10L, 11L -> u64().let { if (keep) it else null }
        12L -> { skip(8, what); null }
        else -> throw GgufError("the file is broken: $what has an unknown value type $type")
    }

    fun info(): GgufInfo {
        need(4, "the start")
        val magic = ByteArray(4); d.readFully(magic); pos += 4
        if (String(magic, Charsets.ISO_8859_1) != "GGUF") throw GgufError("not a GGUF file")
        val version = u32().toInt()
        if (version != 2 && version != 3) throw GgufError("GGUF version $version is not supported (only 2 and 3)")
        val tensors = u64()
        val keys = u64()
        if (keys > GgufInfo.MAX_KEYS) throw GgufError("the file is broken: it claims $keys keys")
        val kept = HashMap<String, Any>()
        var arch: String? = null
        val wanted = setOf("general.architecture", "general.name", "general.file_type", "general.size_label",
            "general.type", "tokenizer.chat_template", "clip.has_vision_encoder")
        for (i in 0 until keys) {
            val key = str(true, "a key name") ?: ""
            val type = u32()
            val keep = key in wanted || key.endsWith(".context_length")
            val v = value(type, keep, "\"$key\"")
            if (keep && v != null) kept[key] = v
            if (key == "general.architecture") arch = v as? String
        }
        val a = arch ?: throw GgufError("the file does not say which model family it is (no general.architecture)")
        val projector = a == "clip" || kept["general.type"] == "mmproj" || kept["clip.has_vision_encoder"] == true
        return GgufInfo(
            version = version, architecture = a, name = kept["general.name"] as? String,
            fileType = (kept["general.file_type"] as? Long)?.toInt(), sizeLabel = kept["general.size_label"] as? String,
            contextLength = kept["$a.context_length"] as? Long,
            hasChatTemplate = (kept["tokenizer.chat_template"] as? String)?.isNotBlank() == true,
            isProjector = projector, tensorCount = tensors)
    }
}
