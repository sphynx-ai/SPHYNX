package com.sphynx.inference

import java.io.ByteArrayOutputStream

/**
 * Turns a model's answer, arriving as UTF-8 bytes in pieces, into text as it comes. A character
 * can be split between two pieces (an emoji is 4 bytes, a token may hold only 2 of them): its
 * first bytes wait until the rest arrives, so streamed text never shows a broken character.
 * Bytes that can never become a character are shown as U+FFFD, never an error.
 */
class Utf8Stream {
    private val pending = ByteArrayOutputStream()
    private val all = StringBuilder()

    /** The text these bytes complete (empty while a character is still incomplete). */
    fun push(bytes: ByteArray): String {
        pending.write(bytes, 0, bytes.size)
        val buf = pending.toByteArray()
        val cut = completeLength(buf)
        if (cut == 0) return ""
        val text = String(buf, 0, cut, Charsets.UTF_8)
        pending.reset()
        if (cut < buf.size) pending.write(buf, cut, buf.size - cut)
        all.append(text)
        return text
    }

    /** At the end: whatever is left (an incomplete character becomes U+FFFD). */
    fun finish(): String {
        if (pending.size() == 0) return ""
        val text = String(pending.toByteArray(), Charsets.UTF_8)
        pending.reset()
        all.append(text)
        return text
    }

    /** Everything returned so far, joined. */
    fun text(): String = all.toString()

    companion object {
        /** How many leading bytes of [b] end on a whole character (an incomplete one at the end is held back). */
        fun completeLength(b: ByteArray): Int {
            val n = b.size
            // look back at most 3 bytes for the start of the last character
            var i = n - 1
            var back = 0
            while (i >= 0 && back < 4) {
                val c = b[i].toInt() and 0xFF
                if (c and 0xC0 != 0x80) {          // a lead byte (or a plain ASCII byte)
                    val need = when {
                        c and 0x80 == 0 -> 1
                        c and 0xE0 == 0xC0 -> 2
                        c and 0xF0 == 0xE0 -> 3
                        c and 0xF8 == 0xF0 -> 4
                        else -> 1                   // never valid: let the decoder replace it
                    }
                    return if (n - i < need) i else n
                }
                i--; back++
            }
            return n                                // only continuation bytes: let the decoder replace them
        }
    }
}
