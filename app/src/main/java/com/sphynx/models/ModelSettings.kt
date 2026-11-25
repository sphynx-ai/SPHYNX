package com.sphynx.models

import com.sphynx.inference.Accelerator
import com.sphynx.inference.ModelConfiguration
import java.util.Locale

/**
 * PHASE 9, step M4 (owner, 2026-11-25: "all of them"): per-model settings, kept in the model's registry record
 * (ModelRecord.settings, text values). Every setting is OFF (absent) by default: the model then runs exactly as before.
 * Each value is checked against safe limits, so a bad value is refused with the reason instead of reaching a runtime.
 * Pure, unit-tested.
 */
object ModelSettings {

    const val CONTEXT = "context"            // GGUF only, tokens, 512..32768
    const val TEMPERATURE = "temperature"    // 0..1.5, replaces the adviser's own value
    const val THREADS = "threads"            // GGUF only, 1..8
    const val KV8 = "kv8"                    // GGUF only, true / false
    const val PROCESSORS = "processors"      // e.g. "GPU,CPU" (a subset of NPU, GPU, CPU; the CPU is always added last)
    const val PICTURES = "pictures"          // true / false: load the picture part when the model has one

    val ALL = listOf(CONTEXT, TEMPERATURE, THREADS, KV8, PROCESSORS, PICTURES)

    /** Why [value] is not allowed for [key] on a model of [format], or null when it is. An empty value clears it. */
    fun problem(key: String, value: String, format: ModelRecord.Format): String? {
        if (value.isEmpty()) return null
        val gguf = format != ModelRecord.Format.LITERT_LM
        return when (key) {
            CONTEXT -> if (!gguf) "the context length of a LiteRT-LM model is set inside the file"
                else value.toIntOrNull()?.let { if (it in 512..32768) null else "context length must be 512 to 32768 tokens" } ?: "not a number"
            TEMPERATURE -> value.toDoubleOrNull()?.let { if (it in 0.0..1.5) null else "temperature must be 0 to 1.5" } ?: "not a number"
            THREADS -> if (!gguf) "threads apply to GGUF models only"
                else value.toIntOrNull()?.let { if (it in 1..8) null else "threads must be 1 to 8" } ?: "not a number"
            KV8 -> if (!gguf) "the 8-bit cache applies to GGUF models only" else if (value == "true" || value == "false") null else "true or false"
            PROCESSORS -> {
                val p = value.split(',').map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }
                when {
                    p.isEmpty() -> "list at least one processor"
                    p.any { it !in setOf("NPU", "GPU", "CPU") } -> "processors are NPU, GPU and CPU"
                    p.distinct().size != p.size -> "a processor is listed twice"
                    gguf && "NPU" in p -> "GGUF models can't use the NPU"
                    else -> null
                }
            }
            PICTURES -> if (value == "true" || value == "false") null else "true or false"
            else -> "unknown setting"
        }
    }

    /** The settings after one change, or the reason it is refused. An empty value removes the setting. */
    fun change(current: Map<String, String>, key: String, value: String, format: ModelRecord.Format): Result<Map<String, String>> {
        val v = value.trim()
        problem(key, v, format)?.let { return Result.failure(IllegalArgumentException(it)) }
        return Result.success(if (v.isEmpty()) current - key else current + (key to v))
    }

    /** The processor order a setting asks for, the CPU always last as the safe fallback; null = not set. */
    fun processors(s: Map<String, String>): List<Accelerator>? = s[PROCESSORS]?.let { v ->
        val p = v.split(',').map { it.trim().uppercase(Locale.US) }.mapNotNull { n -> Accelerator.values().firstOrNull { it.name == n } }
        (p.filter { it != Accelerator.CPU } + Accelerator.CPU).distinct()
    }

    /** A runtime configuration with the model's own settings put in; nothing set = [base] unchanged. */
    fun apply(base: ModelConfiguration, s: Map<String, String>): ModelConfiguration {
        var c = base
        s[CONTEXT]?.toIntOrNull()?.let { c = c.copy(contextTokens = it, maxOutputTokens = minOf(c.maxOutputTokens, it)) }
        s[THREADS]?.toIntOrNull()?.let { c = c.copy(threads = it) }
        s[KV8]?.let { c = c.copy(kvCache8Bit = it == "true") }
        processors(s)?.let { c = c.copy(accelerators = it) }
        s[PICTURES]?.let { if (it == "false") c = c.copy(wantsVision = false) }
        return c
    }

    /** The temperature to use for an answer: the model's own setting when there is one, else the adviser's. */
    fun temperature(s: Map<String, String>, adviser: Double): Double = s[TEMPERATURE]?.toDoubleOrNull() ?: adviser
}
