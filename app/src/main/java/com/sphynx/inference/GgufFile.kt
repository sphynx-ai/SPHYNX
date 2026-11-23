package com.sphynx.inference

// PHASE 8: what is wrong with a file before llama.cpp is asked to load it. A broken download (a web
// page or a Git LFS pointer saved as .gguf, an unfinished file) is named in plain words instead of
// llama.cpp's generic "could not be loaded". From the 2026-11-14 phone test (GGUF spike).
object GgufFile {

    private val MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

    /** Null when the first bytes look like a GGUF model of a plausible size, else the problem. */
    fun checkHeader(head: ByteArray, size: Long): String? {
        if (head.size >= 4 && head.copyOf(4).contentEquals(MAGIC)) {
            return if (size in 0 until 1024L * 1024) "only ${size / 1024} KB: the download did not finish. Download the model again." else null
        }
        if (head.isEmpty()) return "the file is empty. Download the model again."
        val start = String(head, Charsets.ISO_8859_1).trimStart()
        if (start.startsWith("<")) return "this is a web page saved as .gguf, not the model. Download it again with the file's download link."
        if (start.startsWith("version https://git-lfs")) return "this is a Git LFS pointer, not the model. Download it with the file's download link."
        val shown = head.take(8).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return "not a GGUF file (it starts with $shown instead of GGUF). Pick the .gguf model file itself."
    }

    /**
     * Memory a model needs while loaded, roughly: its file (the weights) plus the context's memory
     * and working buffers. Deliberately generous: a refused load is better than the system killing the app.
     */
    fun estimatedMemoryBytes(fileBytes: Long, contextTokens: Int, kv8: Boolean = false): Long =
        (fileBytes * 1.1).toLong() + contextTokens.toLong() * (if (kv8) 32 else 64) * 1024 + 128L * 1024 * 1024
}
