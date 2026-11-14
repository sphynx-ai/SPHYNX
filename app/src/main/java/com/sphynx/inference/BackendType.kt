package com.sphynx.inference

// HYBRID INFERENCE, Phase One (owner-authorized 2026-11-10): the contracts every model runtime
// follows. Pure Kotlin, no Android and no runtime library types, unit tested. Nothing in the app
// uses this package yet - Phase Two wraps today's LiteRT-LM slot behind these contracts with
// identical behaviour. See docs/HYBRID_INFERENCE_AUDIT.md. Phase 8: LlamaCppBackend is the first
// runtime built on these contracts.

/** The model runtimes the app knows. A model file belongs to exactly one. */
enum class BackendType(val label: String, val extensions: List<String>, val shippedNative: Boolean) {
    /** Google LiteRT-LM (.litertlm, older .task bundles). Ships today, native libs from the AAR. */
    LITERT_LM("LiteRT-LM", listOf("litertlm", "task"), shippedNative = true),
    /** llama.cpp GGUF models. Native library built from app/src/main/cpp (arm64-v8a, CPU), Phase 8. */
    LLAMA_CPP("llama.cpp (GGUF)", listOf("gguf"), shippedNative = true);

    companion object {
        /** The runtime for a file name by its extension, or null when no runtime reads it. */
        fun forFileName(name: String): BackendType? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return values().firstOrNull { ext in it.extensions }
        }
    }
}

/** Where a runtime may compute. The order of a load plan is the fallback order. */
enum class Accelerator { NPU, GPU, CPU }

/** The engine a model backs. */
enum class ModelRole { STRATEGIC, TACTICAL }

/** How the app holds the model file (owner, 2026-11-09: both, chosen per model at import). */
enum class StorageMode {
    /** A copy in the app's own storage (free-space check before copying). */
    APP_COPY,
    /** A reference to the original document (persisted SAF permission); never deleted by the app. */
    DOCUMENT_REFERENCE
}
