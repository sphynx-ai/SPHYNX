package com.sphynx.inference

// PHASE 9, step M2: what is wrong with a .litertlm file before it is copied or loaded, in plain words, like GgufFile does
// for GGUF. A LiteRT-LM file starts with the 8 letters "LITERTLM" (LiteRT-LM v0.19.1, schema/core/litertlm_read.cc).
// Pure, unit-tested.
object LiteRtLmFile {

    private val MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)
    const val MIN_BYTES = 1L * 1024 * 1024

    /** Null when the first bytes look like a LiteRT-LM model of a plausible size, else the problem. */
    fun checkHeader(head: ByteArray, size: Long): String? {
        if (head.size >= 8 && head.copyOf(8).contentEquals(MAGIC)) {
            return if (size in 0 until MIN_BYTES) "only ${size / 1024} KB: the download did not finish. Download the model again." else null
        }
        if (head.isEmpty()) return "the file is empty. Download the model again."
        val start = String(head, Charsets.ISO_8859_1).trimStart()
        if (start.startsWith("<")) return "this is a web page saved as .litertlm, not the model. Download it again with the file's download link."
        if (start.startsWith("version https://git-lfs")) return "this is a Git LFS pointer, not the model. Download it with the file's download link."
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())
            return "this is a zip file (an older MediaPipe .task model?), not a .litertlm model."
        val shown = head.take(8).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return "not a LiteRT-LM file (it starts with $shown instead of LITERTLM). Pick the .litertlm model file itself."
    }
}
