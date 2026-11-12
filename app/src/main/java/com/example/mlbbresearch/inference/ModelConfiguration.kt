package com.example.mlbbresearch.inference

/**
 * Everything a runtime needs to load one model. Pure data; [problems] lists what is wrong with it
 * before anything is loaded (an empty list = fine).
 */
data class ModelConfiguration(
    val id: String,                       // stable id in the model registry (Phase Four)
    val displayName: String,
    val backend: BackendType,
    val location: String,                 // file path (APP_COPY) or document URI (DOCUMENT_REFERENCE)
    val storage: StorageMode = StorageMode.APP_COPY,
    val sizeBytes: Long = -1L,            // -1 = unknown
    val role: ModelRole? = null,          // null = in the library, not assigned
    val accelerators: List<Accelerator> = listOf(Accelerator.GPU, Accelerator.CPU),
    val wantsVision: Boolean = false,     // image + text (LiteRT-LM multimodal only)
    val contextTokens: Int = 4096,
    val maxOutputTokens: Int = 512,
    val threads: Int = 0                  // 0 = runtime default
) {
    fun problems(): List<String> {
        val p = ArrayList<String>()
        if (id.isBlank()) p.add("no model id")
        if (location.isBlank()) p.add("no file location")
        if (accelerators.isEmpty()) p.add("no accelerator to run on")
        if (accelerators.distinct().size != accelerators.size) p.add("an accelerator is listed twice")
        if (contextTokens !in 128..131_072) p.add("context length $contextTokens is out of range (128-131072)")
        if (maxOutputTokens !in 1..contextTokens) p.add("max output $maxOutputTokens must be 1..context length")
        if (threads !in 0..16) p.add("threads $threads is out of range (0-16)")
        if (wantsVision && backend != BackendType.LITERT_LM) p.add("image input needs a LiteRT-LM multimodal model")
        if (storage == StorageMode.APP_COPY && location.startsWith("content://")) p.add("an app-storage copy cannot be a document URI")
        if (storage == StorageMode.DOCUMENT_REFERENCE && !location.startsWith("content://")) p.add("a document reference must be a content:// URI")
        val fileType = BackendType.forFileName(displayName.takeIf { '.' in it } ?: location)
        if (fileType != null && fileType != backend) p.add("the file looks like ${fileType.label}, not ${backend.label}")
        return p
    }
}
