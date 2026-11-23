package com.sphynx.testing

import java.io.ByteArrayOutputStream

// Made-up GGUF files for the tests (Phase 8 G2): the real layout (magic, version, tensor count, key/value pairs,
// little-endian) with the keys a test asks for, and nothing else.
object GgufFixture {
    sealed class V { class S(val s: String) : V(); class U32(val v: Long) : V(); class U64(val v: Long) : V()
        class B(val v: Boolean) : V(); class Strings(val items: List<String>) : V(); class F32s(val n: Int) : V() }

    fun model(arch: String = "llama", fileType: Long = 15, ctx: Long = 4096, template: String? = "{{ messages }}",
              extra: List<Pair<String, V>> = emptyList()): ByteArray = bytes(listOf(
        "general.architecture" to V.S(arch), "general.name" to V.S("Test Model"), "general.file_type" to V.U32(fileType),
        "general.size_label" to V.S("1B"), "$arch.context_length" to V.U32(ctx),
        "tokenizer.ggml.tokens" to V.Strings(listOf("<s>", "</s>", "hello", "world")), "tokenizer.ggml.scores" to V.F32s(4),
    ) + (template?.let { listOf("tokenizer.chat_template" to V.S(it)) } ?: emptyList()) + extra)

    fun bytes(kvs: List<Pair<String, V>>, version: Long = 3, tensors: Long = 1): ByteArray {
        val o = ByteArrayOutputStream()
        fun u32(v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun u64(v: Long) { for (i in 0 until 8) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); u64(b.size.toLong()); o.write(b) }
        o.write("GGUF".toByteArray()); u32(version); u64(tensors); u64(kvs.size.toLong())
        for ((k, v) in kvs) {
            str(k)
            when (v) {
                is V.S -> { u32(8); str(v.s) }
                is V.U32 -> { u32(4); u32(v.v) }
                is V.U64 -> { u32(10); u64(v.v) }
                is V.B -> { u32(7); o.write(if (v.v) 1 else 0) }
                is V.Strings -> { u32(9); u32(8); u64(v.items.size.toLong()); v.items.forEach { str(it) } }
                is V.F32s -> { u32(9); u32(6); u64(v.n.toLong()); repeat(v.n * 4) { o.write(0) } }
            }
        }
        return o.toByteArray()
    }
}
