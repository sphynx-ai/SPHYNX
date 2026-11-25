package com.sphynx.core

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

// PHASE 9, step M2: a model file's SHA-256, worked out while it is copied (the file is read once, not twice). The hex
// text is what Hugging Face shows for a file, so a download can be checked against it (M9). Pure, unit-tested.
object FileHash {

    /** Copies [input] to [output] and returns the SHA-256 of what was copied, as lower-case hex. */
    fun copyAndHash(input: InputStream, output: OutputStream, progress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n); output.write(buf, 0, n); total += n
            progress(total)
        }
        return hex(md.digest())
    }

    fun hash(input: InputStream): String = copyAndHash(input, object : OutputStream() { override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {} })     // (OutputStream.nullOutputStream needs Android 13)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** The settings key that keeps a copied file's hash ("name:size"), read by the model registry. */
    fun key(fileName: String, sizeBytes: Long) = "sha256_${fileName}:$sizeBytes"
}
