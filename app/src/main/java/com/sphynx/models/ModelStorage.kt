package com.sphynx.models

/**
 * PHASE 9, step M5: how much room the models take, how much is left, and the files no record points to (leftovers:
 * a half-finished copy, a model the registry no longer lists). Pure, unit-tested; the Model Lab shows the result.
 */
object ModelStorage {

    /** Room kept free after a copy, the same margin the copies use. */
    const val SPARE_BYTES = 256L * 1024 * 1024
    /** Below this much free room the Lab warns before the next copy. */
    const val LOW_BYTES = 1024L * 1024 * 1024
    /** A temporary file younger than this may belong to a copy that is still running: never a leftover. */
    const val BUSY_MS = 15L * 60 * 1000

    class FileInfo(val path: String, val name: String, val size: Long, val modified: Long)

    /** The files in the model folders that nothing uses. [inFilesDir]: the app's own folder, where only model files count. */
    fun leftovers(files: List<FileInfo>, recordNames: Set<String>, now: Long, inFilesDir: (FileInfo) -> Boolean): List<FileInfo> =
        files.filter { f ->
            val n = f.name.lowercase()
            val temp = n.endsWith(".tmp") || n.endsWith(".part")
            val model = n.endsWith(".litertlm") || n.endsWith(".gguf")
            when {
                temp -> now - f.modified > BUSY_MS && (!inFilesDir(f) || n.contains(".litertlm"))
                model -> f.name !in recordNames
                else -> !inFilesDir(f)                  // anything else in files/gguf or files/litertlm
            }
        }

    /** The warning for the free room, or null when there is plenty. */
    fun roomWarning(freeBytes: Long): String? = when {
        freeBytes < 0 -> null
        freeBytes < SPARE_BYTES -> "The phone is almost full (${mb(freeBytes)} MB free): no model can be copied until room is made"
        freeBytes < LOW_BYTES -> "Only ${mb(freeBytes)} MB free: a new model may not fit"
        else -> null
    }

    /** Why a copy of [needBytes] would not fit in [freeBytes], or null when it fits with the spare room left. */
    fun copyProblem(needBytes: Long, freeBytes: Long): String? =
        if (needBytes > 0 && freeBytes >= 0 && freeBytes < needBytes + SPARE_BYTES)
            "it needs ${mb(needBytes)} MB and ${mb(freeBytes)} MB are free (${mb(SPARE_BYTES)} MB are kept spare)" else null

    /** "Models 3,210 MB (LiteRT-LM 2,900, GGUF 310) · free 12,400 MB". */
    fun summary(records: List<ModelRecord>, freeBytes: Long): String {
        val by = records.groupBy { it.format }.mapValues { e -> e.value.sumOf { it.sizeBytes } }
        val parts = ModelRecord.Format.values().mapNotNull { f -> by[f]?.let { "${f.label} ${mb(it)}" } }
        return "Models ${mb(records.sumOf { it.sizeBytes })} MB" + (if (parts.size > 1) " (" + parts.joinToString(", ") + ")" else "") +
            (if (freeBytes >= 0) " · free ${mb(freeBytes)} MB" else "")
    }

    fun mb(bytes: Long) = "%,d".format(java.util.Locale.US, bytes / 1_048_576)
}
